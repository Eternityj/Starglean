// Read-only denial smoke tests. Supply only an active PUBLISHABLE key, never a secret.
// No signups, diary writes, coordinates, content, or auth tokens are generated here.
const key = process.env.STARGLEAN_PUBLISHABLE_KEY;
if (!key?.startsWith('sb_publishable_')) throw new Error('An active publishable key is required');
const projectRef = process.env.STARGLEAN_PROJECT_REF;
if (!/^[a-z]{20}$/.test(projectRef ?? '')) throw new Error('An explicit Supabase project ref is required');
const base = `https://${projectRef}.supabase.co`;
const cases = [
  ['anonymous RPC', '/rest/v1/rpc/nearby_command', { method: 'POST', body: JSON.stringify({ action: 'status', payload: {} }) }, [401, 403]],
  ['anonymous public protocol denied', '/rest/v1/rpc/nearby_public_command', { method: 'POST', body: JSON.stringify({ action: 'register_account', payload: { account_timezone: 'Australia/Sydney' } }) }, [401, 403]],
  ['anonymous moderation denied', '/rest/v1/rpc/nearby_staff_command', { method: 'POST', body: JSON.stringify({ action: 'list_pending', payload: {} }) }, [401, 403]],
  ['table not exposed', '/rest/v1/anchors?select=entry_id', {}, [404]],
  ['private schema denied', '/rest/v1/anchors?select=entry_id', { headers: { 'Accept-Profile': 'nearby_private' } }, [406]],
  ['helper not exposed', '/rest/v1/rpc/range_decision', { method: 'POST', body: '{}' }, [404]],
  ['forged JWT denied', '/rest/v1/rpc/nearby_command', { method: 'POST', body: '{}', headers: { Authorization: 'Bearer not-a-valid-token' } }, [401]],
  ['forged public JWT denied', '/rest/v1/rpc/nearby_public_command', { method: 'POST', body: '{}', headers: { Authorization: 'Bearer not-a-valid-token' } }, [401]],
];
for (const [name, path, options, expected] of cases) {
  const response = await fetch(base + path, {
    ...options,
    headers: { apikey: key, 'Content-Type': 'application/json', ...options.headers },
    signal: AbortSignal.timeout(15000),
  });
  let code;
  try { code = (await response.json()).code; } catch { /* Only safe status data is reported. */ }
  console.log(JSON.stringify({ check: name, http: response.status, code, passed: expected.includes(response.status) }));
  if (!expected.includes(response.status)) process.exitCode = 1;
}
const response = await fetch(base + '/auth/v1/settings', {
  headers: { apikey: key }, signal: AbortSignal.timeout(15000),
});
const settings = await response.json();
console.log(JSON.stringify({ authSettingsHttp: response.status, disableSignup: settings.disable_signup, anonymousEnabled: settings.external?.anonymous_users }));
if (!response.ok) process.exitCode = 1;
