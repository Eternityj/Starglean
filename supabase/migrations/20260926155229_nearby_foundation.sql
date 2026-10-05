-- Restricted test foundation, not an anonymous-content reading API.
-- No accounts are enrolled and no local diary is imported by this migration.
create schema gis;
create extension postgis with schema gis;
revoke all on schema gis from public, anon, authenticated;

create schema nearby_private;
revoke all on schema nearby_private from public, anon, authenticated, service_role;
alter default privileges in schema nearby_private revoke all on tables from public, anon, authenticated, service_role;
alter default privileges in schema nearby_private revoke execute on functions from public, anon, authenticated, service_role;

-- Code points, not UTF-16 units. Excludes Unicode spaces, ISO controls and FORMAT
-- ranges (Unicode 15.1). Keep explicit tests for NBSP, joiners, BOM and emoji.
create function nearby_private.meaningful_length(value text)
returns integer language sql immutable strict security invoker set search_path = '' as $$
  select count(*)::integer from (
    select ascii(ch) as cp from regexp_split_to_table(value, '') ch
  ) p where not (
    cp between 0 and 32 or cp between 127 and 160 or cp = 173 or
    cp between 1536 and 1541 or cp in (1564,1757,1807,2192,2193,2274,5760,6158) or
    cp between 8192 and 8207 or cp between 8232 and 8239 or cp between 8287 and 8292 or
    cp between 8294 and 8303 or
    cp in (12288,65279,65529,65530,65531,69821,69837,917505) or
    cp between 78896 and 78911 or cp between 113824 and 113827 or
    cp between 119155 and 119162 or cp between 917536 and 917631
  );
$$;

create table nearby_private.testers (
  user_id uuid primary key references auth.users(id) on delete cascade,
  enabled boolean not null default false,
  approved_at timestamptz,
  -- NULL until the daily-reset policy and the account's timezone are confirmed.
  account_timezone text,
  check (not enabled or approved_at is not null)
);

create table nearby_private.entries (
  id uuid primary key default gen_random_uuid(),
  owner_id uuid not null references auth.users(id) on delete cascade,
  request_id uuid not null,
  request_hash bytea not null,
  title text not null check (char_length(title) <= 80),
  body text not null check (char_length(body) <= 10000 and nearby_private.meaningful_length(body) >= 10),
  weather_kind text check (weather_kind in ('sunny', 'overcast', 'rain', 'snow', 'fog')),
  created_at timestamptz not null default statement_timestamp(),
  moderation text not null default 'pending' check (moderation in ('pending', 'approved', 'rejected')),
  visibility text not null default 'public' check (visibility in ('public', 'withdrawn')),
  withdrawn_at timestamptz,
  unique (owner_id, request_id),
  check ((visibility = 'withdrawn') = (withdrawn_at is not null))
);

create table nearby_private.anchors (
  entry_id uuid primary key references nearby_private.entries(id) on delete cascade,
  position gis.geography(Point, 4326) not null,
  accuracy_m double precision not null check (accuracy_m > 0 and accuracy_m <= 35),
  captured_at timestamptz not null,
  location_consent_version integer not null check (location_consent_version = 1)
);
create index anchors_position_gist on nearby_private.anchors using gist(position);

alter table nearby_private.testers enable row level security;
alter table nearby_private.entries enable row level security;
alter table nearby_private.anchors enable row level security;
-- Deliberately no table policies or client grants. All table reads/writes are private.
revoke all on all tables in schema nearby_private from public, anon, authenticated, service_role;

-- Pure, internal geometry helper. Never grant it to a client; never return the distance.
-- Accuracy is an estimate, not an attestation or a proof against a modified device.
create function nearby_private.range_decision(
  anchor gis.geography, anchor_accuracy double precision,
  reader gis.geography, reader_accuracy double precision,
  fix_age_ms bigint, is_mock boolean
) returns text language plpgsql immutable security invoker set search_path = '' as $$
begin
  if anchor is null or reader is null or
     anchor_accuracy is null or not (anchor_accuracy > 0 and anchor_accuracy <= 35) or
     reader_accuracy is null or not (reader_accuracy > 0 and reader_accuracy <= 35) or
     fix_age_ms is null or fix_age_ms < 0 or fix_age_ms >= 8000 or
     is_mock is distinct from false or
     gis.st_geometrytype(anchor::gis.geometry) <> 'ST_Point' or
     gis.st_geometrytype(reader::gis.geometry) <> 'ST_Point' or
     gis.st_isempty(anchor::gis.geometry) or gis.st_isempty(reader::gis.geometry) then
    return 'UNKNOWN';
  end if;
  if gis.st_dwithin(anchor, reader, 200.0 - anchor_accuracy - reader_accuracy) then
    return 'IN_RANGE';
  elsif not gis.st_dwithin(anchor, reader, 200.0 + anchor_accuracy + reader_accuracy) then
    return 'OUT_OF_RANGE';
  end if;
  return 'UNKNOWN';
