-- Cloud capacity counts live entry rows, including pending/deleting entries.
-- Publication request receipts and cancellation tombstones remain permanent
-- idempotency records and deliberately do not consume this capacity.
do $migration$
declare
  source text:=pg_get_functiondef('nearby_private.public_command_v1(text,jsonb)'::regprocedure);
  needle constant text:=E'    if (select count(*) from nearby_private.publication_requests where owner_id=actor)>=1000 then\n      return jsonb_build_object(\'status\',\'LIFETIME_SUBMISSION_LIMIT\'); end if;';
  replacement constant text:=E'    if (select count(*) from nearby_private.entries where owner_id=actor)>=1000 then\n      return jsonb_build_object(\'status\',\'CLOUD_ENTRY_LIMIT\'); end if;';
begin
  if (length(source)-length(replace(source,needle,'')))/length(needle)<>1 then
    raise exception 'expected exactly one legacy publication-request capacity guard';
  end if;
  execute replace(source,needle,replacement);
end
$migration$;

comment on function nearby_private.public_command_v1(text,jsonb) is
  'Private dispatcher implementation. Cloud capacity is 1,000 extant entries per owner; deletion releases capacity only after final row removal.';
