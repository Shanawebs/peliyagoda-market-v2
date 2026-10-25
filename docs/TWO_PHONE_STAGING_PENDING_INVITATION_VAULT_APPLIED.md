# Market Pro Two Phone — Staging-Only Pending Invitation Vault

**Applied 2026-10-26 (Asia/Colombo).** Staging Supabase project: `qqiheexpbuzohllugxrk`. NEVER apply to production `lkoptqhodgjeuwwfihfr`.

## Purpose

The Owner and Staff test Auth profiles are confirmed and the Owner has a verified Authenticator (TOTP) factor. The Owner was assigned the QA workspace role. Six direct REST RLS SELECT policies were restricted to existing Owner/Editor/Viewer roles in the prior migration. This is **only a no-access storage preparation** for future short-lived Owner-issued Staff invitations.

## Actual hosted result

- `20261025191049` migration `marketpro_staging_private_pending_invitation_vault`: **SUCCESS**.
- Created `marketpro_private.pending_team_enrollments` with UUID association fields, **SHA-256 invitation token hash** as 32-byte `bytea` (never plaintext), intended device identity, label, expiry, lifecycle status, and future `activated_at`.
- Foreign keys to existing workspace / Auth users, checks for device label, different inviter and staff, expiry after creation, unique intended device, and one pending invite per workspace/staff member.
- Immediately REVOKE all table privileges from `PUBLIC`, `anon`, `authenticated`, turn RLS ON, add **no policies**, and create **no callable RPC**.
- No invitation row, member, phone, OAuth session, live writer, Edge Function, or app change was created.
- Production owner phone, database and dashboard completely outside this Staging-only migration.

## Verified

Live read-only SQL confirmed RLS ON and **0 rows**; `anon` and `authenticated` cannot SELECT; `authenticated` cannot INSERT/UPDATE/DELETE. Owner remains the only workspace member, MFA verified, zero registered phones. Security Advisor informational `rls_enabled_no_policy` for private token vault and the existing `sync_operations` is **intentional default-deny**; a separate warning remains that leaked-password protection is disabled.

Read-only verification: `supabase/staging/staging_pending_invitation_vault_READ_ONLY.sql`.

## Compatibility warning

This empty table's column names cover the review-only V358/V359 invitation lifecycle. **Do not blindly run V358's original `CREATE TABLE` afterwards**; the server proposal chain must be consolidated, audited and tested as one migration on a disposable PG17 database. Owner invitation RPC requires a verified currently enrolled **Owner device**, which is NOT present in hosted Staging yet. A standalone direct SQL insert bypassing owner MFA/session approval would be unsafe and is forbidden.

## Required to reach real pairing

1. Integrate server proposal dependencies and private privilege grants in correct order; tie Owner invite/activation to fresh AAL2, registered Owner device, and independent Staff identity.
2. Review RLS for **all** public tables and views and every callable legacy RPC; Staff paginated reads must require authenticated session plus approved device proof, not just membership/device UUID.
3. Pair a real Staff phone after Owner approval; separate Owner/Staff test alias shares one Gmail inbox, so DO NOT use it for actual worker rollout.
4. Enforce same-day (Asia/Colombo server-clock) unlimited Staff correction, Owner-only prior days, signed/atomic finance operations, revocation/rotation, idempotent offline upload and retries.
5. Build separate Staging Android variant with no compiled Production project refs; two physical device tests, Room/catalog UUID reconciliation, invoice backup, and full cash/PDF parity.

**No Staff App installation, live permission or Production cutover authorized by this milestone.**
