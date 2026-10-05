-- Run after nearby_foundation.sql and public_nearby_service.sql in an isolated database.
-- All identities/content/coordinates are synthetic and the transaction is rolled back.
begin;
set local statement_timeout='30s';
set local lock_timeout='3s';
set local timezone='UTC';

create temporary table public_service_results(label text primary key) on commit drop;
create temporary table public_service_state(
  singleton boolean primary key default true check(singleton),
  owner_id uuid not null default gen_random_uuid(), reader_id uuid not null default gen_random_uuid(),
  staff_id uuid not null default gen_random_uuid(), owner_session uuid not null default gen_random_uuid(),
  reader_session uuid not null default gen_random_uuid(), staff_session uuid not null default gen_random_uuid(),
  entry_id uuid, review_entry uuid, protocol_session uuid, prepare_request uuid not null default gen_random_uuid(),
  last_response jsonb, media_entry uuid, media_one uuid default gen_random_uuid(), media_two uuid default gen_random_uuid(),
  media_session uuid, delete_operation uuid,
  capacity_owner uuid not null default gen_random_uuid(), capacity_session uuid not null default gen_random_uuid(),
  capacity_entry uuid, capacity_delete_operation uuid
) on commit drop;
insert into public_service_state default values;
grant select,update on public_service_state to authenticated;
grant insert,select on public_service_results to authenticated;
do $$ begin execute format('grant usage on schema %I to authenticated',pg_my_temp_schema()::regnamespace::text); end $$;

create function pg_temp.ok(value boolean,label text) returns void language plpgsql security invoker set search_path='' as $$
begin
  if value is distinct from true then raise exception 'public nearby test failed: %',label; end if;
  insert into pg_temp.public_service_results values(label);
end $$;

create function pg_temp.claims(actor uuid,session_uuid uuid) returns void language plpgsql security invoker set search_path='' as $$
begin
  perform set_config('request.jwt.claim.sub','',true);
  perform set_config('request.jwt.claims',jsonb_build_object('sub',actor,'session_id',session_uuid,
    'role','authenticated','exp',extract(epoch from statement_timestamp())+3600)::text,true);
end $$;

create function pg_temp.call(action text,payload jsonb) returns jsonb language plpgsql security invoker set search_path='' as $$
declare response jsonb;
begin
  response:=public.nearby_public_command(action,payload);
  update pg_temp.public_service_state set last_response=response;
  return response;
end $$;

-- Test-owner probes keep app-role assertions from receiving raw table grants.
create function pg_temp.read_used(actor uuid) returns integer language sql stable security definer set search_path='' as $$
  select used from nearby_private.read_days where reader_id=actor
  and account_date=(statement_timestamp() at time zone (select account_timezone from nearby_private.accounts where user_id=actor))::date
$$;
create function pg_temp.read_used_on(actor uuid,day_value date) returns integer language sql stable security definer set search_path='' as $$
  select used from nearby_private.read_days where reader_id=actor and account_date=day_value
$$;
create function pg_temp.account_day_at(actor uuid,instant timestamptz) returns date language sql stable security definer set search_path='' as $$
  select (instant at time zone account_timezone)::date from nearby_private.accounts where user_id=actor
$$;
create function pg_temp.reaction_was_read() returns boolean language sql stable security definer set search_path='' as $$
  select coalesce(bool_and(read_at is not null),false) from nearby_private.reactions
$$;
create function pg_temp.session_was_revoked(value uuid) returns boolean language sql stable security definer set search_path='' as $$
  select state='ended' and lease_until is null from nearby_private.reader_sessions where id=value
$$;
create function pg_temp.audit_exists(entry_value uuid,staff_value uuid) returns boolean language sql stable security definer set search_path='' as $$
  select exists(select 1 from nearby_private.moderation_audit where entry_id=entry_value and staff_id=staff_value)
$$;
create function pg_temp.owner_entry_count(actor uuid) returns bigint language sql stable security definer set search_path='' as $$
  select count(*) from nearby_private.entries where owner_id=actor
$$;
create function pg_temp.owner_request_count(actor uuid) returns bigint language sql stable security definer set search_path='' as $$
  select count(*) from nearby_private.publication_requests where owner_id=actor
$$;
create function pg_temp.post_publication_takedown_recorded(entry_value uuid) returns boolean language sql stable security definer set search_path='' as $$
  select coalesce((select state='rejected' and reviewed_at is not null and automatically_published_at is not null
    from nearby_private.publications where entry_id=entry_value),false)
$$;
create function pg_temp.post_publication_restore_recorded(entry_value uuid) returns boolean language sql stable security definer set search_path='' as $$
  select coalesce((select p.state='approved' and p.publication_mode='post_publication'
      and p.automatically_published_at is not null and p.reviewed_at is not null and e.moderation='approved'
    from nearby_private.publications p join nearby_private.entries e on e.id=p.entry_id
    where p.entry_id=entry_value),false)
$$;
create function pg_temp.finalize_synthetic_delete(operation_id uuid) returns jsonb language sql security definer set search_path='' as $$
  select public.nearby_service_delete('finalize',operation_id)
$$;

do $$
declare relation regclass; role_name text;
begin
  foreach role_name in array array['anon','authenticated','service_role'] loop
    for relation in select c.oid::regclass from pg_class c join pg_namespace n on n.oid=c.relnamespace
      where n.nspname='nearby_private' and c.relkind='r' loop
      perform pg_temp.ok(not has_table_privilege(role_name,relation,'SELECT,INSERT,UPDATE,DELETE'),
        'raw table denied '||role_name||' '||relation::text);
    end loop;
  end loop;
  perform pg_temp.ok(not exists(select 1 from pg_class c join pg_namespace n on n.oid=c.relnamespace
    where n.nspname='nearby_private' and c.relkind='r' and not c.relrowsecurity),'all private tables use RLS');
  perform pg_temp.ok(not exists(select 1 from pg_publication_tables where schemaname='nearby_private'),
    'private tables absent from realtime');
  perform pg_temp.ok(not has_function_privilege('anon','public.nearby_public_command(text,jsonb)','EXECUTE'),
    'anon cannot execute reading endpoint');
  perform pg_temp.ok(has_function_privilege('authenticated','public.nearby_public_command(text,jsonb)','EXECUTE'),
    'authenticated can execute reading endpoint');
  perform pg_temp.ok(not has_function_privilege('authenticated','public.nearby_command(text,jsonb)','EXECUTE'),
    'legacy foundation dispatcher is sealed');
  perform pg_temp.ok((select not prosecdef and provolatile='v' from pg_proc
    where oid='public.nearby_public_command(text,jsonb)'::regprocedure),'public wrapper is volatile invoker');
