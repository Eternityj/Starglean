-- Complete public-app contract. This migration intentionally leaves the global
-- discovery switch unchanged (false in production until the client is verified).

alter table nearby_private.entries
  add column weather_snapshot jsonb,
  add column style_snapshot jsonb,
  add column deleting_at timestamptz;
alter table nearby_private.accounts add column deleting_at timestamptz;

alter table nearby_private.entries add constraint entries_weather_snapshot_check check (
  weather_snapshot is null or ((
    jsonb_typeof(weather_snapshot)='object' and
    weather_snapshot - array['kind','daylight','temperature_c','captured_at','is_demo']='{}'::jsonb and
    jsonb_typeof(weather_snapshot->'kind')='string' and weather_snapshot->>'kind' in ('clear','cloudy','rain','snow','fog','unknown') and
    jsonb_typeof(weather_snapshot->'daylight')='string' and weather_snapshot->>'daylight' in ('day','dusk','night') and
    (not (weather_snapshot ? 'temperature_c') or weather_snapshot->'temperature_c'='null'::jsonb or
      (jsonb_typeof(weather_snapshot->'temperature_c')='number' and weather_snapshot->>'temperature_c' ~ '^-?[0-9]+$'
        and (weather_snapshot->>'temperature_c')::integer between -100 and 100)) and
    (not (weather_snapshot ? 'captured_at') or weather_snapshot->'captured_at'='null'::jsonb or
      (jsonb_typeof(weather_snapshot->'captured_at')='string' and char_length(weather_snapshot->>'captured_at') between 20 and 40)) and
    (weather_snapshot->'is_demo') is not distinct from 'false'::jsonb
  ) is true)
);
alter table nearby_private.entries add constraint entries_style_snapshot_check check (
  style_snapshot is null or style_snapshot = jsonb_build_object('paper',paper_style,'font',font_style)
);
create function nearby_private.reject_snapshot_mutation() returns trigger language plpgsql security definer set search_path='' as $$
begin
  if (old.weather_snapshot is not null and old.weather_snapshot is distinct from new.weather_snapshot) or
    (old.style_snapshot is not null and old.style_snapshot is distinct from new.style_snapshot) or
    old.paper_style is distinct from new.paper_style or old.font_style is distinct from new.font_style or
    old.weather_kind is distinct from new.weather_kind then
    raise exception 'entry snapshot is immutable';
  end if;
  return new;
end $$;
create trigger entry_snapshot_immutable before update on nearby_private.entries
for each row execute function nearby_private.reject_snapshot_mutation();

create table nearby_private.entry_media (
  id uuid primary key,
  entry_id uuid not null references nearby_private.entries(id) on delete cascade,
  owner_id uuid not null references auth.users(id) on delete cascade,
  ordinal smallint not null check (ordinal between 0 and 1),
  object_path text not null unique,
  declared_content_type text not null check (declared_content_type='image/jpeg'),
  declared_bytes integer not null check (declared_bytes between 1 and 8388608),
  declared_sha256 text not null check (declared_sha256 ~ '^[0-9a-f]{64}$'),
  state text not null default 'awaiting_upload' check (state in ('awaiting_upload','available','rejected')),
  validated_content_type text,
  validated_bytes integer,
  validated_width integer,
  validated_height integer,
  validated_sha256 text,
  validated_at timestamptz,
  created_at timestamptz not null default statement_timestamp(),
  unique(entry_id,ordinal),
  check (object_path = owner_id::text||'/'||entry_id::text||'/'||id::text),
  check ((state='available') = (validated_at is not null))
);
create index entry_media_entry_state_idx on nearby_private.entry_media(entry_id,state,ordinal);
alter table nearby_private.entry_media enable row level security;
revoke all on nearby_private.entry_media from public,anon,authenticated,service_role;

alter table nearby_private.publication_requests add column app_request_hash bytea;
create table nearby_private.publication_cancellations(
  owner_id uuid not null references auth.users(id) on delete cascade,
  request_id uuid not null,
  canceled_at timestamptz not null default statement_timestamp(),
  primary key(owner_id,request_id)
);
alter table nearby_private.publication_cancellations enable row level security;
revoke all on nearby_private.publication_cancellations from public,anon,authenticated,service_role;
alter table nearby_private.publications add column publication_mode text not null default 'pre_review'
  check(publication_mode in ('pre_review','post_publication'));
alter table nearby_private.publications add column automatically_published_at timestamptz;
alter table nearby_private.publications drop constraint publications_check;
alter table nearby_private.publications add constraint publications_review_state_check check (
  (state='pending' and reviewed_at is null and automatically_published_at is null) or
  (state='approved' and ((publication_mode='pre_review' and reviewed_at is not null) or
    (publication_mode='post_publication' and reviewed_at is null and automatically_published_at is not null))) or
  (state in ('rejected','withdrawn') and reviewed_at is not null)
);

create table nearby_private.account_state (
  user_id uuid primary key references auth.users(id) on delete cascade,
  version bigint not null default 0 check(version>=0),
  state jsonb not null default '{"instances":[{"id":"cygnus-1","template_id":"cygnus-1","ordinal":1},{"id":"orion-1","template_id":"orion-1","ordinal":1},{"id":"lyra-1","template_id":"lyra-1","ordinal":1},{"id":"cassiopeia-1","template_id":"cassiopeia-1","ordinal":1},{"id":"leo-1","template_id":"leo-1","ordinal":1},{"id":"scorpius-1","template_id":"scorpius-1","ordinal":1},{"id":"pegasus-1","template_id":"pegasus-1","ordinal":1},{"id":"delphinus-1","template_id":"delphinus-1","ordinal":1}],"target_id":null,"home_ids":[],"placements":[]}'::jsonb,
  updated_at timestamptz not null default statement_timestamp(),
  check (octet_length(state::text)<=65536 and jsonb_typeof(state)='object')
);
alter table nearby_private.account_state enable row level security;
revoke all on nearby_private.account_state from public,anon,authenticated,service_role;

