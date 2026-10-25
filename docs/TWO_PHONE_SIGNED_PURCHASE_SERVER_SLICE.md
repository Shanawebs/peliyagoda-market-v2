# Market Pro Two-Phone — Proof-bound Purchase Vertical Slice (REVIEW ONLY)

2026-10-25. **NOT DEPLOYED.** All production Owner v3.3.7, live migration files, deployed Dashboard and existing cloud records remain unchanged.

## Scope actually delivered

**ONE PURCHASE type** (not all five financial types):

1. Staff with independently Owner-approved P-256 public key, active JWT Auth session pairing, enabled phone and write lease requests a fresh 60-second challenge containing the exact purchase UUID, operation UUID, expected revision, action and four-field JSONB payload. Server binds the SHA-256 of the canonical JSONB payload.
2. Android's nonexportable Keystore P-256 key signs a fifteen-line domain-separated v2 ASCII transcript. The Edge verifier rejects v1 downgrade signatures and checks DER against the approved SPKI key.
3. Edge calls exactly one service-role-only SQL transaction. PostgreSQL holds the workspace advisory lock shared with Owner phone revocation; rechecks current Auth session, approved key, phone, Owner writer lease, one-use nonce, exact payload and Asia/Colombo date; applies purchase with optimistic revision; and consumes nonce **in the same transaction**.
4. Financial mutation failures cause SQL rollback without spent nonce. A lost-ACK retry must sign a fresh challenge for its original operation UUID; business operation ledger returns the same purchase revision rather than duplicating cost.
5. Previous direct V2 Staff RPC grants for purchase, requisition, addition, cash and expenses are revoked from authenticated and anon to block bypasses. Production legacy Owner V1 API is unchanged.
6. A typed Android HTTPS challenge/sign/commit client is implemented but **not connected** to Staff automatic Worker. All activation flags and existing UnavailableStaffWriteTransport hard stop stay OFF.

## CI coverage

- Real Node ECDSA P-256 DER + WebCrypto, tampering purpose/session/device/nonce/record/operation/amount digest, expiry and v1 signature downgrade.
- Disposable PG17 verifies exact purchase context, atomic nonce/write/idempotent replay, rejection of yesterday's Staff write, immediate staff writer lease revocation and no direct financial RPC grant.
- Deno Edge typechecking and Android Kotlin unit/compile checks.
- Frozen production manifests, current Owner app flags, migration files and read-only dashboard.

## Remaining NO-GO items

- Independent Owner-confirmed Staff public-key enrollment and proof of key possession. Current fixture keys are test-only; no live Staff device key enrolled.
- Equivalent proof-bound endpoints for requisitions, purchase additions, daily cash and other expenses. Never dispatch arbitrary table names through service_role.
- Fully audited Supabase staging deployment with real JWT sessions, RLS check, device replacement, catalog UUID reconciliation and two physical-phone sync/backups.
- Offline signed operation lost-ACK revision reconciliation, Android genuine hardware attestation (not provided by hardwareBacked flag), Owner historic edits, 4GB performance and direct signed APK delivery.

**This draft must NOT be merged into production or switched on merely because isolated CI is green.**