end $$;

insert into auth.users(id,aud,role,created_at,updated_at,is_anonymous)
select owner_id,'authenticated','authenticated',statement_timestamp(),statement_timestamp(),true from public_service_state
union all select reader_id,'authenticated','authenticated',statement_timestamp(),statement_timestamp(),true from public_service_state
union all select staff_id,'authenticated','authenticated',statement_timestamp(),statement_timestamp(),false from public_service_state;
insert into auth.users(id,aud,role,created_at,updated_at,is_anonymous)
select capacity_owner,'authenticated','authenticated',statement_timestamp(),statement_timestamp(),true from public_service_state;
insert into auth.sessions(id,user_id,created_at,updated_at)
select owner_session,owner_id,statement_timestamp(),statement_timestamp() from public_service_state
union all select reader_session,reader_id,statement_timestamp(),statement_timestamp() from public_service_state
union all select staff_session,staff_id,statement_timestamp(),statement_timestamp() from public_service_state;
insert into auth.sessions(id,user_id,created_at,updated_at)
select capacity_session,capacity_owner,statement_timestamp(),statement_timestamp() from public_service_state;

insert into nearby_private.entries(owner_id,request_id,request_hash,title,body,moderation,visibility)
select owner_id,gen_random_uuid(),extensions.digest('fixture','sha256'),'Synthetic title','0123456789 approved synthetic body','approved','public'
from public_service_state;

-- Capture the generated entry without relying on a client-visible identifier.
update public_service_state s set entry_id=(select id from nearby_private.entries where owner_id=s.owner_id limit 1);
insert into nearby_private.anchors(entry_id,position,accuracy_m,captured_at,location_consent_version)
select entry_id,gis.st_setsrid(gis.st_makepoint(0,0),4326)::gis.geography,5,statement_timestamp(),1 from public_service_state;
update nearby_private.publications set state='approved',reviewed_at=statement_timestamp()
where entry_id=(select entry_id from public_service_state);

set local role authenticated;
do $$ declare s pg_temp.public_service_state%rowtype; response jsonb;
begin
  select * into s from pg_temp.public_service_state;
  perform pg_temp.claims(s.reader_id,s.reader_session);
  response:=pg_temp.call('register_account','{"account_timezone":"Australia/Sydney"}');
  perform pg_temp.ok(response->>'status'='ACCOUNT_READY','fixed IANA timezone registered');
  perform pg_temp.ok(pg_temp.call('prepare_open','{"request_id":null,"generation":null}')->>'status'='SERVICE_DISABLED',
    'kill switch precedes malformed discovery work');
  perform pg_temp.ok(pg_temp.call('register_account','{"account_timezone":"UTC"}')->>'status'='TIMEZONE_IMMUTABLE',
    'account timezone cannot change');
  perform pg_temp.ok(pg_temp.call('prepare_open','{}')->>'status'='SERVICE_DISABLED','kill switch defaults closed');
end $$;
reset role;

update nearby_private.service_control set public_reading_enabled=true where singleton;

