# Market Pro V3.6.1 — Cloud Catalog UUID Mapping Foundation

**STATUS: Source-only Development Branch. Not connected to the running App, no real staff phone enrollment, no production Supabase mutations or APK.**

## Why this is needed

The live Server currently contains **3 categories and 184 catalog items** (verified using READ-ONLY queries on 2026-10-24). They use canonical UUID IDs. Room's existing local categories use the category *name* as primary key; local items use auto-increment integer IDs. Comparing item names alone cannot establish that two records are the same item, especially after a rename or an offline restore.

The previous safe financial Merge engine requires correct **Cloud item UUID → local item ID** before importing purchases or requisitions. Blind name-based links could silently attach a financial record to the wrong item.

## Implemented (offline-safe, dormant)

`CloudCatalogBootstrap.kt` is an **unreferenced** Kotlin Room component that accepts only catalog rows already stored in a validated Cloud Inbox with matching workspace, Cloud UUID and revision.

- `importCategory` inserts a **new** category only when there is NO existing normalized-name collision and NO pending local edit. The insertion and canonical UUID mapping occur in a **single Room transaction**. If the category name already exists in Room without its exact Cloud identity, it returns `REVIEW_NAME_COLLISION` instead of assuming identity.
- `importItem` imports a **new** item only after the Cloud category UUID is already mapped in both directions, the category shadow is not deleted, the existing local category name matches exactly, and no local item has the same normalized name. New Room auto-generated integer ID is bound to the canonical Cloud item UUID in the same transaction.
- A previously mapped category/item is accepted only if UUID↔local-key mappings agree in both directions and the local category/name/active state matches Cloud. Cloud catalog modifications to a mapped item require explicit reconciliation.
- Cloud deletion/tombstone is **never automatically applied** to local catalog or historical transactions. Duplicate name/cross-workspace/pending outbox/revision conflicts fail closed.
- Per-record catalog revision checkpoints are saved transactionally. Invalid or backwards revision checkpoints **throw and roll back** the whole import, including newly inserted rows or UUID identities.
- No invoice photo operations, no finance row rewrite, no outgoing upload journal echo, no active WorkManager/UI invocation, no internet request.

`CatalogLinkPolicy.kt` is independently testable; `CatalogLinkPolicyTest.kt` checks fresh import, preseeded-name collision, absent category UUID, bidirectional identity mismatch, changed/deleted remote catalog, local unsent edits and workspace/revision safety.

## What is still missing

1. **Existing/preseeded staff catalog reconciliation**: the typical Staff App starts with seeded items. Their matching names are NOT automatically considered proof of identity. A future Owner-reviewed mapping workflow must verify each local ID against the intended canonical Cloud UUID, preserve every historical row, and bind in one audited transaction.
2. **End-to-end network**: current `marketpro_team_pull_page` and staff/owner team-write RPC remain review-only code, NOT deployed. Staff Auth/Login is compiled OFF, preview manifest lacks INTERNET permission, and the new catalog bootstrap is **not wired** to any downloader or local UI.
3. Robust Room migration/instrumentation and dual-device full sync tests, handling 184 Cloud items, batch progress on 4GB RAM phone and offline business-day rollover.
4. Other Expenses schema, extra purchase lot UUID mapping, owner-side corrections download, server-side revision conflict display, backup and staged deployment.
5. Existing working V3.3.7 Cloud App and Dashboard UI/URL/login must remain exactly unchanged; existing cloud rows and local invoice photos must remain intact.

## Verification

GitHub CI: `.github/workflows/marketpro-v361-catalog-bootstrap-ci.yml`. Runs Android Kotlin unit tests / compilation, new catalog policy regression tests, PostgreSQL 17 isolated previous staff authorization tests, and verifies no changes under `web/marketpro-dashboard/`.

**No direct APK for this source milestone.** Keep using the working Cloud-connected V3.3.7 until tested upgraded owner and purchasing client releases are ready.
