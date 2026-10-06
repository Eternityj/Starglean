# Supabase API Denial Check Tool

`check-api.mjs` in this directory is a read-only smoke test script designed to verify denial-of-access security rules on the Supabase backend.

## Core Principles

* **Read-Only & Safe**: This script performs read-only boundary checks. It **never** generates signups, diary writes, coordinates, content, or authentication tokens.
* **Prerequisites**: Requires an explicit Supabase Project Ref and a valid Publishable Key. It connects to the live network and should only be run against authorized backends.

## Usage

Provide your publishable key and project reference via environment variables and run the script:

```sh
STARGLEAN_PUBLISHABLE_KEY=sb_publishable_... \
STARGLEAN_PROJECT_REF=your_project_ref \
node tools/supabase-check/check-api.mjs
```

## Check Cases

The script automatically verifies that the following security policies are properly enforced:
1. **Anonymous RPC Denial**: Confirms unauthorized RPC calls return `401` or `403`.
2. **Public Protocol & Moderation Denial**: Ensures anonymous registration or moderation commands are correctly blocked.
3. **Unexposed Tables & Private Schemas**: Verifies unexposed tables return `404` and attempts to access the private schema (`nearby_private`) via `Accept-Profile` return `406`.
4. **Forged JWT Rejection**: Confirms requests with invalid or forged `Bearer` tokens are rejected with `401`.
5. **Auth Global Settings (diagnostic only)**: Prints the signup and anonymous-user settings for manual inspection. It fails on an unsuccessful HTTP response, but does not assert those setting values. A successful script exit therefore does not certify the Auth configuration.
