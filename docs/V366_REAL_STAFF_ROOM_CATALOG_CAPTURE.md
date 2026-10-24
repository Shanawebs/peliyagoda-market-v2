# Market Pro V3.6.6 — Real Staff Phone Room Catalog Candidate Capture

**STATUS: DRAFT CODE ONLY.** No real Staff Auth account/phone enrollment, no production Supabase SQL, no running two-way sync, no signed APK.

## Goal

Purchasing Staff should eventually be able to propose a precise association between their phone's pre-existing Room Item ID and a canonical Cloud UUID, submit it to an authenticated Owner review inbox, and **never** attach Purchase/Price/Quantity History to a wrong Item. A name match is a suggestion for human review, not a UUID binding. Today's Staff corrections are unlimited after server activation; past days remain Owner-only. The live V3.3.7 phone, invoice photos, existing Cloud data and existing Dashboard UI/URL must stay untouched.

## Implemented

- `StaffCatalogCandidateReader.kt`: takes an explicitly selected catalog type, **local Room key** and staged Cloud UUID; in one Room **read-only transaction** re-reads the real Staff Phone Item/Category, Cloud Inbox snapshot, canonical Cloud revision, Cloud Category snapshot, current category UUID↔Room category mapping, existing forward/reverse ID mappings and pending local outbox. All fields sent in a future candidate are derived **from actual Room and previously authenticated Cloud Inbox data**, not arbitrary UI strings.
- Fails closed for invalid workspace/UUID, missing/renamed/deactivated row, Cloud tombstone, stale Inbox record (older than 24 hours), wrong category/active flag, unlinked Cloud category UUID, pending unsent local edit, or any preexisting Cloud UUID↔local key identity. Categories can be reviewed before items; the item candidate path requires its category to be **exactly linked already**.
- `StaffCatalogCapturePolicy.kt`: independently testable safety decisions for verified inputs, category-first, local changes, pending outbox, stale/missing Cloud rows and identity collisions. `StaffCatalogCapturePolicyTest.kt` tests all of these, including normalized Room category keys.
- `CatalogReviewNetworkCoordinator.staffSubmitLocalCatalog(...)`: replaces its former caller-supplied candidate-input entry point. Captures real Room data, passes the exact calculated fingerprint over authenticated Staff Bearer transport, validates server pending-only acknowledgment, and re-reads Room once more afterward. A changed source during flight is **not reported as successfully verified**; however server might still retain a pending candidate and Owner/target-device final verification remains mandatory. The upload does not modify Room, pending journal, cloud UUID identities, purchases, photos or cash.
- **Category key consistency bug fixed** across Android Staff/Owner policy and draft v364 SQL. Room's category primary key is a display-cased NAME (`Vegetables`), but Cloud Record logical keys are normalized lowercase (`vegetables`), while Staff's submitted display name preserves casing. New PG17 tests prove lowercase key is accepted and incorrectly cased key refused; this matters for mapping all Cloud items in that category.
- `StaffCatalogCandidatePanel.kt` in Staff Team Setup: when fully activated in a future release, it loads actual bounded Room/Cloud Inbox candidates, lets Staff explicitly select a Cloud UUID↔Room key, and submits **pending-only** to Owner review. It never automatically approves. Current offline preview states **Not Yet Connected** and has no effective submit/network control.

## Code and integration safeguards

- Compiled `MARKETPRO_LOCAL_PREVIEW=true`; `MARKETPRO_TEAM_PULL_RPC_DEPLOYED=false`; `MARKETPRO_STAFF_LOGIN_ENABLED=false`; `MARKETPRO_CATALOG_REVIEW_RPC_DEPLOYED=false`. Preview Manifest strips INTERNET permission.
- This is source-only, branch not merged into working V3.3.7; no Worker or background sync activation. Existing Owner Cloud Dashboard source directory must remain unchanged.
- v364 pending-only candidate RPC is a REVIEW-ONLY SQL proposal; postgres tests run exclusively in disposable PostgreSQL 17 Docker, not in live Supabase.
- Android JVM unit tests validate pure *capture decision policies*, while GitHub CI compiles actual Room transaction reader and Compose UI. **Full Android instrumented Room tests and physical two-phone tests have NOT run.**

## Blockers before release

1. Staging test backend: full audited v352–v364 team RLS/device enrollment/revision RPC rollout (in correct sequence), no direct DB access bypass, verified Owner and separately authenticated Staff.
2. Staff candidate selection, network submission and Owner fetch/approval tested on **two enrolled physical devices** including code expiry, approval replay, local edits during upload, offline queue, stable Cloud UUIDs and revocations.
3. Target Staff Phone must securely fetch Owner's signed/authenticated approval and re-evaluate the *current* Room contents/fingerprint and Cloud revision transactionally before applying any local item UUID binding. Owner approval alone is NOT the final link.
4. Owner/Staff Purchase and Requisition and Cash two-way sync, extra purchase lot identity, Other Expenses Cloud schema, conflicts and offline resynchronization need full activation & QA.
5. Backup local Room database + invoice image files on live phone, preserve signing key and app data through upgrade, and verify rollback including 4GB RAM phone performance and midnight Sri Lanka time.

**Do NOT uninstall, clear storage or overwrite the production V3.3.7 APK.** No v3.6.6 APK was released.
