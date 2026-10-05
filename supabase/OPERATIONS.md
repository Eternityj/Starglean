# Starglean backend operations

This is an operator runbook, not a client API specification. Never place a
service-role key, a staff access token, or direct private-schema access in the
Android application. The owner explicitly authorized enabling production
discovery on 2026-09-26 at 20:38:11 UTC. `nearby_private.service_control` now has
`public_reading_enabled=true`, confirmed with an independent read after the
update. This changed neither diary visibility nor distance, quota, authentication,
or billing rules. Physical-device and operator workflow acceptance remain separate.

The additive restore/index migration
`20260926195340_fix_post_publication_restore_and_fk_indexes` and the private QA
allowlist migration `20260926195430_add_private_qa_read_allowlist` are deployed.
The cloud-entry capacity migration
`20260926200545_enforce_cloud_entry_limit` and media/deletion lock-order
migration `20260926200608_normalize_media_deletion_lock_order` are also
deployed.

**Pending, NOT deployed yet:** `20261002120000_report_reads_used_today` adds
`reads_used_today` to successful `register_account` and `account_get` replies, so
the app shows today's remaining encounters after a restart. Android 0.5.0 works
with or without it. It renames the current dispatcher to `public_command_v2` and
wraps it, the same pattern as the v2 contract. To deploy:

1. Rehearse: run `tests/report_reads_used_today_rehearsal.sql` in the SQL Editor.
   It applies the migration and 5 checks in one transaction and always rolls back.
   Continue only if the last result is `passed_assertions = 5`.
2. Deploy with the CLI so the migration history stays in step:
   `supabase db push --dry-run` must list only this migration; then `supabase db push`.
   (If it was applied in the SQL Editor instead, record it with
   `supabase migration repair --status applied 20261002120000`.)
3. Confirm: `select proname from pg_proc where proname like 'public_command%'` lists
   `public_command` and `public_command_v2`; move this note into the deployed list
   above and delete the rehearsal file.

To undo, in one transaction: `drop function nearby_private.public_command(text,jsonb);`
`alter function nearby_private.public_command_v2(text,jsonb) rename to public_command;`
`grant execute on function nearby_private.public_command(text,jsonb) to authenticated;`
The explicitly authorized operator account
`047c228c-e80b-4b23-adfa-c7afc5318c19` is currently enabled in
`nearby_private.staff_members`. The manual report/takedown/restore runbook has
not yet been rehearsed end to end.

## Reports and takedowns without an admin UI

Publication uses post-publication moderation: a fully validated public entry is
discoverable immediately. A reader can report only an entry covered by their
active reading session. Reports do not globally remove the entry by themselves;
they hide it from that reporter and enter the operator queue.

The existing authenticated RPC is sufficient for a small, manual workflow:

1. Create or choose a dedicated Auth account for the operator. After explicit
   owner authorization, add that exact user ID to
   `nearby_private.staff_members` with `enabled=true` using the SQL editor. Do
   not grant staff status to an ordinary test account.
2. Sign in as that operator and call `public.nearby_staff_command` through the
   normal authenticated RPC endpoint. `list_reports` accepts
   `{"limit":1..50}` and returns open report IDs plus the affected entry and
   content needed for review; it does not expose reporter IDs.
3. Resolve one report with `report_action` or `report_dismiss`, passing exactly
   `{"report_id":"<uuid>","reason_code":"<1..80 characters>"}`.
   `report_action` atomically rejects the publication, makes the entry
   ineligible for discovery, ends active reading leases through the existing
   revocation trigger, closes the report, and appends an audit record.
   `report_dismiss` closes the report without taking down the entry and still
   writes an audit record.
4. A mistaken takedown can be reversed with `restore`, passing exactly
   `{"entry_id":"<uuid>","reason_code":"<1..80 characters>"}`. Restoration is
   also audited. The required additive migration
   `20260926195340_fix_post_publication_restore_and_fk_indexes` is deployed. Do
   not edit publication or report tables directly as a workaround.
5. Set `staff_members.enabled=false` immediately when an operator should lose
   access. Staff authorization is checked on every RPC call.

There is intentionally no admin UI yet. Queue polling, response-time alerts,
operator enrollment, and appeals remain manual release operations. An
explicitly authorized staff account is enrolled, but until the owner has
rehearsed this workflow, report handling is implemented and authorized but not
operationally validated.

## Entry and account deletion

Clients use the `nearby-private-media` Edge Function for `delete_entry` and
`delete_account`; they must not call the SQL preparation step alone. The Edge
Function first marks the account/entry as deleting, removes Storage objects via
the Storage API, and only then finalizes database/Auth deletion. A retry is
expected after `DELETE_RETRY_REQUIRED` or `OBJECTS_REMAIN`. Direct SQL deletion
from `storage.objects` is forbidden because it can orphan the underlying bytes.

Ordinary accounts are covered by the self-service flow. A staff account that has
written moderation audit rows is deliberately different: retained
`moderation_audit.staff_id` rows reference the Auth user without `ON DELETE
CASCADE`, so self-service Auth deletion can be refused to preserve audit
attribution. Disable the staff membership first and use a separately reviewed
operator offboarding/retention procedure; do not weaken or erase audit history
to make the ordinary endpoint succeed.

Deletion removes refresh sessions when the Auth user is deleted. Existing JWTs
are stateless and may otherwise remain cryptographically valid until expiry, so
the public dispatcher also requires a live `auth.sessions` row on every command.
After finalization, an old token therefore cannot use the application RPCs.

## Free-plan capacity watch

The application is intentionally using only Supabase Free resources. Current
plan figures must be checked in the dashboard/docs before release rather than
treated as permanent constants. As of 2026-09-27 the published allowances
include 500 MB database size per project, 1 GB Storage, 50,000 monthly active
users, 500,000 Edge Function invocations, and 5 GB uncached plus 5 GB cached
egress per organization. Hosted Edge Functions have a 256 MB memory limit, a
2-second CPU limit per request, and a 150-second Free-plan wall-clock limit.

The practical early constraints for this design are private-photo Storage,
photo egress through the no-store Edge proxy, and invocation count (upload
finalization, reads, and deletion all invoke the function). Monitor organization
Usage and Storage before enabling discovery. Free-plan overage is restricted
rather than silently becoming paid capacity, but service restriction is still a
user-visible outage.

Each account may retain at most 1,000 cloud diary entries at once. Pending and
deleting entries count until deletion finalization removes the entry row; a
completed deletion releases one slot. Permanent publication-request receipts
and cancellation/deletion tombstones remain for idempotency but do not consume
diary capacity. Local-only writing does not consume this backend allowance;
the current on-device codec separately limits each account library to 2,000
entries and a 16 MiB encoded diary file. A create
request beyond the limit returns `CLOUD_ENTRY_LIMIT`; retrying an already saved
request remains idempotent and does not consume another slot.

## Edge source verification

`supabase/functions/nearby-private-media/index.ts` is both the deployed entry
module and an importable test module. Deno sets `import.meta.main=true` for the
entry module, so `Deno.serve(handleRequest)` runs in the hosted function. It is
false when `index_test.ts` imports the helpers, preventing a test listener from
starting. Dependencies are version-pinned, request bodies are stream-bounded,
and private media responses use `no-store` after a final live-lease recheck.

Local source verification (does not call the cloud):

```sh
npx --yes deno-bin check \
  supabase/functions/nearby-private-media/index.ts \
  supabase/functions/nearby-private-media/index_test.ts
npx --yes deno-bin test \
  supabase/functions/nearby-private-media/index_test.ts
```
