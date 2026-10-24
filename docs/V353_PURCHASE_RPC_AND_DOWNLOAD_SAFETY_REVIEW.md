# Market Pro V3.5.3 — Non-deployed purchase-write RPC and download safety contract

## Goal

Purchasing staff enter and edit **TODAY's** expenses any number of times, with no Owner approval; Staff can view but never edit yesterday/earlier dates. Owner phone can correct current/past entries; updates flow back to staff phone. Existing V3.3.7 cloud remains operational; existing dashboard UI remains visually identical.

## Delivered: POC code only, NOT LIVE

### Server team purchase RPC (purchase entity only)

File: `supabase/proposals/v353_team_purchase_rpc_REVIEW_ONLY.sql`.

- Deploy only into a separate audited staging database when ready. Depends on private authorization helper in `v352_team_write_gate_REVIEW_ONLY.sql`.
- Creates **new** `public.marketpro_apply_team_purchase_change` without changing old `marketpro_apply_change`.
- Live Supabase has not been altered, so v3.3.7 single-owner sync is preserved.
- Requires correct JWT user, active enrolled device, authenticated membership, role owner/purchasing_staff. Rejects anonymous/viewer and changes on future days.
- Staff may create/correct today's entries, unlimited times with expected revision from latest server response. Owner may edit past dates.
- Server checks Sri Lanka business day, and existing immutable purchase business key/date using locked row in one transaction.
- Serialized transaction, unique idempotent operation UUID, identical-payload replay ACK, stable record UUID, expected-revision compare-and-swap, no silent overwrite of Owner correction, private edit audit and existing sync operation ledger all committed together.
- Only permits date, item, grams and price in whitelisted JSON; no invoice photos/path, arbitrary properties or mutable identity.
- No ability to delete purchases, change historical date/item identity or manipulate catalog using this RPC.
- `REVOKE EXECUTE` from anon/public; only authenticated role can call; server still independently checks membership and enrolled device.

### Safe Cloud download/merge guard (policy only, not network downloader)

Files: `TeamCloudDownloadPolicy.kt` and `TeamCloudDownloadPolicyTest.kt`.

- Reject invalid/cross-workspace UUIDs, unverified catalog mappings, backwards revisions and server row changes when phone still has an unacknowledged local outbox operation.
- Apply higher server revision only when no pending local edits; unchanged rev = skip. Remote tombstones follow same safety checks.
- Intended for future transactional Room server snapshot downloader on BOTH phones. There is **no deployed downloader, no API traffic, and no new APK** in this PR. Realtime changes alone are not the source of truth.

### Tests

- `supabase/tests/v353_team_purchase_rpc_local_only.sql` executes new SQL and v352 helper against an ephemeral PostgreSQL 17 database.
- Covers repeated same-day staff edits, idempotent retry after lost ACK, reused operation UUID tampering, Owner correction, staff stale edit rejected, Owner correction of past day, old-day staff blocked, future day blocked, semantic duplicate key, unauthorized invoice path field, private audit/ledger counts, deny anon execute.
- CI checks existing dashboard source freeze and runs Kotlin/Room tests.
- CI success on code branch is NOT proof of production phone-to-phone sync.

## CRITICAL remaining production blockers

1. Separate Purchasing Staff login; never reuse owner credentials. Dedicated physical Purchasing phone must be enrolled by Owner with correct Device ID.
2. Independently confirm backup/download preservation of existing Cloud data and invoice photos, debug signing migration and current v3.3.7 device identity.
3. Extend team RPC with matching concurrency checks for **requisitions, additional purchases, daily cash, Other Expenses**, and remove any unsafe old-write bypass after upgrade.
4. Build genuine owner/staff full cloud pull: paginated RLS protected snapshot + cursor incremental catchup, Room transactional merge, revision checkpoint, conflict UI, WorkManager retry and Realtime notification as signal only.
5. Do offline/network/restart, time-boundary (midnight Asia/Colombo), concurrent owner/staff edits, 4 GB RAM performance and independent APK signing/install tests.
6. Only after all gates: production SQL migration and coordinated server/client cutover, with rollback and old dashboard visually unchanged. DO NOT change live writer or add staff device under the old V1 RPC prematurely.
