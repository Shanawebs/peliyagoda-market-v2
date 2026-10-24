# Market Pro V3.7.5 — Device-Proof Challenge Server (REVIEW ONLY)

**2026-10-24 — GitHub draft only. Do NOT deploy, migrate, merge into live Owner app or create a Staff account yet.**

## New components

- `supabase/proposals/v375_staff_device_challenge_ledger_REVIEW_ONLY.sql`: private per-workspace *manually preapproved* Staff public-key registry (default `approved=false`) and 60-second cryptographically random challenge ledger, authenticated Staff challenge-issue RPC, service-role-only proof lookup/consume RPCs. Staff issuance requires accepted/activated Owner invitation, matching `auth.sessions` JWT session binding, registered active read-only Staff phone, private rollout gates, and matching independently approved public key. For a `write` challenge, Owner's already-enabled private Staff write lease is also required.
- `supabase/functions/_shared/device_proof.mjs`: strict canonical ASN.1 DER -> IEEE P1363 conversion for Android's `SHA256withECDSA`, with WebCrypto P-256/SHA-256 signature check over V3.7.4's `marketpro-device-proof-v1` challenge transcript. It verifies all bound fields and timestamps, rejects malformed/nonminimal encodings, substituted keys and signatures.
- `supabase/functions/marketpro-staff-device-proof/index.ts`: Deno Edge Function prototype, authenticates real Staff JWTs with Supabase's `withSupabase({auth:'user'})` wrapper. It uses the caller-scoped client **only for challenge issuance**, and a server-held admin client **only for two service-role-limited challenge lookup/consume RPCs**. No client-supplied Staff user ID or session ID is trusted. The privileged RPC must never be exposed to anonymous/authenticated roles. An Android app cannot call `service_role` RPCs directly.
- `supabase/tests/v375_staff_device_challenge_pg17_local_only.sql`: live fixture modeled only in a disposable PostgreSQL 17 database. It reuses V370–V372 comprehensive Staff protections, tests initial denied key, approved key issuance, superseded and consumed nonces, mismatched JWT Auth session, current read-only device, Owner lease emergency stop, removed phone, revoked public key, signed-out JWT, and private/service-role ACL.
- `tests/v375_device_proof_crypto.test.mjs`: Node 22 WebCrypto integration tests generate an actual P-256 signing key + Android-compatible DER signature, verify against imported SPKI and reject tampering, replay across contexts and bad DER.

## Threat model and important limitations

1. **No approved key self-registration exists.** An authenticated Staff user cannot insert their own `staff_device_public_keys` row. The only positive SQL test row is seeded by a simulated privileged administrator. A secure Owner-approved key enrollment and attestation workflow remains a blocker for real devices.
2. This proof ledger records one successful signature verification and **does NOT yet gate v2 financial writes or cloud pulls**. The proof-consume RPC explicitly returns `business_write_permission_granted=false`. Do not interpret a successful proof as ability to write; attach a fresh proof to each sensitive operation in a reviewed atomic backend design in the next stage.
3. No full hardware attestation. Android Keystore reports `hardwareBacked` from the device, but a client-provided boolean is **not** attested or trusted by the backend. Some budget phones have only software-backed Keystore; strong TEE/Play Integrity device attestation and rooted-device response need separate design.
4. A stolen active Staff bearer JWT may still be replayable to unprotected endpoints. Until every applicable read/write path requires a consumed, session-bound, key-bound nonce signed by a non-exportable key, the device proof is not enforced. Earlier SQL guards do verify current membership/session but cannot distinguish stolen complete bearer tokens.
5. Server function `material` and `consume` execute with elevated permissions but only `service_role` is granted. Their trusted caller is **solely** the reviewed Edge Function and must be subject to internal audit. A compromised Edge runtime service key invalidates this boundary. Deployment security reviewers must confirm new/legacy Supabase API key compatibility and actual `withSupabase` runtime behavior.
6. Server times are converted to signed milliseconds to match Java/Kotlin transcript exactly. `nowMillis` freshness is checked again on the Edge server. SQL locks the workspace on issuance/consumption and revalidates the live Staff session, status and key. A challenge expires in 60 seconds, the previous outstanding challenge for that phone is invalidated, and the final consume is one-use.
7. No live migrations, no Staff RPC deployment, no active Staff writer, no signed APK, no dashboard changes. Backup production, audit existing broad Staff SELECT RLS, enforce same-day Colombo-date and revision rules, build secure device key enrollment, support other expenses in Cloud, and test two physical Android phones before any live cutover. Invoice photos remain local; old phone unsynced entries cannot be recovered without backup.

## Security references

Supabase authenticated Edge Functions: https://supabase.com/docs/guides/functions/auth

Supabase user Auth sessions: https://supabase.com/docs/guides/auth/sessions

The V3.7.5 tests are **disposable mock-DB/protocol tests only**, not end-to-end JWT/API tests on a real Supabase deployment.
