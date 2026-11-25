-- 0003_recurring.sql
-- Recurring rules (RFC-010): the nightly job that turns due rules into transactions.
-- See DESIGN.md -> Recurring, budgets, goals. The `recurring` table itself came with 0001.
--
-- What this adds:
--   * recurring.anchor_day: the day of the month a rule is meant to fall on (see below)
--   * recurring_next_date(): the date arithmetic, month-end clamping included
--   * generate_recurring(): inserts a transaction for every due period and advances the rule
--   * a pg_cron job that runs generate_recurring() every night

-- ---------------------------------------------------------------------------
-- Anchor day
-- ---------------------------------------------------------------------------
-- A monthly rule created for the 31st must run on Jan 31, Feb 28, Mar 31 ... and not drift to the 28th
-- for good after February. `next_date` alone cannot say that (once it reads Feb 28 the 31 is gone), so the
-- rule remembers the day it was meant for. The phone sets it whenever the user picks the start date.
-- Null (rows written before this migration, or by a client that does not send it) means "the day of
-- next_date". Weekly rules ignore it. It is an ordinary synced column: the phone has a matching
-- SQLite migration (phone DB version 4) and `authenticated` already holds table-level grants on `recurring`.
alter table public.recurring
  add column anchor_day smallint check (anchor_day between 1 and 31);

-- ---------------------------------------------------------------------------
-- Date arithmetic
-- ---------------------------------------------------------------------------
-- The date after `d` for a rule that repeats `freq`. Weekly: seven days later. Monthly / yearly: the same
-- `anchor` day in the next month / next year, or the last day of that month when it is shorter.
-- (Jan 31 -> Feb 28 (29 in a leap year) -> Mar 31; Feb 29 yearly -> Feb 28 -> ... -> Feb 29 in the next leap year.)
-- A null anchor means the day of `d`. Mirrored by `RecurringDates.next` on the phone; both are tested
-- against the same table of cases (supabase/check_recurring.py, RecurringTest.kt).
create function public.recurring_next_date(freq text, d date, anchor int default null) returns date
language plpgsql immutable set search_path = '' as $$
declare
  want     int := coalesce(anchor, extract(day from d)::int);
  y        int := extract(year from d)::int;
  m        int := extract(month from d)::int;
  last_day int;
begin
  if freq = 'weekly' then
    return d + 7;
  elsif freq = 'monthly' then
    m := m + 1;
    if m > 12 then
      m := 1;
      y := y + 1;
    end if;
  elsif freq = 'yearly' then
    y := y + 1;
  else
    raise exception 'unknown frequency: %', freq using errcode = '22023';
  end if;
  last_day := extract(day from (make_date(y, m, 1) + interval '1 month' - interval '1 day'))::int;
  return make_date(y, m, least(want, last_day));
end;
$$;

