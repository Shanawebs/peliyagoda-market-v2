# Market Pro — QA Staging Owner Device Key Bootstrap / Safety Ledger

**Status:** STAGING-only private schema applied; real Owner phone enrollment NOT active.
**Target:** `qqiheexpbuzohllugxrk` only; working Production Supabase `lkoptqhodgjeuwwfihfr`, Android Owner installation and Cloud Dashboard NOT changed.

## What was completed on 2026-10-26

- Implemented an independent P-256 ECDSA DER signature verifier for the **Owner's own Android Keystore public key**. This uses the already-audited DER→WebCrypto P1363 converter, a distinct `marketpro-owner-bootstrap-key-v1` 10-line canonical message, and an **up-to-60-second** challenge.
- Cryptographic binding includes staging workspace, verified Owner Auth UUID, new device UUID, Supabase live session UUID, server challenge UUID, cryptographically random future nonce, SHA-256(public SPKI) fingerprint, challenge issuance and expiry.
- Node 22 crypto tests sign using real EC P-256 keys; positive validation and mutated/wrong session/device/workspace/nonce/SPKI/fingerprint/expiry all rejected. Existing Staff signature tests also rerun.
- Prepared fully **private and inaccessible Owner onboarding state** in `marketpro_private`: inactive `qa_owner_bootstrap_gate`, empty `qa_owner_bootstrap_challenges`, empty `qa_owner_device_public_keys`. All three use RLS ON, no policies and no client grants. Database invariant prevents key record from asserting `activated=true` or `attestation_verified=true`; neither phone enrollment nor authorization can occur.
- Hosted Staging migration `marketpro_staging_owner_bootstrap_key_vault_inert`, applied as version **20261026040419**.
- Source contract tests prohibit new public signup/writer grants, production project literals, active Owner device/writer inserts, and server grants in the schema-only migration.
- Hosted follow-up query `supabase/staging/verify_qa_owner_bootstrap_vault_READ_ONLY.sql`: no keys, no challenge, no registered phones, no Staff invitations, disabled gates, authenticated/anonymous cannot read/write private vault.

## Important limitations

**NOT an APK or working pairing flow yet.** The signed verifier has not been wired to an authenticated HTTPS Edge Function and server session-aware issue/consume/approve RPC. A valid ECDSA signature alone DOES NOT prevent nonce replay. To be secure, the server must atomically consume one nonce inside a transaction, revalidate active `auth.sessions`, recent Owner TOTP AAL2, account/workspace, and prevent second/revoked Owner phone registration. Android must create a unique Owner P-256 Keystore key in a genuinely isolated staging application ID with a separate staging URL; no Production project fallback. A UUID or client-supplied `hardwareBacked` flag is not proof of hardware attestation. Existing Owner Production Android has NOT been touched.

Next: design and test dormant server-side challenge issue/verify/consume protocols with Edge verification and the separate Owner Android Keystore client; independent physical-device checks and full Owner→Staff authenticated pairing before any Staff writer or Cloud Sync activation.

**Do not activate `qa_owner_bootstrap_gate`, grant new public RPCs, or install a preview APK as a Production replacement.**
