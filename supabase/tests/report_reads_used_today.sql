-- Run only after the reads-used-today migration. Synthetic actors roll back.
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

create temporary table qa_state(actor_a uuid default gen_random_uuid(),session_a uuid default gen_random_uuid()) on commit drop;
insert into qa_state default values;
grant select on qa_state to authenticated;
grant insert,select on qa_results to authenticated;
do $$ begin execute format('grant usage on schema %I to authenticated',pg_my_temp_schema()::regnamespace::text); end $$;
insert into auth.users(id,aud,role,created_at,updated_at,is_anonymous)
select actor_a,'authenticated','authenticated',statement_timestamp(),statement_timestamp(),false from qa_state;
insert into auth.sessions(id,user_id,created_at,updated_at)
select session_a,actor_a,statement_timestamp(),statement_timestamp() from qa_state;

do $$ begin
  perform pg_temp.ok(not has_function_privilege('authenticated','nearby_private.public_command_v2(text,jsonb)','EXECUTE'),
    'clients cannot call the delegate directly');
end $$;

set local role authenticated;
do $$ declare s pg_temp.qa_state%rowtype; response jsonb;
begin
  select * into s from pg_temp.qa_state; perform pg_temp.claims(s.actor_a,s.session_a);
  response:=pg_temp.call('register_account','{"account_timezone":"Australia/Melbourne"}');
  perform pg_temp.ok(response->>'status'='ACCOUNT_READY' and (response->>'reads_used_today')::integer=0,
    'a new account reports zero reads today');
  perform pg_temp.ok(pg_temp.call('prepare_open','{}') ? 'status' and not (pg_temp.call('prepare_open','{}') ? 'reads_used_today'),
    'other actions are unchanged');
end $$;
reset role;

-- Simulate three opened encounters today and one yesterday, in the account's own day.
insert into nearby_private.read_days(reader_id,account_date,used)
select actor_a,(statement_timestamp() at time zone 'Australia/Melbourne')::date,3 from qa_state
union all select actor_a,(statement_timestamp() at time zone 'Australia/Melbourne')::date-1,10 from qa_state;

set local role authenticated;
do $$ declare s pg_temp.qa_state%rowtype; response jsonb;
begin
  select * into s from pg_temp.qa_state; perform pg_temp.claims(s.actor_a,s.session_a);
  response:=pg_temp.call('account_get','{}');
  perform pg_temp.ok(response->>'status'='ACCOUNT_READY' and (response->>'reads_used_today')::integer=3,
    'account_get reports only today''s reads');
  response:=pg_temp.call('register_account','{"account_timezone":"UTC"}');
  perform pg_temp.ok(response->>'status'='TIMEZONE_IMMUTABLE' and (response->>'reads_used_today')::integer=3,
    'reopening with another phone timezone still counts the account''s own day');
end $$;
reset role;

select count(*) as passed_assertions from qa_results;
rollback;
