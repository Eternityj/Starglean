-- Run against the isolated test project as the migration owner, never as app code.
-- Every user, session, location, entry, helper and grant below is synthetic and ROLLED BACK.
-- Passing these database tests is not proof of Auth HTTP behavior, concurrency, GPS,
-- PostgREST exposure settings, moderation, or a working anonymous-reading client.
begin;
set local statement_timeout = '30s';
set local lock_timeout = '3s';
set local timezone = 'UTC';

create temporary table nearby_test_results (label text primary key) on commit drop;
create temporary table nearby_test_state (
  singleton boolean primary key default true check (singleton),
  actor_a uuid not null default gen_random_uuid(),
  actor_b uuid not null default gen_random_uuid(),
  session_a uuid not null default gen_random_uuid(),
  session_b uuid not null default gen_random_uuid(),
  entry_id uuid,
  payload jsonb not null default '{}'::jsonb,
  last_response jsonb
) on commit drop;
insert into nearby_test_state default values;
grant select, insert on nearby_test_results to authenticated, anon;
grant select, update on nearby_test_state to authenticated, anon;
do $$ begin
  execute format('grant usage on schema %I to authenticated, anon', pg_my_temp_schema()::regnamespace::text);
end $$;

create function pg_temp.check_that(ok boolean, label text) returns void
language plpgsql security invoker set search_path = '' as $$
begin
  if ok is distinct from true then raise exception 'Nearby foundation test failed: %', label; end if;
  insert into pg_temp.nearby_test_results values (label);
end $$;

create function pg_temp.claims(actor uuid, session_uuid uuid, expiry numeric default null) returns void
language plpgsql security invoker set search_path = '' as $$
begin
  perform set_config('request.jwt.claim.sub', '', true);
  perform set_config('request.jwt.claims', jsonb_build_object(
    'sub', actor, 'session_id', session_uuid, 'role', 'authenticated',
    'exp', coalesce(expiry, extract(epoch from statement_timestamp()) + 3600)
  )::text, true);
end $$;

create function pg_temp.expect_status(action text, payload jsonb, expected text, label text) returns void
language plpgsql security invoker set search_path = '' as $$
declare result jsonb;
begin
  result := public.nearby_command(action, payload);
  perform pg_temp.check_that(result->>'status' = expected, label);
  -- Strict allowlist, not just an assertion that latitude happens to be absent.
  if result - array['status','entry_id','moderation','visibility','reading_enabled',
      'publication','protocol_version'] <> '{}'::jsonb then
    raise exception 'Nearby response has an unexpected field';
  end if;
  update pg_temp.nearby_test_state set last_response = result;
end $$;

-- Static privileges: no raw tables or pure geometry/text helpers are available to clients.
do $$
declare role_name text; relation regclass; helper record;
begin
  foreach role_name in array array['anon', 'authenticated', 'service_role'] loop
    for relation in select c.oid::regclass from pg_class c join pg_namespace n on n.oid = c.relnamespace
      where n.nspname = 'nearby_private' and c.relkind = 'r' loop
      perform pg_temp.check_that(not has_table_privilege(role_name, relation,
        'SELECT,INSERT,UPDATE,DELETE,TRUNCATE,REFERENCES,TRIGGER'),
        'raw table denial ' || role_name || ' ' || relation::text);
    end loop;
  end loop;
  perform pg_temp.check_that(not exists (
    select 1 from pg_class c join pg_namespace n on n.oid = c.relnamespace
    where n.nspname = 'nearby_private' and c.relkind = 'r' and not c.relrowsecurity
  ), 'all private tables have RLS');
  perform pg_temp.check_that(not exists (
    select 1 from pg_publication_tables where schemaname = 'nearby_private'
  ), 'private tables are not in realtime publication');
  perform pg_temp.check_that(not has_schema_privilege('authenticated', 'nearby_private', 'CREATE'),
    'client cannot replace private objects');
  perform pg_temp.check_that(not has_schema_privilege('authenticated', 'gis', 'USAGE'),
    'client has no GIS schema access');
  for helper in select p.oid, p.proname from pg_proc p join pg_namespace n on n.oid=p.pronamespace
    where n.nspname='nearby_private' and p.proname <> 'command' loop
    foreach role_name in array array['anon','authenticated','service_role'] loop
      perform pg_temp.check_that(not has_function_privilege(role_name, helper.oid, 'EXECUTE'),
        'helper denied ' || role_name || ' ' || helper.proname);
    end loop;
  end loop;
  perform pg_temp.check_that(has_function_privilege('authenticated',
    'public.nearby_command(text,jsonb)', 'EXECUTE'), 'authenticated wrapper grant');
  perform pg_temp.check_that(has_function_privilege('authenticated',
    'nearby_private.command(text,jsonb)', 'EXECUTE'), 'authenticated dispatcher grant');
  perform pg_temp.check_that(not has_function_privilege('anon',
    'public.nearby_command(text,jsonb)', 'EXECUTE'), 'anonymous wrapper denied');
  perform pg_temp.check_that(not has_function_privilege('service_role',
    'public.nearby_command(text,jsonb)', 'EXECUTE'), 'service wrapper denied');
  perform pg_temp.check_that((select not prosecdef and provolatile='v' from pg_proc
    where oid='public.nearby_command(text,jsonb)'::regprocedure), 'wrapper is invoker and volatile');
