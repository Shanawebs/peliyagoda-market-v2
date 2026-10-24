# Market Pro V3.5.7 — Dormant authenticated team Cloud HTTP download connector

## User's confirmed target

A dedicated Purchasing Team phone enters market purchases, requisitions, extra lots, cash and expenses. It may correct today's entries unlimited times; any prior day is read-only to staff. Owner phone downloads changes and may correct any day. Owner fixes sync back to staff. Existing Dashboard URL, login and visual UI must remain exactly unchanged.

## Implemented as branch-only source

- `TeamPullRequestPolicy.kt`: pure Kotlin strict request/auth-envelope validator; session JWT must be present, workspace ID/User ID/Device ID must be UUIDs, request workspace and Device ID must match verified session and the actual physical phone. Exact allowlist for category, item, purchase, requisition, purchase addition, cash; pagination 1–50 with paired timestamp/UUID cursor. No photos, arbitrary SQL table names or audit ledgers.
- `TeamPullHttpTransport.kt`: read-only authenticated HTTPS POST (only trusted exact Supabase host) to the *proposed* `marketpro_team_pull_page` RPC. Small JSON requests, transient Auth JWT used in Authorization header, **no credential logging, no URL query tokens**, TLS, redirects disabled, 12s connect/20s read timeout, max 256 KiB response (hard fail on oversize). HTTP 401/403, RPC-unavailable 404 and network errors stop safely; no retry loop from this class.
- `TeamInboxDownloadCoordinator.kt`: at most 20 pages per explicit call, max 50 records/page, serializes local pull within one instance using mutex, verifies physical `CloudDeviceIdentity`, fetches persisted checkpoint, validates page+cursor in previous `CloudInboxStager`, commits Cloud shadow rows and cursor together per page, counts pending local edits. It **never** directly mutates Market domain tables or creates outbox writes.
- `MARKETPRO_TEAM_PULL_RPC_DEPLOYED=false` is a second compile-time hard gate IN ADDITION TO `MARKETPRO_LOCAL_PREVIEW=true` and the preview manifest removes INTERNET permission. The coordinator is NOT referenced from any Activity, ViewModel, Worker or UI. This version therefore **CANNOT contact Cloud**.
- Kotlin tests verify feature readiness false for any missing prerequisite, exact device/workspace constraints, malformed cursors, missing JWT, invalid page-size, and allowed entity types. Existing mock PostgreSQL 17 regression tests validate team RPC proposals without touching real Supabase.
- Existing Room v7 shadow tables, merge safeguards and original dashboard remain unchanged.

## Important: source-only, not a working team app

- No staff account or registered staff device has been created in live Supabase.
- The full owner/staff team RPCs and paged download RPC are STILL **REVIEW_ONLY SQL proposals**, not deployed.
- No read/write sync or staff permissions on a second phone, no installed APK, no live HTTP calls in this branch.
- Owner authentication currently uses a single-owner `CloudOwnerAuth` with Android Keystore encryption. Before launching staff login, implement a separately validated staff account/session provider and server-confirmed workspace membership/device registration; never share owner's password.
- Catalog UUID→local ID bootstrap is still necessary before `CloudDomainMergeEngine` can safely apply financial rows. An existing local item with matching *name* does not prove its UUID matches.
- Keyset cursor paging must be audited for concurrent moving snapshots, including overlap/catch-up and deletes; Realtime events should only prompt server refresh.
- Invoice photos stay local; no Cloud photo synchronization. Other Expenses still has no Cloud schema.
- Single live V3.3.7 authorized Cloud writer and all records must remain untouched until backup + staged two-device test.
- The old `web/marketpro-dashboard/` directory and Vercel deploy/URL/login/UI must not be edited.

## Next necessary actionable work

1. Owner-approved server team RPC and staff identity enrollment path (first **separate test backend**).
2. Staff/Owner secure session provider injected into this transport; verify actor and physical device against live server on every call.
3. Safe catalog UUID seed/mapping with user-reviewed mismatches, then read-only Cloud staged download and device-version continuity.
4. Actual end-to-end sync, conflict display and source-controlled Android UI permissions for today vs previous days, 4GB tests.
5. Shipping signing and independent backup/rollback before new Cloud APK is activated.
