# Two-Phone Cloud → Room Financial Reconciliation

**2026-10-24 — development branch only. Not deployed, no production APK.**

This increment moves the two-phone release candidate closer to safe visible financial sync without changing the existing Owner phone.

## Completed

- Cloud `daily_other_expenses` is a supported, strictly allowlisted pull type. `CloudInboxStager` checks signed server workspace, row UUID/revision, keyset pagination, server Colombo date, all four integer-cent amounts and notes length; photo/file/upload and unexpected fields rejected.
- A **session-bound** replacement for V372 `marketpro_team_pull_page` adds other expenses while retaining active device and `auth.sessions` checks. Only reviewed RPC grants Staff reading; direct Rest SELECT RLS remains Owner/Editor/Viewer only in the proposal.
- A bounded `CloudDomainMergeEngine` expense branch maps remote UUID to a unique local business-date key, rejects pending local edits, wrong UUID/revision/tombstone and unbaselined changes; keeps local four amounts/remarks and revision fingerprint in one Room transaction. Photos are never read, removed, or uploaded.
- `TeamInboxDownloadCoordinator.stageAndMergeFinancialType` can, after verified server staging and independent activation, reconcile purchase, requisition, daily cash and other expenses in groups of <=200 on 4GB phones. It exposes applied/reviewed/blocked counters instead of automatically declaring conflicts resolved. The existing app does not call this method while preview flags are disabled.
- NEW `MARKETPRO_TEAM_DOMAIN_MERGE_DEPLOYED=false` feature gate; legacy `MARKETPRO_LOCAL_PREVIEW=true`, Team Pull and Staff Login remain false, and AndroidManifest INTERNET remains removed. Existing Owner production app and live migration files unchanged.
- Separate JVM regression protects partial expense drift, local pending edits, wrong UUID, version rollback, remote tombstone and unbaselined legacy business dates.
- Disposable PG17 integration pulls two expense days for Owner and paired Staff with keyset pagination; rejects revoked Staff phone and a different JWT Session ID.

## Still unresolved / safety blockers

1. **The real two-phone sync is NOT live.** Existing functional `CloudSyncEngine` is a different Owner-only app/package and blocks historical upload; Staff upload is not implemented. Production has no v2 RPCs.
2. Catalog/category/item UUID bootstrap is not yet wired to live Room. `CloudDomainMergeEngine` intentionally returns `WAIT_FOR_EXACT_ITEM_UUID_MAPPING` on purchase/requisition lines without exact trusted mapping. Purchase additions also remain unmerged. No name-only item auto-match.
3. Supabase RLS broad member SELECT, role check and Staff session/key/proof authorizations need a consolidated reviewed migration and staging tests. V375 key challenge proof currently does not atomically authorize *each* business RPC.
4. `DailyOtherExpenseEntity` existing Room schema stores one row per date, but older phone financial rows need deterministic UUID reconciliation before any second uploader; old JSON backups omit invoice photos. App still has no direct Staff secure login/Owner pairing active.
5. Real device testing with Owner + Staff phones, missing/retired phone, network disconnect, duplicate operations, midnight Sri Lanka, and public read-only Cloud Dashboard is required before signed APK delivery.

**GO/NO-GO = NO-GO** while any of the above remains. We must not enable Staff by simply setting BuildConfig flags or broadening direct REST RLS. Keep backups and original Owner device intact.
