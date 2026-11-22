-- 0001_init.sql
-- Chimu backend: tables, last-writer-wins + rev triggers, RLS, grants, group RPCs.
-- See DESIGN.md -> Data model, Visibility (RLS), Sync protocol.
--
-- Conventions:
--   * money is integer centavos, times are epoch milliseconds (RULES 7, 8)
--   * synced rows are never hard-deleted, `deleted = true` is the tombstone (RULE 9)
--   * RLS is on for every table; only `authenticated` gets grants, `anon` gets none (RULE 12)
--   * group membership changes go through the RPCs at the bottom only (RULE 16)

-- ---------------------------------------------------------------------------
-- Extensions
-- ---------------------------------------------------------------------------
-- uuid-ossp provides uuid_generate_v5(), used by generate_recurring() in RFC-010.
-- pg_cron schedules that job; RFC-001 only enables it.
create extension if not exists "uuid-ossp" with schema extensions;
create extension if not exists pg_cron with schema pg_catalog;

-- ---------------------------------------------------------------------------
-- Pull cursor
-- ---------------------------------------------------------------------------
create sequence public.rev_seq;

-- ---------------------------------------------------------------------------
-- Tables
-- ---------------------------------------------------------------------------

-- One row per signed-up user; created by the trigger on auth.users below.
create table public.profiles (
  id           uuid primary key references auth.users (id) on delete cascade,
  display_name text not null default ''
);

-- Groups and memberships are written only by the RPCs below.
-- They are not "syncable" tables: no updated_at / deleted / rev. A group is
-- hard-deleted when its last member (the owner) leaves.
create table public.groups (
  id          uuid primary key default gen_random_uuid(),
  name        text not null check (char_length(name) between 1 and 60),
  invite_code text not null unique check (invite_code ~ '^[A-Z0-9]{6}$'),
  owner_id    uuid not null references auth.users (id) on delete cascade,
  created_at  timestamptz not null default now()
);

create table public.group_members (
  group_id  uuid not null references public.groups (id) on delete cascade,
  user_id   uuid not null references auth.users (id) on delete cascade,
  role      text not null check (role in ('owner', 'member')),
  joined_at timestamptz not null default now(),
  primary key (group_id, user_id)
);
create index group_members_user_id_idx on public.group_members (user_id);

-- Syncable tables. Each carries the DESIGN.md conventions:
--   id (client-made uuid), owner_id, updated_at (ms), deleted, rev (trigger-set).

create table public.wallets (
  id              uuid primary key,
  owner_id        uuid not null default auth.uid() references auth.users (id) on delete cascade,
  updated_at      bigint not null,
  deleted         boolean not null default false,
  rev             bigint not null default 0,
  name            text not null check (char_length(name) between 1 and 60),
  type            text not null check (type in ('cash', 'gcash', 'maya', 'card', 'bank', 'savings')),
  -- May be negative (e.g. a card that already carries a balance owed).
  opening_balance bigint not null default 0,
  -- Lets child rows pin "this wallet belongs to the same owner" with a composite FK.
  unique (id, owner_id)
);

create table public.transactions (
  id             uuid primary key,
  owner_id       uuid not null default auth.uid() references auth.users (id) on delete cascade,
  updated_at     bigint not null,
  deleted        boolean not null default false,
  rev            bigint not null default 0,
  type           text not null check (type in ('expense', 'income', 'transfer')),
  wallet_id      uuid not null,
  to_wallet_id   uuid,
  amount         bigint not null check (amount >= 0),
  date           date not null,
  category       text,
  merchant       text,
  note           text,
  is_business    boolean not null default false,
  source         text not null default 'manual'
                   check (source in ('manual', 'receipt', 'notification', 'sms', 'recurring')),
  ref_no         text,
  -- Plain uuid, no FK: `recurring` is pushed after `transactions` in the sync order.
  recurring_id   uuid,
  receipt_path   text,
  seller_tin     text,
  invoice_no     text,
  vatable_sales  bigint check (vatable_sales >= 0),
  vat            bigint check (vat >= 0),
  vat_exempt     bigint check (vat_exempt >= 0),
  foreign key (wallet_id, owner_id) references public.wallets (id, owner_id),
  foreign key (to_wallet_id, owner_id) references public.wallets (id, owner_id),
  -- A transfer needs a different destination wallet; other types must not have one.
  check (
    (type = 'transfer' and to_wallet_id is not null and to_wallet_id <> wallet_id)
    or (type <> 'transfer' and to_wallet_id is null)
  )
);

