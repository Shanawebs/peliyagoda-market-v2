# Market Pro Expressive v3.2.8 — Cloud Reconnection / Preflight

## Purpose

Safely prepare a *different Android application* (Expressive v3.2.8) for syncing to the existing Market Pro Supabase workspace / read-only web dashboard.

The currently working V2.31 legacy cloud writer and all 2.36 / Expressive v3.0–v3.2.7 applications remain unmodified.

## Live environment discovered (read-only SQL, 23 Oct 2026)

- Existing Cloud workspace is **not empty**: 184 items, 105 requisitions, 26 purchases, 10 purchase-addition lots and 2 cash records.
- 331 accepted sync operations.
- Two registered devices total; ONE enabled writer.
- The existing V2.31 is the protected writer. This v3.2.8 has a new package, Room DB and CloudDeviceIdentity UUID.
- The V2.31->V3.2.8 local autoincrement item IDs and `cloud_entity_ids` UUID maps are **not known equal**.
- Existing cloud web dashboard is https://marketpro-cloud-dashboard.vercel.app/ (read-only).

## v3.2.8 release scope

1. Enable **existing-owner Supabase sign-in** in the Expressive preview, with AndroidKeyStore-encrypted session as existing auth code.
2. Add **read-only** Cloud Connection screen, owner-auth REST reads, cloud/local record counts and *this-install* device enrollment status.
3. Link to existing web dashboard; photos stay local. Neither RLS nor financial cloud data/schema changed.
4. Keep all outbox uploads disabled: do NOT call `CloudSyncDialog`, `enableAfterOwnerConfirmation`, `CloudSyncScheduler.kick`, or writer RPC from this preview.
5. Add unit tests for safe Content-Range parser and hard-locked writer policy.
6. Unique Android application ID and independent APK; never replace the original live device app.

## REQUIRED to safely implement actual Cloud-to-Expressive migration and auto sync later

A. Backup existing V2.31 *including* the immutable cloud item UUID mapping and financial records, or construct a read-only cloud-to-local mapping with explicit identity checks. Plain JSON backups do not preserve photo files or all cloud UUID mappings.

B. Reconcile local items by stable semantic identity **plus authoritative server UUIDs**. Categories by owner workspace. Requisitions/purchases by business-date + kitchen + immutable item UUID, additions by stable lot UUID (NOT local autoincrement ID), and cash by business-date. Preserve revisions and tombstones.

C. Reject any ambiguous mappings; show a report of unmatched/different rows and quantities. Compare month/day aggregate LKR cents and units. Do not overwrite the existing 184-item cloud catalog simply because another fresh preview seeds a catalog.

D. Take explicit owner approval to promote an Expressive installation as the one active cloud writer. Perform server-side single-writer device enrollment switch only after old writer has been disabled and new writer is verified. Never give anonymous insert/update rights or embed secret API keys.

E. Then enable WorkManager immediate + 15-minute periodic network sync and verified ack/resume. Run on-phone QA including offline queues, account refresh, simultaneous worker guard, purchases, requisitions, cash and web dashboard parity. Never upload photos.

## Security

- Cloud owner JWT is never displayed, logged, or sent to chat.
- Access to server reads uses only public publishable key + owner JWT, enforced by workspace RLS.
- Service role keys are absent.
- Existing Supabase advisor reports a publicly exposed privileged writer RPC and leaked-password protection disabled. Review before promoting any second writer.

## Manual reviewer steps

1. Keep installed 2.31, 2.36 and Expressive v3.2.7 as is.
2. Install standalone v3.2.8 and open Menu -> Cloud Owner Login -> sign in using EXISTING owner credentials.
3. Open Menu -> Cloud Connection (Read-Only) -> Check My Cloud Connection.
4. Compare Phone vs Cloud counts; the cloud list should include existing data but Phone counts may differ because this is a separate local DB.
5. Open Cloud Web Dashboard and check existing totals. Do not delete records, clear old apps, or sign up again.
6. Cloud upload is expected to remain LOCKED; this milestone verifies secure data access, not a completed two-way sync.

## Design decision

Keep Room as offline transaction source for a single authorized writer. Web dashboard is a read-only, near-real-time consumer (currently HTTP polling every 15 seconds). Multi-device writes require a separate conflict-resolution protocol and are out of scope.
