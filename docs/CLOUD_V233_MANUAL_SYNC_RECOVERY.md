# Market Pro Cloud V2.33 — Manual sync recovery (TEST-ONLY)

Date: 2026-10-22

## Observed issue

On the user's installed V2.31 Android app, manual Cloud Sync reports pending=1 and a previous Auth HTTP 400 even after a successful new login. Read-only Supabase inspection confirms the 2026-10-20 Indian Kitchen carrot requisition is still 0 g (revision=1), while the user reports the local edit to 5,000 g.

## Why the old Sync Now button does not give reliable feedback

V2.31 calls WorkManager `enqueueUniqueWork(..., ExistingWorkPolicy.KEEP)` from the button. Existing pending/retrying work can suppress the new request. The UI incorrectly reports "Sync work requested" without waiting for the upload result. Automatic periodic scheduling is independent.

## Changes isolated on development branch

1. Manual Sync Now calls `CloudSyncEngine.drain(maxChanges=100)` on Dispatchers.IO and waits for the server-confirmed ACK result, showing the actual remaining count or a sanitized error.
2. V2.32's process-wide `CloudSyncRunGate` mutex serializes direct manual and periodic workers and preserves exact operation UUID/idempotent ACK semantics.
3. `CloudSyncConfig.LAST_ATTEMPT` is stored in an existing local Room key-value table and shown in the UI, without schema migration, so attempts and old errors can be distinguished.
4. `CloudOwnerAuth` uses one process-wide session mutex around sign-in, refresh and sign-out to avoid concurrent rotating-refresh-token requests.
5. V2.31 database and pending queue are never reset, deleted or migrated. No Supabase SQL/RLS changes.

## CRITICAL — release is BLOCKED by APK signing

Installed V2.31 was generated with an ephemeral GitHub Actions debug signer. `feature/cloud-v1-reliability-v232` previously established that an unrelated debug APK cannot be safely installed as an in-place update. **Do not distribute any debug APK**, change Android package ID while pretending to preserve data, uninstall the app, clear app data or reset Cloud enrollment.

Safe recovery requires either:
- Prove that an update APK is signed with the **same certificate** as the installed V2.31 and then verify local DB persistence, outbox=0 and cloud reconciliation on a real device; or
- Create a controlled separate-install migration with a verified and owner-approved transfer of local changes/photos and cloud UUID writer enrollment after backup and consistency audit.

Cloud-only manual reconciliation of the specific 5 kg record also requires explicit approval, a verified local value/backup, and a plan for the existing outbox record to prevent later incorrect overwrites.

## Release verification gate

Dedicated V2.32/V2.33 GitHub Actions workflow is **test-only**: Kotlin compile and unit tests, **no downloadable APK artifact**. Branch stays isolated from main and currently installed V2.31. Never claim the phone issue is resolved until an owner-verified end-to-end sync completes.