create table public.budgets (
  id            uuid primary key,
  owner_id      uuid not null default auth.uid() references auth.users (id) on delete cascade,
  updated_at    bigint not null,
  deleted       boolean not null default false,
  rev           bigint not null default 0,
  -- null = personal budget. If the group is deleted the budget falls back to personal.
  group_id      uuid references public.groups (id) on delete set null,
  -- null = overall budget
  category      text,
  monthly_limit bigint not null check (monthly_limit >= 0)
);

create table public.recurring (
  id                 uuid primary key,
  owner_id           uuid not null default auth.uid() references auth.users (id) on delete cascade,
  updated_at         bigint not null,
  deleted            boolean not null default false,
  rev                bigint not null default 0,
  -- Only expense/income: generate_recurring() cannot fill in a transfer's second wallet.
  type               text not null check (type in ('expense', 'income')),
  wallet_id          uuid not null,
  amount             bigint not null check (amount >= 0),
  category           text,
  merchant           text,
  note               text,
  frequency          text not null check (frequency in ('weekly', 'monthly', 'yearly')),
  next_date          date not null,
  remind_days_before integer not null default 1 check (remind_days_before between 0 and 30),
  active             boolean not null default true,
  foreign key (wallet_id, owner_id) references public.wallets (id, owner_id)
);

create table public.goals (
  id         uuid primary key,
  owner_id   uuid not null default auth.uid() references auth.users (id) on delete cascade,
  updated_at bigint not null,
  deleted    boolean not null default false,
  rev        bigint not null default 0,
  name       text not null check (char_length(name) between 1 and 60),
  target     bigint not null check (target >= 0),
  deadline   date,
  -- A `savings` wallet; the phone enforces the wallet type.
  wallet_id  uuid not null,
  foreign key (wallet_id, owner_id) references public.wallets (id, owner_id)
);

-- Pull cursor: `where rev > last_rev` per owner.
create index wallets_owner_rev_idx      on public.wallets      (owner_id, rev);
create index transactions_owner_rev_idx on public.transactions (owner_id, rev);
create index budgets_owner_rev_idx      on public.budgets      (owner_id, rev);
create index recurring_owner_rev_idx    on public.recurring    (owner_id, rev);
create index goals_owner_rev_idx        on public.goals        (owner_id, rev);

-- ---------------------------------------------------------------------------
-- Triggers
-- ---------------------------------------------------------------------------

-- BEFORE UPDATE. Server-enforced last-writer-wins: an update that carries an
-- older updated_at than the stored row is discarded (the old row is kept).
-- Also pins id and owner_id so an update can never move a row to someone else.
-- Fires before set_rev (triggers run in name order), so a discarded write
-- leaves NEW = OLD and set_rev leaves rev alone.
create function public.lww_guard() returns trigger
language plpgsql set search_path = '' as $$
begin
  new.id := old.id;
  new.owner_id := old.owner_id;
  if new.updated_at < old.updated_at then
    return old;
  end if;
  return new;
end;
$$;

-- BEFORE INSERT OR UPDATE. Stamps the pull cursor. An update that changes
-- nothing (including one discarded by lww_guard) keeps its rev.
create function public.set_rev() returns trigger
language plpgsql set search_path = '' as $$
begin
  if tg_op = 'UPDATE' and new is not distinct from old then
    return new;
  end if;
  new.rev := nextval('public.rev_seq');
  return new;
end;
$$;

do $$
declare
  t text;
