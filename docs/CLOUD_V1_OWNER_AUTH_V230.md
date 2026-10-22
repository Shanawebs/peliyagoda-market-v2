# Cloud V1 Owner Authentication — V2.30 DEV Preview

This is a **non-production developer preview**. No automated uploads are enabled.

## Owner account onboarding
- Supabase DEV project: `lkoptqhodgjeuwwfihfr` (Tokyo region).
- Open **Market Pro Cloud DEV V2.30** → upper-right ⋮ menu → **Cloud Owner Login / Sign Up**.
- Use the owner-controlled email account; choose a strong password *on the phone* (12+ characters). Do not send passwords or email-verification links via chat.
- Tap **Create Owner Account / Send Verification Email**, follow Supabase's confirmation link, then return and **Sign In**. Supabase hosted email confirmation is typically enabled by default; check `Authentication > Providers > Email` if no message arrives.
- Confirmation links may navigate to the project's configured Site URL after verifying. Set a suitable allowed Site URL/redirect in the Supabase Dashboard before production. Do not share access tokens or verification links.
- Sign-in tokens stored in Android Keystore AES-256/GCM encrypted SharedPreferences. No plaintext password saved. Session refresh is attempted when the login dialog is reopened.
- Once signed in, use **Copy Auth User ID** (not a secret) and **Cloud Sync Status (Preview) → Copy Device ID**. Actual registered device enrollment is an admin-only, audited step *after* confirmation; do not allow anyone to self-promote to owner.
- Do **not** create users directly in `auth.users` SQL. Use Supabase Auth signup or admin invite.

## Isolation and photo exclusion
- V2.30 DEV application ID is `lk.peliyagoda.marketpro.v230.clouddev`. It has its OWN private Room database; does not read V2.28's existing data/photos, and is not an in-place upgrade.
- Auth client only communicates with Supabase Auth endpoints `/auth/v1/signup`, `/auth/v1/token`, `/auth/v1/user` and `/auth/v1/logout`.
- Only the publicly intended Supabase URL and `sb_publishable_` key are compiled into the APK. NO service-role/secret key/password is embedded.
- There is **no** WorkManager uploader or active sync worker; do not call the application a working realtime cloud-sync client.
- Invoice Photos, Media Storage, image metadata, existing app data and main branch remain untouched.
- All database financial tables remain writable only via separately audited, owner/device-authorized RPC. Signed in != enrolled != upload authorized.

## Gates to enabling real uploads
1. A real owner Auth account is confirmed.
2. Admin read-only verify identity; create workspace + owner membership + exactly one registered primary DEV device.
3. Re-review public `SECURITY DEFINER` RPC warning; test authorization and cross-workspace denial.
4. Implement outbox replay with auth refresh, dependency ordering, stable entity UUIDs, sequential idempotent RPC, server-ACK conditional deletion and restart/network backoff.
5. Safely import historical business data from V2.28 with a separate, explicit backup and validated import method. This DEV package cannot see V2.28 private files. Photo files stay local; no auto-transfer.
6. Verify 4GB Android performance, crash/restart, offline, duplicates, conflicts and arithmetic parity.