end $$;

-- Pure synthetic geometry, no actual person's location.
do $$
declare origin gis.geography := gis.st_setsrid(gis.st_makepoint(0,0),4326)::gis.geography;
begin
  perform pg_temp.check_that(nearby_private.range_decision(origin,5,origin,5,0,false)='IN_RANGE', 'same point');
  perform pg_temp.check_that(nearby_private.range_decision(origin,5,gis.st_project(origin,189,0),5,0,false)='IN_RANGE', '189m safely inside');
  perform pg_temp.check_that(nearby_private.range_decision(origin,5,gis.st_project(origin,191,0),5,0,false)='UNKNOWN', '191m uncertainty overlap');
  perform pg_temp.check_that(nearby_private.range_decision(origin,5,gis.st_project(origin,200,0),5,0,false)='UNKNOWN', '200m uncertainty overlap');
  perform pg_temp.check_that(nearby_private.range_decision(origin,5,gis.st_project(origin,209,0),5,0,false)='UNKNOWN', '209m uncertainty overlap');
  perform pg_temp.check_that(nearby_private.range_decision(origin,5,gis.st_project(origin,211,0),5,0,false)='OUT_OF_RANGE', '211m safely outside');
  perform pg_temp.check_that(nearby_private.range_decision(origin,5,origin,35,7999,false)='IN_RANGE', 'qualified upper accuracy and age');
  perform pg_temp.check_that(nearby_private.range_decision(origin,5,origin,5,8000,false)='UNKNOWN', '8s fix expired');
  perform pg_temp.check_that(nearby_private.range_decision(origin,5,origin,5,-1,false)='UNKNOWN', 'negative fix age');
  perform pg_temp.check_that(nearby_private.range_decision(origin,5,origin,0,0,false)='UNKNOWN', 'zero accuracy');
  perform pg_temp.check_that(nearby_private.range_decision(origin,5,origin,36,0,false)='UNKNOWN', 'poor accuracy');
  perform pg_temp.check_that(nearby_private.range_decision(origin,'NaN',origin,5,0,false)='UNKNOWN', 'NaN anchor accuracy');
  perform pg_temp.check_that(nearby_private.range_decision(origin,5,origin,'Infinity',0,false)='UNKNOWN', 'infinite reader accuracy');
  perform pg_temp.check_that(nearby_private.range_decision(origin,5,origin,5,0,true)='UNKNOWN', 'mock fix');
  perform pg_temp.check_that(nearby_private.range_decision(origin,5,origin,5,0,null)='UNKNOWN', 'missing mock marker');
  perform pg_temp.check_that(nearby_private.range_decision(null,5,origin,5,0,false)='UNKNOWN', 'missing anchor');
  perform pg_temp.check_that(nearby_private.range_decision('POINT EMPTY'::gis.geography,5,origin,5,0,false)='UNKNOWN', 'empty anchor');
  perform pg_temp.check_that(nearby_private.range_decision('LINESTRING(0 0,0 1)'::gis.geography,5,origin,5,0,false)='UNKNOWN', 'nonpoint anchor');
  perform pg_temp.check_that(nearby_private.meaningful_length(repeat('🪐',10))=10, 'emoji codepoint count');
  perform pg_temp.check_that(nearby_private.meaningful_length(E' \n\t' || U&'\00a0\200b\200d\feff')=0, 'invisible padding excluded');