begin
  foreach t in array array['wallets', 'transactions', 'budgets', 'recurring', 'goals'] loop
    execute format(
      'create trigger lww_guard before update on public.%I
         for each row execute function public.lww_guard()', t);
    execute format(
      'create trigger set_rev before insert or update on public.%I
         for each row execute function public.set_rev()', t);
  end loop;
end;
$$;

-- New auth user -> profile row. The display name comes from sign-up metadata
-- when present, otherwise the part of the email before the "@".
create function public.handle_new_user() returns trigger
language plpgsql security definer set search_path = '' as $$
begin
  insert into public.profiles (id, display_name)
  values (
    new.id,
    coalesce(nullif(btrim(new.raw_user_meta_data ->> 'display_name'), ''),
             split_part(coalesce(new.email, ''), '@', 1))
  );
  return new;
end;
$$;

create trigger on_auth_user_created
  after insert on auth.users
  for each row execute function public.handle_new_user();

-- Users that signed up before this migration (e.g. created in the dashboard).
insert into public.profiles (id, display_name)
select u.id, split_part(coalesce(u.email, ''), '@', 1)
from auth.users u
on conflict (id) do nothing;

-- ---------------------------------------------------------------------------
-- Membership helpers (SECURITY DEFINER so policies on group_members don't recurse)
-- ---------------------------------------------------------------------------

-- True if the caller and `other` are in at least one common group.
create function public.shares_group(other uuid) returns boolean
language sql stable security definer set search_path = '' as $$
  select exists (
    select 1
    from public.group_members a
    join public.group_members b on b.group_id = a.group_id
    where a.user_id = (select auth.uid())
      and b.user_id = other
  );
$$;

-- True if the caller belongs to group `gid`.
create function public.is_group_member(gid uuid) returns boolean
language sql stable security definer set search_path = '' as $$
  select exists (
    select 1
    from public.group_members m
    where m.group_id = gid
      and m.user_id = (select auth.uid())
  );
$$;

-- ---------------------------------------------------------------------------
-- Row Level Security
-- ---------------------------------------------------------------------------
alter table public.profiles      enable row level security;
alter table public.groups        enable row level security;
alter table public.group_members enable row level security;
alter table public.wallets       enable row level security;
alter table public.transactions  enable row level security;
alter table public.budgets       enable row level security;
alter table public.recurring     enable row level security;
alter table public.goals         enable row level security;

-- profiles: your own, plus the people you share a group with (for member names).
create policy profiles_select on public.profiles for select to authenticated
  using (id = (select auth.uid()) or public.shares_group(id));
create policy profiles_update on public.profiles for update to authenticated
  using (id = (select auth.uid()))
  with check (id = (select auth.uid()));

-- groups / group_members: read-only for members; writes go through the RPCs.
create policy groups_select on public.groups for select to authenticated
  using (public.is_group_member(id));
create policy group_members_select on public.group_members for select to authenticated
  using (user_id = (select auth.uid()) or public.is_group_member(group_id));

-- Owned tables: you see your own rows and those of everyone you share a group
-- with; you write only your own. No delete policy (tombstones only).
do $$
declare
  t text;
begin
  foreach t in array array['wallets', 'transactions', 'recurring', 'goals'] loop
    execute format(
      'create policy %I on public.%I for select to authenticated
         using (owner_id = (select auth.uid()) or public.shares_group(owner_id))',
      t || '_select', t);
    execute format(
      'create policy %I on public.%I for insert to authenticated
         with check (owner_id = (select auth.uid()))',
      t || '_insert', t);
    execute format(
      'create policy %I on public.%I for update to authenticated
         using (owner_id = (select auth.uid()))
         with check (owner_id = (select auth.uid()))',
      t || '_update', t);
  end loop;
end;
$$;

-- budgets: same rules, plus a group budget can only point at a group the writer is in.
create policy budgets_select on public.budgets for select to authenticated
  using (owner_id = (select auth.uid()) or public.shares_group(owner_id));
create policy budgets_insert on public.budgets for insert to authenticated
  with check (
    owner_id = (select auth.uid())
    and (group_id is null or public.is_group_member(group_id))
  );
create policy budgets_update on public.budgets for update to authenticated
  using (owner_id = (select auth.uid()))
  with check (
    owner_id = (select auth.uid())
    and (group_id is null or public.is_group_member(group_id))
  );

-- ---------------------------------------------------------------------------
-- Group RPCs. Parameter names are part of the API (PostgREST passes JSON keys
-- as named arguments), so they match DESIGN.md. Inside the bodies they are
-- qualified with the function name to avoid clashing with column names.
-- ---------------------------------------------------------------------------

-- 6 characters from an alphabet without 0/O/1/I. Bytes 0-5 of a v4 uuid are
-- fully random, and 256 is a multiple of 32, so the mapping has no modulo bias.
create function public.new_invite_code() returns text
language plpgsql volatile set search_path = '' as $$
declare
  alphabet constant text := 'ABCDEFGHJKLMNPQRSTUVWXYZ23456789';
  raw   bytea := uuid_send(gen_random_uuid());
  code  text := '';
begin
  for i in 0..5 loop
    code := code || substr(alphabet, (get_byte(raw, i) % 32) + 1, 1);
  end loop;
  return code;
end;
$$;

create function public.create_group(name text) returns public.groups
language plpgsql security definer set search_path = '' as $$
declare
  uid      uuid := auth.uid();
  new_name text := btrim(create_group.name);
  g        public.groups;
  attempts int := 0;
begin
  if uid is null then
    raise exception 'not signed in' using errcode = '28000';
  end if;
  if new_name is null or char_length(new_name) not between 1 and 60 then
    raise exception 'group name must be 1 to 60 characters' using errcode = '22023';
  end if;

  loop
    begin
      insert into public.groups (name, invite_code, owner_id)
      values (new_name, public.new_invite_code(), uid)
      returning * into g;
      exit;
    exception when unique_violation then
      attempts := attempts + 1;
      if attempts >= 10 then
        raise exception 'could not generate an invite code, try again' using errcode = '55000';
      end if;
    end;
  end loop;

  insert into public.group_members (group_id, user_id, role) values (g.id, uid, 'owner');
  return g;
end;
$$;

create function public.join_group(code text) returns public.groups
language plpgsql security definer set search_path = '' as $$
declare
  uid uuid := auth.uid();
  g   public.groups;
begin
  if uid is null then
    raise exception 'not signed in' using errcode = '28000';
  end if;

  select * into g
  from public.groups gr
  where gr.invite_code = upper(btrim(join_group.code));
  if not found then
    raise exception 'invalid invite code' using errcode = '22023';
  end if;

  insert into public.group_members (group_id, user_id, role)
  values (g.id, uid, 'member')
  on conflict (group_id, user_id) do nothing;
  return g;
end;
$$;

-- A member leaves. The owner can leave only when nobody else is left, which
-- deletes the (now empty) group; otherwise they must remove the others first.
create function public.leave_group(group_id uuid) returns void
language plpgsql security definer set search_path = '' as $$
declare
  uid     uuid := auth.uid();
  my_role text;
begin
  if uid is null then
    raise exception 'not signed in' using errcode = '28000';
  end if;

  select m.role into my_role
  from public.group_members m
  where m.group_id = leave_group.group_id and m.user_id = uid;
  if not found then
    raise exception 'you are not in this group' using errcode = '42501';
  end if;

  if my_role = 'owner' then
    if exists (
      select 1 from public.group_members m
      where m.group_id = leave_group.group_id and m.user_id <> uid
    ) then
      raise exception 'the owner cannot leave while other members remain' using errcode = '42501';
    end if;
    delete from public.groups g where g.id = leave_group.group_id;
  else
    delete from public.group_members m
    where m.group_id = leave_group.group_id and m.user_id = uid;
  end if;
end;
$$;

-- Owner only. The owner cannot remove themself (they use leave_group).
create function public.remove_member(group_id uuid, user_id uuid) returns void
language plpgsql security definer set search_path = '' as $$
declare
  uid uuid := auth.uid();
begin
  if uid is null then
    raise exception 'not signed in' using errcode = '28000';
  end if;

  if not exists (
    select 1 from public.group_members m
    where m.group_id = remove_member.group_id and m.user_id = uid and m.role = 'owner'
  ) then
    raise exception 'only the group owner can remove members' using errcode = '42501';
  end if;

  if remove_member.user_id = uid then
    raise exception 'the owner cannot remove themself' using errcode = '22023';
  end if;

  delete from public.group_members m
  where m.group_id = remove_member.group_id and m.user_id = remove_member.user_id;
  if not found then
    raise exception 'that user is not in this group' using errcode = '22023';
  end if;
end;
$$;

-- ---------------------------------------------------------------------------
-- Grants. Start from nothing, then give `authenticated` exactly what the app
-- needs and `anon` nothing. Projects created after 2026-05-30 don't auto-grant,
-- older ones grant everything (even delete/truncate) to anon and authenticated:
-- resetting first makes this migration behave the same on both.
-- ---------------------------------------------------------------------------
revoke all on all tables    in schema public from anon, authenticated, public;
revoke all on all sequences in schema public from anon, authenticated, public;
revoke all on all functions in schema public from anon, authenticated, public;

grant usage on schema public to authenticated;

grant select, update (display_name) on public.profiles      to authenticated;
grant select                        on public.groups        to authenticated;
grant select                        on public.group_members to authenticated;
grant select, insert, update on public.wallets      to authenticated;
grant select, insert, update on public.transactions to authenticated;
grant select, insert, update on public.budgets      to authenticated;
grant select, insert, update on public.recurring    to authenticated;
grant select, insert, update on public.goals        to authenticated;

-- The set_rev trigger runs as the writing user.
grant usage on sequence public.rev_seq to authenticated;

grant execute on function public.shares_group(uuid)           to authenticated;
grant execute on function public.is_group_member(uuid)        to authenticated;
grant execute on function public.create_group(text)           to authenticated;
grant execute on function public.join_group(text)             to authenticated;
grant execute on function public.leave_group(uuid)            to authenticated;
grant execute on function public.remove_member(uuid, uuid)    to authenticated;