create function nearby_private.valid_constellation_state(value jsonb,actor uuid) returns boolean
language sql stable security definer set search_path='' as $$
with templates(id,stars,pos) as (values ('cygnus-1',6,1),('orion-1',9,2),('lyra-1',5,3),('cassiopeia-1',5,4),
  ('leo-1',9,5),('scorpius-1',10,6),('pegasus-1',8,7),('delphinus-1',5,8)),
i as (select x->>'id' id,x->>'template_id' template_id,(x->>'ordinal')::integer ordinal,seq
  from jsonb_array_elements(value->'instances') with ordinality q(x,seq)),
p as (select (x->>'entry_id')::uuid entry_id,x->>'instance_id' instance_id,(x->>'star_index')::integer star_index from jsonb_array_elements(value->'placements') x),
h as (select jsonb_array_elements_text(value->'home_ids') id)
select coalesce((value-array['instances','target_id','home_ids','placements']='{}'::jsonb
  and jsonb_typeof(value->'instances')='array' and jsonb_typeof(value->'home_ids')='array' and jsonb_typeof(value->'placements')='array'
  and jsonb_array_length(value->'instances') between 8 and 40 and jsonb_array_length(value->'home_ids')<=8
  and not exists(select 1 from jsonb_array_elements(value->'instances') x where jsonb_typeof(x)<>'object'
    or x-array['id','template_id','ordinal']<>'{}'::jsonb or jsonb_typeof(x->'id')<>'string'
    or jsonb_typeof(x->'template_id')<>'string' or jsonb_typeof(x->'ordinal')<>'number' or x->>'ordinal' !~ '^[0-9]+$')
  and not exists(select 1 from jsonb_array_elements(value->'placements') x where jsonb_typeof(x)<>'object'
    or x-array['entry_id','instance_id','star_index']<>'{}'::jsonb or jsonb_typeof(x->'entry_id')<>'string'
    or jsonb_typeof(x->'instance_id')<>'string' or jsonb_typeof(x->'star_index')<>'number' or x->>'star_index' !~ '^[0-9]+$')
  and (select count(*)=count(distinct id) from h)
  and (select count(*)=count(distinct id) and bool_and(ordinal between 1 and 5 and id=regexp_replace(template_id,'-1$','')||'-'||ordinal
    and exists(select 1 from templates t where t.id=i.template_id)) from i)
  and (select count(*)=8 from i join templates t on t.id=i.template_id where i.ordinal=1 and i.seq=t.pos)
  and (select bool_and((select array_agg(ordinal order by ordinal) from i z where z.template_id=t.id)=
    (select array_agg(n) from generate_series(1,(select count(*)::integer from i z where z.template_id=t.id)) n)) from templates t)
  and ((value->'target_id')='null'::jsonb or exists(select 1 from i where id=value->>'target_id'))
  and (select count(*)=count(distinct entry_id) and count(*)=count(distinct (instance_id,star_index)) and coalesce(bool_and(
    exists(select 1 from nearby_private.entries e where e.id=p.entry_id and e.owner_id=actor and e.deleting_at is null)
    and exists(select 1 from i join templates t on t.id=i.template_id where i.id=p.instance_id and p.star_index between 0 and t.stars-1)),true) from p)
  and (select coalesce(bool_and(exists(select 1 from i where i.id=h.id) and
    (select count(*) from p where p.instance_id=h.id)=(select t.stars from i join templates t on t.id=i.template_id where i.id=h.id)),true) from h)
),false) $$;

create table nearby_private.deletion_operations (
  id uuid primary key default gen_random_uuid(),
  actor_id uuid not null references auth.users(id) on delete cascade,
  entry_id uuid references nearby_private.entries(id) on delete cascade,
  scope text not null check(scope in ('entry','account')),
  state text not null default 'purging' check(state in ('purging','complete')),
  created_at timestamptz not null default statement_timestamp(),
  check((scope='entry')=(entry_id is not null))
);
alter table nearby_private.deletion_operations enable row level security;
revoke all on nearby_private.deletion_operations from public,anon,authenticated,service_role;
create table nearby_private.entry_deletion_tombstones(
  actor_id uuid not null references auth.users(id) on delete cascade, entry_id uuid not null,
  deleted_at timestamptz not null default statement_timestamp(), primary key(actor_id,entry_id)
);
alter table nearby_private.entry_deletion_tombstones enable row level security;
revoke all on nearby_private.entry_deletion_tombstones from public,anon,authenticated,service_role;
create table nearby_private.media_access_tickets(
  id uuid primary key default gen_random_uuid(), reader_id uuid not null references auth.users(id) on delete cascade,
  media_id uuid not null references nearby_private.entry_media(id) on delete cascade,
  expires_at timestamptz not null default statement_timestamp()+interval '15 seconds',
  unique(reader_id,media_id)
);
create index media_access_tickets_expiry_idx on nearby_private.media_access_tickets(expires_at);
alter table nearby_private.media_access_tickets enable row level security;
revoke all on nearby_private.media_access_tickets from public,anon,authenticated,service_role;

insert into storage.buckets(id,name,public,file_size_limit,allowed_mime_types)
values('nearby-private-photos','nearby-private-photos',false,8388608,array['image/jpeg'])
on conflict(id) do update set public=false,file_size_limit=excluded.file_size_limit,allowed_mime_types=excluded.allowed_mime_types;

-- Upload only a predeclared object owned by the caller. Objects cannot be updated,
-- listed, or selected by clients; all reads use the no-store Edge proxy.
create function nearby_private.can_upload_media(path text) returns boolean language sql stable security definer set search_path='' as $$
  select nearby_private.current_actor() is not null and exists(select 1 from nearby_private.entry_media m
    join nearby_private.entries e on e.id=m.entry_id join nearby_private.accounts a on a.user_id=m.owner_id
    where m.object_path=path and m.owner_id=auth.uid() and m.state='awaiting_upload'
      and e.deleting_at is null and a.deleting_at is null)
