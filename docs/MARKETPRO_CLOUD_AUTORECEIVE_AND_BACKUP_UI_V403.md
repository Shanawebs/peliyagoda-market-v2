# Market Pro Ecosystem — Cloud auto-receive foundation & Restore-only PIN UI

**2026-10-27 • isolated QA APK v4.0.3.** Production Market Pro unchanged. Same APK source for Owner and Team.

## Completed and tested

- GitHub Actions run `40470040054` SUCCESS: real PostgreSQL 17 Owner/Team device/pull tests, complete Kotlin tests, signed Android staging APK build.
- QA-only APK: https://github.com/Shanawebs/peliyagoda-market-v2/releases/download/marketpro-unified-cloud-phone-pull-v4.0.3/MarketPro-Unified-CloudPhonePull-Staging-v4.0.3-debug.apk
- Added `CloudReceiveAutoSyncScheduler` and `CloudReceiveWorker` shared by Owner and Team login:
  - on app resume and after login, queue an immediate safe read;
  - background WorkManager read every 15 minutes while device/network constraints permit;
  - only process seven server-authenticated Cloud record types via existing paged transport;
  - write only verified Room Inbox SHADOW records and cursors; retain unsynced local outbox, never overwrite Market/Dashboard records.
- **No live push/realtime**. Android background 15-minute WorkManager is a minimum and can be delayed by OS battery restrictions. Instantaneous cross-phone updates require server financial writes, safe visible merge and foreground Realtime signals, none activated yet.
- Reorganized menu to `⋮ → Security & Backup`: `Backup All Records (JSON)` and `Restore Backup (JSON) — PIN Required`. The independent 6-digit **Verify Local PIN** button was removed from `Security & Team Sync`. Existing Restore flow still requires PIN, file picker, explicit replacement confirmation. No PIN for Owner/Team Cloud linking.
- `Security & Team Sync` now shows `Account & Phone — Cloud Sync`; old Owner/Team UI remains the same shared Android APK.
- Original offline Preview flag `MARKETPRO_CLOUD_RECEIVE_AUTO_ENABLED=false`; Staging flag true; `MARKETPRO_STAFF_OUTBOX_AUTO_UPLOAD=false` and `MARKETPRO_TEAM_DOMAIN_MERGE_DEPLOYED=false`.

## Important signing/device continuity risk

Prior Staging v4.0.2 and new Staging v4.0.3 are **debug APKs generated in separate GitHub Actions runner jobs**. The debug signing private key is not yet confirmed stable across jobs. Android may reject upgrade with UPDATE_INCOMPATIBLE. **Do not uninstall or clear data of the registered Owner's v4.0.2 APK**: its private app-install phone UUID is linked to the QA backend (the screenshot shows Owner APPROVED). If install over v4.0.2 fails, leave Owner phone untouched and test the v4.0.3 apk on a *separate/unregistered Team device*. We need a stable app signing keystore plus a safe Owner replacement/relink flow for future APK upgrades.
- Staging Team email `mywin.7070@gmail.com` is a QA dotted alias to Owner's Gmail inbox, not suitable as independent production Staff login.
- No financial photos in cloud. Restore backups and local invoice photo copies must be secured before migration.

## Next to complete real-world realtime ecosystem

1. Staging `marketpro_qa_finance_apply_v1` server SQL with role/device/session checks, server-date, optimistic revision and idempotent operation IDs — developed in separate review branch. Keep finance gate OFF until catalog mapping, protected Owner/Team writer transport and two physical phones pass.
2. Upload Room financial outbox via one server-tested finance route for Owner and Team; update records and revision baseline only on confirmed receipt, preserve pending changes on network loss.
3. Merge verified Inbox financial rows into Market/Dashboard without overwriting unsynced local edits. Include catalog UUID mapping, separate expense/cash domain and exact revisions.
4. Expose Staging read-only Cloud Dashboard and test owner/team updates; use foreground Realtime notification/re-fetch (WorkManager alone is not instant).
5. Run two-phone QA, add stable staged APK signing for in-place updates, then Production backup/cutover by explicit Owner approval.

**Status:** v4.0.3 includes automatic safe Cloud Inbox RECEIVE only, NOT automatic financial upload or near-instant two-phone visible sync. Do not label FINAL.
