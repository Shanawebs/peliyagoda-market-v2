# Market Pro V3.7.0 — Dual-channel Team Writer Safety (Draft)

**2026-10-24. REVIEW ONLY. Zero production Supabase DDL/DML and no APK.**

## Blocker found in real production

The live `registered_devices_one_enabled_writer_idx` UNIQUE index only permits **one device with enabled=true and can_write=true per workspace**. It must remain in place for the legacy Owner sync. Turning on `can_write=true` for Staff would either fail the unique index or require dropping a live safety invariant.

V3.7.0 uses **two separate server authorization channels** instead:

- Owner: existing `registered_devices.enabled=true AND can_write=true`; legacy `public.marketpro_apply_change` remains untouched and checks `m.role='owner'`.
- Staff: `registered_devices.enabled=true AND can_write=false`, `m.role='purchasing_staff'`, and one *private* `marketpro_private.staff_write_leases` row bound to the workspace, Staff account, and exact registered device, with administrator-controlled `enabled=true`.
- Only audited v2 revision-aware Staff RPCs can consume the private lease. Callers cannot grant a lease through the Android or web client; there is **no publicly exposed grant management endpoint** in this phase.
- The existing unique Owner writer index is retained throughout. A revoked device (`registered_devices.enabled=false`) cannot use a lease, even if the JWT remains temporarily valid.
- All Staff financial changes still use the locked server `Asia/Colombo` business date check: **today unlimited without Owner review; all prior days read-only; Owner can correct prior days**.
- V370 overrides v352 helper and the pre-replay authorization checks in v353 purchase and v354 financial RPC **together**, not just one function. RPC validation, idempotency, audit, revision checks remain as before.

## Code artifacts

- `supabase/proposals/v370_dual_channel_staff_writer_REVIEW_ONLY.sql`: private lease and complete v2 RPC overrides. Requires original v352/v353/v354 proposals and complete role/RLS/Staff enrollment cutover.
- `supabase/tests/v370_dual_channel_staff_writer_pg17_local_only.sql`: disposable PG17 mock that replicates the live single-owner-writer unique index; tests both parallel channels, purchase and finance revision flows, old-day denial, private-grant revocation, device disable, wrong device, viewer denial, and Owner continuity.
- `.github/workflows/marketpro-v370-dual-channel-write-gate-ci.yml`: automatically executes the SQL on a fresh local Postgres 17 container and guards original Dashboard and migration files.

## Remaining before production

1. Real Cloud database backup and restore drill; independent physical-phone Room backup and unsynced invoice photos recovery.
2. Full private grants design audit: administrator or Owner-approved **server** activation mechanism; grant rotation on every replaced/revoked Staff device; avoid hidden privilege changes, add append-only audit and session-device binding beyond a copyable UUID.
3. Deploy role constraint change, restricted Staff REST policies, reviewed device-bound paged pull, invitation/claim, and RPCs as an audited atomic staged migration; **do not grant Staff membership before direct REST isolation**.
4. Audit all exposed tables/views and v1/v2 RPC privileges with real PostgREST/JWT tests. Test security advisor warnings, leak-password protection, server timezone, and other expenses still missing.
5. Wire actual Staff Auth + user confirmation, Owner revoke/replace, safe catalog UUID links, Room inbox/merge and signed Android APKs on **two physical phones**. Evaluate low-RAM scrolling and offline reconnection.
6. Test two independent active devices simultaneously, Owner old-date corrections, Staff today repeated edits, midnight cutoff and revocation. Preserve original dashboard URL/UI/login and historical Cloud records.

**This branch does not touch live Supabase, the original Owner Android installation, or deployed dashboard.** The private lease is NOT enabled on production; no Staff phone is linked.
