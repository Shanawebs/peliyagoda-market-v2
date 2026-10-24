# Market Pro V3.6.9 — Live Server Activation Preflight (2026-10-24)

**Status: DEVELOPMENT SAFETY MILESTONE ONLY. No Supabase migration applied, no APK, no staff credentials/device enrolled.**

## Verified production baseline (READ-ONLY)

Live Supabase project: `marketpro-cloud-dev` (existing workspace only).
The SQL audit `scripts/v369_live_activation_preflight_READ_ONLY.sql` was executed on the existing production-connected project using SELECT statements only.

- 12 safety checks: **2 PASS / 10 BLOCKED**.
- One active Owner writer, zero Staff writers. Three registered Owner devices total, only one enabled. Do not disable or transfer it.
- Existing Owner sync RPC `marketpro_apply_change` still exists.
- Staff role not yet allowed in the live membership CHECK; no Purchasing Staff user/device enrolled.
- All six live SELECT policies still use broad workspace membership, so enrolling Purchasing Staff now would expose records over direct REST. **NEVER activate Staff enrollment until server RLS is hardened and authenticated device-bound pull has been audited.**
- Team invite, activation, read/write and phone transfer RPCs are **source-only**; not deployed.
- Private staff rollout gate does not exist on live.
- Other Expenses does not yet have a Cloud schema; do not promise its cross-phone synchronization.

Live row counts on audit: categories 3, master items 184, purchases 29, requisitions 108, purchase additions 11, cash 3, sync operations 346. **Do not delete, reimport, or mutate these.**

GitHub PR #35/V3.6.8 CI had succeeded for isolated PostgreSQL 17 / Kotlin, but this is NOT physical-phone E2E evidence.

## Fail-closed activation sequence

1. Snapshot and independently verify backup/restore coverage: Cloud PostgreSQL plus Room/JSON pending queue and **local-only invoice photos**. Do not uninstall Owner V3.3.7.
2. On disposable PostgreSQL 17 with representative schema, audit and integrate proposed V3.5.2–3.6.8 SQL. Verify idempotence, minimal EXECUTE grants, ownership checks, RLS for both REST and RPC, expired/replayed invitations, revoked devices, same-day-only Staff edits by **Asia/Colombo server clock**, and preservation of all existing Owners/Viewers/read dashboards. Start with every write/replacement rollout flag false.
3. Review *every* exposed table/view/legacy RPC and enforce Staff direct-REST isolation **before** granting a Staff workspace membership. No individual proposal should be selectively applied. Disable other authenticated access paths to revoked staff data; never trust device UUID alone as hardware identity.
4. Deploy the complete audited server baseline by a versioned, rollback-conscious migration only when preproduction tests pass. Immediately rerun this preflight and Supabase Security Advisors. Deployment of RPC definitions alone is **not** permission to enable Staff writes.
5. Create separate verified Purchasing Staff Auth, one owner-approved read-only device, and a real staff login/session provider. Revoke/replace requires active verified Owner JWT, registered Owner device and explicit UI confirmation. A one-time 30-minute pairing code never grants immediate Staff writer privileges.
6. Integrate Android signed release (not offline preview): INTERNET, authenticated paged Cloud Inbox, UUID-safe catalog merge, initial Staff read-only sync; then audited revision-safe writes, conflict and midnight policy controls.
7. Perform **two real Android phone** tests: Staff today unlimited edits, previous-day readonly; Owner older-day corrections; offline queue, interrupted sync, duplicate/retry handling, catalog UUID approval, dashboard unchanged, emergency revoke, repeat phone replacement, old-token denied, and replacement phone only restores previously synced Cloud records.
8. Independently implement Other Expenses Cloud sync; verify purchase/requisition/additional purchase/cash balance reconciliation and local invoice photo handling.
9. Only after physical QA, owner verification of Pending=0, backups, confirmed sync, and a server-enforced single-writer handoff, enable Staff writing using reviewed v2 RPC gates. Preserve Owner V3.3.7 until explicit controlled cutover.

## Non-negotiable user rules

- Staff corrections to **today** unlimited, without Owner approval.
- Prior business days editable by **Owner only**, not Staff. Use server Asia/Colombo date, not handset clock.
- Existing Cloud historical data, Owner writer and URL/login/UI of `https://marketpro-cloud-dashboard.vercel.app/` unchanged.
- Owner can revoke a phone at any time; a replacement must not re-enable a stolen phone or overwrite historical rows.
- Revocation stops future server operations, **not** the offline copy on an old phone. Unsynced old-phone purchases/invoice photos do not magically reappear on replacement.
- No direct APK promised until actual Android network/app tests and build artifact exist.

## Today’s delivered checkpoint

This version adds **read-only SQL preflight**, disposable PG17 CI regression, and written server go/no-go. It does NOT silently activate cloud permissions or mislabel the dormant Android 3.5.0 preview as a full team release.