set local role authenticated;
do $$
declare s pg_temp.public_service_state%rowtype; payload jsonb; response jsonb; staged uuid; request_id uuid:=gen_random_uuid();
begin
  select * into s from pg_temp.public_service_state; perform pg_temp.claims(s.reader_id,s.reader_session);
  perform pg_temp.ok(pg_temp.call('react','{"request_id":null,"reader_session_id":null,"kind":null}')->>'status'='INVALID_REQUEST',
    'null reaction fields are rejected');
  perform pg_temp.ok(pg_temp.call('report','{"reader_session_id":null,"category":null}')->>'status'='INVALID_REQUEST',
    'null report fields are rejected');
  payload:=jsonb_build_object('request_id',request_id,'generation',1,'latitude',1,'longitude',1,'accuracy_m',5,
    'captured_at',to_char(statement_timestamp(),'YYYY-MM-DD"T"HH24:MI:SS.MS"Z"'),'is_mock',false);
  response:=pg_temp.call('prepare_open',payload);
  perform pg_temp.ok(response->>'status'='NO_ELIGIBLE_CONTENT','out of range reveals no candidate');
  perform pg_temp.ok(pg_temp.read_used(s.reader_id) is null,'failed prepare spends nothing');

  payload:=payload||jsonb_build_object('request_id',s.prepare_request,'latitude',0,'longitude',0);
  response:=pg_temp.call('prepare_open',payload); staged:=(response->>'reader_session_id')::uuid;
  update pg_temp.public_service_state set protocol_session=staged;
  perform pg_temp.ok(response->>'status'='CONTENT_STAGED' and response->>'content_ready'='true','approved in-range text stages');
  perform pg_temp.ok(not (response ? 'title') and not (response ? 'body') and response->>'media_ready'='true',
    'prepare confirms readiness without disclosing content');
  perform pg_temp.ok(not (response ? 'entry_id') and not (response ? 'owner_id') and not (response ? 'latitude'),
    'staging response hides author anchor and stable entry identity');
  perform pg_temp.ok(pg_temp.read_used(s.reader_id) is null,'successful prepare still spends nothing');
  perform pg_temp.ok(pg_temp.call('prepare_open',payload)=response,'prepare retry is idempotent');
  perform pg_temp.ok(pg_temp.call('prepare_open',payload||jsonb_build_object('request_id',gen_random_uuid()))->>'status'='READING_IN_PROGRESS',
    'a different prepare request is rejected explicitly while this account is reading');

  request_id:=gen_random_uuid();
  payload:=jsonb_build_object('request_id',request_id,'reader_session_id',staged,'generation',1,'sequence',1,
    'latitude',0,'longitude',0,'accuracy_m',5,
    'captured_at',to_char(statement_timestamp(),'YYYY-MM-DD"T"HH24:MI:SS.MS"Z"'),'is_mock',false);
  response:=pg_temp.call('open_commit',payload);
  perform pg_temp.ok(response->>'status'='OPEN_COMMITTED' and response->>'used'='1' and response->>'daily_limit'='10',
    'ready commit atomically spends one read');
  perform pg_temp.ok(response ? 'title' and response ? 'body' and response->'media'='[]'::jsonb,
    'content is released only with committed receipt');
  response:=pg_temp.call('open_commit',payload);
  perform pg_temp.ok(response->>'status'='OPEN_COMMITTED' and not (response ? 'title') and not (response ? 'body'),
    'commit retry returns billing receipt without replaying content');
  perform pg_temp.ok(pg_temp.read_used(s.reader_id)=1,'commit retry did not double spend');
  perform pg_temp.ok(pg_temp.call('open_commit',payload||jsonb_build_object('generation',2))->>'status'='REQUEST_CONFLICT',
    'changed idempotency payload rejected');

  request_id:=gen_random_uuid();
  payload:=jsonb_build_object('request_id',request_id,'reader_session_id',staged,'generation',1,'sequence',2,'latitude',0,'longitude',0,
    'accuracy_m',5,'captured_at',to_char(statement_timestamp(),'YYYY-MM-DD"T"HH24:MI:SS.MS"Z"'),'is_mock',false);
  response:=pg_temp.call('validate',payload);
  perform pg_temp.ok(response->>'status'='IN_RANGE' and response ? 'server_expires_at','continuous validation has absolute expiry');
  perform pg_temp.ok((pg_temp.call('validate',payload))->>'server_expires_at'=response->>'server_expires_at',
    'validation retry preserves the original absolute expiry');
  response:=pg_temp.call('validate',jsonb_build_object('request_id',gen_random_uuid(),'reader_session_id',staged,
    'generation',1,'sequence',3,'latitude',0,'longitude',0,'accuracy_m',5,
    'captured_at',to_char(statement_timestamp(),'YYYY-MM-DD"T"HH24:MI:SS.MS"Z"'),'is_mock',true));
  perform pg_temp.ok(response->>'status'='UNKNOWN' and not (response ? 'body'),
    'an unreliable fix revokes plaintext without ending or extending recovery');
  response:=pg_temp.call('resume_content',jsonb_build_object('reader_session_id',staged,'generation',1,'sequence',4,
    'latitude',0,'longitude',0,'accuracy_m',5,
    'captured_at',to_char(statement_timestamp(),'YYYY-MM-DD"T"HH24:MI:SS.MS"Z"'),'is_mock',false));
  perform pg_temp.ok(response->>'status'='CONTENT_RESUMED' and response ? 'body',
    'fresh in-range fix can recover content inside the short UNKNOWN recovery window');
  perform pg_temp.ok(response->>'generation'='1' and response->>'sequence'='4',
    'resumed content carries the exact accepted generation and sequence envelope');
  perform pg_temp.ok(pg_temp.read_used(s.reader_id)=1,
    'recovery returns the already charged content without spending another daily slot');
  request_id:=gen_random_uuid();
  response:=pg_temp.call('react',jsonb_build_object('request_id',request_id,'reader_session_id',staged,'kind','hug'));
  perform pg_temp.ok(response->>'status'='REACTION_ACCEPTED','first reaction accepted during lease');
  perform pg_temp.ok(pg_temp.call('react',jsonb_build_object('request_id',request_id,'reader_session_id',staged,'kind','hug'))=response,
    'reaction retry is idempotent');
  perform pg_temp.ok(pg_temp.call('react',jsonb_build_object('request_id',gen_random_uuid(),'reader_session_id',staged,'kind','joy'))->>'status'='ALREADY_REACTED',
    'only one reaction per session');
  response:=pg_temp.call('resume_content',jsonb_build_object('reader_session_id',staged,'generation',1,'sequence',5,
    'latitude',1,'longitude',1,'accuracy_m',5,
    'captured_at',to_char(statement_timestamp(),'YYYY-MM-DD"T"HH24:MI:SS.MS"Z"'),'is_mock',false));
  perform pg_temp.ok(response->>'status'='OUT_OF_RANGE','out-of-range resume permanently ends the session');
  response:=pg_temp.call('resume_content',jsonb_build_object('reader_session_id',staged,'generation',1,'sequence',6,
    'latitude',0,'longitude',0,'accuracy_m',5,
    'captured_at',to_char(statement_timestamp(),'YYYY-MM-DD"T"HH24:MI:SS.MS"Z"'),'is_mock',false));
  perform pg_temp.ok(response->>'status'='ENDED','returning to range cannot revive an ended session');
end $$;
reset role;

-- The account date is derived from the immutable registered zone. This fixed
-- pair straddles Sydney midnight after the 2026 daylight-saving transition.
do $$ declare s pg_temp.public_service_state%rowtype; begin
  select * into s from pg_temp.public_service_state;
  perform pg_temp.ok(pg_temp.account_day_at(s.reader_id,'2026-04-05 13:59:59+00')=date '2026-04-05'
    and pg_temp.account_day_at(s.reader_id,'2026-04-05 14:00:00+00')=date '2026-04-06',
    'registered timezone maps the exact local-midnight boundary to a new account date');
  insert into nearby_private.read_days(reader_id,account_date,used)
    values(s.reader_id,(statement_timestamp() at time zone 'Australia/Sydney')::date-1,10);
  perform pg_temp.ok(pg_temp.read_used_on(s.reader_id,
    (statement_timestamp() at time zone 'Australia/Sydney')::date-1)=10 and pg_temp.read_used(s.reader_id)=1,
    'a full previous account date neither carries into nor overwrites the current date');
end $$;