$$;
create policy nearby_photo_insert on storage.objects for insert to authenticated with check (
  bucket_id='nearby-private-photos' and nearby_private.can_upload_media(name));

create function nearby_private.serialize_media_upload() returns trigger language plpgsql security definer set search_path='' as $$
declare owner_uuid uuid;
begin
  if new.bucket_id<>'nearby-private-photos' then return new; end if;
  select owner_id into owner_uuid from nearby_private.entry_media where object_path=new.name;
  if owner_uuid is null then raise exception 'undeclared media object'; end if;
  perform pg_catalog.pg_advisory_xact_lock(pg_catalog.hashtextextended('submit:'||owner_uuid::text,0));
  if not exists(select 1 from nearby_private.entry_media m join nearby_private.entries e on e.id=m.entry_id
    join nearby_private.accounts a on a.user_id=m.owner_id where m.object_path=new.name and m.state='awaiting_upload'
      and e.deleting_at is null and a.deleting_at is null) then raise exception 'media upload no longer active'; end if;
  return new;
end $$;
create trigger nearby_photo_serialize_insert before insert on storage.objects
for each row execute function nearby_private.serialize_media_upload();

-- Keep the proven text/location protocol as the delegate. The v2 dispatcher adds
-- immutable snapshots, owner reconciliation, lifecycle, and media descriptors.
alter function nearby_private.public_command(text,jsonb) rename to public_command_v1;

create function nearby_private.public_command(action text,payload jsonb)
returns jsonb language plpgsql security definer set search_path='' as $$
declare
  actor uuid:=nearby_private.current_actor(); result jsonb; sanitized jsonb; item jsonb;
  entry_uuid uuid; media_uuid uuid; request_uuid uuid; cursor_id uuid; media_items jsonb; requested_limit integer; cursor_at timestamptz;
  manifest jsonb; weather jsonb; style jsonb; declared_media_count integer:=0; idx integer;
  full_hash bytea; stored_hash bytea; first_claim boolean:=false;
