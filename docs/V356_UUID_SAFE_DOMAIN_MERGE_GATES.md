# Market Pro V3.5.6 — Strict UUID, Revision and Outbox Safe Domain Merge

## State and safety boundary

**Source-only, isolated branch; not live, not a production APK.** No Supabase SQL executed, no new staff accounts, no device writer enrollment, no changes to the old dashboard's URL/login/UI. The sole real Cloud writer remains the installed V3.3.7 app.

## Completed

1. Fixed V3.5.5 Cloud Inbox validator to recognize the actual live Postgres `categories.name_key` and `items.name_key` columns. These are real server fields and previously caused otherwise valid catalog pages to be refused.
2. `DomainMergePolicy.kt` evaluates snapshot safety BEFORE local visible records may be changed. Conditions:
   - exact expected workspace and canonical UUID;
   - exact **Cloud item UUID→existing local Room item ID** mapping (matching the item name is NOT sufficient);
   - pending local outbox blocks remote update (including unsent entries with unmapped UUIDs);
   - existing natural business-key row must already map to the same incoming canonical Cloud UUID; unrelated/historical local business-key collisions are blocked;
   - local existing row with no prior download baseline can only be acknowledged if EVERY editable field already matches the Cloud row (never auto-overwrite a restored owner's entry);
   - last-applied revision and local-content SHA-256 must match before advancing a higher Cloud revision, protecting against unjournaled edits or backup restores;
   - duplicate/same revision with different values is a conflict;
   - deleted/tombstoned Cloud rows are **not** locally deleted; owner reconciliation is required.
3. `CloudDomainMergeEngine.kt`: Room `withTransaction` for a **verified staged** Purchase, Requisition or Daily Cash row; computes logical key, validates exact linked Item UUID, checks pending outbox and existing UUID/natural-key identity, applies at most one Room row without writing an outbox operation, and saves revision and SHA-256 local-value checkpoint in the **same transaction**. Any blocked condition returns an explicit outcome and does not mutate visible records.
4. Additional Room DAO pending-local-key guard for changes whose Cloud UUID has not yet been bound.
5. Pure Kotlin tests for new financial rows, old unmatched rows, UUID collision, pending offline edits, old revision, unbased restored data, content changes, tombstones and owner correction. Existing v355 server Postgres tests retained.

## Limits and gates BEFORE activation

- This merge engine is intentionally NOT connected to the App lifecycle, WorkManager or the UI. Calling it before verified authenticated **server download** and correct catalog UUID mapping would be unsafe.
- Real STAFF/OWNER login and enrollments, live server revision-aware upload & read API, initial catalog bootstrap, paginated HTTP download, and per-phone identity setup **have not been deployed**.
- Only `purchase`, `requisition`, `daily_cash_received` are accepted for local financial merge. `purchase_addition` requires safe UUID to Room AUTOINCREMENT lot ID mapping and collision handling; catalog and `daily_other_expenses` require separate implementations and server support.
- No automatic deletion of local domain data and no touching local invoice photos. JSON backups do not contain photos; physical photo backup remains required.
- Existing dashboard's **visual design stays 100% unchanged**. Revisions and Other Expenses still need correctly gated server rollouts and financial aggregation tests.
- The Kotlin/JVM unit tests exercise the POLICY, not a full Android instrumented Room migration-and-merge or two physical phones. Before deployment use Room migration tests with a real SQLite runtime + two phone end-to-end tests including offline edits, midnight Asia/Colombo, owner correction/reconnect, stale revisions, signing continuity and 4GB performance.
- Review server cursor stability: existing proposed keyset endpoint uses updated_at+id and is not a global atomic moving snapshot. Production catch-up needs overlap/refresh handling, not just a one-time scan.

**Do not uninstall or clear data on live V3.3.7; do not create extra live Cloud writers until reviewed and verified.**