-- Simulate a client crash whose UNKNOWN recovery window elapsed. A later prepare
-- must retire the abandoned open row instead of being trapped by the active index.
update nearby_private.reader_sessions
set state='open',ended_at=null,lease_until=created_at,recovery_deadline=created_at
where id=(select protocol_session from public_service_state);
set local role authenticated;
do $$ declare s pg_temp.public_service_state%rowtype; response jsonb;
begin
  select * into s from pg_temp.public_service_state; perform pg_temp.claims(s.reader_id,s.reader_session);
  response:=pg_temp.call('prepare_open',jsonb_build_object('request_id',gen_random_uuid(),'generation',20,
    'latitude',0,'longitude',0,'accuracy_m',5,
    'captured_at',to_char(statement_timestamp(),'YYYY-MM-DD"T"HH24:MI:SS.MS"Z"'),'is_mock',false));
  perform pg_temp.ok(response->>'status'='NO_ELIGIBLE_CONTENT',
    'expired crash recovery is cleaned before a new prepare instead of blocking forever');
  perform pg_temp.ok(pg_temp.session_was_revoked(s.protocol_session),'expired recovery permanently ends the abandoned session');
end $$;
reset role;

set local role authenticated;
do $$ declare s pg_temp.public_service_state%rowtype; response jsonb; create_payload jsonb; create_request uuid:=gen_random_uuid(); public_entry uuid;
begin
  select * into s from pg_temp.public_service_state; perform pg_temp.claims(s.owner_id,s.owner_session);
  response:=pg_temp.call('register_account','{"account_timezone":"Australia/Sydney"}');
  create_payload:=jsonb_build_object('request_id',create_request,'title','Immutable synthetic','body','0123456789 immutable snapshot',
    'paper_style','kraft','font_style','serif','weather_kind','sunny','latitude',0,'longitude',0,'accuracy_m',5,
    'captured_at',to_char(statement_timestamp(),'YYYY-MM-DD"T"HH24:MI:SS.MS"Z"'),'is_mock',false,
    'movement_status','settled','consent_version',1,'share_confirmed',true,
    'weather_snapshot',jsonb_build_object('kind','clear','daylight','day','temperature_c',21,
      'captured_at',to_char(statement_timestamp(),'YYYY-MM-DD"T"HH24:MI:SS.MS"Z"'),'is_demo',false),
    'media_manifest','[]'::jsonb);
  response:=pg_temp.call('create_public',create_payload);
  public_entry:=(response->>'entry_id')::uuid;
  perform pg_temp.ok(response->>'status'='PUBLICATION_PREPARED' and response->>'publication_state'='ready_for_immediate_publication',
    'authenticated anonymous account can prepare immutable public snapshot for review');
  perform pg_temp.ok(pg_temp.call('create_public',create_payload)=response,'public create retry recovers same snapshot');
  perform pg_temp.ok(pg_temp.call('create_public',create_payload||jsonb_build_object('body','0123456789 changed snapshot'))->>'status'='REQUEST_CONFLICT',
    'immutable public snapshot rejects changed retry');
  perform pg_temp.ok(pg_temp.call('create_public',jsonb_set(create_payload,'{weather_snapshot,daylight}','"night"'))
    ->>'status'='REQUEST_CONFLICT','request identity includes full weather snapshot');
  perform pg_temp.ok(pg_temp.call('create_public',jsonb_set(create_payload,'{media_manifest}',
    jsonb_build_array(jsonb_build_object('id',gen_random_uuid(),'content_type','image/jpeg','bytes',100,'sha256',repeat('a',64)))))
    ->>'status'='REQUEST_CONFLICT','request identity includes complete media manifest');
  perform pg_temp.ok(pg_temp.call('cancel_publication_request',jsonb_build_object('request_id',create_request))->>'status'='FOUND',
    'cancel cannot overwrite an already committed publication receipt');
  declare
    canceled_request uuid:=gen_random_uuid();
  begin
    perform pg_temp.ok(pg_temp.call('cancel_publication_request',jsonb_build_object('request_id',canceled_request))->>'status'='CANCELLED',
      'uncommitted publication request is durably canceled');
    perform pg_temp.ok(pg_temp.call('cancel_publication_request',jsonb_build_object('request_id',canceled_request))->>'status'='CANCELLED',
      'cancel retry is idempotent');
    perform pg_temp.ok(pg_temp.call('create_public',jsonb_set(create_payload,'{request_id}',to_jsonb(canceled_request::text)))->>'status'='CANCELLED',
      'late create cannot publish after cancellation won the serialization lock');
  end;
  perform pg_temp.ok(pg_temp.call('set_visibility',jsonb_build_object('entry_id',public_entry,'visibility','private'))->>'visibility'='private',
    'owner can make a publication private without a new location');
  response:=pg_temp.call('set_visibility',jsonb_build_object('entry_id',public_entry,'visibility','public'));
  perform pg_temp.ok(response->>'visibility'='public' and response->>'publication_state'='ready_for_immediate_publication'
    and response->>'moderation_mode'='post_publication' and not (response ? 'moderation'),
    'republishing retains the snapshot and is explicitly post-publication moderation');
  response:=pg_temp.call('delete_entry',jsonb_build_object('entry_id',public_entry));
  perform pg_temp.ok(response->>'status'='DELETE_PREPARED', 'owner can begin deletion of the snapshot');
  perform pg_temp.ok(pg_temp.finalize_synthetic_delete((response->>'operation_id')::uuid)->>'status'='ENTRY_DELETED',
    'server confirms text-only snapshot deletion');
  perform pg_temp.ok(pg_temp.call('create_public',create_payload)->>'status'='DELETED',
    'deleted request tombstone prevents recreation');
  response:=pg_temp.call('reaction_inbox','{}');
  perform pg_temp.ok(response->>'status'='FOUND' and jsonb_array_length(response->'items')=1,'owner sees anonymous aggregate');
  perform pg_temp.ok(response::text not like '%'||s.reader_id::text||'%','inbox does not disclose reader identity');
  perform pg_temp.ok((pg_temp.call('reaction_inbox',jsonb_build_object('seen_through',response->>'seen_through'))
    ->'items'->0->>'unread')::integer=0,'bounded watermark acknowledgement marks only displayed reactions');
  perform pg_temp.ok(pg_temp.reaction_was_read(),'owner mark-read persisted');
end $$;
reset role;

