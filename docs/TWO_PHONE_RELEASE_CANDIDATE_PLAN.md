# MARKET PRO TWO-PHONE RELEASE CANDIDATE — Fast, Safe Completion

**2026-10-24 — ACTIVE WORK PLAN (not a production deployment).**
Owner v3.3.7 currently works. Do not modify the live owner device while release
preflight, migrations or two-physical-phone QA are incomplete. Current V3.5+
security APK uses a different application ID and has INTERNET disabled.

## Verified blockers on real Supabase (READ-ONLY introspection, 2026-10-24)

- Deployed public RPC set: only `marketpro_apply_change` (legacy Owner V1).
- `workspace_members` role CHECK permits owner/editor/viewer; no purchasing_staff
  role deployed. Existing direct SELECT policies for six tables use broad
  `marketpro_private.is_workspace_member(workspace_id)`; enabling Staff
  membership first would expose data via direct PostgREST REST reads without
  the device/session/proof guard. Deploy full RLS gate BEFORE enrolling Staff.
- Live `staff_session_bindings`, `staff_write_leases`, Staff device-proof
  challenge/key tables and reviewed v2 RPCs absent.
- `daily_other_expenses` Cloud table/RPC absent, but Room already stores four
  independent expense types + remarks (and JSON backup includes them).
- Current Staff Android `TeamInboxDownloadCoordinator` stages only (does not
  merge to visible Room). `CloudDomainMergeEngine` supports only purchase,
  requisition and cash; addition-lot/catalog and expenses merges incomplete.
  Current `CloudSyncEngine` remains Owner single-writer fresh-only; it cannot
  be used as Staff uploader.
- Current V3.5 security preview has no INTERNET permission, team onboarding,
  server deployment and signing status still require independent verification.

## Completed in this branch

1. **Database proposal for Other Expenses**: immutable business-date key;
   four integer-cent categories and remarks, own RLS owner/editor/viewer
   direct REST SELECT (Staff direct REST denied); authenticated, audited
   date-checked Owner/Staff RPC with optimistic revision, server Colombo-date
   rules and idempotent per-operation tracking. Existing Owner V1 writer RPC
   untouched. All fields mandatory, prevents photo URL/path injection.
2. **PG17 regression**: Owner edits prior-day expenses; Staff unlimited
   *today-only* corrections, stale revisions rejected, same operation
   replay acknowledged and tampering blocked, disabled Staff phone rejected.
3. **Release preflight and go/no-go plan** for production migration and two-phone
   tests. No app install or data migration yet.

## Strict cutover sequence — no shortcuts

1. **Backup** production database and both phones' local JSON, verify data
   counts/reconciliation and recovery. Verify offline photos separately (JSON
   exports do not contain photo bytes). Do not overwrite Owner-installed app or
   Android package identity until a tested migrations path exists.
2. Build one ordered **transactional Supabase migration set** from V3.5.2–V3.7.5;
   consolidate duplicate helper overrides; audit RLS for ALL public tables
   plus roles, grants, service endpoints, JWT claims, device proof, and locks.
   Test with actual Supabase PostgREST authenticated Staff/Owner sessions
   against a separate staging Supabase project.
3. Wire **server-issued challenge proof to each business read/write** atomically.
   V3.7.5 currently verifies and *records* a proof only; it does not protect
   the business RPC calls. The proposed device key must be Owner-approved and
   bound to a real hardware key if available, with appropriate attestation.
4. Finish Room staging/merge for catalog, additions, cash, four expense fields
   and requisitions; verify GUID linkage to already synced 184 master items.
   Add proper Staff outbox with revision-safe create/correct RPC, offline retry,
   conflict inbox, durable ACK and server-date rules. No blanket uploads of
   backup/old Room IDs. Route the other-expenses outbox to its NEW v2 RPC; do
   not send it through legacy `marketpro_apply_change`.
5. Build owner/staff Android client UI with real sign-in, pairing, read-only
   activation, owner writer approval, revoke/replacement; keep Owner historic
   edit policy and Staff today-unlimited corrections. Ensure 4GB RAM smooth
   operation and invoice pictures stay local.
6. Test with two PHYSICAL devices, real JWT + PostgREST, Staff login on a
   third phone (must fail), server clock crossing midnight Asia/Colombo,
   prior-day correction by Staff (must fail), Owner prior-day edit, revocation
   while pending offline sync, replacement, internet loss/retry, double submit,
   device clock spoofing, and parity with the read-only web dashboard.
7. ONLY after all steps pass, deploy to live in stages, back up and watch
   rollback triggers, then create a signed APK and provide a direct link in
   chat. Never publish a new standalone preview APK as if it were final Cloud
   dual-phone app.

## Go/no-go rules

- **NO-GO:** any unauthorized direct Staff REST read, Staff past-day write,
  second Staff phone with copied UUID/session, lost/revoked phone still
  uploading, duplicate financial events, mismatched cash balance, unmigrated
  local records, or missing backup.
- **GO:** Production RLS and Edge signatures independently reviewed;
  all financial data including Other Expenses reconciled; Owner writer
  unchanged; two-phone signed APK pass and tested rollback; old phone can be
  remotely denied Cloud but cannot remotely purge offline files.

This branch is not ready for live migration until the blockers are CLOSED.