-- ---------------------------------------------------------------------------
-- The nightly job
-- ---------------------------------------------------------------------------
-- For every active, non-deleted rule whose next_date is on or before today (Asia/Manila), inserts one
-- transaction per due period, oldest first, and moves next_date past today. A job that did not run for
-- three months therefore catches up all three on its next run. Returns how many transactions it created.
--
-- * Idempotent. The transaction id is uuid_generate_v5(<rule id>, '<due date as YYYY-MM-DD>'), and the
--   insert is `on conflict do nothing`, so running it twice, or running it for a rule whose next_date a
--   phone pushed back, never duplicates a period. A generated transaction the user deleted stays deleted
--   (the tombstone row still holds the id), it is not recreated.
-- * The rows are ordinary synced rows: owner_id of the rule's owner (auth.uid() is null in a cron job, so
--   it is set explicitly), updated_at in epoch milliseconds, source 'recurring', recurring_id set, and the
--   set_rev trigger from 0001 stamps `rev`, which is what the phones pull on.
-- * The anchor day (see above) is read once per rule per run. A rule without one gets it pinned to the day of its
--   next_date the first time it runs, so Jan 31 -> Feb 28 -> Mar 31 holds even if a run stops on Feb 28.
-- * The rule's own next_date change fires set_rev too, so phones pull the new date. updated_at on the rule is
--   left alone on purpose: this is the system moving the schedule on, not an edit, and bumping it would make
--   lww_guard throw away a change the user made on a phone since the last sync (last writer wins). If a phone
--   then pushes an older next_date back, the ids above make the re-run harmless.
-- * One rule that cannot be generated (any error) is skipped with a WARNING and retried tomorrow; it does
--   not stop everybody else's rules. A rule is capped at 366 periods per run; the rest is done by the next run.
-- * `as_of` exists so the check script can pretend to be another day. The cron job passes nothing.
--
-- SECURITY INVOKER on purpose, and no one but postgres may call it (see the grants below). It writes other
-- people's rows, so it must never be reachable through the API.
create function public.generate_recurring(as_of date default null) returns integer
language plpgsql set search_path = '' as $$
declare
  today   date := coalesce(as_of, (now() at time zone 'Asia/Manila')::date);
  r       public.recurring;
  due     date;
  steps   int;
  created int := 0;
  added   int;
  rule_created int;
  anchor  int;
begin
  for r in
    select * from public.recurring
    where active and not deleted and next_date <= today
    order by owner_id, id
    for update
  loop
    begin
      due := r.next_date;
      anchor := coalesce(r.anchor_day, extract(day from r.next_date))::int; -- fixed for the whole run
      steps := 0;
      rule_created := 0;
      while due <= today and steps < 366 loop
        insert into public.transactions
          (id, owner_id, updated_at, type, wallet_id, amount, date, category, merchant, note, source, recurring_id)
        values (
          extensions.uuid_generate_v5(r.id, to_char(due, 'YYYY-MM-DD')),
          r.owner_id,
          (extract(epoch from clock_timestamp()) * 1000)::bigint,
          r.type, r.wallet_id, r.amount, due, r.category, r.merchant, r.note, 'recurring', r.id
        )
        on conflict (id) do nothing;
        get diagnostics added = row_count;
        rule_created := rule_created + added;
        due := public.recurring_next_date(r.frequency, due, anchor);
        steps := steps + 1;
      end loop;
      -- Pin the anchor day on a rule that has none yet, so it survives a run that ends on a clamped date.
      update public.recurring
         set next_date = due,
             anchor_day = case when r.frequency = 'weekly' then r.anchor_day else anchor::smallint end
       where id = r.id;
      created := created + rule_created;
    exception when others then
      raise warning 'generate_recurring: rule % skipped: %', r.id, sqlerrm;
    end;
  end loop;
  return created;
end;
$$;

-- ---------------------------------------------------------------------------
-- Grants. New functions in `public` are executable by everyone by default (and Supabase also grants
-- them to anon / authenticated through default privileges), so take that away and give it to postgres,
-- the role pg_cron runs the job as. Nobody signed in can generate rows for other users.
-- ---------------------------------------------------------------------------
revoke all on function public.recurring_next_date(text, date, int) from public, anon, authenticated;
revoke all on function public.generate_recurring(date)             from public, anon, authenticated;
grant execute on function public.recurring_next_date(text, date, int) to postgres;
grant execute on function public.generate_recurring(date)             to postgres;

-- ---------------------------------------------------------------------------
-- Schedule: every day at 00:10 Asia/Manila.
-- pg_cron reads its schedule in UTC. Manila is UTC+8 all year (no daylight saving), so 00:10 in Manila is
-- 16:10 UTC of the previous calendar day: minute 10, hour 16. The job runs as the role that schedules it
-- (postgres, when the migration is pushed). cron.schedule with an existing name replaces that job, so
-- scheduling it again never leaves two.
-- ---------------------------------------------------------------------------
select cron.schedule(
  'chimu-generate-recurring',
  '10 16 * * *',
  $$select public.generate_recurring()$$
);