-- Add ten fresh, approved, text-only candidates; the reader already spent one read.
do $$ declare s pg_temp.public_service_state%rowtype; new_entry uuid; i integer;
begin
  select * into s from pg_temp.public_service_state;
  for i in 1..10 loop
    insert into nearby_private.entries(owner_id,request_id,request_hash,title,body,moderation,visibility)
      values(s.owner_id,gen_random_uuid(),extensions.digest('quota-'||i,'sha256'),'Quota synthetic '||i,
        '0123456789 quota synthetic body '||i,'approved','public') returning id into new_entry;
    insert into nearby_private.anchors values(new_entry,gis.st_setsrid(gis.st_makepoint(0,0),4326)::gis.geography,5,statement_timestamp(),1);
    update nearby_private.publications set state='approved',reviewed_at=statement_timestamp() where entry_id=new_entry;
    if i=1 then update public_service_state set review_entry=new_entry; end if;
  end loop;
end $$;

set local role authenticated;
do $$
declare s pg_temp.public_service_state%rowtype; prep jsonb; committed jsonb; i integer; captured text;
begin
  select * into s from pg_temp.public_service_state; perform pg_temp.claims(s.reader_id,s.reader_session);
  for i in 2..10 loop
    captured:=to_char(statement_timestamp(),'YYYY-MM-DD"T"HH24:MI:SS.MS"Z"');
    prep:=pg_temp.call('prepare_open',jsonb_build_object('request_id',gen_random_uuid(),'generation',i,'latitude',0,
      'longitude',0,'accuracy_m',5,'captured_at',captured,'is_mock',false));
    perform pg_temp.ok(prep->>'status'='CONTENT_STAGED','quota prepare '||i);
    committed:=pg_temp.call('open_commit',jsonb_build_object('request_id',gen_random_uuid(),
      'reader_session_id',prep->>'reader_session_id','generation',i,'sequence',1,'latitude',0,'longitude',0,
      'accuracy_m',5,'captured_at',captured,'is_mock',false));
    perform pg_temp.ok(committed->>'status'='OPEN_COMMITTED' and (committed->>'used')::integer=i,'quota commit '||i);
    perform pg_temp.ok(pg_temp.call('end_session',jsonb_build_object('reader_session_id',prep->>'reader_session_id'))->>'status'='ENDED',
      'quota session ended '||i);
  end loop;
  prep:=pg_temp.call('prepare_open',jsonb_build_object('request_id',gen_random_uuid(),'generation',11,'latitude',0,
    'longitude',0,'accuracy_m',5,'captured_at',to_char(statement_timestamp(),'YYYY-MM-DD"T"HH24:MI:SS.MS"Z"'),'is_mock',false));
  perform pg_temp.ok(prep->>'status'='DAILY_LIMIT_REACHED' and prep->>'used'='10',
    'eleventh read is refused before content staging');
end $$;
reset role;

-- Cloud capacity counts extant entries, not permanent request receipts. The
-- submit advisory lock serializes both legacy create_public and create_entry.
set local role authenticated;
do $$ declare s pg_temp.public_service_state%rowtype; begin
  select * into s from pg_temp.public_service_state; perform pg_temp.claims(s.capacity_owner,s.capacity_session);
  perform pg_temp.ok(pg_temp.call('register_account','{"account_timezone":"Etc/UTC"}')->>'status'='ACCOUNT_READY',
    'capacity fixture account registers');
end $$;
reset role;
insert into nearby_private.entries(owner_id,request_id,request_hash,title,body,moderation,visibility)
select s.capacity_owner,gen_random_uuid(),extensions.digest(gen_random_uuid()::text,'sha256'),
  'Capacity fixture','0123456789 synthetic capacity fixture','pending','private'
from pg_temp.public_service_state s cross join generate_series(1,999);
set local role authenticated;
do $$
declare s pg_temp.public_service_state%rowtype; payload jsonb; response jsonb; first_response jsonb; first_request uuid:=gen_random_uuid();
begin
  select * into s from pg_temp.public_service_state; perform pg_temp.claims(s.capacity_owner,s.capacity_session);
  payload:=jsonb_build_object('request_id',first_request,'title','Boundary entry','body','0123456789 boundary cloud entry',
    'paper_style','warm_white','font_style','clear','visibility','private','latitude',0,'longitude',0,'accuracy_m',5,
    'captured_at',to_char(statement_timestamp(),'YYYY-MM-DD"T"HH24:MI:SS.MS"Z"'),'is_mock',false,
    'movement_status','settled','consent_version',1,'share_confirmed',true,
    'weather_snapshot',jsonb_build_object('kind','unknown','daylight','day','temperature_c',null,
      'captured_at',null,'is_demo',false),'media_manifest','[]'::jsonb);
  first_response:=pg_temp.call('create_entry',payload);
  update pg_temp.public_service_state set capacity_entry=(first_response->>'entry_id')::uuid;
  perform pg_temp.ok(first_response->>'status'='PRIVATE_SAVED' and pg_temp.owner_entry_count(s.capacity_owner)=1000,
    'the one-thousandth extant cloud entry is accepted [status='||coalesce(first_response->>'status','null')||
      ', count='||pg_temp.owner_entry_count(s.capacity_owner)::text||']');
  perform pg_temp.ok(pg_temp.call('create_entry',payload)=first_response and pg_temp.owner_entry_count(s.capacity_owner)=1000,
    'idempotent retry at capacity consumes no additional slot');
  response:=pg_temp.call('create_public',payload-'visibility'||jsonb_build_object('request_id',gen_random_uuid()));
  perform pg_temp.ok(response->>'status'='CLOUD_ENTRY_LIMIT' and pg_temp.owner_entry_count(s.capacity_owner)=1000,
    'both delegated create paths reject a new entry above the shared limit');
  response:=pg_temp.call('delete_entry',jsonb_build_object('entry_id',(first_response->>'entry_id')::uuid));
  update pg_temp.public_service_state set capacity_delete_operation=(response->>'operation_id')::uuid;
  perform pg_temp.ok(response->>'status'='DELETE_PREPARED' and pg_temp.owner_entry_count(s.capacity_owner)=1000,
    'deleting entries continue consuming capacity until finalization');
end $$;
reset role;
do $$ declare s pg_temp.public_service_state%rowtype; deletion_result jsonb; begin
  select * into s from pg_temp.public_service_state;
  deletion_result:=public.nearby_service_delete('finalize',s.capacity_delete_operation);
  perform pg_temp.ok(deletion_result->>'status'='ENTRY_DELETED'
    and pg_temp.owner_entry_count(s.capacity_owner)=999 and pg_temp.owner_request_count(s.capacity_owner)=1,
    'completed deletion releases one slot while retaining its idempotency receipt');
