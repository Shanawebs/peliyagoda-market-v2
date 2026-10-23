# Market Pro Expressive v3.3.4 — Local UUID Stage & Verified Rollback

## User-validated input

v3.3.2 physical-phone Dry Run: **330 of 330 unique candidates**; 0 ambiguous; 330 local UUIDs different from Cloud; 330 pending restore-generated operations, 13 archived cloud items. v3.3.3 physical-device AES-GCM recovery snapshot successfully verified. The latest Cloud workspace still contains 184 items, 105 requisitions, 26 purchases, 10 additions and 2 daily cash records, with one authorized writer.

## Goal

In a new, independent Android package only, safely reconcile the restored 330 local UUIDs to the matching existing Supabase server UUIDs without uploading, overwriting or duplicating Cloud data.

## Exact v3.3.4 changes

- Re-run a fresh authenticated RLS-scoped GET audit. Compare ALL active and archived catalog records and financial fingerprints.
- Construct a complete bijective plan by unique semantic key, with no record ordering assumptions or ambiguous duplicate lots.
- Require that every local identity has one Cloud candidate, every pending operation is a restored **upsert** covering exactly that identity, and local SHA-256 has not changed.
- Automatically save and decrypt/verify an AES-GCM, AndroidKeyStore-protected recovery snapshot in private noBackupFilesDir BEFORE touching the Room identities/outbox.
- Under ONE `RoomDatabase.withTransaction`: recheck exact business/content fingerprint and queue; replace the local `cloud_entity_ids` UUIDs with existing server UUIDs, clear the restore-only `cloud_sync_outbox`, verify new identity count and zero queue, persist the recovery snapshot filename as a local stage marker.
- In the same version, offer an explicit **Rollback** action to restore encrypted original UUIDs and all previous pending operations, only if there have been no business changes or new pending edits, writer is not enrolled, and the saved snapshot is intact. Rollback is another single Room transaction.
- The `CloudLocalIdentityStager` has **NO HTTP write method**; the original V2.31 writer and all Supabase records, storage, policy, and schema remain unchanged. No service-role secret or invoice-photo upload.
- This is **NOT Cloud Auto Sync / enrollment**. Do not advertise completed real-time sync until a separately authorized server-side single-writer handover passes tests.

## Critical caveats

- Every version in this Expressive prototype is a distinct Android package and therefore has independent local Room data and AndroidKeyStore keys.
- v3.3.3 recovery snapshot CANNOT restore into v3.3.4. To test v3.3.4, use the already verified external JSON backup and repeat the read-only check in the new test app; the in-app Stage action takes a NEW private snapshot automatically.
- Never uninstall v3.3.4 between Stage and Rollback, because uninstall removes its private AES key and encrypted recovery files.
- Rollback is limited to local identities + outbox for **unchanged business records**. It does not restore invoice photos or replace the preexisting Cloud snapshot; those remain with the old app and original separate backup.
- Until a controlled writer switchover is approved, DO NOT activate any other Cloud writer or enable WorkManager background uploads.
- Concurrent writes by the original Cloud writer after an audit can invalidate parity; re-audit and investigate before cutover. This release cannot freeze the original writer on the server.

## Manual verification checklist

1. Install separate v3.3.4 and restore verified JSON backup, sign in to EXISTING Supabase Owner Login.
2. Confirm 171 active / 13 archived catalogs, 105 requisitions, 26 purchases, 10 additions and 2 cash records.
3. Check Cloud Connection & Local Migration. Before staging, expect 330 different UUIDs, 330 pending local restore operations, 330/330 unique candidates.
4. Choose Prepare Local UUID Stage and confirm the local-only warning. The app should create verified encrypted snapshot, atomically rebind 330 UUIDs and quarantine 330 pending restore ops.
5. Recheck Cloud: expect UUIDs same 330, UUIDs different 0, pending 0; writer **Not Enrolled** and upload **LOCKED**.
6. If wanting to test rollback, use Restore Original Local UUIDs/Pending Queue. Verify the old 330 UUIDs + pending operations come back, without any Supabase change.
7. Do not enter new business transactions during testing; doing so intentionally blocks rollback to prevent losing later edits.

## Future Auto Sync release gate

Securely freeze/retire the old authorized device's writer, ensure no unsent old-device operations, validate Cloud record revisions and continuity, explicitly enroll the new test device by a server-verified transactional transition, and then enable background WorkManager. Run duplicate/replay, offline-resume, token-refresh and partial-failure tests before production.
