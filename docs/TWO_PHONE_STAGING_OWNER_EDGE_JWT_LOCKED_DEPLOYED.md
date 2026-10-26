# Market Pro — Staging Owner Bootstrap Edge Function (JWT Protected, Locked)

**Date:** 2026-10-26. **Isolated Staging project:** `qqiheexpbuzohllugxrk`. **Do not touch Production:** `lkoptqhodgjeuwwfihfr`.

## Deployed staging code, disabled operational permissions

- New Edge function `marketpro-qa-owner-bootstrap`, version 1 initially, **status ACTIVE** for HTTP routing, with **`verify_jwt=true`** and `withSupabase({auth:"user"})`. Active *route* is NOT active *Owner/Staff pairing*: database onboarding gate is OFF and client invitation/registration grants do not exist.
- Uses **pinned** `npm:@supabase/server@1.9.2`, with user-scoped `ctx.supabase` for challenge issue and server-only `ctx.supabaseAdmin` for challenge material & atomic commit. No service-role key, bearer token or TOTP code appears in code/config or logging.
- Two supported POST intents:
  - `issue` — checks signed Owner JWT identity/session, actual `aal2`, a TOTP `amr` timestamp not older than 300 s, Staging-only workspace and strict payload, then calls **user-scoped** `marketpro_qa_owner_issue_bootstrap`. This call is currently intentionally **denied by DB EXECUTE ACL**.
  - `verify` — derives Owner UUID and session UUID from verified JWT (never body), fetches authoritative challenge material through server-admin service-role RPC, checks exact challenge/user/session/workspace and genuine EC P-256 Android Keystore signature via shared WebCrypto verifier, then requests atomic one-time nonce consumption. Receipt MUST explicitly report `phone_activated=false` and `can_write=false`.
- Tests using **real Node EC P-256 DER signatures**, malicious Owner JWT/AAL1/stale MFA, tampered SPKI/nonce/session/device, expired/replayed nonce (via controlled RPC fixture), reply tampering and unauthorized keys PASS. Supabase Edge TypeScript **Deno check PASS**.
- Database inspection after initial deployment: Owner MFA factor verified, no Staff membership, 0 keys/challenges/registered phones/invitations, both bootstrap and Staff invite switches OFF; authenticated/anon cannot invoke private commit/issue RPCs.

## Verification boundaries

- Supabase project management returned deployed Edge status ACTIVE and `verify_jwt=true`, and database read-only audit confirmed permissions OFF.
- Automated tests use mocked Supabase RPC calls; **they are not a real hosted authenticated JWT/HTTP integration test**. A live unauthenticated HTTP smoke request from the current execution environment could **not be completed due to DNS/network resolution restrictions**. Do not assert it returned 401 until independently tested.
- No real physical Owner Android Keystore device has been paired. No Android staging APK has been created, signed or installed. No service-side Owner device activation handler exists; successful P-256 proof stores an inert pending public key only, not a phone enrolled in `public.registered_devices`.
- This infrastructure has not granted Staff role, business data read, historical corrections, live signed purchase, money transfer, or auto sync.

## Mandatory next steps

1. Connect a separately isolated **Staging Android applicationId/host + Owner Keystore key** without any Production host fallback; pass `scripts/verify_staging_isolation.py --mode enforce`.
2. Smoke-test actual hosted Edge function with bad/missing/expired/AAL1 and fresh AAL2 JWTs (no passwords or bearer tokens logged), with DB gate left disabled.
3. Build reviewed Owner key activation protocol with actual challenge proof and independent approval/revoke mechanism; do not accept untrusted client device UUID as hardware attestation.
4. Integrate Staff invitation/acceptance, Staff signed-key approval, read-only cutover, writer lease, device revoke/replacement and same-day Colombo edits vs Owner-only older days; test on two physical phones.
5. Verify Room/Cloud UUID reconciliation, cash totals, kitchen PDF, invoice photos and offline retry before publishing any APK.

Read-only SQL gate: `supabase/staging/verify_qa_owner_edge_locked_READ_ONLY.sql`.

**Strict NO-GO:** do not flip private switches, grant client SQL EXECUTE, merge to Production, install over Production owner app, or send a live Staff APK while untested.