end $$;
set local role authenticated;
do $$ declare s pg_temp.public_service_state%rowtype; payload jsonb; response jsonb; begin
  select * into s from pg_temp.public_service_state; perform pg_temp.claims(s.capacity_owner,s.capacity_session);
  payload:=jsonb_build_object('request_id',gen_random_uuid(),'title','Replacement entry','body','0123456789 replacement cloud entry',
    'paper_style','warm_white','font_style','clear','visibility','private','latitude',0,'longitude',0,'accuracy_m',5,
    'captured_at',to_char(statement_timestamp(),'YYYY-MM-DD"T"HH24:MI:SS.MS"Z"'),'is_mock',false,
    'movement_status','settled','consent_version',1,'share_confirmed',true,
    'weather_snapshot',jsonb_build_object('kind','unknown','daylight','day','temperature_c',null,
      'captured_at',null,'is_demo',false),'media_manifest','[]'::jsonb);
  response:=pg_temp.call('create_entry',payload);
  perform pg_temp.ok(response->>'status'='PRIVATE_SAVED'
    and pg_temp.owner_entry_count(s.capacity_owner)=1000 and pg_temp.owner_request_count(s.capacity_owner)=2,
    'a new entry succeeds after deletion despite the retained old receipt');
end $$;
reset role;

set local role authenticated;
do $$ declare s pg_temp.public_service_state%rowtype; response jsonb;
begin
  select * into s from pg_temp.public_service_state; perform pg_temp.claims(s.reader_id,s.reader_session);
  response:=pg_temp.call('report',jsonb_build_object('reader_session_id',s.protocol_session,'category','privacy','detail','synthetic'));
  perform pg_temp.ok(response='{"status":"REPORT_RECEIVED"}'::jsonb,'report receipt is opaque');
  perform pg_temp.ok(pg_temp.call('block',jsonb_build_object('reader_session_id',s.protocol_session))->>'status'='BLOCKED',
    'reader can block encountered author without learning identity');
  perform pg_temp.ok(pg_temp.call('end_session',jsonb_build_object('reader_session_id',s.protocol_session))->>'status'='ENDED',
    'explicit end is available and idempotently revokes the lease');
end $$;
reset role;

set local role authenticated;
do $$ declare s pg_temp.public_service_state%rowtype;
begin
  select * into s from pg_temp.public_service_state; perform pg_temp.claims(s.owner_id,s.owner_session);
  perform pg_temp.ok(pg_temp.call('withdraw_publication',jsonb_build_object('entry_id',s.entry_id))->>'status'='WITHDRAWN',
    'owner withdraws without returning to anchor');
  perform pg_temp.ok(pg_temp.session_was_revoked(s.protocol_session),
    'withdraw immediately revokes active server lease');
end $$;
reset role;

-- Manual review is server/staff-table authorized, not JWT user_metadata authorized.
set local role authenticated;
do $$ declare s pg_temp.public_service_state%rowtype; response jsonb;
begin
  select * into s from pg_temp.public_service_state;
  perform pg_temp.claims(s.reader_id,s.reader_session);
  response:=public.nearby_staff_command('reject',jsonb_build_object('entry_id',s.review_entry,'reason_code','privacy'));
  perform pg_temp.ok(response->>'status'='AUTH_REQUIRED','ordinary reader cannot moderate');
  perform set_config('request.jwt.claims',(current_setting('request.jwt.claims')::jsonb||
    '{"user_metadata":{"staff":true},"app_metadata":{"staff":true}}'::jsonb)::text,true);
  perform pg_temp.ok(public.nearby_staff_command('reject',jsonb_build_object('entry_id',s.review_entry,'reason_code','privacy'))->>'status'='AUTH_REQUIRED',
    'JWT metadata cannot grant staff');
  perform pg_temp.claims(s.staff_id,s.staff_session);
  perform pg_temp.ok(public.nearby_staff_command('reject',jsonb_build_object('entry_id',s.review_entry,'reason_code','privacy'))->>'status'='AUTH_REQUIRED',
    'unlisted signed-in user is not staff');
end $$;
reset role;
insert into nearby_private.staff_members(user_id,enabled) select staff_id,true from public_service_state;
set local role authenticated;
do $$ declare s pg_temp.public_service_state%rowtype; response jsonb;
begin
  select * into s from pg_temp.public_service_state; perform pg_temp.claims(s.staff_id,s.staff_session);
  response:=public.nearby_staff_command('list_pending','{"limit":10}'::jsonb);
  perform pg_temp.ok(response->>'status'='FOUND' and response::text not like '%owner_id%' and
    response::text not like '%latitude%' and response::text not like '%longitude%',
    'staff queue exposes review content without author identity or precise anchor');
  response:=public.nearby_staff_command('reject',jsonb_build_object('entry_id',s.review_entry,'reason_code','privacy'));
  perform pg_temp.ok(response->>'status'='REVIEWED','enabled staff can record manual decision');
  perform pg_temp.ok(pg_temp.audit_exists(s.review_entry,s.staff_id),
    'moderation decision audited');
end $$;
reset role;