begin
  if actor is null then return jsonb_build_object('status','AUTH_REQUIRED'); end if;
  if payload is null or jsonb_typeof(payload)<>'object' or octet_length(payload::text)>65536 then
    return jsonb_build_object('status','INVALID_REQUEST');
  end if;

  if action='account_get' then
    if payload<>'{}'::jsonb then return jsonb_build_object('status','INVALID_REQUEST'); end if;
    select jsonb_build_object('status',case when deleting_at is null then 'ACCOUNT_READY' else 'ACCOUNT_DELETING' end,
      'account_timezone',account_timezone,'daily_limit',10) into result from nearby_private.accounts where user_id=actor;
    return coalesce(result,jsonb_build_object('status','ACCOUNT_SETUP_REQUIRED'));
  end if;
  if action='account_delete' and exists(select 1 from nearby_private.accounts where user_id=actor and deleting_at is not null) then
    select jsonb_build_object('status','DELETE_PREPARED','operation_id',id) into result
      from nearby_private.deletion_operations where actor_id=actor and scope='account' and state='purging' order by created_at limit 1;
    return coalesce(result,jsonb_build_object('status','DELETE_RETRY_REQUIRED'));
  elsif exists(select 1 from nearby_private.accounts where user_id=actor and deleting_at is not null) then
    return jsonb_build_object('status','ACCOUNT_DELETING');
  end if;

  if action in ('create_entry','create_public') then
    weather:=payload->'weather_snapshot'; manifest:=coalesce(payload->'media_manifest','[]'::jsonb);
    if weather is null or jsonb_typeof(weather)<>'object' or jsonb_typeof(manifest)<>'array' or jsonb_array_length(manifest)>2 then
      return jsonb_build_object('status','INVALID_REQUEST'); end if;
    full_hash:=extensions.digest(convert_to(payload::text,'UTF8'),'sha256');
    request_uuid:=(payload->>'request_id')::uuid;
    perform pg_catalog.pg_advisory_xact_lock(pg_catalog.hashtextextended('submit:'||actor::text,0));
    perform pg_catalog.pg_advisory_xact_lock(pg_catalog.hashtextextended(actor::text||':'||request_uuid::text,0));
    if exists(select 1 from nearby_private.publication_cancellations where owner_id=actor and request_id=request_uuid) then
      return jsonb_build_object('status','CANCELLED','request_id',request_uuid); end if;
    select app_request_hash into stored_hash from nearby_private.publication_requests
      where owner_id=actor and request_id=(payload->>'request_id')::uuid;
    if found then
      if stored_hash is null or stored_hash<>full_hash then return jsonb_build_object('status','REQUEST_CONFLICT'); end if;
      select jsonb_build_object('status',case when e.visibility='private' then 'PRIVATE_SAVED' else 'PUBLICATION_PREPARED' end,
        'entry_id',e.id,'visibility',e.visibility,'moderation_mode','post_publication','weather_snapshot',e.weather_snapshot,
        'style_snapshot',e.style_snapshot,'publication_state',case when e.visibility='private' then 'private'
          when exists(select 1 from nearby_private.entry_media m where m.entry_id=e.id and m.state<>'available') then 'awaiting_media_validation'
          when p.state='approved' then 'ready_for_immediate_publication' else p.state end)
        into result from nearby_private.entries e join nearby_private.publications p on p.entry_id=e.id
        where e.owner_id=actor and e.request_id=(payload->>'request_id')::uuid;
      return coalesce(result,jsonb_build_object('status','DELETED'));
    end if;
    if weather->>'kind'<>'unknown' and (weather->'captured_at' is null or weather->'captured_at'='null'::jsonb or
      (weather->>'captured_at')::timestamptz>statement_timestamp()+interval '5 minutes' or
      (weather->>'captured_at')::timestamptz<statement_timestamp()-interval '6 hours') then
      return jsonb_build_object('status','INVALID_REQUEST'); end if;
    -- Let table constraints enforce the same canonical snapshot shape atomically.
    sanitized:=(payload-array['weather_snapshot','media_manifest'])||
      jsonb_build_object('weather_kind',case weather->>'kind'
        when 'clear' then 'sunny' when 'cloudy' then 'overcast' when 'unknown' then null else weather->>'kind' end);
    result:=nearby_private.public_command_v1(action,sanitized);
    if result->>'status' not in ('PUBLICATION_PREPARED','PRIVATE_SAVED') then return result; end if;
    entry_uuid:=(result->>'entry_id')::uuid;
    update nearby_private.publication_requests set app_request_hash=full_hash
      where owner_id=actor and request_id=(payload->>'request_id')::uuid and app_request_hash is null returning true into first_claim;
    if not coalesce(first_claim,false) then
      select app_request_hash into stored_hash from nearby_private.publication_requests
        where owner_id=actor and request_id=(payload->>'request_id')::uuid;
      if stored_hash<>full_hash then return jsonb_build_object('status','REQUEST_CONFLICT'); end if;
      return (result-'moderation')||jsonb_build_object('moderation_mode','post_publication');
    end if;
    style:=jsonb_build_object('paper',payload->>'paper_style','font',payload->>'font_style');
    update nearby_private.entries set weather_snapshot=weather,style_snapshot=style where id=entry_uuid and owner_id=actor;
    if not found then return jsonb_build_object('status','NOT_FOUND'); end if;
    for idx in 0..greatest(jsonb_array_length(manifest)-1,0) loop
      exit when jsonb_array_length(manifest)=0;
      item:=manifest->idx;
      if item-array['id','content_type','bytes','sha256']<>'{}'::jsonb or
        jsonb_typeof(item->'id')<>'string' or jsonb_typeof(item->'content_type')<>'string' or
        jsonb_typeof(item->'bytes')<>'number' or jsonb_typeof(item->'sha256')<>'string' then
        raise check_violation; end if;
      media_uuid:=(item->>'id')::uuid;
      insert into nearby_private.entry_media(id,entry_id,owner_id,ordinal,object_path,declared_content_type,declared_bytes,declared_sha256)
      values(media_uuid,entry_uuid,actor,idx,actor::text||'/'||entry_uuid::text||'/'||media_uuid::text,
        item->>'content_type',(item->>'bytes')::integer,item->>'sha256');
      declared_media_count:=declared_media_count+1;
    end loop;
    -- Existing reader protocol treats nonzero as not ready. It becomes zero only
    -- after every declared object passes server byte validation.
    update nearby_private.publications set media_count=case when declared_media_count=0 then 0 else 1 end,
      publication_mode='post_publication' where entry_id=entry_uuid;
    if result->>'visibility'='public' and declared_media_count=0 then
      update nearby_private.entries set moderation='approved' where id=entry_uuid;
      update nearby_private.publications set state='approved',reviewed_at=null,automatically_published_at=statement_timestamp(),reason_code=null where entry_id=entry_uuid;
    end if;
    return (result-'moderation')||jsonb_build_object('moderation_mode','post_publication','weather_snapshot',weather,'style_snapshot',style,
      'publication_state',case when result->>'visibility'='private' then 'private'
        when declared_media_count=0 then 'ready_for_immediate_publication' else 'awaiting_media_validation' end);
  end if;

  if action in ('cancel_publication_request','cancel_uncommitted_publication') then
    if payload-array['request_id']<>'{}'::jsonb or jsonb_typeof(payload->'request_id') is distinct from 'string' then
      return jsonb_build_object('status','INVALID_REQUEST'); end if;
    request_uuid:=(payload->>'request_id')::uuid;
    perform pg_catalog.pg_advisory_xact_lock(pg_catalog.hashtextextended('submit:'||actor::text,0));
    perform pg_catalog.pg_advisory_xact_lock(pg_catalog.hashtextextended(actor::text||':'||request_uuid::text,0));
    select jsonb_build_object('status',case when pr.deleted_at is not null or e.id is null then 'DELETED' else 'FOUND' end,
      'request_id',pr.request_id,'entry_id',pr.entry_id,'visibility',e.visibility,
      'publication_state',case p.state when 'approved' then 'published' else p.state end) into result
      from nearby_private.publication_requests pr left join nearby_private.entries e on e.id=pr.entry_id
      left join nearby_private.publications p on p.entry_id=e.id where pr.owner_id=actor and pr.request_id=request_uuid;
    if result is not null then return result; end if;
    insert into nearby_private.publication_cancellations(owner_id,request_id) values(actor,request_uuid) on conflict do nothing;
    return jsonb_build_object('status','CANCELLED','request_id',request_uuid);
  end if;

  if action='reaction_inbox' then
    if payload-array['seen_through']<>'{}'::jsonb or (payload ? 'seen_through' and
      jsonb_typeof(payload->'seen_through') is distinct from 'string') then
      return jsonb_build_object('status','INVALID_REQUEST'); end if;
    cursor_at:=statement_timestamp();
    if payload ? 'seen_through' then
      update nearby_private.reactions set read_at=coalesce(read_at,cursor_at) where owner_id=actor
        and created_at<=(payload->>'seen_through')::timestamptz;
    end if;
    select coalesce(jsonb_agg(jsonb_build_object('entry_id',entry_id,'kind',kind,'count',amount,'unread',unread_count)
      order by entry_id,kind),'[]'::jsonb) into result from (select entry_id,kind,count(*) amount,
      count(*) filter(where read_at is null) unread_count from nearby_private.reactions
      where owner_id=actor and created_at<=cursor_at group by entry_id,kind) q;
    return jsonb_build_object('status','FOUND','seen_through',cursor_at,'items',result);
  end if;

  if action='own_entries' then
    if payload-array['limit','before_created_at','before_id']<>'{}'::jsonb or jsonb_typeof(payload->'limit') is distinct from 'number' or
      ((payload ? 'before_created_at')<>(payload ? 'before_id')) then
      return jsonb_build_object('status','INVALID_REQUEST'); end if;
    requested_limit:=(payload->>'limit')::integer;
    if requested_limit not between 1 and 50 then return jsonb_build_object('status','INVALID_REQUEST'); end if;
    if payload ? 'before_created_at' then cursor_at:=(payload->>'before_created_at')::timestamptz; cursor_id:=(payload->>'before_id')::uuid; end if;
    select coalesce(jsonb_agg(row_value order by created_at desc,id desc),'[]'::jsonb) into result from (
      select e.created_at,e.id,jsonb_build_object('entry_id',e.id,'request_id',e.request_id,'title',e.title,'body',e.body,'created_at',e.created_at,
        'visibility',e.visibility,'publication_state',case p.state when 'approved' then 'published' else p.state end,
        'moderation_mode','post_publication','paper_style',e.paper_style,'font_style',e.font_style,
        'anchor',jsonb_build_object('latitude',gis.st_y(a.position::gis.geometry),'longitude',gis.st_x(a.position::gis.geometry),
          'accuracy_m',a.accuracy_m,'captured_at',a.captured_at),
        'weather_snapshot',e.weather_snapshot,'media',coalesce((select jsonb_agg(jsonb_build_object('id',m.id,
          'ordinal',m.ordinal,'state',m.state,'content_type',coalesce(m.validated_content_type,m.declared_content_type),
          'bytes',coalesce(m.validated_bytes,m.declared_bytes),'sha256',coalesce(m.validated_sha256,m.declared_sha256),
          'width',m.validated_width,'height',m.validated_height)
          order by m.ordinal) from nearby_private.entry_media m where m.entry_id=e.id),'[]'::jsonb)) row_value
      from nearby_private.entries e join nearby_private.publications p on p.entry_id=e.id join nearby_private.anchors a on a.entry_id=e.id
      where e.owner_id=actor and e.deleting_at is null and (cursor_at is null or (e.created_at,e.id)<(cursor_at,cursor_id))
      order by e.created_at desc,e.id desc limit requested_limit
    ) q;
    return jsonb_build_object('status','FOUND','items',result);
  end if;

  if action='own_entry' then
    if payload-array['entry_id']<>'{}'::jsonb then return jsonb_build_object('status','INVALID_REQUEST'); end if;
    entry_uuid:=(payload->>'entry_id')::uuid;
    select jsonb_build_object('status','FOUND','item',jsonb_build_object('entry_id',e.id,'request_id',e.request_id,
      'title',e.title,'body',e.body,'created_at',e.created_at,'visibility',e.visibility,
      'publication_state',case p.state when 'approved' then 'published' else p.state end,
      'paper_style',e.paper_style,'font_style',e.font_style,'weather_snapshot',e.weather_snapshot,
      'media',coalesce((select jsonb_agg(jsonb_build_object('id',m.id,'ordinal',m.ordinal,'state',m.state,
        'content_type',coalesce(m.validated_content_type,m.declared_content_type),
        'bytes',coalesce(m.validated_bytes,m.declared_bytes),'sha256',coalesce(m.validated_sha256,m.declared_sha256),
        'width',m.validated_width,'height',m.validated_height) order by m.ordinal)
        from nearby_private.entry_media m where m.entry_id=e.id),'[]'::jsonb),
      'anchor',jsonb_build_object('latitude',gis.st_y(a.position::gis.geometry),'longitude',gis.st_x(a.position::gis.geometry),
        'accuracy_m',a.accuracy_m,'captured_at',a.captured_at))) into result
      from nearby_private.entries e join nearby_private.publications p on p.entry_id=e.id join nearby_private.anchors a on a.entry_id=e.id
      where e.id=entry_uuid and e.owner_id=actor and e.deleting_at is null;
    return coalesce(result,jsonb_build_object('status','NOT_FOUND'));
  end if;

  if action='publication_status' then
    if payload-array['request_id']<>'{}'::jsonb or jsonb_typeof(payload->'request_id') is distinct from 'string' then
      return jsonb_build_object('status','INVALID_REQUEST'); end if;
    select jsonb_build_object('status','FOUND','request_id',e.request_id,'entry_id',e.id,'visibility',e.visibility,
      'publication_state',case p.state when 'approved' then 'published' else p.state end,
      'moderation_mode','post_publication','media_ready',not exists(select 1 from nearby_private.entry_media m
        where m.entry_id=e.id and m.state<>'available')) into result
      from nearby_private.entries e join nearby_private.publications p on p.entry_id=e.id
      where e.owner_id=actor and e.request_id=(payload->>'request_id')::uuid;
    if result is not null then return result; end if;
    if exists(select 1 from nearby_private.publication_requests pr where pr.owner_id=actor
      and pr.request_id=(payload->>'request_id')::uuid and pr.deleted_at is not null) then
      return jsonb_build_object('status','DELETED','request_id',payload->>'request_id'); end if;
    return jsonb_build_object('status','NOT_FOUND');
  end if;

  if action='account_state_get' then
    if payload<>'{}'::jsonb then return jsonb_build_object('status','INVALID_REQUEST'); end if;
    insert into nearby_private.account_state(user_id) values(actor) on conflict do nothing;
    select jsonb_build_object('status','FOUND','version',version,'state',state) into result
      from nearby_private.account_state where user_id=actor;
    return result;
  end if;

  if action='account_state_put' then
    if payload-array['expected_version','state']<>'{}'::jsonb or jsonb_typeof(payload->'expected_version') is distinct from 'number'
      or jsonb_typeof(payload->'state') is distinct from 'object' or octet_length((payload->'state')::text)>65536 then
      return jsonb_build_object('status','INVALID_REQUEST'); end if;
    -- Validate the complete, bounded constellation topology and diary ownership.
    if not nearby_private.valid_constellation_state(payload->'state',actor) then
      return jsonb_build_object('status','INVALID_REQUEST'); end if;
    insert into nearby_private.account_state(user_id) values(actor) on conflict do nothing;
    update nearby_private.account_state set state=payload->'state',version=version+1,updated_at=statement_timestamp()
      where user_id=actor and version=(payload->>'expected_version')::bigint
      returning jsonb_build_object('status','UPDATED','version',version,'state',state) into result;
    if result is null then select jsonb_build_object('status','VERSION_CONFLICT','version',version,'state',state)
      into result from nearby_private.account_state where user_id=actor; end if;
    return result;
  end if;

  if action='media_upload' then
    if payload-array['entry_id','media_id']<>'{}'::jsonb then return jsonb_build_object('status','INVALID_REQUEST'); end if;
    entry_uuid:=(payload->>'entry_id')::uuid; media_uuid:=(payload->>'media_id')::uuid;
    select jsonb_build_object('status','UPLOAD_READY','bucket','nearby-private-photos','object_path',m.object_path,
      'content_type',m.declared_content_type,'bytes',m.declared_bytes,'sha256',m.declared_sha256)
      into result from nearby_private.entry_media m where m.id=media_uuid and m.entry_id=entry_uuid and m.owner_id=actor
      and m.state='awaiting_upload';
    if result is null and exists(select 1 from nearby_private.entry_media m where m.id=media_uuid and m.entry_id=entry_uuid
      and m.owner_id=actor and m.state='available') then return jsonb_build_object('status','MEDIA_ALREADY_VALIDATED'); end if;
    return coalesce(result,jsonb_build_object('status','NOT_FOUND'));
  end if;

  if action in ('media_access','media_access_check') then
    if payload-array[case when action='media_access' then 'media_id' else 'ticket_id' end]<>'{}'::jsonb then
      return jsonb_build_object('status','INVALID_REQUEST'); end if;
    if action='media_access' then
      media_uuid:=(payload->>'media_id')::uuid;
      if not exists(select 1 from nearby_private.entry_media m join nearby_private.entries e on e.id=m.entry_id
        join nearby_private.accounts a on a.user_id=m.owner_id where m.id=media_uuid and m.state='available'
        and e.deleting_at is null and a.deleting_at is null and (m.owner_id=actor or exists(
          select 1 from nearby_private.reader_sessions rs join nearby_private.publications p on p.entry_id=e.id
          where rs.entry_id=e.id and rs.reader_id=actor and rs.state='open' and rs.lease_until>statement_timestamp()
            and e.visibility='public' and e.moderation='approved' and p.state='approved'
            and not exists(select 1 from nearby_private.reports r where r.reporter_id=actor and r.entry_id=e.id)
            and not exists(select 1 from nearby_private.blocks b where (b.blocker_id=actor and b.blocked_id=e.owner_id)
              or (b.blocker_id=e.owner_id and b.blocked_id=actor))))) then
        return jsonb_build_object('status','MEDIA_NOT_AUTHORIZED'); end if;
      delete from nearby_private.media_access_tickets where expires_at<=statement_timestamp();
      insert into nearby_private.media_access_tickets(reader_id,media_id) values(actor,media_uuid)
        on conflict(reader_id,media_id) do update set expires_at=statement_timestamp()+interval '15 seconds'
        returning id into entry_uuid;
      return jsonb_build_object('status','MEDIA_AUTHORIZED','ticket_id',entry_uuid);
    end if;
    entry_uuid:=(payload->>'ticket_id')::uuid;
    if exists(select 1 from nearby_private.media_access_tickets t join nearby_private.entry_media m on m.id=t.media_id
      join nearby_private.entries e on e.id=m.entry_id join nearby_private.publications p on p.entry_id=e.id
      where t.id=entry_uuid and t.reader_id=actor and t.expires_at>statement_timestamp() and e.deleting_at is null
      and m.state='available' and (m.owner_id=actor or (e.visibility='public' and e.moderation='approved' and p.state='approved'
        and exists(select 1 from nearby_private.reader_sessions rs where rs.entry_id=e.id and rs.reader_id=actor
          and rs.state='open' and rs.lease_until>statement_timestamp())
        and not exists(select 1 from nearby_private.reports r where r.reporter_id=actor and r.entry_id=e.id)
        and not exists(select 1 from nearby_private.blocks b where (b.blocker_id=actor and b.blocked_id=e.owner_id)
          or (b.blocker_id=e.owner_id and b.blocked_id=actor))))) then
      return jsonb_build_object('status','MEDIA_AUTHORIZED'); end if;
    return jsonb_build_object('status','MEDIA_NOT_AUTHORIZED');
  end if;

  if action='media_remove' then
    return jsonb_build_object('status','UNSUPPORTED_ACTION');
  end if;

  if action='account_delete' then
    if payload<>jsonb_build_object('confirmation','DELETE') then return jsonb_build_object('status','INVALID_REQUEST'); end if;
    perform pg_catalog.pg_advisory_xact_lock(pg_catalog.hashtextextended('submit:'||actor::text,0));
    update nearby_private.accounts set deleting_at=coalesce(deleting_at,statement_timestamp()) where user_id=actor;
    update nearby_private.entries set deleting_at=coalesce(deleting_at,statement_timestamp()) where owner_id=actor;
    update nearby_private.entries set visibility='withdrawn',withdrawn_at=coalesce(withdrawn_at,statement_timestamp()) where owner_id=actor;
    update nearby_private.publications set state='withdrawn',reviewed_at=coalesce(reviewed_at,statement_timestamp())
      where entry_id in(select id from nearby_private.entries where owner_id=actor);
    update nearby_private.reader_sessions set state='ended',ended_at=coalesce(ended_at,statement_timestamp()),lease_until=null
      where reader_id=actor or entry_id in(select id from nearby_private.entries where owner_id=actor);
    insert into nearby_private.deletion_operations(actor_id,scope) values(actor,'account') returning id into media_uuid;
    return jsonb_build_object('status','DELETE_PREPARED','operation_id',media_uuid);
  end if;

  if action='delete_entry' then
    if payload-array['entry_id']<>'{}'::jsonb then return jsonb_build_object('status','INVALID_REQUEST'); end if;
    entry_uuid:=(payload->>'entry_id')::uuid;
    perform pg_catalog.pg_advisory_xact_lock(pg_catalog.hashtextextended('submit:'||actor::text,0));
    select id into media_uuid from nearby_private.deletion_operations where actor_id=actor and entry_id=entry_uuid
      and state='purging' order by created_at limit 1;
    if media_uuid is not null then return jsonb_build_object('status','DELETE_PREPARED','operation_id',media_uuid); end if;
    update nearby_private.entries set deleting_at=coalesce(deleting_at,statement_timestamp()) where id=entry_uuid and owner_id=actor;
    if not found then
      if exists(select 1 from nearby_private.entry_deletion_tombstones where actor_id=actor and entry_id=entry_uuid) then
        return jsonb_build_object('status','ENTRY_DELETED'); end if;
      return jsonb_build_object('status','NOT_FOUND');
    end if;
    update nearby_private.reader_sessions set state='ended',ended_at=coalesce(ended_at,statement_timestamp()),lease_until=null
      where entry_id=entry_uuid and state<>'ended';
    update nearby_private.account_state s set state=jsonb_set(jsonb_set(s.state,'{placements}',coalesce((select jsonb_agg(x)
      from jsonb_array_elements(s.state->'placements') x where x->>'entry_id'<>entry_uuid::text),'[]'::jsonb)),
      '{home_ids}',coalesce((select jsonb_agg(h) from jsonb_array_elements(s.state->'home_ids') h where h #>> '{}' not in
        (select x->>'instance_id' from jsonb_array_elements(s.state->'placements') x where x->>'entry_id'=entry_uuid::text)),'[]'::jsonb)),
      version=version+1,updated_at=statement_timestamp() where user_id=actor
      and exists(select 1 from jsonb_array_elements(s.state->'placements') x where x->>'entry_id'=entry_uuid::text);
    update nearby_private.entries set visibility='withdrawn',withdrawn_at=coalesce(withdrawn_at,statement_timestamp()) where id=entry_uuid;
    update nearby_private.publications set state='withdrawn',reviewed_at=coalesce(reviewed_at,statement_timestamp()) where entry_id=entry_uuid;
    insert into nearby_private.deletion_operations(actor_id,entry_id,scope) values(actor,entry_uuid,'entry') returning id into media_uuid;
    return jsonb_build_object('status','DELETE_PREPARED','operation_id',media_uuid);
  end if;

  -- v1's intermediate pending state predates the automatic-publication timestamp.
  -- Clear that timestamp under the same entry lock before that state transition.
  if action='set_visibility' and payload->>'visibility'='public' then
    perform 1 from nearby_private.entries where id=(payload->>'entry_id')::uuid and owner_id=actor for update;
    update nearby_private.publications set automatically_published_at=null
      where entry_id=(payload->>'entry_id')::uuid and state<>'approved'
      and exists(select 1 from nearby_private.entries e where e.id=entry_id and e.owner_id=actor);
    -- A public -> public request is already fulfilled and must not revoke a reader.
    if exists(select 1 from nearby_private.entries e join nearby_private.publications p on p.entry_id=e.id
      where e.id=(payload->>'entry_id')::uuid and e.owner_id=actor and e.visibility='public' and p.state='approved') then
      return jsonb_build_object('status','VISIBILITY_UPDATED','entry_id',payload->>'entry_id','visibility','public',
        'moderation_mode','post_publication','publication_state','ready_for_immediate_publication');
    end if;
  end if;
  result:=nearby_private.public_command_v1(action,payload);
  if action='set_visibility' and result->>'status'='VISIBILITY_UPDATED' and result->>'visibility'='public' and
    not exists(select 1 from nearby_private.entry_media where entry_id=(payload->>'entry_id')::uuid and state<>'available') then
    update nearby_private.entries set moderation='approved' where id=(payload->>'entry_id')::uuid and owner_id=actor;
    update nearby_private.publications set state='approved',publication_mode='post_publication',reviewed_at=null,
      automatically_published_at=statement_timestamp(),reason_code=null,media_count=0
      where entry_id=(payload->>'entry_id')::uuid;
    result:=(result-'moderation')||jsonb_build_object('moderation_mode','post_publication',
      'publication_state','ready_for_immediate_publication');
  end if;
  if result->>'status' in ('OPEN_COMMITTED','CONTENT_RESUMED') and result ? 'body' and payload ? 'reader_session_id' then
    select coalesce(jsonb_agg(jsonb_build_object('id',m.id,'ordinal',m.ordinal,
      'content_type',m.validated_content_type,'bytes',m.validated_bytes,'sha256',m.validated_sha256,
      'width',m.validated_width,'height',m.validated_height) order by m.ordinal),'[]'::jsonb)
      into media_items from nearby_private.entry_media m join nearby_private.reader_sessions rs on rs.entry_id=m.entry_id
      where rs.id=(payload->>'reader_session_id')::uuid and rs.reader_id=actor and rs.state='open'
        and rs.lease_until>statement_timestamp() and m.state='available';
    result:=result||jsonb_build_object('media',media_items);
    select result||jsonb_build_object('weather_snapshot',e.weather_snapshot,'style_snapshot',e.style_snapshot,
      'generation',rs.generation,'sequence',rs.last_fix_sequence)
      into result from nearby_private.reader_sessions rs join nearby_private.entries e on e.id=rs.entry_id
      where rs.id=(payload->>'reader_session_id')::uuid and rs.reader_id=actor and rs.state='open'
        and rs.lease_until>statement_timestamp();
  end if;
  return result;