end $$;

-- Fake identities exist only within this rolled-back transaction; they cannot log in.
insert into auth.users(id,aud,role,created_at,updated_at,is_anonymous)
  select actor_a,'authenticated','authenticated',statement_timestamp(),statement_timestamp(),true from nearby_test_state
  union all select actor_b,'authenticated','authenticated',statement_timestamp(),statement_timestamp(),true from nearby_test_state;
insert into auth.sessions(id,user_id,created_at,updated_at)
  select session_a,actor_a,statement_timestamp(),statement_timestamp() from nearby_test_state
  union all select session_b,actor_b,statement_timestamp(),statement_timestamp() from nearby_test_state;

set local role anon;
do $$ begin
  begin
    perform public.nearby_command('status','{}');
    raise exception 'Anonymous role unexpectedly executed public endpoint';
  exception when insufficient_privilege then
    perform pg_temp.check_that(true,'actual anonymous execution denied');
  end;
end $$;
reset role;

set local role authenticated;
do $$
declare state pg_temp.nearby_test_state%rowtype;
begin
  select * into state from pg_temp.nearby_test_state;
  perform set_config('request.jwt.claims','{}',true);
  perform pg_temp.expect_status('status','{}','AUTH_REQUIRED','missing token claims');
  perform pg_temp.claims(state.actor_a,state.session_b);
  perform pg_temp.expect_status('status','{}','AUTH_REQUIRED','other user session');
  perform pg_temp.claims(state.actor_a,gen_random_uuid());
  perform pg_temp.expect_status('status','{}','AUTH_REQUIRED','absent session');
  perform pg_temp.claims(state.actor_a,state.session_a,extract(epoch from statement_timestamp())-1);
  perform pg_temp.expect_status('status','{}','AUTH_REQUIRED','expired JWT');
  perform pg_temp.claims(state.actor_a,state.session_a);
  perform pg_temp.expect_status('status','{}','TESTER_NOT_APPROVED','unlisted authenticated user');
  perform set_config('request.jwt.claims',jsonb_build_object('sub',state.actor_a,'session_id','not-a-uuid',
    'exp',extract(epoch from statement_timestamp())+3600)::text,true);
  perform pg_temp.expect_status('status','{}','AUTH_REQUIRED','malformed session UUID');
  perform pg_temp.claims(state.actor_a,state.session_a);
  perform set_config('request.jwt.claims',(current_setting('request.jwt.claims')::jsonb ||
    '{"user_metadata":{"tester":true},"app_metadata":{"tester":true}}'::jsonb)::text,true);
  perform pg_temp.expect_status('status','{}','TESTER_NOT_APPROVED','token metadata cannot enroll tester');
  begin
    perform 1 from nearby_private.anchors;
    raise exception 'Authenticated role unexpectedly read raw anchors';
  exception when insufficient_privilege then
    perform pg_temp.check_that(true,'actual raw anchor SELECT denied');
  end;
  begin
    perform nearby_private.meaningful_length('synthetic fixture');
    raise exception 'Authenticated role unexpectedly executed private helper';
  exception when insufficient_privilege then
    perform pg_temp.check_that(true,'actual private helper execution denied');
  end;
end $$;
reset role;

insert into nearby_private.testers(user_id,enabled,approved_at)
  select actor_a,true,statement_timestamp() from nearby_test_state
  union all select actor_b,true,statement_timestamp() from nearby_test_state;
update nearby_test_state set payload=jsonb_build_object(
  'request_id',gen_random_uuid(),'title','Synthetic test only','body','0123456789',
  'weather_kind','sunny','latitude',0,'longitude',0,'accuracy_m',5,
  'captured_at',to_char(statement_timestamp()-interval '100 milliseconds', 'YYYY-MM-DD"T"HH24:MI:SS.MS"Z"'),
  'is_mock',false,'consent_version',1,'share_confirmed',true);

