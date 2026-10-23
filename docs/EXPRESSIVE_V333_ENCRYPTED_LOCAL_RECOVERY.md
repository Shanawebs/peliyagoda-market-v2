# Expressive v3.3.3 — Encrypted Local Recovery Preparation (NOT A CLOUD CUTOVER)

## Why

The user confirmed that all 330 local records were restored from an older JSON backup. v3.3.2 audits confirmed 330/330 unique candidate matches, zero ambiguous/missing, 330 different local/cloud UUIDs and 330 unsent local operations.

The JSON backup does NOT include the source installation's cloud_entity_ids and cloud_sync_outbox. Creating fresh identities during restore is intentional in pre-enrollment but unsafe to upload into the existing nonempty workspace.

## v3.3.3 scope

- Add a **user-initiated** "Prepare Encrypted Recovery Snapshot" action to the read-only Cloud Connection dialog.
- Reauthenticate and re-run the complete Cloud audit immediately before snapshot preparation. Gate on content parity, one-to-one candidate coverage, pending count, no enrollment and no Cloud writer on the preview installation.
- Compute a SHA-256 fingerprint over every current Room business record, UUID map, outbox event/operation ID, and local sync state. Compare fingerprint from audit with snapshot captured under ONE Room read transaction.
- Serialize the original local table records **without changing them**: categories, all active and archived items, requisitions, purchases, additions, cash, all cloud UUID identities, all 330 pending operations and local sync flags.
- Encrypt the snapshot using a non-exportable AndroidKeyStore AES-256 GCM key, new random IV, and authenticated purpose label.
- Save to Android noBackupFilesDir using file sync + rename; read/decrypt and verify bytes immediately. Never overwrite prior copies. Maximum 5 private copies per preview installation. No network upload or photo export.
- Show only record counts, saved copy count and verified state in UI. Never expose raw UUIDs, operation IDs, phone records or account JWT.
- Keep the Cloud device un-enrolled and the original v2.31 writer unchanged.
- Retain a separate backup of the **original** JSON and older Android app: private snapshot disappears on uninstall; AndroidKeyStore key cannot be restored on another device.
- **No "Restore Snapshot", "Apply UUID Map" or "Enable Cloud Upload" button** in this preview. A rollback mechanism still requires a separate audited, owner-approved local migration implementation. Do not claim cutover or full rollback is ready.

## Preconditions and tests

- Gate rejects any local change to money, grams, item names, source identity, pending operation ID, record count, enrollment or upload-enabled state between audit and snapshot.
- Gate refuses incomplete/ambiguous/cloud-different candidate coverage.
- Unit tests check restored 330 rows and failure cases. Android build validates UI, resources, app ID, tests and absence of writer activation in the preview.
- Full AES-GCM AndroidKeyStore runtime testing still requires the physical Android phone. JVM unit tests do not substitute for that hardware security test.

## Important next step

Before an actual UUID mapping *write* and outbox quarantine, obtain a transactionally consistent Cloud backup or server revision snapshot, preserve the old writer's device-specific queue and identities, and ensure no concurrent old-writer edits can race the migration. Next release should implement a one-transaction local UUID+outbox rebind with a recoverable rollback and compare exact server IDs under explicit user confirmation. Only after this can separate writer cutover be evaluated.

## Security

No Supabase DDL, DML, policy change, write RPC, new public endpoint, service role secret, photo upload or cloud write. Keep all earlier Android apps independent.
