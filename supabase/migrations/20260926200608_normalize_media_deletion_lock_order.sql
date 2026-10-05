-- Every media/deletion path must acquire the per-owner advisory lock before a
-- mutable row lock. Otherwise validation can hold entry_media while waiting on
-- an owner deletion that holds the owner lock and waits on the same media row.
do $migration$
declare
  source text;
  needle text;
  replacement text;
begin
  source:=pg_get_functiondef('public.nearby_media_validation(uuid,text,integer,integer,integer,text,boolean)'::regprocedure);
  needle:='declare m nearby_private.entry_media%rowtype; remaining integer;';
  replacement:='declare m nearby_private.entry_media%rowtype; remaining integer; owner_uuid uuid;';
  if (length(source)-length(replace(source,needle,'')))/length(needle)<>1 then
    raise exception 'unexpected nearby_media_validation declaration'; end if;
  source:=replace(source,needle,replacement);
  needle:=E'  select * into m from nearby_private.entry_media where id=media_id for update;\n  if not found then return jsonb_build_object(\'status\',\'NOT_FOUND\'); end if;\n  perform pg_catalog.pg_advisory_xact_lock(pg_catalog.hashtextextended(\'submit:\'||m.owner_id::text,0));';
  replacement:=E'  select owner_id into owner_uuid from nearby_private.entry_media where id=media_id;\n  if not found then return jsonb_build_object(\'status\',\'NOT_FOUND\'); end if;\n  perform pg_catalog.pg_advisory_xact_lock(pg_catalog.hashtextextended(\'submit:\'||owner_uuid::text,0));\n  select * into m from nearby_private.entry_media where id=media_id for update;\n  if not found or m.owner_id is distinct from owner_uuid then\n    return jsonb_build_object(\'status\',\'NOT_FOUND\'); end if;';
  if (length(source)-length(replace(source,needle,'')))/length(needle)<>1 then
    raise exception 'unexpected nearby_media_validation lock sequence'; end if;
  execute replace(source,needle,replacement);

  source:=pg_get_functiondef('public.nearby_service_delete(text,uuid)'::regprocedure);
  needle:='declare op nearby_private.deletion_operations%rowtype; paths jsonb;';
  replacement:='declare op nearby_private.deletion_operations%rowtype; paths jsonb; actor_uuid uuid;';
  if (length(source)-length(replace(source,needle,'')))/length(needle)<>1 then
    raise exception 'unexpected nearby_service_delete declaration'; end if;
  source:=replace(source,needle,replacement);
  needle:=E'  select * into op from nearby_private.deletion_operations where id=operation_id and state=\'purging\' for update;\n  if not found then return jsonb_build_object(\'status\',\'NOT_FOUND\'); end if;\n  perform pg_catalog.pg_advisory_xact_lock(pg_catalog.hashtextextended(\'submit:\'||op.actor_id::text,0));';
  replacement:=E'  select actor_id into actor_uuid from nearby_private.deletion_operations\n    where id=operation_id and state=\'purging\';\n  if not found then return jsonb_build_object(\'status\',\'NOT_FOUND\'); end if;\n  perform pg_catalog.pg_advisory_xact_lock(pg_catalog.hashtextextended(\'submit:\'||actor_uuid::text,0));\n  select * into op from nearby_private.deletion_operations where id=operation_id and state=\'purging\' for update;\n  if not found or op.actor_id is distinct from actor_uuid then\n    return jsonb_build_object(\'status\',\'NOT_FOUND\'); end if;';
  if (length(source)-length(replace(source,needle,'')))/length(needle)<>1 then
    raise exception 'unexpected nearby_service_delete lock sequence'; end if;
  execute replace(source,needle,replacement);
end
$migration$;
