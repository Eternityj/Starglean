-- Report today's opened encounters with the account replies, so the app shows the right
-- remaining count after it restarts. Same pattern as the v2 contract: keep the current
-- dispatcher as the delegate and only add one field to two successful account replies.
alter function nearby_private.public_command(text,jsonb) rename to public_command_v2;

create function nearby_private.public_command(action text,payload jsonb)
returns jsonb language plpgsql security definer set search_path='' as $$
declare
  actor uuid:=nearby_private.current_actor(); result jsonb; zone_name text; used_today integer;
begin
  result:=nearby_private.public_command_v2(action,payload);
  if actor is null or action not in ('register_account','account_get') or
     coalesce(result->>'status','') not in ('ACCOUNT_READY','TIMEZONE_IMMUTABLE') then
    return result;
  end if;
  select account_timezone into zone_name from nearby_private.accounts where user_id=actor;
  if zone_name is null then return result; end if;
  -- The same account day that open_commit counts against.
  select used into used_today from nearby_private.read_days
    where reader_id=actor and account_date=(statement_timestamp() at time zone zone_name)::date;
  return result||jsonb_build_object('reads_used_today',coalesce(used_today,0));
end $$;

revoke all on function nearby_private.public_command_v2(text,jsonb) from public,anon,authenticated,service_role;
revoke all on function nearby_private.public_command(text,jsonb) from public,anon,authenticated,service_role;
grant execute on function nearby_private.public_command(text,jsonb) to authenticated;
