# Market Pro V3.6.0 — Purchasing Staff Auth adapter (dormant)

**Status: source-only development branch. No live Supabase changes, no new staff credentials, no second writer, no download/merge activation, no APK.**

## User's confirmed rule
- Purchasing staff may enter or correct TODAY's market data any number of times without approval.
- Past business dates are visible but read-only on staff phone, based on the SERVER Asia/Colombo date at midnight.
- Owner can edit previous business dates, and updates will eventually sync back to staff.
- Existing V3.3.7 writer, historical cloud rows, invoice photos, current dashboard URL/login and visual UI MUST be preserved.

## This branch actually adds
- `CloudStaffAuth.kt`: a **dedicated Staff Supabase Auth client**, isolated from legacy `CloudOwnerAuth` by its own AES-256-GCM Android Keystore alias and separate encrypted Preferences file. No Staff credentials go into Owner's session; no server service-role key is placed in Android.
- Supports email/password sign-in and secure refresh token rotation for a FUTURE activated server release. Calls `/auth/v1/user` after login AND after refresh, checks verified email, same email + same authenticated user UUID and explicitly rejects signing in with the Owner Auth UUID.
- Transient access tokens are included only in HTTPS Authorization headers (never in URL query, logcat, UI or backups). Network requests use exact fixed Supabase project host, no redirects and a strict bounded response. All response errors shown to UI are generic, no server payload leaks. Session model string rendering redacts access/refresh JWTs.
- `VerifiedStaffPullSessionProvider`: injectable adapter between separate verified Staff Auth and existing owner/staff `TeamInboxDownloadCoordinator`; physical phone UUID comes from the local app identity, but this UUID is NOT cryptographic attestation. Backend STILL checks real Auth identity, registered device, enabled state, member role and expected workspace on each request.
- `StaffAuthSafetyPolicy` + JVM tests: reject Owner account, anonymous/unverified auth, account change during refresh, email mismatch, UUID garbage; triple-off `MARKETPRO_LOCAL_PREVIEW=true`, `MARKETPRO_STAFF_LOGIN_ENABLED=false`, `MARKETPRO_TEAM_PULL_RPC_DEPLOYED=false`. Tests also verify JWT redaction from logs via Kotlin `toString`.
- Readiness-gated `TeamAccessSetupDialog`: Staff email/password controls and sign-in action only rendered when the **reviewed network/backend build flags are enabled** in a future build. Current offline screen still has a DISABLED placeholder and collects no passwords, sends no API calls.
- Existing temporary V3.5.0 APK package ID/display version is deliberately not bumped; a finished **V3.6.0 APK has NOT been generated**.

## Security warnings / missing work
1. Future device identity must be strengthened beyond a client-generated UUID (optional hardware-backed attestation). Do not assume knowing UUID proves possession of the purchasing phone.
2. The v358/v359 invitation, RLS, read-only activation and v353/v354 financial team RPCs are **proposals**, not installed on live Supabase. Reviewed backend Auth/RLS under live PostgREST and operational enrollment is required BEFORE enabling Android flags.
3. Separate staff account provisioning must be done securely on the actual phone, not by placing passwords or one-time invitation codes in this chat.
4. No operational staff login, two-way worker sync, Cloud catalog bootstrap, invoice photo Cloud storage, extra-lot merge or Other Expenses back-end yet. Real download can only be enabled after verified mappings and a complete end-to-end rollback plan.
5. Signing, encrypted data restore, server role controls and two physical 4GB phones must be tested. App uninstall clears local unsent purchases/photos.
6. Existing Cloud Dashboard must remain **visually unchanged**, old user role Owner + legacy Viewer must retain approved access.

The project goal is end-to-end two-way phone sync. **This milestone only prepares its isolated secure Staff Auth integration and does not itself provide live sync.**
