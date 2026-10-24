# Market Pro V3.7.3 — Android Staff Auth-Session Phone Pairing (Development Preview)

**2026-10-24 — review-only Android code. No new release APK, live Supabase DDL/DML, active Staff login, or Cloud writer activation.**

## Scope implemented

- Added `TeamSessionPairingPolicy.kt` (pure Kotlin fail-closed checks): separate Staff vs Owner user identity, distinct physical phone UUID, exact 64-character lower-case invitation, trimmed non-control phone label, verified matching workspace and success payload.
- Added `TeamSessionPairingRpcClient.kt` using the existing encrypted `CloudStaffAuth` and `CloudOwnerAuth` sessions and local `CloudDeviceIdentity`. All RPC paths are allowlisted; HTTPS static origin, no redirects, bounded responses, strict JWT length and no reflected error bodies.
- Owner can **prepare** an invitation (pending only, 30 min on server), Staff can redeem its one-time code from a separate signed-in Auth session, and Owner can explicitly activate Staff as **read-only** after accepting the code. No client-side role flags are trusted.
- The RPC adapter includes separate future Owner-approved writer enable/disable endpoints. Writer approval is behind its own independent gate, and no visible preview button grants writing.
- Added optional Owner/Staff pairing UI in Team Access; password and pairing-code controls do not appear while preview gates are disabled. When future approved rollout gates are enabled, Staff OTP never uses SavedState and is cleared before RPC invocation; Owner-issued code is displayed only in transient UI state and cleared when dismissed.
- Added JUnit policy regression and CI Android build + full JVM unit suite; CI also reruns the complete V372 PostgreSQL Auth-session/write/pull regression in a throwaway PG17 instance.

## Hard safety constraints

The following remain intentionally **false** in `app/build.gradle.kts`: `MARKETPRO_STAFF_LOGIN_ENABLED`, `MARKETPRO_TEAM_PULL_RPC_DEPLOYED`, `MARKETPRO_STAFF_PAIRING_RPC_DEPLOYED`, `MARKETPRO_STAFF_WRITE_APPROVAL_RPC_DEPLOYED`. `MARKETPRO_LOCAL_PREVIEW=true`. AndroidManifest still explicitly removes INTERNET permission; the preview cannot reach Supabase. It uses a separate preview `applicationId` and must not overwrite the Owner's existing Market Pro app.

The original production Owner `marketpro_apply_change` RPC, live migrations, Cloud data, the working web dashboard URL/UI and enrolled devices are unchanged.

## Remaining before two real phones

1. Server-side production migration audit & backup/restore, private `auth.sessions` privileges, **all** Staff SELECT RLS/read protection, deploy safe invitation/RPC functions and correct application role grants. No Staff membership before hardened RLS.
2. Staff account provision with independent verified email; separate Android installers using same workspace but correct staff local Room UUID/cursors. Validate owner/catalog semantic mapping before upload.
3. Hardware-backed device challenge signing and token theft response. **Signed JWT + session_id does not cryptographically prove device possession.**
4. Add explicit Owner confirmation for write approval, emergency full device revocation (not merely writer revoke), idempotent upload, Room conflict reconciliation, midnight Asia/Colombo denial and other expenses Cloud support.
5. Physical Owner+Staff phone test and signed APK cutover only after full backup and functional QA; unsynced records and invoice photos on retired phone cannot be recovered automatically.

## Expected behavior

Owner Phone (existing) stays authorized for past-day edits. Team Phone: today's purchase/requisition/cash edits unlimited with revisions AFTER explicit Owner approval; past-day changes always rejected by the server. Loss/replacement of Staff phone must revoke old device first and pair a fresh phone. Invoice photos remain local.
