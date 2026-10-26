# Market Pro Two-Phone — Owner Bootstrap One-Use Server Nonce

Date: **2026-10-26**, Staging project `qqiheexpbuzohllugxrk`.

## Completed and independently verified

- Source: `supabase/staging/staging_qa_owner_bootstrap_atomic_challenge_REVIEW_ONLY.sql`.
- Physical Owner phone is **NOT yet registered**. A first-phone bootstrap needs its own Owner AAL2 verified account/session check; the existing invite helper expects an already registered phone, so cannot bootstrap the first phone.
- Owner challenge issuance SQL checks an authenticated Owner with a verified TOTP factor, **live matching Auth session**, and a TOTP AMR within five minutes. It issues one random 256-bit nonce, binds workspace/owner/device/session/SPKI fingerprint, limits validity to 60 seconds, invalidates the previous pending nonce under a workspace advisory lock.
- Only a trusted Edge verifier with service_role is intended to read authoritative challenge material, verify the Owner P-256 DER signature with `verifyOwnerBootstrapKeyPossession`, and then call the service-role-only atomic commit RPC.
- Commit rechecks same workspace, exact Auth owner and session, factor and active session, current gate and challenge validity. It consumes nonce once under a workspace lock, and stores **only a pending, inactive public key**. It never writes `public.registered_devices` or Staff memberships, and cannot mark hardware attestation/activation complete.
- PostgreSQL `encode(...,'base64')` wraps output with line breaks for long values; the material RPC normalizes to one-line Base64 for strict WebCrypto verifier interoperability. This was caught and corrected by disposable PostgreSQL 17 CI.
- Current hosted migration: `20261026043140_marketpro_staging_owner_bootstrap_atomic_nonce_dormant`, **applied to Staging only**.
- On hosted Staging, `16/16` read-only checks PASS: Owner verified, no devices/Staff/invitations/public keys or active challenges; private switches OFF; no `anon`/`authenticated` rights on any new call; trusted server commit only.
- Latest GitHub Actions workflow `marketpro-staging-owner-bootstrap-atomic-nonce-ci.yml`: three jobs PASS — unchanged Production code, actual P-256 crypto tests, temporary PG17 database tests (fresh MFA, live session, nonce rotation, wrong session, stale/replayed challenge, inactive staged key).
- Production Android code, dashboard, production Supabase project `lkoptqhodgjeuwwfihfr`, all real purchases and finance entries: **unchanged**.

## Important limits / next steps

**This is a dormant backend component, not working Android pairing.** A privileged service-role commit function only performs safe, atomic storage: it relies on a separately reviewed Edge HTTPS handler to verify ECDSA *before* calling commit. The existing Node verifier and SQL transaction passed independent tests, but they have not yet been wired into an authenticated Edge endpoint or tested with actual hosted PostgREST JWT requests. DO NOT expose service_role or secrets to Android.

A valid signature is proof of private key possession, not certified phone hardware attestation, biometric identity or authorization to change financial data. Owner Phone onboarding must later require a separately isolated Staging Android applicationId, server-verified live session, and fresh TOTP; then separate Owner key activation and Staff pairing/approval. Staff financial Cloud Sync remains disabled until signed per-operation reads/writes, revocation/replacement, and two real-phones end-to-end tests pass.

Security Advisors on Staging flag INFO for intentionally deny-all private RLS tables, and a WARN that leaked-password protection is disabled. Review Auth settings independently; never broaden access simply to silence default-deny INFO.

**Safety**: bootstrap gate stays OFF. The new issue RPC is deliberately NOT granted to authenticated; Staff invitation and any writer grants remain OFF. Never install a test APK over Production.