exception when invalid_text_representation or numeric_value_out_of_range or invalid_datetime_format or datetime_field_overflow or check_violation then
  return jsonb_build_object('status','INVALID_REQUEST');
end $$;

-- Service-role-only callback for an Edge Function that has downloaded and parsed
-- the actual bytes. The function must reject EXIF/APP1, malformed signatures,
-- mismatched hashes/types/sizes, and dimensions outside 1..8192 before calling.
create function public.nearby_media_validation(media_id uuid,content_type text,byte_count integer,
  width integer,height integer,sha256 text,exif_absent boolean)
returns jsonb language plpgsql security definer set search_path='' as $$
declare m nearby_private.entry_media%rowtype; remaining integer;
begin
  -- Caller identity is enforced by EXECUTE grants; current_user inside this
  -- SECURITY DEFINER function is deliberately not used as an authorization signal.
  if exif_absent is distinct from true or
    content_type<>'image/jpeg' or byte_count not between 1 and 8388608 or
    width not between 1 and 2048 or height not between 1 and 2048 or sha256 !~ '^[0-9a-f]{64}$' then
    return jsonb_build_object('status','VALIDATION_REJECTED'); end if;
  select * into m from nearby_private.entry_media where id=media_id for update;
  if not found then return jsonb_build_object('status','NOT_FOUND'); end if;
  perform pg_catalog.pg_advisory_xact_lock(pg_catalog.hashtextextended('submit:'||m.owner_id::text,0));
  if exists(select 1 from nearby_private.entries e join nearby_private.accounts a on a.user_id=e.owner_id
    where e.id=m.entry_id and (e.deleting_at is not null or a.deleting_at is not null)) then
    return jsonb_build_object('status','DELETE_IN_PROGRESS'); end if;
  if m.declared_content_type<>content_type or m.declared_bytes<>byte_count or m.declared_sha256<>sha256 or
    not exists(select 1 from storage.objects where bucket_id='nearby-private-photos' and name=m.object_path) then
    update nearby_private.entry_media set state='rejected' where id=media_id;
    return jsonb_build_object('status','VALIDATION_REJECTED');
  end if;
  update nearby_private.entry_media set state='available',validated_content_type=content_type,validated_bytes=byte_count,
    validated_width=width,validated_height=height,validated_sha256=sha256,validated_at=statement_timestamp() where id=media_id;
  select count(*) into remaining from nearby_private.entry_media where entry_id=m.entry_id and state<>'available';
  if remaining=0 and exists(select 1 from nearby_private.entries where id=m.entry_id and visibility='public') then
    update nearby_private.entries set moderation='approved' where id=m.entry_id;
    update nearby_private.publications set state='approved',publication_mode='post_publication',reviewed_at=null,
      automatically_published_at=statement_timestamp(),reason_code=null where entry_id=m.entry_id;
    update nearby_private.publications set media_count=0 where entry_id=m.entry_id;
  end if;
  return jsonb_build_object('status',case when remaining=0 then 'MEDIA_READY' else 'MEDIA_PARTIAL' end);
