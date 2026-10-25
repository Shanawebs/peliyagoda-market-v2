# Market Pro — Five-Entity Signed Staff Finance AutoSync Review

**2026-10-25. DRAFT REVIEW-ONLY, no production deployment, no signed APK.**

## What the actual Android worker now does in this branch

Existing `CloudSyncScheduler.kick()` and `schedulePeriodic()` already call `StaffFinanceAutoSyncScheduler`, which preserves a separate Staff work name and queue from Owner's legacy V1 writer. Previously its future worker used the `UnavailableStaffWriteTransport` only, and could not upload anything even after all cutover gates were enabled. This branch injects `SignedAllStaffFinancialWriteTransport` **only** into that Staff worker.

Each queued row routes to exactly one reviewed signed endpoint:

| Entity | Endpoint | Crypto proof |
|---|---|---|
| Purchase | `marketpro-staff-signed-purchase` | Android Keystore ECDSA P256, v2 domain transcript |
| Requisition | `marketpro-staff-signed-financial` | Android Keystore ECDSA P256, v3 domain transcript |
| Purchase Addition | `marketpro-staff-signed-financial` | Android Keystore ECDSA P256, v3 domain transcript |
| Daily Cash Received | `marketpro-staff-signed-financial` | Android Keystore ECDSA P256, v3 domain transcript |
| Daily Other Expenses | `marketpro-staff-signed-financial` | Android Keystore ECDSA P256, v3 domain transcript |

Unknown catalog changes, delete requests, `item` and all other types are explicitly blocked with no fallback to the Owner legacy `marketpro_apply_change` REST RPC. Each accepted request still requires one-time fresh signed proof, current Staff session/Owner-approved P256 key, active Staff device and Owner-granted write lease. Remote PostgreSQL is the authority for the Colombo business day, UUID, optimistic revision, outbox idempotency and same-day unlimited Staff corrections; previous dates remain Owner-only.

**Bug discovered and fixed:** the signed Purchase Edge endpoint had a malformed 8-4-4-12 UUID regex, so normal RFC4122 8-4-4-4-12 device/operation IDs were unconditionally rejected. Fixed and covered by a Node test checking both purchase and nonpurchase finance endpoints. The matching Kotlin HTTP transports now also refuse CRLF in bearer headers, and reject URL userinfo/query/fragment.

## Explicit hard rollout gates

To register, schedule or execute the Staff financial Worker, ALL independent conditions must pass: not local preview; Staff login enabled; Staff pull enabled; Staff pairing RPC deployed; Owner Authenticator MFA deployed; Staff P256 key enrollment deployed; explicit Owner Staff writer approval available; finance RPC deployed; cryptographic proof enforcement enabled; Staff Outbox Auto Upload enabled. **All remain false except local preview is true in this review APK.** An old retained WorkManager job rechecks flags on execution and succeeds without sending anything if rolled back.

Existing Owner live Room, cloud sync, invoice photos, migrations and Dashboard were not modified. Financial writes come only from the verified Room outbox. The backend is NOT deployed on production.

## CI and known remaining release blockers

- Node: static source routing/UUID contract plus actual P256 ECDSA crypto tests.
- Disposable PostgreSQL 17: staged proof-bound all-five finances, owner MFA, old-day and revoked-phone denial, atomic idempotent write simulation.
- Deno: both finance signed Edge endpoints and Staff key enrollment Edge typecheck.
- Android unit tests: full Kotlin `:app:testDebugUnitTest` including the signed routing and all missing-flag denial tests.
- Freeze-live GitHub check: Android Owner database, AndroidManifest, buildConfig flags, cloud migrations and web Dashboard unchanged.

**Still mandatory** before production: real (separate!) Supabase staging project with verified app user JWT/TOTP and actual two phones, rigorous Server RLS/DB migration review and Owner first-boot backup/cutover, live Staff device pairing/replacement and key rotation, exactly-once race/replay under real network, duplicate/old-day conflicts, quantitative cash/PDF reconciliation, photo backups and reliable UUID sync. Do not replace the original Owner APK or enable flags until those pass. These are mock/disposable CI tests, not a real-phone production-ready demonstration.