end;
$$;

-- Only this private function can cross the table boundary. Public wrapper remains INVOKER.
-- Session presence is checked on every call, so a signed-out token is not sufficient.
create function nearby_private.command(action text, payload jsonb)
returns jsonb language plpgsql security definer set search_path = '' as $$
declare
  actor uuid;
  session_uuid uuid;
  token_exp numeric;
  request_uuid uuid;
  entry_uuid uuid;
  existing nearby_private.entries%rowtype;
  digest bytea;
  lat double precision;
  lon double precision;
  accuracy double precision;
  captured timestamptz;
  now_at timestamptz := statement_timestamp();
begin
  actor := auth.uid();
  begin
    session_uuid := (auth.jwt()->>'session_id')::uuid;
    token_exp := (auth.jwt()->>'exp')::numeric;
  exception when invalid_text_representation or numeric_value_out_of_range then
    return jsonb_build_object('status', 'AUTH_REQUIRED');
  end;
  if actor is null or session_uuid is null or token_exp is null or
     not (token_exp > extract(epoch from now_at) and token_exp < 'Infinity'::numeric) or
     not exists (
       select 1 from auth.sessions s join auth.users u on u.id = s.user_id
       where s.id = session_uuid and s.user_id = actor and u.deleted_at is null
       and (u.banned_until is null or u.banned_until <= now_at)
       and (s.not_after is null or s.not_after > now_at)
     ) then
    return jsonb_build_object('status', 'AUTH_REQUIRED');
  end if;

  -- Serializes same-account submissions and enrollment changes in one short transaction.
  perform 1 from nearby_private.testers where user_id = actor and enabled for update;
  if not found then return jsonb_build_object('status', 'TESTER_NOT_APPROVED'); end if;
  if action = 'status' and payload = '{}'::jsonb then
    return jsonb_build_object('status', 'READY_FOR_TEST_SUBMISSION', 'reading_enabled', false,
      'publication', 'manual_review_required', 'protocol_version', 1);
  end if;
  if payload is null or jsonb_typeof(payload) <> 'object' or octet_length(payload::text) > 65536 then
    return jsonb_build_object('status', 'INVALID_REQUEST');
  end if;

  if action in ('publication_status', 'withdraw') then
    if payload - array['entry_id'] <> '{}'::jsonb or jsonb_typeof(payload->'entry_id') is distinct from 'string' then
      return jsonb_build_object('status', 'INVALID_REQUEST');
    end if;
    entry_uuid := (payload->>'entry_id')::uuid;
    select * into existing from nearby_private.entries where id = entry_uuid and owner_id = actor for update;
    if not found then return jsonb_build_object('status', 'NOT_FOUND'); end if;
    if action = 'withdraw' then
      update nearby_private.entries set visibility = 'withdrawn', withdrawn_at = coalesce(withdrawn_at, now_at)
        where id = existing.id;
      return jsonb_build_object('status', 'WITHDRAWN', 'entry_id', existing.id);
    end if;
    return jsonb_build_object('status', 'FOUND', 'entry_id', existing.id,
      'moderation', existing.moderation, 'visibility', existing.visibility);
  end if;

  if action is distinct from 'submit' then return jsonb_build_object('status', 'UNSUPPORTED_ACTION'); end if;
  if payload - array['request_id','title','body','weather_kind','latitude','longitude',
      'accuracy_m','captured_at','is_mock','consent_version','share_confirmed'] <> '{}'::jsonb or
     jsonb_typeof(payload->'request_id') is distinct from 'string' or
     jsonb_typeof(payload->'title') is distinct from 'string' or
     jsonb_typeof(payload->'body') is distinct from 'string' or
     jsonb_typeof(payload->'latitude') is distinct from 'number' or
     jsonb_typeof(payload->'longitude') is distinct from 'number' or
     jsonb_typeof(payload->'accuracy_m') is distinct from 'number' or
     jsonb_typeof(payload->'captured_at') is distinct from 'string' or
     char_length(payload->>'captured_at') > 40 or
     (payload->>'captured_at') !~ '^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}([.][0-9]{1,9})?(Z|[+-][0-9]{2}:[0-9]{2})$' or
     (payload->'is_mock') is distinct from 'false'::jsonb or
     (payload->'share_confirmed') is distinct from 'true'::jsonb or
     (payload->'consent_version') is distinct from '1'::jsonb or
     char_length(payload->>'title') > 80 or
     char_length(payload->>'body') > 10000 or nearby_private.meaningful_length(payload->>'body') < 10 or
     (payload ? 'weather_kind' and payload->'weather_kind' <> 'null'::jsonb and
       (jsonb_typeof(payload->'weather_kind') <> 'string' or
        payload->>'weather_kind' not in ('sunny','overcast','rain','snow','fog'))) then
    return jsonb_build_object('status', 'INVALID_REQUEST');
  end if;
  request_uuid := (payload->>'request_id')::uuid;
  digest := extensions.digest(convert_to(payload::text, 'UTF8'), 'sha256');
  select * into existing from nearby_private.entries where owner_id = actor and request_id = request_uuid;
  -- Recover an identical already-committed submission even when its original fix is now old.
  if found then
    if existing.request_hash <> digest then return jsonb_build_object('status', 'REQUEST_CONFLICT'); end if;
    return jsonb_build_object('status', 'SUBMITTED', 'entry_id', existing.id,
      'moderation', existing.moderation, 'visibility', existing.visibility);
  end if;
  lat := (payload->>'latitude')::double precision;
  lon := (payload->>'longitude')::double precision;
  accuracy := (payload->>'accuracy_m')::double precision;
  captured := (payload->>'captured_at')::timestamptz;
  if not (lat between -90 and 90 and lon between -180 and 180 and accuracy > 0 and accuracy <= 35) or
     not (captured <= now_at and captured > now_at - interval '8 seconds') then
    return jsonb_build_object('status', 'LOCATION_UNRELIABLE');
  end if;
  -- Restricted test safety cap, not the daily anonymous-reading quota.
  if (select count(*) from nearby_private.entries where owner_id = actor) >= 1000 then
    return jsonb_build_object('status', 'TEST_STORAGE_LIMIT');
  end if;
  insert into nearby_private.entries(owner_id, request_id, request_hash, title, body, weather_kind)
    values(actor, request_uuid, digest, payload->>'title', payload->>'body', payload->>'weather_kind')
    returning id into entry_uuid;
  insert into nearby_private.anchors(entry_id, position, accuracy_m, captured_at, location_consent_version)
    values(entry_uuid, gis.st_setsrid(gis.st_makepoint(lon, lat), 4326)::gis.geography, accuracy, captured, 1);
  return jsonb_build_object('status', 'SUBMITTED', 'entry_id', entry_uuid,
    'moderation', 'pending', 'visibility', 'public');
exception when invalid_text_representation or numeric_value_out_of_range or
  invalid_datetime_format or datetime_field_overflow then
  -- Do not echo coordinates, content, token claims, or SQLERRM to callers/logs.
  return jsonb_build_object('status', 'INVALID_REQUEST');
end;
$$;

create function public.nearby_command(action text, payload jsonb default '{}'::jsonb)
returns jsonb language sql security invoker set search_path = '' as $$
  select nearby_private.command(action, payload);
$$;

revoke execute on all functions in schema nearby_private from public, anon, authenticated, service_role;
revoke all on function public.nearby_command(text, jsonb) from public, anon, authenticated, service_role;
grant usage on schema nearby_private to authenticated;
grant execute on function nearby_private.command(text, jsonb) to authenticated;
grant execute on function public.nearby_command(text, jsonb) to authenticated;
-- The private schema is not a Data API exposed schema. Even if misconfigured later,
-- command() repeats all checks; its tables and geometry helper still have no client grants.
comment on function public.nearby_command(text, jsonb) is
  'Restricted test submission/status/withdrawal only. No anonymous content or coordinates returned. Reading not enabled.';
