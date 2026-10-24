# Market Pro V3.7.1 — Owner-approved Staff Writing and Phone Replacement

**Development-only review (2026-10-24). Nothing deployed to live Supabase or Android.**

The older single-Owner device index and the deployed `marketpro_apply_change` owner API are preserved. Staff devices always remain `can_write=false`; a distinct private Staff lease authorizes only audited v2 revision-aware RPC operations.

## New protective server actions (review-only SQL)

1. `marketpro_owner_enable_staff_writer`: Authenticated Owner on the already-enrolled Owner writer device only. Requires independently certified private rollout gates, email-verified Staff Auth, `purchasing_staff` membership, an enabled **read-only** registered Staff phone, an accepted, nonrevoked, **activated** invitation for precisely that phone, and no other active Staff device in workspace. The Owner cannot enroll via this endpoint; it can ONLY authorize writing after read-only activation.
2. `marketpro_owner_disable_staff_writer`: authenticated Owner-only immediate write-lease disable without deleting data or removing read-only Staff access; repeat disable returns `changed=false`, never fake success.
3. V371 overrides V370's `marketpro_private.require_dual_channel_writer`: the administrator-owned private `team_writes_reviewed`, RLS and device-bound pull flags are checked on **every** Staff financial operation. Setting a gate false immediately denies even a previously granted Staff lease. Owner writes are independent of this Staff kill switch.
4. A private append-style activation audit records Owner grant/revoke and device transfer metadata; neither anon nor authenticated may directly read/edit it.
5. On replacement, old phone is disabled through v368 first and the new phone must independently complete invited + accepted + read-only activation before Owner explicitly grants its own lease. An obsolete old device ID or stale pending invitation cannot be used to regain rights. The existing lease is replaced only when the previous phone is disabled; at most one active Staff phone in a workspace.

## Simulated PG17 regression

`supabase/tests/v371_owner_staff_write_approval_pg17_local_only.sql` first runs V370's complete purchase, requisition, additions, cash revision/correction and preserved Owner writer index suite; then tests missing security gate, missing invitation, invited-but-not-activated Staff, Staff/Viewer/unregistered Owner denial, idempotent Owner grant, emergency kill-switch, Owner revoke/regrant, lost old phone rejection, new phone transfer and private ACL restrictions. Runs in a throwaway GitHub Actions PostgreSQL 17 instance, never against production.

## Important remaining blockers

- NO live Supabase migration, Staff Auth user, enrolled Staff device, Cloud Sync rollout or deployable APK.
- Real PostgREST/JWT impersonation and exposed schema/view/RLS audit remain required; mock local PostgreSQL tests are **not** proof of production authentication security.
- Device UUID alone is NOT hardware-backed attestation. Add session/device binding and secure enrollment, replay protection, and credential rotation before real staff access.
- Owner UI must display explicit irreversible pairing/revoke confirmations. A write-lease revoke does **not** revoke read-only Staff access. For a lost/stolen phone also call v368 `marketpro_owner_disable_team_phone` and revoke sessions.
- Keep strong Owner phone backup, Room pending queue and invoice photos. Replace cannot recover unsynced old-device local records.
- Other Expenses remains without Cloud model. Complete its sync and physical two-phone end-to-end QA before claiming business functionality is live.
- Preserve existing dashboard URL/UI/login and all historical records without reimport or cleanup.