set local role authenticated;
do $$
declare state pg_temp.nearby_test_state%rowtype; body_payload jsonb; item record;
begin
  select * into state from pg_temp.nearby_test_state;
  perform pg_temp.claims(state.actor_a,state.session_a);
  perform pg_temp.expect_status('status','{}','READY_FOR_TEST_SUBMISSION','approved tester status');
  perform pg_temp.check_that((select last_response->'reading_enabled'='false'::jsonb from pg_temp.nearby_test_state), 'reading stays explicitly disabled');
  perform pg_temp.expect_status(null,state.payload,'UNSUPPORTED_ACTION','null action does not submit');
  perform pg_temp.expect_status('read',state.payload,'UNSUPPORTED_ACTION','unimplemented reading fails closed');
  perform pg_temp.expect_status('submit',null,'INVALID_REQUEST','null payload');
  perform pg_temp.expect_status('submit','[]','INVALID_REQUEST','array payload');
  perform pg_temp.expect_status('publication_status','{}','INVALID_REQUEST','missing entry ID');
  perform pg_temp.expect_status('withdraw','{"entry_id":null}','INVALID_REQUEST','null entry ID');
  perform pg_temp.expect_status('withdraw','{"entry_id":"bad"}','INVALID_REQUEST','malformed entry ID');
  for item in select * from (values
    ('unknown payload field','{"owner_id":"fake"}'::jsonb),
    ('missing explicit consent','{"consent_version":0}'::jsonb),
    ('sharing not confirmed','{"share_confirmed":false}'::jsonb),
    ('sharing confirmation wrong type','{"share_confirmed":"true"}'::jsonb),
    ('mock sample','{"is_mock":true}'::jsonb),
    ('relative timestamp','{"captured_at":"now"}'::jsonb),
    ('timestamp without timezone','{"captured_at":"2026-09-17T01:00:00"}'::jsonb),
    ('invalid timestamp','{"captured_at":"2026-99-17T01:00:00Z"}'::jsonb),
    ('unsupported weather','{"weather_kind":"drizzle"}'::jsonb),
    ('coordinate wrong type','{"latitude":"0"}'::jsonb),
    ('missing coordinate','{"latitude":null}'::jsonb)
  ) cases(label,patch) loop
    perform pg_temp.expect_status('submit',state.payload||item.patch,'INVALID_REQUEST',item.label);
  end loop;
  perform pg_temp.expect_status('submit',state.payload-'is_mock','INVALID_REQUEST','missing mock marker on submit');
  perform pg_temp.expect_status('submit',state.payload||jsonb_build_object('title',repeat('🪐',81)), 'INVALID_REQUEST','title limit counts codepoints');
  perform pg_temp.expect_status('submit',state.payload||jsonb_build_object('body',repeat('🪐',9)), 'INVALID_REQUEST','nine emoji too short');
  perform pg_temp.expect_status('submit',state.payload||jsonb_build_object('body',repeat('a',9)||repeat(U&'\200b',20)), 'INVALID_REQUEST','invisible padding cannot meet minimum');
  perform pg_temp.expect_status('submit',state.payload||jsonb_build_object('body',repeat('a',10001)), 'INVALID_REQUEST','body maximum enforced');
  for item in select * from (values
    ('latitude outside range','{"latitude":91}'::jsonb),
    ('longitude outside range','{"longitude":181}'::jsonb),
    ('zero submit accuracy','{"accuracy_m":0}'::jsonb),
    ('poor submit accuracy','{"accuracy_m":36}'::jsonb),
    ('stale submit timestamp',jsonb_build_object('captured_at',to_char(statement_timestamp()-interval '9 seconds','YYYY-MM-DD"T"HH24:MI:SS.MS"Z"'))),
    ('future submit timestamp',jsonb_build_object('captured_at',to_char(statement_timestamp()+interval '1 second','YYYY-MM-DD"T"HH24:MI:SS.MS"Z"')))
  ) cases(label,patch) loop
    perform pg_temp.expect_status('submit',state.payload||item.patch,'LOCATION_UNRELIABLE',item.label);
  end loop;
  perform pg_temp.expect_status('submit',state.payload,'SUBMITTED','valid submission remains pending');
  update pg_temp.nearby_test_state set entry_id=(last_response->>'entry_id')::uuid;
  perform pg_temp.check_that((select last_response->>'moderation'='pending' from pg_temp.nearby_test_state),'no automatic approval');
  perform pg_temp.expect_status('submit',state.payload,'SUBMITTED','same request retry recovers');
  perform pg_temp.check_that((select (last_response->>'entry_id')::uuid=entry_id from pg_temp.nearby_test_state),'retry returns same entry ID');
  perform pg_temp.expect_status('submit',state.payload||'{"title":"Changed title"}'::jsonb,'REQUEST_CONFLICT','changed idempotent title rejected');
  perform pg_temp.expect_status('submit',state.payload||'{"latitude":1}'::jsonb,'REQUEST_CONFLICT','changed idempotent anchor rejected');
  select * into state from pg_temp.nearby_test_state;
  perform pg_temp.expect_status('publication_status',jsonb_build_object('entry_id',state.entry_id),'FOUND','owner status requires no location');
  perform pg_temp.claims(state.actor_b,state.session_b);
  perform pg_temp.expect_status('publication_status',jsonb_build_object('entry_id',state.entry_id),'NOT_FOUND','other owner status hidden');
  perform pg_temp.expect_status('withdraw',jsonb_build_object('entry_id',state.entry_id),'NOT_FOUND','other owner cannot withdraw');
  perform pg_temp.expect_status('publication_status',jsonb_build_object('entry_id',gen_random_uuid()),'NOT_FOUND','absent ID same status as other owner');
