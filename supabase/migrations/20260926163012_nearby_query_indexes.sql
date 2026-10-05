-- Preserve the exact range guard; use GiST to prefilter candidates and index staff audit ownership.
create index moderation_audit_staff_idx on nearby_private.moderation_audit(staff_id, created_at desc);

create or replace function nearby_private.public_command(action text, payload jsonb)
returns jsonb language plpgsql security definer set search_path = '' as $$
declare
  actor uuid := nearby_private.current_actor(); now_at timestamptz := statement_timestamp();
  request_uuid uuid; session_uuid uuid; entry_uuid uuid; generation_value bigint; fix_sequence bigint;
  digest bytea; prior_hash bytea; existing_commit nearby_private.open_commits%rowtype; existing_session nearby_private.reader_sessions%rowtype;
  selected record; zone_name text; account_day date; day_used integer; decision text; result jsonb; prior_response jsonb;
  allowed_keys text[]; reaction_kind text; report_category text; detail_value text;
begin
  if actor is null then return jsonb_build_object('status','AUTH_REQUIRED'); end if;
  if payload is null or jsonb_typeof(payload) <> 'object' or octet_length(payload::text) > 65536 then
    return jsonb_build_object('status','INVALID_REQUEST');
  end if;

  if action = 'register_account' then
    if payload - array['account_timezone'] <> '{}'::jsonb or jsonb_typeof(payload->'account_timezone') is distinct from 'string' or
       not exists (select 1 from pg_timezone_names() z where z.name=payload->>'account_timezone') then
      return jsonb_build_object('status','INVALID_REQUEST'); end if;
    insert into nearby_private.accounts(user_id,account_timezone) values(actor,payload->>'account_timezone')
      on conflict (user_id) do nothing;
    if (select account_timezone from nearby_private.accounts where user_id=actor) <> payload->>'account_timezone' then
      return jsonb_build_object('status','TIMEZONE_IMMUTABLE'); end if;
    return jsonb_build_object('status','ACCOUNT_READY','account_timezone',payload->>'account_timezone','daily_limit',10);
  end if;

  select account_timezone into zone_name from nearby_private.accounts where user_id=actor;
  if zone_name is null then return jsonb_build_object('status','ACCOUNT_SETUP_REQUIRED'); end if;
  if action in ('prepare_open','open_commit','validate','resume_content','react') and
     not (select public_reading_enabled from nearby_private.service_control where singleton) then
    return jsonb_build_object('status','SERVICE_DISABLED'); end if;

  if action in ('create_public','create_entry') then
    allowed_keys:=array['request_id','title','body','paper_style','font_style','weather_kind','latitude','longitude',
      'accuracy_m','captured_at','is_mock','movement_status','consent_version','share_confirmed'];
    if action='create_entry' then allowed_keys:=array_append(allowed_keys,'visibility'); end if;
    if payload-allowed_keys<>'{}'::jsonb or jsonb_typeof(payload->'request_id') is distinct from 'string' or
       jsonb_typeof(payload->'title') is distinct from 'string' or jsonb_typeof(payload->'body') is distinct from 'string' or
       (payload->'movement_status') is distinct from '"settled"'::jsonb or
       (payload->'consent_version') is distinct from '1'::jsonb or
       (payload->'share_confirmed') is distinct from 'true'::jsonb or
       payload->>'paper_style' not in ('warm_white','kraft','midnight') or
       payload->>'font_style' not in ('clear','handwritten','serif') or
       jsonb_typeof(payload->'paper_style') is distinct from 'string' or
       jsonb_typeof(payload->'font_style') is distinct from 'string' or
       char_length(payload->>'title')>80 or char_length(payload->>'body')>10000 or
       nearby_private.meaningful_length(payload->>'body')<10 or
       (payload ? 'weather_kind' and payload->'weather_kind'<>'null'::jsonb and
         (jsonb_typeof(payload->'weather_kind')<>'string' or payload->>'weather_kind' not in ('sunny','overcast','rain','snow','fog')))
       then return jsonb_build_object('status','INVALID_REQUEST'); end if;
    if action='create_entry' and (jsonb_typeof(payload->'visibility') is distinct from 'string' or
      payload->>'visibility' not in ('public','private')) then return jsonb_build_object('status','INVALID_REQUEST'); end if;
    -- movement_status is a client observation, not attestation. It cannot prevent a modified
    -- client from lying; moderation/rate limits remain necessary abuse controls.
    request_uuid:=(payload->>'request_id')::uuid;
    -- Conservative engineering capacity, unrelated to the daily ten-read allowance.
    -- Tombstones are retained for deletion idempotency; a later capacity migration may raise it.
    perform pg_catalog.pg_advisory_xact_lock(pg_catalog.hashtextextended('submit:'||actor::text,0));
    perform pg_catalog.pg_advisory_xact_lock(pg_catalog.hashtextextended(actor::text||':'||request_uuid::text,0));
    digest:=extensions.digest(convert_to(payload::text,'UTF8'),'sha256');
    select pr.*,e.moderation,e.visibility into selected from nearby_private.publication_requests pr
      left join nearby_private.entries e on e.id=pr.entry_id where pr.owner_id=actor and pr.request_id=request_uuid;
    if found then
      if selected.request_hash<>digest then return jsonb_build_object('status','REQUEST_CONFLICT'); end if;
      if selected.deleted_at is not null or selected.moderation is null then return jsonb_build_object('status','DELETED'); end if;
      return jsonb_build_object('status',case when selected.visibility='private' then 'PRIVATE_SAVED' else 'PUBLICATION_PREPARED' end,
        'entry_id',selected.entry_id,'moderation',selected.moderation,
        'visibility',selected.visibility);
    end if;
    now_at:=clock_timestamp();
    if not nearby_private.valid_location(payload,now_at) then return jsonb_build_object('status','LOCATION_UNRELIABLE'); end if;
    if (select count(*) from nearby_private.publication_requests where owner_id=actor)>=1000 then
      return jsonb_build_object('status','LIFETIME_SUBMISSION_LIMIT'); end if;
    insert into nearby_private.entries(owner_id,request_id,request_hash,title,body,weather_kind,paper_style,font_style,
      moderation,visibility) values(actor,request_uuid,digest,payload->>'title',payload->>'body',payload->>'weather_kind',
      payload->>'paper_style',payload->>'font_style','pending',
      case when action='create_public' then 'public' else payload->>'visibility' end) returning id into entry_uuid;
    insert into nearby_private.anchors(entry_id,position,accuracy_m,captured_at,location_consent_version)
      values(entry_uuid,gis.st_setsrid(gis.st_makepoint((payload->>'longitude')::double precision,
        (payload->>'latitude')::double precision),4326)::gis.geography,(payload->>'accuracy_m')::double precision,
        (payload->>'captured_at')::timestamptz,1);
    insert into nearby_private.publication_requests(owner_id,request_id,request_hash,entry_id)
      values(actor,request_uuid,digest,entry_uuid);
    return jsonb_build_object('status',case when action='create_public' or payload->>'visibility'='public'
      then 'PUBLICATION_PREPARED' else 'PRIVATE_SAVED' end,'entry_id',entry_uuid,'moderation','pending',
      'visibility',case when action='create_public' then 'public' else payload->>'visibility' end);
  end if;

  if action = 'prepare_open' then
    -- These are client-reported fixes. Accuracy/mock checks and conservative geometry reduce
    -- accidental disclosure but do not attest hardware position or defeat a modified client.
    allowed_keys := array['request_id','generation','latitude','longitude','accuracy_m','captured_at','is_mock'];
    if payload - allowed_keys <> '{}'::jsonb or jsonb_typeof(payload->'request_id') is distinct from 'string' or
       jsonb_typeof(payload->'generation') is distinct from 'number' or not nearby_private.valid_location(payload,now_at) then
      return jsonb_build_object('status','LOCATION_UNRELIABLE'); end if;
    request_uuid := (payload->>'request_id')::uuid; generation_value := (payload->>'generation')::bigint;
    if generation_value <= 0 then return jsonb_build_object('status','INVALID_REQUEST'); end if;
    perform pg_catalog.pg_advisory_xact_lock(pg_catalog.hashtextextended('reader:'||actor::text,0));
    perform pg_catalog.pg_advisory_xact_lock(pg_catalog.hashtextextended(actor::text||':'||request_uuid::text,0));
    now_at:=clock_timestamp();
    if not nearby_private.valid_location(payload,now_at) then return jsonb_build_object('status','LOCATION_UNRELIABLE'); end if;
    digest := extensions.digest(convert_to(payload::text,'UTF8'),'sha256');
    select * into existing_session from nearby_private.reader_sessions
      where reader_id=actor and prepare_request_id=request_uuid for update;
    if found then
      if existing_session.prepare_hash <> digest then return jsonb_build_object('status','REQUEST_CONFLICT'); end if;
      now_at:=clock_timestamp();
      if not nearby_private.valid_location(payload,now_at) then return jsonb_build_object('status','LOCATION_UNRELIABLE'); end if;
      if existing_session.state <> 'staged' or existing_session.staged_until <= now_at then return jsonb_build_object('status','EXPIRED'); end if;
      if not exists(select 1 from nearby_private.entries e join nearby_private.publications p on p.entry_id=e.id
        where e.id=existing_session.entry_id and e.visibility='public' and e.moderation='approved' and p.state='approved'
        and p.media_count=0 and not exists(select 1 from nearby_private.reports r
          where r.reporter_id=actor and r.entry_id=e.id)
        and not exists(select 1 from nearby_private.blocks b where
          (b.blocker_id=actor and b.blocked_id=e.owner_id) or (b.blocker_id=e.owner_id and b.blocked_id=actor))) then
        update nearby_private.reader_sessions set state='ended',ended_at=now_at where id=existing_session.id;
        return jsonb_build_object('status','NOT_READY');
      end if;
      return jsonb_build_object('status','CONTENT_STAGED','reader_session_id',existing_session.id,
        'generation',existing_session.generation,'staged_until',existing_session.staged_until,
        'content_ready',true,'media_ready',true);
    end if;
    update nearby_private.reader_sessions set state='ended',ended_at=now_at
      where reader_id=actor and state='staged' and staged_until<=now_at;
    update nearby_private.reader_sessions set state='ended',ended_at=now_at,lease_until=null
      where reader_id=actor and state='open' and (recovery_deadline is null or recovery_deadline<=now_at);
    if exists(select 1 from nearby_private.reader_sessions where reader_id=actor and state in ('staged','open')) then
      return jsonb_build_object('status','READING_IN_PROGRESS');
    end if;
    account_day:=(now_at at time zone zone_name)::date;
    if coalesce((select used from nearby_private.read_days where reader_id=actor and account_date=account_day),0)>=10 then
      return jsonb_build_object('status','DAILY_LIMIT_REACHED','account_date',account_day,'used',10,'daily_limit',10);
    end if;
    -- Selection reads only the opaque entry key. Plaintext is fetched only inside
    -- open_commit after the quota row is locked and incremented.
    select e.id into selected
      from nearby_private.entries e join nearby_private.anchors a on a.entry_id=e.id
      join nearby_private.publications p on p.entry_id=e.id
      where e.owner_id <> actor and e.visibility='public' and e.moderation='approved'
        and p.state='approved' and p.media_count=0
        and not exists (select 1 from nearby_private.encounters x where x.reader_id=actor and x.entry_id=e.id)
        and not exists (select 1 from nearby_private.reader_sessions rs where rs.reader_id=actor and rs.entry_id=e.id
          and rs.state in ('staged','open'))
        and not exists (select 1 from nearby_private.reports r where r.reporter_id=actor and r.entry_id=e.id)
        and not exists (select 1 from nearby_private.blocks b where
          (b.blocker_id=actor and b.blocked_id=e.owner_id) or (b.blocker_id=e.owner_id and b.blocked_id=actor))
        -- range_decision can return IN_RANGE only inside 200m because its inner
        -- threshold is 200m minus both positive accuracy radii. Keep its stricter
        -- uncertainty-aware decision below; this indexed predicate only prunes.
        and gis.st_dwithin(a.position,
          gis.st_setsrid(gis.st_makepoint((payload->>'longitude')::double precision,
            (payload->>'latitude')::double precision),4326)::gis.geography,200.0)
        and nearby_private.range_decision(a.position,a.accuracy_m,
          gis.st_setsrid(gis.st_makepoint((payload->>'longitude')::double precision,
            (payload->>'latitude')::double precision),4326)::gis.geography,
          (payload->>'accuracy_m')::double precision,
          greatest(0,(extract(epoch from now_at-(payload->>'captured_at')::timestamptz)*1000)::bigint),false)='IN_RANGE'
      order by gen_random_uuid() limit 1 for update of e skip locked;
    if not found then return jsonb_build_object('status','NO_ELIGIBLE_CONTENT'); end if;
    insert into nearby_private.reader_sessions(reader_id,entry_id,prepare_request_id,prepare_hash,generation,state,staged_until)
      values(actor,selected.id,request_uuid,digest,generation_value,'staged',now_at+interval '8 seconds') returning id into session_uuid;
    return jsonb_build_object('status','CONTENT_STAGED','reader_session_id',session_uuid,'generation',generation_value,
      'staged_until',now_at+interval '8 seconds','content_ready',true,'media_ready',true);
  end if;

  if action = 'open_commit' then
    allowed_keys := array['request_id','reader_session_id','generation','sequence','latitude','longitude','accuracy_m','captured_at','is_mock'];
    if payload - allowed_keys <> '{}'::jsonb or jsonb_typeof(payload->'request_id') is distinct from 'string' or
       jsonb_typeof(payload->'reader_session_id') is distinct from 'string' or
       jsonb_typeof(payload->'generation') is distinct from 'number' or
       jsonb_typeof(payload->'sequence') is distinct from 'number'
       then return jsonb_build_object('status','INVALID_REQUEST'); end if;
    request_uuid := (payload->>'request_id')::uuid; session_uuid := (payload->>'reader_session_id')::uuid;
    generation_value := (payload->>'generation')::bigint; fix_sequence := (payload->>'sequence')::bigint;
    perform pg_catalog.pg_advisory_xact_lock(pg_catalog.hashtextextended(actor::text||':'||request_uuid::text,0));
    digest := extensions.digest(convert_to(payload::text,'UTF8'),'sha256');
    select * into existing_commit from nearby_private.open_commits where reader_id=actor and request_id=request_uuid;
    if found then
      if existing_commit.request_hash <> digest then return jsonb_build_object('status','REQUEST_CONFLICT'); end if;
      -- A billing receipt is durable; content authorization is not. Never replay plaintext.
      return existing_commit.response;
    end if;
    now_at:=clock_timestamp();
    if not nearby_private.valid_location(payload,now_at) then return jsonb_build_object('status','LOCATION_UNRELIABLE'); end if;
    select entry_id into entry_uuid from nearby_private.reader_sessions where id=session_uuid and reader_id=actor;
    if entry_uuid is null then return jsonb_build_object('status','NOT_FOUND'); end if;
    perform 1 from nearby_private.entries where id=entry_uuid for update;
    if not found then return jsonb_build_object('status','NOT_FOUND'); end if;
    perform 1 from nearby_private.publications where entry_id=entry_uuid for update;
    if not found then return jsonb_build_object('status','NOT_READY'); end if;
    select rs.*,e.visibility,e.moderation,e.title,e.body,e.paper_style,e.font_style,e.weather_kind,e.owner_id,
      p.state as publication_state,p.media_count,
      a.position,a.accuracy_m as anchor_accuracy
      into selected from nearby_private.reader_sessions rs join nearby_private.entries e on e.id=rs.entry_id
      join nearby_private.publications p on p.entry_id=e.id join nearby_private.anchors a on a.entry_id=e.id
      where rs.id=session_uuid and rs.reader_id=actor and e.id=entry_uuid for update of rs;
    if not found then return jsonb_build_object('status','NOT_FOUND'); end if;
    -- A concurrent identical request may have committed while this call waited for the
    -- session lock. Re-read after locking before inspecting the now-open session state.
    select * into existing_commit from nearby_private.open_commits where reader_id=actor and request_id=request_uuid;
    if found then
      if existing_commit.request_hash <> digest then return jsonb_build_object('status','REQUEST_CONFLICT'); end if;
      return existing_commit.response;
    end if;
    -- Row/advisory locks can wait. Re-sample wall time only after the entry,
    -- publication and session are all locked; statement_timestamp() is too old here.
    now_at:=clock_timestamp();
    if not nearby_private.valid_location(payload,now_at) then
      return jsonb_build_object('status','LOCATION_UNRELIABLE');
    end if;
    if selected.generation <> generation_value or selected.state <> 'staged' or selected.staged_until <= now_at or
       selected.visibility <> 'public' or selected.moderation <> 'approved' or selected.publication_state <> 'approved' or
       selected.media_count <> 0 or fix_sequence<=selected.last_fix_sequence or
       exists(select 1 from nearby_private.blocks b where
         (b.blocker_id=actor and b.blocked_id=selected.owner_id) or (b.blocker_id=selected.owner_id and b.blocked_id=actor)) or
       exists(select 1 from nearby_private.reports r where r.reporter_id=actor and r.entry_id=selected.entry_id)
       then return jsonb_build_object('status','NOT_READY'); end if;
    decision:=nearby_private.range_decision(selected.position,selected.anchor_accuracy,
      gis.st_setsrid(gis.st_makepoint((payload->>'longitude')::double precision,(payload->>'latitude')::double precision),4326)::gis.geography,
      (payload->>'accuracy_m')::double precision,
      greatest(0,(extract(epoch from now_at-(payload->>'captured_at')::timestamptz)*1000)::bigint),false);
    if decision<>'IN_RANGE' then return jsonb_build_object('status',decision); end if;
    if exists(select 1 from nearby_private.encounters where reader_id=actor and entry_id=selected.entry_id for update) then
      update nearby_private.reader_sessions set state='ended',ended_at=now_at where id=session_uuid;
      return jsonb_build_object('status','ALREADY_ENCOUNTERED');
    end if;
    account_day := (now_at at time zone zone_name)::date;
    insert into nearby_private.read_days(reader_id,account_date,used) values(actor,account_day,0)
      on conflict (reader_id,account_date) do nothing;
    select used into day_used from nearby_private.read_days where reader_id=actor and account_date=account_day for update;
    now_at:=clock_timestamp();
    if account_day<>(now_at at time zone zone_name)::date then
      account_day:=(now_at at time zone zone_name)::date;
      insert into nearby_private.read_days(reader_id,account_date,used) values(actor,account_day,0) on conflict do nothing;
      select used into day_used from nearby_private.read_days where reader_id=actor and account_date=account_day for update;
      now_at:=clock_timestamp();
    end if;
    if not nearby_private.valid_location(payload,now_at) then return jsonb_build_object('status','LOCATION_UNRELIABLE'); end if;
    if selected.staged_until<=now_at then return jsonb_build_object('status','EXPIRED'); end if;
    decision:=nearby_private.range_decision(selected.position,selected.anchor_accuracy,
      gis.st_setsrid(gis.st_makepoint((payload->>'longitude')::double precision,(payload->>'latitude')::double precision),4326)::gis.geography,
      (payload->>'accuracy_m')::double precision,
      greatest(0,(extract(epoch from now_at-(payload->>'captured_at')::timestamptz)*1000)::bigint),false);
    if decision<>'IN_RANGE' then return jsonb_build_object('status',decision); end if;
    if day_used >= 10 then return jsonb_build_object('status','DAILY_LIMIT_REACHED','account_date',account_day,'used',day_used,'daily_limit',10); end if;
    day_used := day_used+1;
    update nearby_private.read_days set used=day_used,updated_at=now_at where reader_id=actor and account_date=account_day;
    update nearby_private.reader_sessions set state='open',last_fix_sequence=fix_sequence,
      lease_until=now_at+interval '5 seconds',recovery_deadline=now_at+interval '35 seconds' where id=session_uuid;
    insert into nearby_private.encounters(reader_id,entry_id) values(actor,selected.entry_id);
    result := jsonb_build_object('status','OPEN_COMMITTED','reader_session_id',session_uuid,'generation',generation_value,
      'server_expires_at',now_at+interval '5 seconds','account_date',account_day,'used',day_used,'daily_limit',10,
      'server_committed_at',now_at);
    insert into nearby_private.open_commits(reader_id,request_id,request_hash,reader_session_id,account_date,ordinal,committed_at,response)
      values(actor,request_uuid,digest,session_uuid,account_day,day_used,now_at,result);
    return result||jsonb_build_object('title',selected.title,'body',selected.body,'paper_style',selected.paper_style,
      'font_style',selected.font_style,'weather_kind',selected.weather_kind,'media',jsonb_build_array());
  end if;

  if action = 'validate' then
    -- Every renewal is bound to an authenticated account, opaque session, generation,
    -- monotonically increasing fix sequence and idempotency request. It still cannot prove GPS.
    allowed_keys := array['request_id','reader_session_id','generation','sequence','latitude','longitude','accuracy_m','captured_at','is_mock'];
    if payload - allowed_keys <> '{}'::jsonb or jsonb_typeof(payload->'request_id') is distinct from 'string' or
       jsonb_typeof(payload->'reader_session_id') is distinct from 'string' or
       jsonb_typeof(payload->'generation') is distinct from 'number' or
       jsonb_typeof(payload->'sequence') is distinct from 'number' then
      return jsonb_build_object('status','INVALID_REQUEST'); end if;
    request_uuid := (payload->>'request_id')::uuid; session_uuid := (payload->>'reader_session_id')::uuid;
    generation_value := (payload->>'generation')::bigint; digest:=extensions.digest(convert_to(payload::text,'UTF8'),'sha256');
    perform pg_catalog.pg_advisory_xact_lock(pg_catalog.hashtextextended(actor::text||':'||request_uuid::text,0));
    fix_sequence := (payload->>'sequence')::bigint;
    select entry_id into entry_uuid from nearby_private.reader_sessions where id=session_uuid and reader_id=actor;
    if entry_uuid is null then return jsonb_build_object('status','ENDED'); end if;
    perform 1 from nearby_private.entries where id=entry_uuid for update;
    perform 1 from nearby_private.publications where entry_id=entry_uuid for update;
    select rs.*,e.visibility,e.moderation,e.owner_id,p.state as publication_state,a.position,a.accuracy_m as anchor_accuracy
      into selected from nearby_private.reader_sessions rs join nearby_private.entries e on e.id=rs.entry_id
      join nearby_private.publications p on p.entry_id=e.id join nearby_private.anchors a on a.entry_id=e.id
      where rs.id=session_uuid and rs.reader_id=actor and e.id=entry_uuid for update of rs;
    if not found then return jsonb_build_object('status','ENDED'); end if;
    now_at:=clock_timestamp();
    if selected.recovery_deadline is null or selected.recovery_deadline<=now_at then
      update nearby_private.reader_sessions set state='ended',ended_at=now_at,lease_until=null where id=session_uuid and state<>'ended';
      return jsonb_build_object('status','ENDED');
    end if;
    if selected.state<>'open' or selected.visibility<>'public' or selected.moderation<>'approved' or
       selected.publication_state<>'approved' or
       exists(select 1 from nearby_private.reports r
         where r.reporter_id=actor and r.entry_id=selected.entry_id) or exists(select 1 from nearby_private.blocks b where
         (b.blocker_id=actor and b.blocked_id=selected.owner_id) or (b.blocker_id=selected.owner_id and b.blocked_id=actor)) then
      update nearby_private.reader_sessions set state='ended',ended_at=coalesce(ended_at,now_at),lease_until=null
        where id=session_uuid and state<>'ended';
      return jsonb_build_object('status','ENDED');
    end if;
    if not nearby_private.valid_location(payload,now_at) then
      update nearby_private.reader_sessions set lease_until=now_at where id=session_uuid;
      return jsonb_build_object('status','UNKNOWN');
    end if;
    -- Serialize on the session, then recover a request that committed while this call waited.
    select response,request_hash into prior_response,prior_hash from nearby_private.validation_receipts
      where reader_id=actor and request_id=request_uuid;
    if found then
      if prior_hash<>digest then return jsonb_build_object('status','REQUEST_CONFLICT'); end if;
      if prior_response->>'status'='IN_RANGE' then
        if selected.lease_until is null or selected.lease_until<=now_at or
           (prior_response->>'server_expires_at')::timestamptz<=now_at then
          return jsonb_build_object('status','UNKNOWN');
        end if;
        return prior_response||jsonb_build_object('remaining_ms',greatest(0,
          (extract(epoch from ((prior_response->>'server_expires_at')::timestamptz-now_at))*1000)::bigint));
      end if;
      return prior_response;
    end if;
    if selected.generation <> generation_value or selected.state <> 'open' or
       selected.visibility <> 'public' or selected.moderation <> 'approved' or selected.publication_state <> 'approved' or
       fix_sequence <= selected.last_fix_sequence then
      update nearby_private.reader_sessions set state='ended',ended_at=coalesce(ended_at,now_at),lease_until=null
        where id=session_uuid and state<>'ended';
      result:=jsonb_build_object('status','ENDED');
      insert into nearby_private.validation_receipts values(actor,request_uuid,digest,session_uuid,result,now_at);
      return result;
    end if;
    decision := nearby_private.range_decision(selected.position,selected.anchor_accuracy,
      gis.st_setsrid(gis.st_makepoint((payload->>'longitude')::double precision,(payload->>'latitude')::double precision),4326)::gis.geography,
      (payload->>'accuracy_m')::double precision,
      greatest(0,(extract(epoch from now_at-(payload->>'captured_at')::timestamptz)*1000)::bigint),false);
    if decision='IN_RANGE' then
      update nearby_private.reader_sessions set last_fix_sequence=fix_sequence,
        lease_until=now_at+interval '5 seconds',recovery_deadline=now_at+interval '35 seconds' where id=session_uuid;
      result:=jsonb_build_object('status','IN_RANGE','reader_session_id',session_uuid,'generation',generation_value,
        'sequence',fix_sequence,'server_expires_at',now_at+interval '5 seconds');
    elsif decision='OUT_OF_RANGE' then
      update nearby_private.reader_sessions set state='ended',ended_at=now_at,lease_until=null where id=session_uuid;
      result:=jsonb_build_object('status','OUT_OF_RANGE');
    else
      update nearby_private.reader_sessions set last_fix_sequence=fix_sequence,lease_until=now_at where id=session_uuid;
      result:=jsonb_build_object('status','UNKNOWN');
    end if;
    insert into nearby_private.validation_receipts values(actor,request_uuid,digest,session_uuid,result,now_at);
    return result;
  end if;

  if action='resume_content' then
    allowed_keys:=array['reader_session_id','generation','sequence','latitude','longitude','accuracy_m','captured_at','is_mock'];
    if payload-allowed_keys<>'{}'::jsonb or jsonb_typeof(payload->'reader_session_id') is distinct from 'string' or
       jsonb_typeof(payload->'generation') is distinct from 'number' or jsonb_typeof(payload->'sequence') is distinct from 'number'
       then return jsonb_build_object('status','INVALID_REQUEST'); end if;
    session_uuid:=(payload->>'reader_session_id')::uuid; generation_value:=(payload->>'generation')::bigint;
    fix_sequence:=(payload->>'sequence')::bigint;
    select entry_id into entry_uuid from nearby_private.reader_sessions where id=session_uuid and reader_id=actor;
    if entry_uuid is null then return jsonb_build_object('status','ENDED'); end if;
    perform 1 from nearby_private.entries where id=entry_uuid for update;
    perform 1 from nearby_private.publications where entry_id=entry_uuid for update;
    select rs.*,e.title,e.body,e.paper_style,e.font_style,e.weather_kind,e.visibility,e.moderation,e.owner_id,
      p.state as publication_state,a.position,a.accuracy_m as anchor_accuracy into selected
      from nearby_private.reader_sessions rs join nearby_private.entries e on e.id=rs.entry_id
      join nearby_private.publications p on p.entry_id=e.id join nearby_private.anchors a on a.entry_id=e.id
      where rs.id=session_uuid and rs.reader_id=actor and e.id=entry_uuid for update of rs;
    now_at:=clock_timestamp();
    if not found then return jsonb_build_object('status','ENDED'); end if;
    if selected.recovery_deadline is null or selected.recovery_deadline<=now_at then
      update nearby_private.reader_sessions set state='ended',ended_at=coalesce(ended_at,now_at),lease_until=null
        where id=session_uuid and state<>'ended';
      return jsonb_build_object('status','ENDED');
    end if;
    -- lease_until controls current plaintext visibility. A fresh in-range fix may
    -- recover it until recovery_deadline; UNKNOWN never advances that deadline.
    if selected.state<>'open' or selected.generation<>generation_value or
       fix_sequence<=selected.last_fix_sequence or selected.visibility<>'public' or
       selected.moderation<>'approved' or selected.publication_state<>'approved' or
       exists(select 1 from nearby_private.reports r where r.reporter_id=actor and r.entry_id=selected.entry_id) or
       exists(select 1 from nearby_private.blocks b where (b.blocker_id=actor and b.blocked_id=selected.owner_id) or
         (b.blocker_id=selected.owner_id and b.blocked_id=actor)) then
      update nearby_private.reader_sessions set state='ended',ended_at=coalesce(ended_at,now_at),lease_until=null
        where id=session_uuid and state<>'ended';
      return jsonb_build_object('status','ENDED');
    end if;
    if not nearby_private.valid_location(payload,now_at) then
      update nearby_private.reader_sessions set lease_until=now_at where id=session_uuid;
      return jsonb_build_object('status','UNKNOWN');
    end if;
    decision:=nearby_private.range_decision(selected.position,selected.anchor_accuracy,
      gis.st_setsrid(gis.st_makepoint((payload->>'longitude')::double precision,(payload->>'latitude')::double precision),4326)::gis.geography,
      (payload->>'accuracy_m')::double precision,greatest(0,
        (extract(epoch from now_at-(payload->>'captured_at')::timestamptz)*1000)::bigint),false);
    if decision='OUT_OF_RANGE' then
      update nearby_private.reader_sessions set state='ended',ended_at=now_at,lease_until=null where id=session_uuid;
      return jsonb_build_object('status','OUT_OF_RANGE');
    elsif decision<>'IN_RANGE' then
      update nearby_private.reader_sessions set last_fix_sequence=fix_sequence,lease_until=now_at where id=session_uuid;
      return jsonb_build_object('status','UNKNOWN');
    end if;
    update nearby_private.reader_sessions set last_fix_sequence=fix_sequence,
      lease_until=now_at+interval '5 seconds',recovery_deadline=now_at+interval '35 seconds' where id=session_uuid;
    return jsonb_build_object('status','CONTENT_RESUMED','reader_session_id',session_uuid,
      'server_expires_at',now_at+interval '5 seconds','title',selected.title,'body',selected.body,
      'paper_style',selected.paper_style,'font_style',selected.font_style,'weather_kind',selected.weather_kind,'media',jsonb_build_array());
  end if;

  if action = 'react' then
    allowed_keys := array['request_id','reader_session_id','kind'];
    if payload - allowed_keys <> '{}'::jsonb or jsonb_typeof(payload->'request_id') is distinct from 'string' or
       jsonb_typeof(payload->'reader_session_id') is distinct from 'string' or
       jsonb_typeof(payload->'kind') is distinct from 'string' then return jsonb_build_object('status','INVALID_REQUEST'); end if;
    request_uuid := (payload->>'request_id')::uuid; session_uuid := (payload->>'reader_session_id')::uuid;
    reaction_kind := payload->>'kind'; digest:=extensions.digest(convert_to(payload::text,'UTF8'),'sha256');
    perform pg_catalog.pg_advisory_xact_lock(pg_catalog.hashtextextended(actor::text||':'||request_uuid::text,0));
    if reaction_kind not in ('hug','understand','thanks','joy') then return jsonb_build_object('status','INVALID_REQUEST'); end if;
    select request_hash,kind into selected from nearby_private.reactions where reader_id=actor and request_id=request_uuid;
    if found then
      if selected.request_hash<>digest then return jsonb_build_object('status','REQUEST_CONFLICT'); end if;
      return jsonb_build_object('status','REACTION_ACCEPTED','kind',selected.kind);
    end if;
    select entry_id into entry_uuid from nearby_private.reader_sessions where id=session_uuid and reader_id=actor;
    if entry_uuid is null then return jsonb_build_object('status','SESSION_NOT_ACTIVE'); end if;
    perform 1 from nearby_private.entries where id=entry_uuid for update;
    perform 1 from nearby_private.publications where entry_id=entry_uuid for update;
    select rs.entry_id,e.owner_id into selected from nearby_private.reader_sessions rs
      join nearby_private.entries e on e.id=rs.entry_id join nearby_private.publications p on p.entry_id=e.id
      where rs.id=session_uuid and rs.reader_id=actor and rs.state='open' and rs.lease_until>now_at
      and e.visibility='public' and e.moderation='approved' and p.state='approved'
      and not exists(select 1 from nearby_private.reports r where r.reporter_id=actor and r.entry_id=e.id)
      and not exists(select 1 from nearby_private.blocks b where (b.blocker_id=actor and b.blocked_id=e.owner_id) or
        (b.blocker_id=e.owner_id and b.blocked_id=actor)) for update of rs;
    if not found then return jsonb_build_object('status','SESSION_NOT_ACTIVE'); end if;
    now_at:=clock_timestamp();
    if not exists(select 1 from nearby_private.reader_sessions where id=session_uuid and state='open' and lease_until>now_at) then
      return jsonb_build_object('status','SESSION_NOT_ACTIVE');
    end if;
    insert into nearby_private.reactions(reader_session_id,reader_id,request_id,request_hash,entry_id,owner_id,kind)
      values(session_uuid,actor,request_uuid,digest,selected.entry_id,selected.owner_id,reaction_kind) on conflict do nothing;
    if not found then
      select request_hash,kind into prior_hash,reaction_kind from nearby_private.reactions
        where reader_id=actor and request_id=request_uuid;
      if found and prior_hash=digest then return jsonb_build_object('status','REACTION_ACCEPTED','kind',reaction_kind); end if;
      return jsonb_build_object('status','ALREADY_REACTED');
    end if;
    return jsonb_build_object('status','REACTION_ACCEPTED','kind',reaction_kind);
  end if;

  if action = 'reaction_inbox' then
    if payload - array['mark_read'] <> '{}'::jsonb or (payload ? 'mark_read' and jsonb_typeof(payload->'mark_read') <> 'boolean') then
      return jsonb_build_object('status','INVALID_REQUEST'); end if;
    select coalesce(jsonb_agg(jsonb_build_object('entry_id',entry_id,'kind',kind,'count',amount,'unread',unread_count)
      order by entry_id,kind),'[]'::jsonb) into result from (
      select entry_id,kind,count(*) amount,count(*) filter(where read_at is null) unread_count
      from nearby_private.reactions where owner_id=actor group by entry_id,kind) grouped;
    if payload->'mark_read'='true'::jsonb then update nearby_private.reactions set read_at=coalesce(read_at,now_at) where owner_id=actor; end if;
    return jsonb_build_object('status','FOUND','items',result);
  end if;

  if action = 'report' then
    allowed_keys := array['reader_session_id','category','detail'];
    if payload - allowed_keys <> '{}'::jsonb or jsonb_typeof(payload->'reader_session_id') is distinct from 'string' or
       jsonb_typeof(payload->'category') is distinct from 'string' or
       (payload ? 'detail' and payload->'detail'<>'null'::jsonb and jsonb_typeof(payload->'detail')<>'string')
       then return jsonb_build_object('status','INVALID_REQUEST'); end if;
    session_uuid := (payload->>'reader_session_id')::uuid; report_category := payload->>'category'; detail_value := payload->>'detail';
    if report_category not in ('privacy','harassment','inappropriate','spam') or char_length(coalesce(detail_value,''))>1000 then
      return jsonb_build_object('status','INVALID_REQUEST'); end if;
    select entry_id into entry_uuid from nearby_private.reader_sessions where id=session_uuid and reader_id=actor;
    if entry_uuid is null then return jsonb_build_object('status','NOT_FOUND'); end if;
    perform 1 from nearby_private.entries where id=entry_uuid for update;
    insert into nearby_private.reports(reporter_id,entry_id,category,detail) values(actor,entry_uuid,report_category,nullif(detail_value,''))
      on conflict (reporter_id,entry_id) do update set category=excluded.category,detail=excluded.detail,status='open';
    update nearby_private.reader_sessions set state='ended',ended_at=coalesce(ended_at,now_at),lease_until=null
      where id=session_uuid and reader_id=actor and state<>'ended';
    return jsonb_build_object('status','REPORT_RECEIVED');
  end if;

  if action = 'block' then
    if payload - array['reader_session_id'] <> '{}'::jsonb then return jsonb_build_object('status','INVALID_REQUEST'); end if;
    session_uuid := (payload->>'reader_session_id')::uuid;
    select e.owner_id,rs.entry_id into selected from nearby_private.reader_sessions rs join nearby_private.entries e on e.id=rs.entry_id
      where rs.id=session_uuid and rs.reader_id=actor;
    if not found then return jsonb_build_object('status','NOT_FOUND'); end if;
    perform 1 from nearby_private.entries where id=selected.entry_id for update;
    insert into nearby_private.blocks(blocker_id,blocked_id) values(actor,selected.owner_id) on conflict do nothing;
    update nearby_private.reader_sessions set state='ended',ended_at=coalesce(ended_at,now_at),lease_until=null
      where reader_id=actor and entry_id in (select id from nearby_private.entries where owner_id=selected.owner_id) and state<>'ended';
    return jsonb_build_object('status','BLOCKED');
  end if;

  if action='end_session' then
    if payload-array['reader_session_id']<>'{}'::jsonb then return jsonb_build_object('status','INVALID_REQUEST'); end if;
    session_uuid:=(payload->>'reader_session_id')::uuid;
    update nearby_private.reader_sessions set state='ended',ended_at=coalesce(ended_at,now_at),lease_until=null
      where id=session_uuid and reader_id=actor;
    if not found then return jsonb_build_object('status','NOT_FOUND'); end if;
    return jsonb_build_object('status','ENDED');
  end if;

  if action in ('withdraw_publication','delete_entry','set_visibility','appeal','appeal_status') then
    if payload - (case when action='appeal' then array['entry_id','detail']
      when action='set_visibility' then array['entry_id','visibility'] else array['entry_id'] end) <> '{}'::jsonb then
      return jsonb_build_object('status','INVALID_REQUEST'); end if;
    entry_uuid := (payload->>'entry_id')::uuid;
    select * into selected from nearby_private.entries where id=entry_uuid and owner_id=actor for update;
    if not found then return jsonb_build_object('status','NOT_FOUND'); end if;
    if action='set_visibility' then
      if jsonb_typeof(payload->'visibility') is distinct from 'string' or payload->>'visibility' not in ('public','private') then
        return jsonb_build_object('status','INVALID_REQUEST'); end if;
      if payload->>'visibility'='private' then
        update nearby_private.publications set state='withdrawn',reviewed_at=coalesce(reviewed_at,now_at) where entry_id=entry_uuid;
        update nearby_private.entries set visibility='private',withdrawn_at=null where id=entry_uuid;
      else
        update nearby_private.publications set state='pending',reviewed_at=null,reason_code=null where entry_id=entry_uuid;
        update nearby_private.entries set visibility='public',moderation='pending',withdrawn_at=null where id=entry_uuid;
      end if;
      update nearby_private.reader_sessions set state='ended',ended_at=coalesce(ended_at,now_at),lease_until=null
        where entry_id=entry_uuid and state<>'ended';
      return jsonb_build_object('status','VISIBILITY_UPDATED','entry_id',entry_uuid,'visibility',payload->>'visibility',
        'moderation',case when payload->>'visibility'='public' then 'pending' else selected.moderation end);
    end if;
    if action='appeal_status' then
      select state into decision from nearby_private.appeals where entry_id=entry_uuid and owner_id=actor order by created_at desc limit 1;
      if decision is null then return jsonb_build_object('status','NOT_FOUND'); end if;
      return jsonb_build_object('status','FOUND','appeal_state',decision);
    end if;
    if action='appeal' then
      detail_value:=payload->>'detail'; if char_length(coalesce(detail_value,'')) not between 1 and 1000 then return jsonb_build_object('status','INVALID_REQUEST'); end if;
      if not exists(select 1 from nearby_private.publications where entry_id=entry_uuid and state='rejected') then
        return jsonb_build_object('status','NOT_APPEALABLE'); end if;
      insert into nearby_private.appeals(entry_id,owner_id,detail) values(entry_uuid,actor,detail_value);
      return jsonb_build_object('status','APPEAL_RECEIVED');
    end if;
    perform 1 from nearby_private.publications where entry_id=entry_uuid for update;
    update nearby_private.reader_sessions set state='ended',ended_at=coalesce(ended_at,now_at),lease_until=null where entry_id=entry_uuid and state<>'ended';
    if action='delete_entry' then
      update nearby_private.publication_requests set deleted_at=coalesce(deleted_at,now_at) where owner_id=actor and entry_id=entry_uuid;
      delete from nearby_private.entries where id=entry_uuid;
      return jsonb_build_object('status','DELETED');
    end if;
    update nearby_private.entries set visibility='withdrawn',withdrawn_at=coalesce(withdrawn_at,now_at) where id=entry_uuid;
    update nearby_private.publications set state='withdrawn',reviewed_at=coalesce(reviewed_at,now_at) where entry_id=entry_uuid;
    return jsonb_build_object('status','WITHDRAWN');
  end if;
  return jsonb_build_object('status','UNSUPPORTED_ACTION');
exception when invalid_text_representation or numeric_value_out_of_range or invalid_datetime_format or datetime_field_overflow or check_violation then
  return jsonb_build_object('status','INVALID_REQUEST');
end $$;
