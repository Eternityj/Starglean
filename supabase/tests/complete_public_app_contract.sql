-- Run after all migrations in an isolated database. Synthetic data rolls back.
begin;
create temporary table contract_results(label text primary key);
create function pg_temp.ok(value boolean,label text) returns void language plpgsql as $$
begin if value is not true then raise exception 'failed: %',label; end if; insert into contract_results values(label); end $$;

do $$ begin
  perform pg_temp.ok((select not public and file_size_limit=8388608 from storage.buckets where id='nearby-private-photos'),
    'photo bucket is private and size bounded');
  perform pg_temp.ok(not has_function_privilege('authenticated',
    'public.nearby_media_validation(uuid,text,integer,integer,integer,text,boolean)','EXECUTE'),
    'clients cannot assert image validation');
  perform pg_temp.ok(has_function_privilege('service_role',
    'public.nearby_media_validation(uuid,text,integer,integer,integer,text,boolean)','EXECUTE'),
    'validation callback is service only');
  perform pg_temp.ok(not has_function_privilege('authenticated','public.nearby_service_media_path(uuid)','EXECUTE'),
    'clients cannot resolve opaque media tickets to paths');
  perform pg_temp.ok(not has_function_privilege('authenticated','public.nearby_service_delete(text,uuid)','EXECUTE'),
    'clients cannot finalize lifecycle deletion');
  perform pg_temp.ok(not has_table_privilege('authenticated','nearby_private.entry_media','SELECT'),
    'media metadata has no direct client read');
  perform pg_temp.ok(not has_table_privilege('authenticated','nearby_private.account_state','SELECT'),
    'account state has no direct client read');
  perform pg_temp.ok(not has_table_privilege('authenticated','nearby_private.deletion_operations','SELECT'),
    'deletion operation internals are private');
  perform pg_temp.ok((select count(*)=1 from pg_policies where schemaname='storage' and tablename='objects'
    and policyname like 'nearby_photo_%'),'storage operations have explicit policies');
  perform pg_temp.ok((select public_reading_enabled=false from nearby_private.service_control where singleton),
    'migration does not enable discovery');
  perform pg_temp.ok((select prosecdef from pg_proc where oid='nearby_private.public_command(text,jsonb)'::regprocedure),
    'dispatcher remains restricted security definer');
  perform pg_temp.ok(not has_function_privilege('anon','public.nearby_public_command(text,jsonb)','EXECUTE'),
    'anonymous role remains denied');
  perform pg_temp.ok((select count(*)=4 from pg_indexes where schemaname='nearby_private' and indexname in (
    'deletion_operations_actor_idx','deletion_operations_entry_idx','entry_media_owner_idx',
    'media_access_tickets_media_idx')),'lifecycle foreign keys have supporting indexes');
  perform pg_temp.ok((select lower(pg_get_constraintdef(oid)) like '%automatically_published_at is not null%'
    from pg_constraint where conrelid='nearby_private.publications'::regclass
      and conname='publications_review_state_check'),
    'post-publication restore constraint retains automatic publication history');
end $$;
select count(*) as passed_assertions from contract_results;
rollback;