end $$;
reset role;

-- Simulate a committed request whose exact original sample has since aged out.
-- Change fixture and saved digest together only as the test owner, never via the API.
update nearby_test_state set payload=payload||jsonb_build_object('captured_at',
  to_char(statement_timestamp()-interval '1 hour','YYYY-MM-DD"T"HH24:MI:SS.MS"Z"'));
update nearby_private.entries e set request_hash=extensions.digest(convert_to(s.payload::text,'UTF8'),'sha256')
  from nearby_test_state s where e.id=s.entry_id;
update nearby_private.anchors a set captured_at=(s.payload->>'captured_at')::timestamptz
  from nearby_test_state s where a.entry_id=s.entry_id;
set local role authenticated;
do $$
declare state pg_temp.nearby_test_state%rowtype;
begin
  select * into state from pg_temp.nearby_test_state;
  perform pg_temp.claims(state.actor_a,state.session_a);
  perform pg_temp.expect_status('submit',state.payload,'SUBMITTED','old committed request recovers without new sample');
  perform pg_temp.expect_status('withdraw',jsonb_build_object('entry_id',state.entry_id),'WITHDRAWN','owner withdraw requires no location');
  perform pg_temp.expect_status('withdraw',jsonb_build_object('entry_id',state.entry_id),'WITHDRAWN','withdraw idempotent retry');
  perform pg_temp.expect_status('submit',state.payload,'SUBMITTED','retry does not republish withdrawn entry');
  perform pg_temp.check_that((select last_response->>'visibility'='withdrawn' from pg_temp.nearby_test_state),'withdrawn status survives original retry');
end $$;
reset role;

do $$ begin
  perform pg_temp.check_that((select count(*)=1 from nearby_private.entries e join nearby_test_state s on e.owner_id=s.actor_a),'one entry after all idempotent retries');
  perform pg_temp.check_that((select count(*)=1 from nearby_private.anchors a join nearby_test_state s on a.entry_id=s.entry_id),'one protected anchor');
  perform pg_temp.check_that((select e.title=s.payload->>'title' and e.body=s.payload->>'body'
    from nearby_private.entries e join nearby_test_state s on e.id=s.entry_id),'immutable submitted text');
end $$;

update nearby_private.testers set enabled=false where user_id=(select actor_a from nearby_test_state);
set local role authenticated;
do $$ begin
  perform pg_temp.expect_status('status','{}','TESTER_NOT_APPROVED','tester revocation applies to existing JWT');
  perform pg_temp.expect_status('submit',(select payload from pg_temp.nearby_test_state),'TESTER_NOT_APPROVED','revoked tester cannot recover through submit');
end $$;
reset role;
update nearby_private.testers set enabled=true where user_id=(select actor_a from nearby_test_state);
update auth.sessions set not_after=statement_timestamp()-interval '1 second' where id=(select session_a from nearby_test_state);
set local role authenticated;
do $$ begin perform pg_temp.expect_status('status','{}','AUTH_REQUIRED','expired server session'); end $$;
reset role;
delete from auth.sessions where id=(select session_a from nearby_test_state);
set local role authenticated;
do $$ begin perform pg_temp.expect_status('status','{}','AUTH_REQUIRED','deleted server session'); end $$;
reset role;

select count(*) as passed_assertions from nearby_test_results;
rollback;
