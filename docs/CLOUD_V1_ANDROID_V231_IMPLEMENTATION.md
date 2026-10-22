# Peliyagoda Market Pro — V2.31 Cloud Sync DEV handover (2026-10-22)

**Status:** Cloud upload implementation complete on a DEV feature branch, subject to real device enrollment and end-to-end upload tests. **Not merged to main.** NOT production-safe until all gates pass.

## Important package & Android signing compatibility
- V2.28 production app: `lk.peliyagoda.marketpro.v228`, unchanged.
- V2.30 Cloud Owner Auth DEV app: `lk.peliyagoda.marketpro.v230.clouddev`, unchanged.
- V2.31 test app: **`lk.peliyagoda.marketpro.v231.clouddev`**, separate installation. The earlier V2.29 and V2.30 APKs were proven to have DIFFERENT Android debug signing certificates, so an in-place update could fail. NEVER uninstall V2.28 or V2.30 in an attempt to force an update.
- Github CI auto-generates debug signing certs: further DEV builds are NOT guaranteed update compatible. A stable securely managed signing keystore is REQUIRED before distributing a single upgradable Android package.
- V2.31 has its OWN Room data, device UUID and encrypted Auth session. Sign in again. **The registered V2.30 phone UUID will not match V2.31's generated UUID**. Copy the NEW V2.31 Device ID and have an admin disable old writer and enroll new writer in one safe transaction; otherwise upload enable is blocked (correctly).
- No Android phone/physical 4GB benchmark has been performed yet.

## Cloud V1 scope — no photos
- Cloud: categories/items ID references, Kitchen Requisitions, initial Purchases, additional purchase lots, Daily Cash Received. Total Expenses and Cash Balance are derived in a security-invoker PostgreSQL daily view.
- **Invoice JPGs, photo bytes, Android photo paths and Supabase Storage are STRICTLY EXCLUDED**. Cloud REST posts only explicit JSON payloads; server RPC rejects unknown keys, including image metadata.
- Storage buckets/objects were verified as zero before V2.31 development.

## How actual upload is gated and executed
1. Install V2.31 DEV *separately* and Sign In with the confirmed owner email, on the enrolled owner account.
2. Open `⋮ → Cloud Sync & Backup (DEV)` and copy the newly generated V2.31 **Device ID**.
3. Admin must disable V2.30 registered device and enroll V2.31 device as the **one** enabled writer. Server-side RPC checks verified Supabase Auth, owner workspace role, matching registered device UUID and allowlisted JSON for EVERY request.
4. Optional historic V2.28 data: first export `Backup all records (JSON)` from V2.28, keep original JPGs on V2.28, then use `Restore backup (JSON)` on V2.31 BEFORE enabling. Check counts/daily totals. Exported JSON intentionally omits original invoice images; restore never copies originals. If syncing fresh empty records, no backup is required.
5. User explicitly checks import/fresh-data acknowledgement and taps `Enable Verified DEV Cloud Sync`. This verifies live Auth, enrolled phone, and that all six business cloud tables are EMPTY, preventing accidental overwrite. It creates a complete local backfill and sets enrolled flag in ONE Room transaction.
6. Worker runs with WorkManager NetworkType.CONNECTED, immediate schedule after local changes, recurring 15-minute fallback, retry on transient network, strict unique work and batch cap 100. It uses encrypted Auth tokens for one job, serializes dependencies `category → item → financial rows`, computes stable per-record UUIDs and pushes to `marketpro_apply_change`.
7. Server idempotently commits a change. Only a matching acknowledged operation UUID can remove local pending row. Other rows and local master data NEVER get deleted by a cloud response. Deletes are represented as safe server-side tombstones.
8. UI shows local pending count, last successful upload and error message. If no auth/device or failed 4xx the queue stays pending.

## Explicit testing before real use
- GitHub `testDebugUnitTest` + `assembleDebug` branch CI.
- Existing Supabase migration pgTAP: RLS cross-workspace, RPC idempotency, photo rejection, per-lot cost HALF_UP, cash balances.
- DEV device enrollment after receiving newly generated V2.31 UUID.
- Dry run with disposable sample purchases/requisitions/extra lots/cash **from the app** and compare Supabase records with local data.
- Test restore snapshot → first import, no duplicate replay, transient 5xx, 401 refresh, per-row error retention, Airplane Mode, app restart/reboot, rapid edit/delete.
- Test on actual 4GB Android hardware for scroll performance and bounded sync memory.
- Do NOT merge this DEV branch or enable production data sync until verified and user-approved.

## Current DEV IDs
Supabase project: `lkoptqhodgjeuwwfihfr`.
Workspace: `e46b90c5-ed6a-4952-82e9-8582fac06d89`.
Owner UUID: `bf49c22f-f245-4d3c-987e-c5ec081d7cad`.
Old V2.30 registered Device ID (to be disabled upon explicit cutover): `73532d0b-5db2-4cb2-a895-3f4d39501c7a`.

These IDs are identifiers, not security credentials. No passwords or API secret keys are included.
