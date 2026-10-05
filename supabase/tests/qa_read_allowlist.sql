-- Run only after the private QA read-allowlist migration. Synthetic actors roll back.
begin;
create temporary table qa_results(label text primary key) on commit drop;
create function pg_temp.ok(value boolean,label text) returns void language plpgsql security invoker set search_path='' as $$
begin if value is not true then raise exception 'failed: %',label; end if; insert into pg_temp.qa_results values(label); end $$;
create function pg_temp.claims(actor uuid,session_value uuid) returns void language plpgsql security invoker set search_path='' as $$
begin
  perform set_config('request.jwt.claim.sub','',true);
  perform set_config('request.jwt.claims',jsonb_build_object('sub',actor,'role','authenticated',
    'session_id',session_value,'exp',extract(epoch from statement_timestamp())+3600)::text,true);
end $$;
create function pg_temp.call(action_value text,payload_value jsonb) returns jsonb language sql security invoker set search_path='' as $$
  select public.nearby_public_command(action_value,payload_value)
$$;

create temporary table qa_state(actor_a uuid default gen_random_uuid(),actor_b uuid default gen_random_uuid(),
  session_a uuid default gen_random_uuid(),session_b uuid default gen_random_uuid()) on commit drop;
insert into qa_state default values;
grant select on qa_state to authenticated;
grant insert,select on qa_results to authenticated;
do $$ begin execute format('grant usage on schema %I to authenticated',pg_my_temp_schema()::regnamespace::text); end $$;
insert into auth.users(id,aud,role,created_at,updated_at,is_anonymous)
select actor_a,'authenticated','authenticated',statement_timestamp(),statement_timestamp(),false from qa_state
union all select actor_b,'authenticated','authenticated',statement_timestamp(),statement_timestamp(),false from qa_state;
insert into auth.sessions(id,user_id,created_at,updated_at)
select session_a,actor_a,statement_timestamp(),statement_timestamp() from qa_state
union all select session_b,actor_b,statement_timestamp(),statement_timestamp() from qa_state;

do $$ begin
  perform pg_temp.ok((select public_reading_enabled=false from nearby_private.service_control where singleton),
    'QA migration leaves global discovery disabled');
  perform pg_temp.ok((select relrowsecurity from pg_class where oid='nearby_private.qa_read_audience'::regclass),
    'private QA audience has RLS defense in depth');
  perform pg_temp.ok(not has_table_privilege('authenticated','nearby_private.qa_read_audience','SELECT') and
    not has_table_privilege('service_role','nearby_private.qa_read_audience','SELECT'),
    'QA audience has no Data API or service-role table access');
  perform pg_temp.ok(not has_function_privilege('authenticated','nearby_private.reading_enabled_for_actor(uuid)','EXECUTE'),
    'clients cannot call the QA gate helper directly');
end $$;

set local role authenticated;
do $$ declare s pg_temp.qa_state%rowtype;
begin
  select * into s from pg_temp.qa_state;
  perform pg_temp.claims(s.actor_a,s.session_a);
  perform pg_temp.ok(pg_temp.call('register_account','{"account_timezone":"Etc/UTC"}')->>'status'='ACCOUNT_READY','actor A registers');
  perform pg_temp.claims(s.actor_b,s.session_b);
  perform pg_temp.ok(pg_temp.call('register_account','{"account_timezone":"Etc/UTC"}')->>'status'='ACCOUNT_READY','actor B registers');
end $$;
reset role;

insert into nearby_private.qa_read_audience(user_id,purpose,run_id,expires_at)
select actor_b,'nearby_http_e2e',gen_random_uuid(),statement_timestamp()-interval '1 second' from qa_state;
set local role authenticated;
do $$ declare s pg_temp.qa_state%rowtype;
begin
  select * into s from pg_temp.qa_state; perform pg_temp.claims(s.actor_b,s.session_b);
  perform pg_temp.ok(pg_temp.call('prepare_open','{}')->>'status'='SERVICE_DISABLED',
    'expired QA audience cannot bypass closed service');
end $$;
reset role;

delete from nearby_private.qa_read_audience where user_id=(select actor_b from qa_state);
insert into nearby_private.qa_read_audience(user_id,purpose,run_id,expires_at)
select actor_b,'nearby_http_e2e',gen_random_uuid(),statement_timestamp()+interval '10 minutes' from qa_state;
set local role authenticated;
do $$ declare s pg_temp.qa_state%rowtype;
begin
  select * into s from pg_temp.qa_state;
  perform pg_temp.claims(s.actor_b,s.session_b);
  perform pg_temp.ok(pg_temp.call('prepare_open','{}')->>'status'='LOCATION_UNRELIABLE',
    'unexpired QA audience reaches normal location validation');
  perform pg_temp.claims(s.actor_a,s.session_a);
  perform pg_temp.ok(pg_temp.call('prepare_open','{}')->>'status'='SERVICE_DISABLED',
    'arbitrary authenticated actor remains denied');
end $$;
reset role;

do $$ declare failed boolean:=false;
begin
  begin
    insert into nearby_private.qa_read_audience(user_id,purpose,run_id,expires_at)
    select actor_a,'nearby_http_e2e',gen_random_uuid(),statement_timestamp()+interval '16 minutes' from qa_state;
  exception when others then failed:=true; end;
  perform pg_temp.ok(failed,'QA enrollment beyond 15 minutes is rejected');
  perform pg_temp.ok((select public_reading_enabled=false from nearby_private.service_control where singleton),
    'actor-specific QA never changes global discovery setting');
end $$;

select count(*) as passed_assertions from qa_results;
rollback;
