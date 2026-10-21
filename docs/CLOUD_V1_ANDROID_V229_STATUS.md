# Cloud V1 Android V2.29 — Offline journal / Secure upload preparation

Status: **staged development only, not a completed live cloud sync release**.
Date: 2026-10-21.

## Confirmed facts

- Branch `feature/cloud-v1-android-outbox-v229` derives from the verified Cloud V1 Supabase DEV schema and Android V2.28 `main`.
- Android Room **schema 4→5**: add `cloud_sync_outbox`, `cloud_entity_ids` (stable UUID mapping), `cloud_sync_state`. Existing business tables are NOT dropped or rewritten; original invoice photos untouched.
- Every supported user edit to categories, items, kitchen requisitions, initial purchases, additional lots or daily cash is journaled in **the same Room transaction** as the edit.
- Duplicate rapid edits coalesce per local logical row; each replaces the pending `operationId`. Server ACK must include that operationId, and local ACK SQL is conditional on it, preventing an earlier ACK from discarding a newer unsent change.
- Existing manual JSON backup restore is allowed only when NOT cloud-enrolled. After local restore, the unapplied journal and local-to-cloud UUID identity mapping are rebuilt in the same transaction; cloud-linked restore is blocked until a separate audited rebootstrap procedure exists.
- `Cloud Sync Status (Preview)` reports pending count. It makes no claim that records have been uploaded.
- A separate **V2.29 Cloud DEV** application ID is used: `lk.peliyagoda.marketpro.v229.clouddev`. It is NOT an in-place update to V2.28. The two apps have separate private Room databases and invoice-photo directories.
- V2.28 is left installed. The V2.29 DEV APK must never trigger unplanned uninstall, database overwrite or automatic photo migration. To test with real data, first save a verified JSON backup and export original invoices separately. Do not delete V2.28.

## Secure backend RPC, still gated

- `public.marketpro_apply_change` uses verified Supabase Auth, owner role, registered enabled primary device, explicit entity/property allowlists, server-calculated SHA-256 replay hash and soft-delete tombstones.
- No client direct table writes, no service-role key in APK, no anonymous RPC execution.
- `sync_operations` ledger is idempotent; a replay with identical operation UUID/payload returns `replayed=true`; with a changed payload is rejected.
- Financial values use exact integer grams and cents; derived daily view matches Android half-up rounding per lot.
- **No invoice photos, no Storage bucket or photo upload code**.
- At this stage there is NO login UI, no background uploader and no authenticated workspace/device enrollment. This is a foundation only. Do not tell the owner that automatic cloud upload is already working.

## Pending real upload gates

1. Verify local Supabase DB migrations, security, RPC negative tests and migration history alignment.
2. Deploy the new RPC migration to Supabase DEV only after CI tests; retest RLS/grants, read-only workspace rows and no Storage bucket.
3. Obtain a verified owner Auth user and enroll one specific device (admin-only), with no password or token shared in chat.
4. Add secured Kotlin Auth session handling, WorkManager upload worker, ordered serialization and ACK/retry under network constraints. No auth / no enrollment => no upload.
5. Perform a verified one-time initial backfill: categories→items→requisitions/purchases/additions/cash; test counts and daily money totals, then enable incremental writes.
6. Test airplane mode, rapid edits, app restart/force-stop, repeated RPC operations, deletes, expired token, unauthorized device and 4GB RAM performance.
7. Only after those gates may a clearly marked real cloud-sync APK be released.
