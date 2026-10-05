# Supabase Auth email templates

Local deployment-ready source only. These files are not proof that a hosted template or email delivery has been configured.

## Dashboard mapping

- `confirm-sign-up.html` → Authentication → Email Templates → **Confirm sign up**. First-time email registration can use this OTP template; it does not grant moderator privileges. Registration is a separate, explicitly requested action from recovery of an existing account.
- `magic-link.html` → Authentication → Email Templates → **Magic Link**. It intentionally presents `{{ .Token }}` as an OTP, not a clickable sign-in link. Account-recovery calls must set `shouldCreateUser: false` so an unknown address cannot create an account.
- `email-change.html` → Authentication → Email Templates → **Change Email Address**. Use it when `updateUser({ email })` binds an email identity to the current anonymous user or changes an existing address. Updating the signed-in user preserves that user's ID; do not replace this flow with a new sign-up.

With **Secure Email Change** enabled, Supabase can require two separate confirmations and send one code to the current address and another to the new address. Each recipient must use the code from their own message. With it disabled, only the new address is confirmed. The client must follow the project's actual Auth setting and complete every confirmation Supabase requires.

For local or self-hosted use, point the relevant `supabase/config.toml` template paths at these files. For hosted projects, paste the matching HTML into the dashboard template editor. A new Free project using Supabase's default SMTP may not customize Auth templates; custom SMTP is required for customization under the current Supabase policy.

No expiry is stated because validity is controlled by the deployed Auth configuration. These templates contain no external resources, tracking links, user metadata, email-address interpolation, secrets, or delivery claims.

Official references: [Email Templates](https://supabase.com/docs/guides/auth/auth-email-templates), [Anonymous Sign-Ins](https://supabase.com/docs/guides/auth/auth-anonymous), [Send Email Hook](https://supabase.com/docs/guides/auth/auth-hooks/send-email-hook), and [Free-tier template customization change](https://supabase.com/changelog/46599-changes-to-email-template-customisation-on-free-tier).
