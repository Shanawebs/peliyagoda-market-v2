# Market Pro Cloud — V3.35 Source-Only Cutover Readiness

Date: 2026-10-23
Parent source: `prototype/marketpro-expressive-v334-local-uuid-stage`

## Live data (read-only inspection)

- Existing V2.31 writer remains enrolled and enabled on Supabase. A separate older V2.30 registration is disabled.
- Current protected Cloud row snapshot: 3 categories, 184 items, 105 requisitions, 26 purchases, 10 additional lots, 2 cash records (330 records).
- V3.34 physical phone video shows 330/330 UUID matches, different UUID = 0 and restored outbox = 0 after local stage.
- 13 archived catalog items are part of the 184 and must not be deleted.
- Neither the Cloud server nor original V2.31 phone's **local unsent queue** is guaranteed frozen by a GET-only preflight.

## What this source-only phase adds

- `LocalCutoverReadinessPolicy` pure Kotlin evaluator for staged flag, complete record-count parity, catalog/financial fingerprints, exact 330-identity parity, ambiguous ID rejection, zero pending local ops and unexpected local writer registration.
- Read-only UI section that separates **LOCAL DATA READY** from **SERVER WRITER HANDOVER APPROVED**. Even a perfect local precheck NEVER grants Cloud upload.
- JVM unit tests for fail-closed cases.
- A test-only CI workflow with no APK, no production database changes and no deployable artifact.

## Mandatory handover gates BEFORE auto-sync implementation

1. Preserve the old V2.31 installation and every local backup. Do not uninstall, clear data or overwrite V3.34: its AndroidKeyStore snapshot and Room data belong to that installed package.
2. Open the ORIGINAL V2.31 phone and verify its outbox shows **0 pending**, its current data matches Cloud and its last successful sync is recent. A remote SQL count cannot prove old phone's queue is empty.
3. Freeze all business edits during the transition. Obtain a verified Cloud export/backup (including `registered_devices`, `sync_operations`, all business rows, and revision/tombstone fields).
4. Re-run the V3.34 read-only audit against this frozen source. Require exact content, count and UUID parity and no pending local edits.
5. Prepare a dedicated transactional, server-authorized cutover that disables the old registered writer and enables ONLY the new registered device as a writer, with expected old-device ID and new-device ID, owner authorization and concurrency checks. Never use `service_role` inside Android and never relax RLS.
6. Only AFTER the cutover commits successfully, start a new Android sync implementation that is **not** restricted to `CloudSyncEngine.enableAfterOwnerConfirmation()`'s **empty-workspace import** behavior. Persist state atomically with the local queue, and check server ownership before every push.
7. Exercise offline edits, network reconnection, mid-batch failures, duplicate op replay, revision conflicts, simultaneous WorkManager workers, JWT refresh, account logout and 4GB-phone performance. Confirm fresh transactions appear on the dashboard with identical UUID and value.
8. Establish verifiably compatible APK signing or a controlled new-package migration before installing a replacement. CI debug signing keys from an arbitrary new run may not match the installed V3.34 app.

## Safety status

- **V3.35 is SOURCE ONLY**. It is NOT a new installable APK and NOT a verified live auto-sync release.
- It **does not** enroll devices, disable or enable any writer, upload photos, change Supabase data, enable WorkManager or delete/replace records.
- Old V2.31 writer stays operational pending an approved controlled handover.
