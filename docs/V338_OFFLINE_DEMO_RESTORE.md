# V3.3.8 Offline Demo Archive — PIN-gated old backup restore

The owner requested permission to restore an old demo JSON backup, but the currently active **Market Pro v3.3.7 Fresh Cloud Sync** installation blocks restore to prevent corrupting Room→Supabase UUID identity consistency. This guard is correct and MUST remain unchanged.

## Safest actionable approach
Build a distinct **Market Pro Demo v3.3.8** Android application under `lk.peliyagoda.marketpro.offlinedemo.v338`, side-by-side with the Cloud writer app. Do not grant its device any Supabase write permissions.

- Fresh empty local Room database of its OWN app-private sandbox.
- PIN-gated original Restore Backup (JSON) flow is fully available since the offline sandbox is NEVER enrolled. Local restore still validates every row, checks unique IDs/item references and replaces all local tables transactionally. Old backup files may contain categories, items, requisitions, purchases, additions and cash.
- The manifest explicitly removes INTERNET permission, and Gradle buildConfig `MARKETPRO_DEMO_ARCHIVE_ONLY=true` disables any Cloud engine activity even if APIs are somehow called.
- Hide both Cloud Owner Login and Cloud Connection menu items, and label the app clearly as *Demo*.
- Initial restore may take a moment; if prompted to set a new local 6-digit Restore PIN, do so. It is an app-specific PIN, not the live V3 Cloud application's PIN.
- Restoring **REPLACES ONLY** data in the new standalone demo app. It does not combine records with fresh V3.3.7, does not sync historical demos to Supabase and does not touch cloud dashboard.
- Invoice photos are NOT in the JSON; original local photos remain with the original app where taken.
- Do NOT uninstall/clear the live V3.3.7 app, change registered device ID, reset Supabase, or change web UI.

## CI safety verification

- `gradle :app:testDebugUnitTest :app:assembleDebug`
- Inspect APK package `lk.peliyagoda.marketpro.offlinedemo.v338` and versionName `3.3.8`; launcher label explicitly includes Demo.
- `aapt dump permissions` must show NO `android.permission.INTERNET`. Fail release otherwise.
- Original `web/marketpro-dashboard/` directory must compare unchanged against the v3.3.4 baseline.

## Not part of this patch
Restoring or merging backup rows into the **cloud-connected** V3.3.7 Room store. That would require separate user-approved bidirectional reconciliation, conflict management, catalog mapping, outbox retention, rollback and ledger audits; no bypass of the enrolled-restore guard is permitted.
