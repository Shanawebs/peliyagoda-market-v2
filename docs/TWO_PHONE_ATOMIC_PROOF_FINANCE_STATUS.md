# Market Pro — Atomic Staff Device Proof Financial Write (Review Only)

**2026-10-24. GitHub Draft. NO live migration or deployed Edge endpoint.** Owner v3.3.7 single writer, Dashboard, photos, and production catalog unchanged.

## What this stage delivers

1. A Staff JWT-authenticated `marketpro_staff_issue_finance_challenge` RPC validates the exact financial intent and calls the v375 issuer to create an active **60-second** one-time device proof challenge. It stores the **SHA-256 commitment** of the signed-in Staff user, enrolled phone, workspace, operation/entity UUIDs, entity type, action, optimistic revision, and canonical JSON payload in a service-private table. An attacker cannot substitute a different purchase price, date, operation ID, or other-expenses amount later and reuse the same signed nonce.
2. The new draft `marketpro-staff-finance` Edge Function authenticates real Staff JWT claims, calls the current Staff-scoped challenge-issuer, looks up challenge material using its trusted server-only client, verifies AndroidKeystore P-256 `SHA256withECDSA` over the v375 domain-separated challenge transcript, then calls the **only service-role-allowed** `marketpro_service_apply_proof_bound_finance`.
3. That SQL RPC takes the workspace transaction lock, rechecks the Auth session, device, Owner-controlled lease, approved public key, one-time nonce and **exact committed request hash**. It consumes the challenge and invokes one of the existing audited revision/date finance RPCs *in the SAME SQL TRANSACTION*. On failed write/revision conflict, BOTH nonce consumption and financial changes roll back. Legacy Owner V1 `marketpro_apply_change` is deliberately unaffected.
4. **Critical bypass closure:** revoke direct `authenticated` execute privileges from the three older v2 finance RPCs. If a Staff user can POST to the older v2 RPC without a signed proof, device verification is bypassed. For first cutover, revoke and deploy atomically; never deploy only the new endpoint while leaving old public rights.
5. All request types and fields are explicitly allowlisted in Edge validation: purchase, requisition, additional lot, cash received, other expenses. Photos, catalog mutations, arbitrary JSON fields, deletes and credential/body reflection are prohibited.
6. PG17 integration tests simulate the Edge *after* its crypto verification and test request-hash changes, nonce replay, current Staff session/lease, unlimited same-day corrections, previous-day denial, stale-revision rollback, direct-RPC ACL and original Owner writer cardinality. Node tests exercise WebCrypto DER conversion using actual P-256 keys. Deno type-checks the Edge endpoint.

## Known unresolved blockers before production

- **The PG17 SQL test simulates a trusted Edge after verifying cryptography; it does NOT test a real signed Supabase JWT + PostgREST + installed handset on a deployed project.** Do not claim full device possession or E2E production security from these tests alone.
- Owner-approved key enrollment/attestation is still review-only. A `StaffDeviceProofKeyStore` key can be software-backed on some devices; hardware attestation has not been verified.
- The Swift/Android app currently uses `UnavailableStaffWriteTransport` and has all Staff deployment flags OFF. A real Android HTTPS transport integrating challenge issue → local AndroidKeystore signature → Edge submit remains to implement and test. The local signing code currently supports the v375 transcript but is not wired to finance upload.
- Supabase Staff RLS/member-role migration must be consolidated and tested BEFORE a Staff user exists, and the unprotected finance RPC permissions revoked in the same audited deployment. Current live production has only one Owner writer and no Staff dual-channel tables.
- Need real Supabase staging with two physical Android phones, genuine Auth JWT session expiry/refresh, phone loss and revocation mid-RPC, same-day Colombo midnight, outbox exactly-once behavior, cloud catalog GUID reconciliation and Backup/restore (photo backups separate). Final signed APK only after release readiness.
- Dashboard financial summary with four Other Expenses must reconcile with Server and Room.

**NO-GO for production; there is no live Staff auto-sync or new APK at this point.**
