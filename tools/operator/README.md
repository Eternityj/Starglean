# Starglean operator CLI

This local tool uses the public project URL/key and an ordinary short-lived email-OTP session. Authorization still comes exclusively from the server-side `nearby_private.staff_members` table. It never accepts a service-role key and cannot grant staff access.

```sh
node tools/operator/starglean.mjs list reports 20
node tools/operator/starglean.mjs view REPORT_UUID
node tools/operator/starglean.mjs action REPORT_UUID
node tools/operator/starglean.mjs dismiss REPORT_UUID
node tools/operator/starglean.mjs restore ENTRY_UUID
```

The email address and eight-digit OTP are requested interactively. The OTP is entered with terminal echo disabled and is never accepted through arguments, environment variables, or files. The access token remains only in process memory. On exit the CLI calls Supabase Auth logout with `scope=local`, revoking only this CLI session rather than other signed-in devices.

List output contains only opaque report/entry identifiers, category and timestamps; report detail, diary title/body, author identity and coordinates are deliberately omitted. The default list size is five and the server maximum is fifty.

`view REPORT_UUID` requires typing the complete `VIEW REPORT_UUID` confirmation before it requests report content. It displays only that report's title, body and report detail in the current terminal. ANSI, control and bidirectional formatting characters are escaped to prevent terminal injection; author identity and location are never displayed or persisted. Every mutation prompts for a short reason, prints the exact opaque target summary, and requires typing the operation plus UUID before it sends the staff RPC.

Local fake-client verification, with no OTP or network traffic:

```sh
node --test tools/operator/starglean.test.mjs
```
