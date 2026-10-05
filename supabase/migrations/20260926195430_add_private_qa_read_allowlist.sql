-- Fail before making any schema change unless the deployed v1 dispatcher is the
-- exact revision this narrowly scoped patch was reviewed against.
do $$
declare
  body text;
  needle constant text := E'  if action in (\'prepare_open\',\'open_commit\',\'validate\',\'resume_content\',\'react\') and\n     not (select public_reading_enabled from nearby_private.service_control where singleton) then';
  occurrences integer;
begin
  select p.prosrc into body from pg_catalog.pg_proc p
    where p.oid='nearby_private.public_command_v1(text,jsonb)'::pg_catalog.regprocedure
      and p.prosecdef and p.provolatile='v';
  if body is null then raise exception 'expected secured v1 dispatcher is absent'; end if;
  occurrences := (length(body)-length(replace(body,needle,'')))/length(needle);
  if occurrences<>1 then raise exception 'expected exactly one v1 discovery gate, found %',occurrences; end if;
end $$;

create table nearby_private.qa_read_audience (
  user_id uuid primary key references auth.users(id) on delete cascade,
  purpose text not null check (purpose='nearby_http_e2e'),
  run_id uuid not null,
  approved_at timestamptz not null default statement_timestamp(),
  expires_at timestamptz not null
);
alter table nearby_private.qa_read_audience enable row level security;
revoke all on nearby_private.qa_read_audience from public,anon,authenticated,service_role;
create index qa_read_audience_expiry_idx on nearby_private.qa_read_audience(expires_at);

create function nearby_private.bound_qa_read_audience() returns trigger
language plpgsql security definer set search_path='' as $$
begin
  new.approved_at:=statement_timestamp();
  if new.purpose<>'nearby_http_e2e' or new.expires_at>new.approved_at+interval '15 minutes' then
    raise exception 'QA read authorization must expire within 15 minutes';
  end if;
  return new;
end $$;
create trigger bound_qa_read_audience before insert or update on nearby_private.qa_read_audience
for each row execute function nearby_private.bound_qa_read_audience();

create function nearby_private.reading_enabled_for_actor(actor uuid) returns boolean
language sql stable security definer set search_path='' as $$
  select coalesce((select public_reading_enabled from nearby_private.service_control where singleton),false)
    or exists(select 1 from nearby_private.qa_read_audience q
      where q.user_id=actor and q.purpose='nearby_http_e2e' and q.expires_at>statement_timestamp())
$$;

do $$
declare
  definition text;
  needle constant text := E'  if action in (\'prepare_open\',\'open_commit\',\'validate\',\'resume_content\',\'react\') and\n     not (select public_reading_enabled from nearby_private.service_control where singleton) then';
  replacement constant text := E'  if action in (\'prepare_open\',\'open_commit\',\'validate\',\'resume_content\',\'react\') and\n     not nearby_private.reading_enabled_for_actor(actor) then';
begin
  select pg_catalog.pg_get_functiondef('nearby_private.public_command_v1(text,jsonb)'::pg_catalog.regprocedure)
    into strict definition;
  definition:=replace(definition,needle,replacement);
  execute definition;
  if (select (length(prosrc)-length(replace(prosrc,'nearby_private.reading_enabled_for_actor(actor)','')))
      /length('nearby_private.reading_enabled_for_actor(actor)')
      from pg_catalog.pg_proc where oid='nearby_private.public_command_v1(text,jsonb)'::pg_catalog.regprocedure)<>1 then
    raise exception 'QA gate replacement verification failed';
  end if;
end $$;

revoke all on function nearby_private.bound_qa_read_audience() from public,anon,authenticated,service_role;
revoke all on function nearby_private.reading_enabled_for_actor(uuid) from public,anon,authenticated,service_role;

comment on table nearby_private.qa_read_audience is
  'Private, operator-enrolled, maximum-15-minute discovery allowlist for isolated HTTP QA while the global gate is closed.';
