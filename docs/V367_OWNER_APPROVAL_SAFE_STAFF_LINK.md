# Market Pro V3.6.7 — Server-Approved Catalog UUID → Staff Room Safe Apply

**STATUS: DEVELOPMENT / DRAFT ONLY. NO live Supabase migration, no second active writer and NO APK release.**

## User goal

Owner reviews a Staff Phone's *real* Room Item / Category mapping candidate and accepts exactly that Cloud canonical UUID. The Staff Phone must then independently fetch Owner's approved decision under its own authenticated Staff session, confirm that the submitted source record has not changed, and only then create an exact UUID mapping. Preserves all existing purchases, requisitions, cash, other expenses, additional purchase lots, local invoice photos and the unchanged Dashboard.

## Implemented

### Server proposal — never deployed to production

`supabase/proposals/v367_staff_approved_catalog_fetch_REVIEW_ONLY.sql` replaces the earlier v362 device approval lookup with a **fail-closed, exact-candidate fetch**:

- The Auth JWT subject must still be the *enrolled, enabled* Purchasing Staff user's physical Device UUID and workspace. Simply knowing the device ID is insufficient; checks are performed independently by the server (although client UUID is not strong device attestation).
- Finds unexpired Owner approval in the private table and verifies it came from the Staff's own **status=approved** submission with matching device, user, type, Cloud UUID/revision, local key and SHA-256 fingerprint. This prevents an arbitrary old v362 direct Owner approval from bypassing candidate review.
- Rejects disabled/revoked Staff phone or removed/revoked Owner writing device/Owner workspace role, and rejects changed/deleted Cloud category/item, a revised item or changed parent-category UUID.
- Returns only necessary proof details, including exact reviewed owner user ID, approval identifier, target Staff phone, Cloud UUID/revision, local fingerprint, PostgreSQL server timestamp and **requires_current_room_recheck=true**. No table contents are directly exposed to `authenticated`/ `anon`.
- **No new writer** is granted; this is approval retrieval only.

`supabase/tests/v367_staff_approved_mapping_local_only.sql` uses disposable PostgreSQL 17 and the previous reviewed-candidate test fixture, including staff submission → pending denied → explicit Owner approval → same Staff-device fetch allowed, bad JWT/Viewer/second phone denied, stale Cloud revision/category rejected, arbitrary approval rejected, expired approval rejected, Owner/Staff revocation denied and only one old owner writer retained.

### Android

- `StaffCatalogApprovalPolicy.kt`: pure JVM-testable rules require a valid approval UUID, target workspace/physical Device UUID, exact requested Cloud UUID/type, canonical local Item ID, correct Owner user ID, SHA-256 fingerprint, 24-hour server approval window, unexpired **server-reported time**, explicit server recheck status.
- `HttpsStaffCatalogApprovalTransport.kt`: dormant HTTPS-only fixed Supabase project origin, **only** `marketpro_device_catalog_link_approval`, bearer from separately Keystore-protected Staff Auth session, strict body/response sizes, no redirects, generic errors with no sensitive response-body logging, and **four off-by-default rollout flags**.
- `StaffCatalogApprovedLinkCoordinator.kt`: obtains verified Staff JWT for the actual device, fetches server-approved proof and parses it strictly, checks official Owner user ID/device/workspace and uses server timestamp rather than device time. Calls the existing **transactional** `OwnerCatalogLinker` only after all identity and proof checks.
- `OwnerCatalogLinker.kt` now requires the FOURTH server-approved RPC gate too. It re-reads the *current* Room Item/Category/Cloud Inbox revision and current local SHA-256 fields inside `db.withTransaction`, rejects any Cloud category tombstone, wrong mapped category, unsent outbox/identity collision or changed row, and writes only `cloud_entity_ids` plus a per-approval sync checkpoint on success. It does **not** rewrite existing actual catalog names, purchases, historical financial records, or invoice images.
- `StaffCatalogCandidatePanel.kt`: future read-only UI presents a **Fetch Owner Approval & Verify Link** action on an explicitly selected Staff catalog candidate. Current offline preview never renders active controls.
- `StaffCatalogApprovalPolicyTest.kt`: JVM tests for correct proof, revoked/wrong Owner, another device/workspace, mismatched category/Cloud UUID, changed revision, replay window/expiry, invalid SHA and fake local proof. CI also compiles Room and Compose source and runs earlier security regression.
- GitHub Actions: `.github/workflows/marketpro-v367-approved-staff-mapping-ci.yml` runs Kotlin tests and independent local PostgreSQL security tests plus original Dashboard no-change guard.

## Production gates still unmet

1. The reviewed v352–v367 Supabase SQL migrations have **NOT** been applied live, and must receive an independent complete role/RLS/definer/function privilege and concurrent revision safety audit before **any** live activation.
2. Existing V3.3.7 sole Owner writer, all Cloud historical data, original Dashboard URL/login/visual UI and all local photos must remain unchanged. Never uninstall / clear App storage.
3. The target Staff Phone must log into a separate verified account and complete secure invitation/enrollment, with a backend that checks registered Device Auth. Raw UUID alone is not cryptographic proof of the physical phone.
4. Staff Room Cloud Inbox and category UUID mappings must be bootstrapped safely first, and Owner candidates must be reviewed on the actual source staff phone.
5. True two-way **Purchase, Requisition, Purchase-Addition, Cash and Other Expenses** sync, conflict reconciliation, offline-midnight logic, WorkManager retry, outbox pending recovery and real-device QA are still missing/unactivated. Approval of an Item UUID is NOT a financial row sync.
6. Physical two-phone tests (including 4GB RAM device), backup/restore including invoice photos, certificate/signature upgrade continuity, rate limits and server error recovery are required before any Cloud/App production cutover.
7. Android unit tests/compilation plus mocked PostgreSQL tests are **not equivalent** to connected real-device tests or independent code security review.

**NO V3.6.7 APK**: this is a source milestone, not a fully connected working two-phone installation.
