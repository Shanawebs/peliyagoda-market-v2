# Market Pro V3.5.4 — Staff financial RPC + owner/staff paged read (review only)

**Current state: SOURCE AND MOCK-DB TESTS ONLY. DO NOT RUN THESE SQL FILES ON LIVE SUPABASE.**

Owner's final rule: Purchasing Team may create/correct today's records any number of times without approval. Team has READ-ONLY access to old days. Owner can edit any past or current day; all restrictions determined by server Sri Lanka date at midnight (Asia/Colombo). Both phones must eventually download and show changes while retaining the existing Cloud Dashboard UI/URL/login untouched.

## Draft new financial write RPC

`supabase/proposals/v354_team_financial_rpc_REVIEW_ONLY.sql`

- Supports **requisition**, **purchase_addition**, and **daily_cash_received** create/correct. Earlier v353 separate RPC covers purchase.
- Requires real auth JWT, registered enabled writer phone, workspace member role Owner/Staff, server-controlled business date and server-side locked existing row.
- Exact allowed JSON fields: Requisitions `business_date/kitchen/item_id/quantity_grams`; Extra lots `business_date/item_id/quantity_grams/price_cents_per_kg`; Daily cash `business_date/amount_cents`. Photos and arbitrary fields disallowed.
- Keep stable Cloud UUID, kitchen/item/date identity; corrections only update editable quantity/price/amount.
- `expected_revision` compare-and-swap and workspace transaction lock prevent overwriting concurrent Owner changes with stale Staff offline edits.
- Atomic `sync_operations` receipt + **private** before/after audit + row update, identical retry ACK via operation UUID. Caller cannot forge actor/device.
- All old-day staff writes and future-date owner/staff writes denied.

## Draft Cloud download endpoint

`supabase/proposals/v354_team_paged_pull_REVIEW_ONLY.sql`

- Proposed `marketpro_team_pull_page` reads catalog, requisitions, purchases, extra lots and daily cash for approved, authenticated & enrolled devices, including viewer read-only role. Each authenticated user only receives the specified **same workspace** records.
- Cursor `updated_at,id` ascending with max 100 rows/page and `has_more`. Includes deleted_at/tombstone rows so both phones can remove/mark deleted records correctly.
- Cloud `server_today_colombo` and `server_time_utc` included in response. Client can use date as a **UI hint**, but only write RPC server check grants staff edit permission.
- RLS read permissions and authorization are implemented in the RPC body because it would run as SECURITY DEFINER. Do NOT expose any direct private audit data, sync operations, or credential fields.
- The downloader is **NOT** integrated into Android Room. Real implementation MUST verify catalog UUID mapping, outbox cleanliness and revision before each Room merge; tombstones are not auto-deleted if offline edits remain. Realtime notifications can only prompt new paged HTTP pull.

## Staging tests

- Local disposable PG17 `v354_team_financial_rpc_local_only.sql`: 10 same-day corrections, cash and lot corrections, Owner old-day edit, staff old/future-day reject, revision stale conflicts, payload injection reject, all accepted ops audited exactly once.
- Local disposable PG17 `v354_team_paged_pull_local_only.sql`: paged workspace-scoped reads, no duplicate/missing cursor records, tombstones, empty final page, enabled Viewer read, disabled phone, other-workspace, anonymous auth, disallowed table and malformed cursor rejected.
- GitHub workflow `marketpro-v354-financial-rpc-local-ci.yml` runs **both independent mock databases** and Kotlin security tests.
- No changes under `web/marketpro-dashboard/`; no user accounts/devices created; original V3.3.7 writer remains the only live writer.

## Important remaining blockers

1. **Separate staff account/device enrollment**, managed by Owner on a real designated purchasing phone. Never share Owner credentials.
2. Stable signing/install update path for existing v3.3.7 package, verified local Room/Cloud UUID integrity, separate backups for LOCAL ONLY invoice images.
3. Android HTTPS caller for new team RPC, version ACK/outbox retry and authenticated paged downloader connected to transactional Room merge.
4. Android staff date-based read-only controls, owner old-day editable UI, conflict messages, staff resync after owner correction. Offline data queued before midnight but uploaded after midnight must remain recoverable for Owner instead of silently dropped.
5. Include new Other Expenses entity in **separate** Supabase schema/RPC/finance-aggregation work; not part of these existing six Cloud tables.
6. Security audit, fresh snapshot+rollback, multi-device offline/restart/timezone/concurrency tests before live cutover. Original web Dashboard **visual UI** must never be changed.

**No APK is produced by v3.5.4. Source-only development until full Android integration.**