-- Complete-contract functional path: immutable two-photo private save, validation,
-- post-publication discovery, opaque stranger media, CAS, and lifecycle revocation.
set local role authenticated;
do $$ declare s pg_temp.public_service_state%rowtype; payload jsonb; response jsonb; request_id uuid:=gen_random_uuid();
begin
  select * into s from pg_temp.public_service_state; perform pg_temp.claims(s.owner_id,s.owner_session);
  perform pg_temp.ok(pg_temp.call('account_get','{}')->>'account_timezone'='Australia/Sydney','account_get returns registered immutable timezone');
  payload:=jsonb_build_object('request_id',request_id,'title','Two private photos','body','0123456789 immutable two photo body',
    'paper_style','kraft','font_style','serif','visibility','private','latitude',0,'longitude',0,'accuracy_m',5,
    'captured_at',to_char(statement_timestamp(),'YYYY-MM-DD"T"HH24:MI:SS.MS"Z"'),'is_mock',false,
    'movement_status','settled','consent_version',1,'share_confirmed',true,
    'weather_snapshot',jsonb_build_object('kind','clear','daylight','night','temperature_c',18,
      'captured_at',to_char(statement_timestamp(),'YYYY-MM-DD"T"HH24:MI:SS.MS"Z"'),'is_demo',false),
    'media_manifest',jsonb_build_array(
      jsonb_build_object('id',s.media_one,'content_type','image/jpeg','bytes',100,'sha256',repeat('a',64)),
      jsonb_build_object('id',s.media_two,'content_type','image/jpeg','bytes',120,'sha256',repeat('b',64))));
  response:=pg_temp.call('create_entry',payload);
  update pg_temp.public_service_state set media_entry=(response->>'entry_id')::uuid;
  perform pg_temp.ok(response->>'publication_state'='private','private two-photo snapshot is not discoverable while uploading');
  perform pg_temp.ok(pg_temp.call('create_entry',payload)=response,'full two-photo retry is immutable and idempotent');
  perform pg_temp.ok(pg_temp.call('create_entry',jsonb_set(payload,'{weather_snapshot,temperature_c}','19'))
    ->>'status'='REQUEST_CONFLICT','changed frozen weather is rejected');
  response:=pg_temp.call('account_state_get','{}');
  perform pg_temp.ok(pg_temp.call('account_state_put',jsonb_build_object('expected_version',response->'version','state',
    jsonb_set(response->'state','{placements}',jsonb_build_array(jsonb_build_object('entry_id',(select media_entry from pg_temp.public_service_state),
      'instance_id','cygnus-1','star_index',0)))))->>'status'='UPDATED','valid owned cloud placement CAS succeeds');
  perform pg_temp.ok(pg_temp.call('account_state_put',jsonb_build_object('expected_version',1,'state',
    jsonb_set(response->'state','{instances,0,ordinal}','1.5')))->>'status'='INVALID_REQUEST','fractional constellation ordinal fails closed');
end $$;
reset role;

insert into storage.objects(bucket_id,name,owner_id,metadata)
select 'nearby-private-photos',owner_id::text||'/'||media_entry||'/'||media_one,owner_id::text,'{}'::jsonb from public_service_state
union all select 'nearby-private-photos',owner_id::text||'/'||media_entry||'/'||media_two,owner_id::text,'{}'::jsonb from public_service_state;
do $$ declare s pg_temp.public_service_state%rowtype; begin
  select * into s from pg_temp.public_service_state;
  perform pg_temp.ok(public.nearby_media_validation(s.media_one,'image/jpeg',100,640,480,repeat('a',64),true)->>'status'='MEDIA_PARTIAL',
    'first validated photo keeps publication unavailable');
  perform pg_temp.ok(public.nearby_media_validation(s.media_two,'image/jpeg',120,800,600,repeat('b',64),true)->>'status'='MEDIA_READY',
    'second validated photo completes immutable media set');
end $$;

set local role authenticated;
do $$ declare s pg_temp.public_service_state%rowtype; response jsonb;
begin
  select * into s from pg_temp.public_service_state; perform pg_temp.claims(s.reader_id,s.reader_session);
  perform pg_temp.ok(pg_temp.call('media_upload',jsonb_build_object('entry_id',s.media_entry,'media_id',s.media_one))->>'status'='NOT_FOUND',
    'foreign account cannot obtain owner upload path');
  perform pg_temp.claims(s.owner_id,s.owner_session);
  response:=pg_temp.call('own_entry',jsonb_build_object('entry_id',s.media_entry));
  perform pg_temp.ok(jsonb_array_length(response->'item'->'media')=2 and response::text not like '%object_path%',
    'owner reconciliation returns two opaque media descriptors');
  perform pg_temp.ok(pg_temp.call('set_visibility',jsonb_build_object('entry_id',s.media_entry,'visibility','public'))
    ->>'publication_state'='ready_for_immediate_publication','fully validated private media can publish without returning to anchor');
end $$;
reset role;

delete from nearby_private.blocks where blocker_id in(select owner_id from public_service_state union all select reader_id from public_service_state)
  or blocked_id in(select owner_id from public_service_state union all select reader_id from public_service_state);
delete from nearby_private.reports where reporter_id=(select reader_id from public_service_state);
delete from nearby_private.encounters where reader_id=(select reader_id from public_service_state);
delete from nearby_private.read_days where reader_id=(select reader_id from public_service_state);
update nearby_private.entries set visibility='withdrawn',withdrawn_at=coalesce(withdrawn_at,statement_timestamp())
where owner_id=(select owner_id from public_service_state) and id<>(select media_entry from public_service_state);
update nearby_private.publications set state='withdrawn',reviewed_at=coalesce(reviewed_at,statement_timestamp())
where entry_id in(select id from nearby_private.entries where owner_id=(select owner_id from public_service_state))
  and entry_id<>(select media_entry from public_service_state);
set local role authenticated;
do $$ declare s pg_temp.public_service_state%rowtype; prep jsonb; opened jsonb; captured text; access_one jsonb; access_retry jsonb;
begin
  select * into s from pg_temp.public_service_state; perform pg_temp.claims(s.reader_id,s.reader_session);
  prep:=pg_temp.call('account_state_get','{}');
  perform pg_temp.ok(pg_temp.call('account_state_put',jsonb_build_object('expected_version',prep->'version','state',
    jsonb_set(prep->'state','{placements}',jsonb_build_array(jsonb_build_object('entry_id',s.media_entry,
      'instance_id','cygnus-1','star_index',0)))))->>'status'='INVALID_REQUEST',
    'foreign account cannot place another owners cloud entry');
  captured:=to_char(statement_timestamp(),'YYYY-MM-DD"T"HH24:MI:SS.MS"Z"');
  prep:=pg_temp.call('prepare_open',jsonb_build_object('request_id',gen_random_uuid(),'generation',100,'latitude',0,'longitude',0,
    'accuracy_m',5,'captured_at',captured,'is_mock',false));
  opened:=pg_temp.call('open_commit',jsonb_build_object('request_id',gen_random_uuid(),'reader_session_id',prep->>'reader_session_id',
    'generation',100,'sequence',1,'latitude',0,'longitude',0,'accuracy_m',5,'captured_at',captured,'is_mock',false));
  update pg_temp.public_service_state set media_session=(prep->>'reader_session_id')::uuid;
  perform pg_temp.ok(opened->>'status'='OPEN_COMMITTED' and jsonb_array_length(opened->'media')=2
    and opened::text not like '%object_path%' and opened::text not like '%'||s.owner_id::text||'%',
    'stranger receives both opaque photos atomically without author or storage identity');
  access_one:=pg_temp.call('media_access',jsonb_build_object('media_id',s.media_one));
  access_retry:=pg_temp.call('media_access',jsonb_build_object('media_id',s.media_one));
  perform pg_temp.ok(access_one->>'status'='MEDIA_AUTHORIZED' and access_retry->>'ticket_id'=access_one->>'ticket_id',
    'media access reuses one short-lived ticket per reader and media');