end $$;

create function public.nearby_service_media_path(ticket_id uuid) returns text language sql security definer set search_path='' as $$
  select m.object_path from nearby_private.media_access_tickets t join nearby_private.entry_media m on m.id=t.media_id
  join nearby_private.entries e on e.id=m.entry_id join nearby_private.accounts a on a.user_id=m.owner_id
  where t.id=ticket_id and t.expires_at>statement_timestamp() and m.state='available' and e.deleting_at is null and a.deleting_at is null
$$;
create function public.nearby_service_delete(action text,operation_id uuid) returns jsonb language plpgsql security definer set search_path='' as $$
declare op nearby_private.deletion_operations%rowtype; paths jsonb;
begin
  select * into op from nearby_private.deletion_operations where id=operation_id and state='purging' for update;
  if not found then return jsonb_build_object('status','NOT_FOUND'); end if;
  perform pg_catalog.pg_advisory_xact_lock(pg_catalog.hashtextextended('submit:'||op.actor_id::text,0));
  if action='paths' then
    select coalesce(jsonb_agg(object_path),'[]'::jsonb) into paths from nearby_private.entry_media
      where owner_id=op.actor_id and (op.scope='account' or entry_id=op.entry_id);
    return jsonb_build_object('status','FOUND','bucket','nearby-private-photos','paths',paths);
  elsif action='finalize' then
    if exists(select 1 from storage.objects o join nearby_private.entry_media m on m.object_path=o.name
      where o.bucket_id='nearby-private-photos' and m.owner_id=op.actor_id and (op.scope='account' or m.entry_id=op.entry_id)) then
      return jsonb_build_object('status','OBJECTS_REMAIN'); end if;
    if op.scope='entry' then
      insert into nearby_private.entry_deletion_tombstones(actor_id,entry_id) values(op.actor_id,op.entry_id) on conflict do nothing;
      update nearby_private.publication_requests set deleted_at=coalesce(deleted_at,statement_timestamp())
        where owner_id=op.actor_id and entry_id=op.entry_id;
      delete from nearby_private.entries where id=op.entry_id and owner_id=op.actor_id;
    else delete from auth.users where id=op.actor_id; end if;
    return jsonb_build_object('status',case when op.scope='entry' then 'ENTRY_DELETED' else 'ACCOUNT_DELETED' end);
  end if;
  return jsonb_build_object('status','INVALID_REQUEST');
