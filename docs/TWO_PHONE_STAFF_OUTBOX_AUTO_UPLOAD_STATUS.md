# MARKET PRO TWO-PHONE — STAFF OFFLINE OUTBOX AND AUTOMATIC UPLOAD (REVIEW ONLY)

**2026-10-24 — Owner v3.3.7 unaffected. New Staff write path is OFF; no production deployment or signed APK.**

## Implemented in the Staff upload development branch

- A new Staff financial-only queue selector reads durable Room `cloud_sync_outbox` changes. Category/item/deletion operations cannot be sent through Staff finance RPCs. Stable Cloud UUID + operation ID stay in Room until an explicitly verified server acknowledgement.
- Staff-edited Transport, Labor, Meals/Drinks, Others and Remarks now join the journal **in the same Room transaction as the edited value**; restored records are journaled but never treated as Cloud proof or authorized upload. These edits remain offline-capable.
- The `StaffOutboxUploadPolicy` requires a distinct authenticated Staff user and enrolled physical device, fresh server Colombo date (<=60 seconds since authenticated download), today's exact financial business date, reviewed item UUID link, verified new-entity origin or known server revision, no detected other-device newer revision, plus an unchanged pending operation. Older-day unsynced changes are HELD for Owner review rather than uploaded by a Staff device after midnight.
- A `StaffFinancialOutboxCoordinator` builds exact typed JSON envelopes (purchase, requisition, purchase addition, cash, other expenses), takes a Room snapshot before the network call, requires a separately provided proof-bound transport, then advances acknowledged revision and deletes only the exact pending operation UUID via conditional Room `acknowledge` after server confirmation. If the row was re-edited during upload, the newer operation stays queued. No photo bytes or file paths enter the envelope.
- Separate `StaffFinanceAutoSyncScheduler` uses unique immediate network-constrained WorkManager and 15-minute periodic recovery; retry on connection errors, hold conflicts, avoid infinite retries for Owner review. The existing Owner sync scheduling method calls the Staff scheduler **only when all three new BuildConfig gates and existing Staff/Pull gates are enabled**.
- New default-OFF flags: `MARKETPRO_STAFF_FINANCE_RPC_DEPLOYED=false`, `MARKETPRO_STAFF_DEVICE_PROOF_WRITE_ENFORCED=false`, `MARKETPRO_STAFF_OUTBOX_AUTO_UPLOAD=false`. `MARKETPRO_LOCAL_PREVIEW=true`, Staff Login/Pull/Domain Merge disabled, and AndroidManifest continues to REMOVE INTERNET.
- Pure JVM unit tests cover same-day unlimited correction, old-day queued hold, stale remote revision, copied/deactivated device, blocked DELETE/catalog, strict operation ID / revision acceptance and BuildConfig release gates.

## CRITICAL: this is NOT a working live two-phone sync release

`UnavailableStaffWriteTransport` deliberately raises `IOException` without network request. The V3.7.5 server `proof_verified_once` is NOT an atomic business-write permission, and sending Staff RPC writes after a separate proof would leave a bearer-token replay gap. A new **proof-bound finance transaction endpoint** must be implemented, deployed and independently audited first; it must atomically consume a Staff AndroidKeyStore signature challenge in the **same locked financial write transaction** and repeat JWT session, live Owner lease, exact registered device, Colombo date, idempotency, and expected revision verification. Only then can a real transport be substituted.

Do not flip preview flags simply to make an APK upload; it would fail/retry and is not a security shortcut.

### Additional remaining launch blockers
1. Live Supabase currently exposes only Owner `marketpro_apply_change`; Staff role and safe restricted RLS/session, key registration and proof binding are not deployed.
2. Staff device enroll/replacement and verified catalog UUID onboarding must be tested on staging Supabase with actual JWTs. The existing Owner Phone populated database/UUID keys cannot be overwritten with new Room IDs.
3. Staff direct SQL entries must pass full two-device offline retries, 24h Colombo midnight, Owner old-day corrections, rejected Staff old-day corrections, revocation, internet failure, power loss during ACK and photo-safe recovery.
4. All five financial entities + read-only web Dashboard (including Other Expenses totals) must match. Background sync is not instant while app closed; WorkManager's minimum periodic interval is 15 minutes, and immediate constrained work is opportunistic.
5. Backup cloud DB and both phones, include invoice photos outside JSON backup, sign/test APK and provide direct chat download link only after go-live checks pass.

**No migrations, Supabase live writes or Staff accounts changed.** These files are development work toward, not certification of, the final release.