end $$;
reset role;

-- Post-publication reports can be actioned and then restored without erasing
-- the automatic publication timestamp or misrepresenting the human restore.
set local role authenticated;
do $$ declare s pg_temp.public_service_state%rowtype; response jsonb;
begin
  select * into s from pg_temp.public_service_state; perform pg_temp.claims(s.reader_id,s.reader_session);
  response:=pg_temp.call('report',jsonb_build_object('reader_session_id',s.media_session,
    'category','spam','detail','synthetic post-publication report'));
  perform pg_temp.ok(response->>'status'='REPORT_RECEIVED','reader can report the post-publication media entry');
end $$;
reset role;
set local role authenticated;
do $$ declare s pg_temp.public_service_state%rowtype; response jsonb; report_id uuid;
begin
  select * into s from pg_temp.public_service_state; perform pg_temp.claims(s.staff_id,s.staff_session);
  response:=public.nearby_staff_command('list_reports','{"limit":50}'::jsonb);
  select (item->>'report_id')::uuid into report_id from jsonb_array_elements(response->'items') item
    where (item->>'entry_id')::uuid=s.media_entry;
  perform pg_temp.ok(report_id is not null,'staff queue contains the post-publication report');
  perform pg_temp.ok(public.nearby_staff_command('report_action',jsonb_build_object(
    'report_id',report_id,'reason_code','confirmed_spam'))->>'status'='REPORT_RESOLVED',
    'staff report action takes down the post-publication entry');
  perform pg_temp.ok(pg_temp.post_publication_takedown_recorded(s.media_entry),
    'takedown retains automatic publication history and records human review');
  perform pg_temp.ok(public.nearby_staff_command('restore',jsonb_build_object(
    'entry_id',s.media_entry,'reason_code','operator_correction'))->>'status'='REVIEWED',
    'staff can restore an automatically published entry after takedown');
  perform pg_temp.ok(pg_temp.post_publication_restore_recorded(s.media_entry),
    'restored publication distinguishes original automatic publication from later human approval');
end $$;
reset role;

set local role authenticated;
do $$ declare s pg_temp.public_service_state%rowtype; response jsonb;
begin
  select * into s from pg_temp.public_service_state; perform pg_temp.claims(s.owner_id,s.owner_session);
  response:=pg_temp.call('delete_entry',jsonb_build_object('entry_id',s.media_entry));
  update pg_temp.public_service_state set delete_operation=(response->>'operation_id')::uuid;
  perform pg_temp.ok(response->>'status'='DELETE_PREPARED','entry deletion enters persistent purge state');
  response:=pg_temp.call('account_state_get','{}');
  perform pg_temp.ok(response->>'version'='2' and jsonb_array_length(response->'state'->'placements')=0,
    'delete atomically removes cloud placement and advances CAS version');
end $$;
reset role;
set local role authenticated;
do $$ declare s pg_temp.public_service_state%rowtype; begin
  select * into s from pg_temp.public_service_state; perform pg_temp.claims(s.reader_id,s.reader_session);
  perform pg_temp.ok(pg_temp.call('media_access',jsonb_build_object('media_id',s.media_one))->>'status'='MEDIA_NOT_AUTHORIZED',
    'delete start revokes stranger media authorization');
end $$;
reset role;
-- Storage API cleanup is a separate HTTP integration test. Do not bypass
-- storage.protect_delete: these synthetic metadata rows disappear on rollback.
do $$ declare s pg_temp.public_service_state%rowtype; begin
  select * into s from pg_temp.public_service_state;
  perform pg_temp.ok(public.nearby_service_delete('finalize',s.delete_operation)->>'status'='OBJECTS_REMAIN',
    'service finalizer refuses deletion while storage objects remain');
end $$;

set local role authenticated;
do $$ declare s pg_temp.public_service_state%rowtype; response jsonb; begin
  select * into s from pg_temp.public_service_state; perform pg_temp.claims(s.owner_id,s.owner_session);
  response:=pg_temp.call('account_delete','{"confirmation":"DELETE"}');
  update pg_temp.public_service_state set delete_operation=(response->>'operation_id')::uuid;
  perform pg_temp.ok(response->>'status'='DELETE_PREPARED','account deletion enters retryable persistent purge state');
end $$;
reset role;
do $$ declare s pg_temp.public_service_state%rowtype; begin
  select * into s from pg_temp.public_service_state;
  perform pg_temp.ok(public.nearby_service_delete('finalize',s.delete_operation)->>'status'='OBJECTS_REMAIN',
    'account finalizer also refuses deletion while storage objects remain');
end $$;
set local role authenticated;
do $$ declare s pg_temp.public_service_state%rowtype; response jsonb; begin
  select * into s from pg_temp.public_service_state; perform pg_temp.claims(s.reader_id,s.reader_session);
  response:=pg_temp.call('account_delete','{"confirmation":"DELETE"}');
  perform pg_temp.ok(response->>'status'='DELETE_PREPARED','clean generated account enters deletion');
  perform pg_temp.ok(pg_temp.finalize_synthetic_delete((response->>'operation_id')::uuid)->>'status'='ACCOUNT_DELETED',
    'account finalizer removes a clean auth account');
  perform pg_temp.ok(pg_temp.call('account_get','{}')->>'status'='AUTH_REQUIRED','deleted accounts invalidate old JWT sessions');
end $$;
reset role;

select count(*) as passed_assertions from public_service_results;
rollback;
