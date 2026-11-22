-- 0002_storage.sql
-- Private bucket for receipt photos. Object path: {owner_id}/{transaction_id}.jpg
-- See DESIGN.md -> Visibility (RLS).

-- Photos are compressed JPEGs from the phone (a few hundred KB). The size and
-- type limits are server-side backstops (RULES 18), not features.
insert into storage.buckets (id, name, public, file_size_limit, allowed_mime_types)
values ('receipts', 'receipts', false, 5242880, array['image/jpeg'])
on conflict (id) do update
  set public = false,
      file_size_limit = excluded.file_size_limit,
      allowed_mime_types = excluded.allowed_mime_types;

-- storage.objects already has RLS enabled by Supabase. Policies below apply only
-- to this bucket. The first path segment is the owner's user id.

-- Write (upload, replace, remove) inside your own folder only.
create policy receipts_insert_own on storage.objects for insert to authenticated
  with check (
    bucket_id = 'receipts'
    and (storage.foldername(name))[1] = (select auth.uid())::text
  );

create policy receipts_update_own on storage.objects for update to authenticated
  using (
    bucket_id = 'receipts'
    and (storage.foldername(name))[1] = (select auth.uid())::text
  )
  with check (
    bucket_id = 'receipts'
    and (storage.foldername(name))[1] = (select auth.uid())::text
  );

create policy receipts_delete_own on storage.objects for delete to authenticated
  using (
    bucket_id = 'receipts'
    and (storage.foldername(name))[1] = (select auth.uid())::text
  );

-- Read your own photos, and those of anyone you share a group with. The CASE
-- keeps a malformed folder name from being cast to uuid (which would error).
create policy receipts_select_own_or_group on storage.objects for select to authenticated
  using (
    bucket_id = 'receipts'
    and (
      (storage.foldername(name))[1] = (select auth.uid())::text
      or case
           when (storage.foldername(name))[1] ~* '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'
             then public.shares_group(((storage.foldername(name))[1])::uuid)
           else false
         end
    )
  );