end $$;

revoke all on function nearby_private.public_command_v1(text,jsonb) from public,anon,authenticated,service_role;
revoke all on function nearby_private.public_command(text,jsonb) from public,anon,authenticated,service_role;
revoke all on function nearby_private.reject_snapshot_mutation() from public,anon,authenticated,service_role;
revoke all on function nearby_private.valid_constellation_state(jsonb,uuid) from public,anon,authenticated,service_role;
revoke all on function nearby_private.can_upload_media(text) from public,anon,authenticated,service_role;
revoke all on function nearby_private.serialize_media_upload() from public,anon,authenticated,service_role;
revoke all on function public.nearby_media_validation(uuid,text,integer,integer,integer,text,boolean) from public,anon,authenticated;
revoke all on function public.nearby_service_media_path(uuid) from public,anon,authenticated;
revoke all on function public.nearby_service_delete(text,uuid) from public,anon,authenticated;
grant execute on function nearby_private.public_command(text,jsonb) to authenticated;
grant execute on function nearby_private.can_upload_media(text) to authenticated;
grant execute on function public.nearby_media_validation(uuid,text,integer,integer,integer,text,boolean) to service_role;
grant execute on function public.nearby_service_media_path(uuid) to service_role;
grant execute on function public.nearby_service_delete(text,uuid) to service_role;
