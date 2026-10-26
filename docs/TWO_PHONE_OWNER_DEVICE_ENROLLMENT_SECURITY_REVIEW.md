# Market Pro two-phone: Owner device enrollment security review

**Date:** 2026-10-26
**Scope:** isolated Staging `qqiheexpbuzohllugxrk`; **review only** — not a deployment or grant. Production `lkoptqhodgjeuwwfihfr` must remain untouched.

## What the physical v0.4.3 result proves

The Owner's phone screenshot shows `PASS — Server verified REAL Phone P-256 Signature; One-time challenge consumed`. Staging SQL independently showed the latest trial consumed at 2026-10-26 16:15:46 UTC and exact 60-second issued/expires intervals. Password + TOTP `aal2` and the authenticated Edge status were previously tested on the physical phone.

**It proves:** the tested app could use the corresponding P-256 private key to sign a server challenge, and the server accepted one proof tied to the signed-in Owner/Auth session.

**It does NOT prove:** the phone is registered, the key is hardware-attested, device integrity verified, any Owner role's business permission has been granted, any Staff role approved, or synchronisation enabled. Do not treat `consumed_at IS NOT NULL` alone as proof: issuance intentionally consumes superseded challenges, too; only the independently verified Edge success response confirms signature verification.

## Current safety gates (must stay OFF during design)

- `public.registered_devices`: zero rows. No active Owner or Staff phone, no `can_write` lease.
- `marketpro_private.qa_owner_bootstrap_gate.enabled`: OFF.
- `marketpro_private.qa_invite_gate.enabled`: OFF.
- `marketpro_private.pending_team_enrollments`: empty.
- `marketpro_private.qa_owner_device_public_keys`: empty.
- No authenticated/anon ability to invoke service-role proof commit. No authenticated EXECUTE for dormant Owner bootstrap challenge issue.
- Production APK, Room records, cloud finance, web dashboard and automatic sync are NOT modified.

## Decision: separate FIRST-OWNER registration, not remote-proof replay

The v0.4.3 `proof_issue/proof_verify` endpoints are QA-only tests; their signed responses MUST NOT be used as an Owner device enrollment receipt. A new registration challenge must be bound to an authenticated Owner, an actual Auth session, fresh MFA (not merely enrolled TOTP), a **stable install/device record ID**, an independently checked public SPKI fingerprint, workspace, one-time nonce, and a short 60-second validity window. Store a UUID securely per installation; **the v0.4.3 test uses a newly generated device UUID for each attempt** and is not suitable for enrollment.

A dedicated staging Owner onboarding endpoint should:
1. Verify Owner membership, confirmed email, fresh `aal2` TOTP AMR and live `auth.sessions` membership; deny anonymous, Viewer and Staff.
2. Require separate **explicit Owner approval/confirmation** (not inferred from the proof button), bound to that same live session and challenged public key.
3. Issue and consume a nonce atomically, allowing one active challenge per workspace; retain no reusable proof.
4. Verify genuine P-256 DER signature against server-owned SPKI using a trusted server-side WebCrypto verifier, never client booleans or user-supplied Owner UUID.
5. Optionally verify **Android Key Attestation** with a fresh, dedicated attestation challenge, the certificate chain anchored in trusted roots, integrity/authorization properties and revocation information. `KeyInfo.isInsideSecureHardware` returned by the Android phone alone is **not remote attestation**. If hardware attestation is unavailable/unverified, report that fact, fail closed for policies requiring it, and provide an independently approved fallback appropriate to the deployment's risk.
6. Commit the proof and public key in a transaction with a uniqueness invariant: one approved Owner device per QA workspace for initial registration, conflict causes denial rather than overwrite.
7. Start with `registered_devices.enabled=false`, `can_write=false`; activate only under a **separate audited, staged approval transition**, if hardware/identity requirements have passed. Never grant Staff by activating Owner.
8. Establish signed per-operation server-side authorisation and revocation before any cloud writer becomes available. Revoke old device ID and all associated writer leases/tokens before replacing a phone.

## Staff Phone rules to preserve

- Staff phone enrollment requires its **own individual auth identity**, separate from Owner. The dotted and undotted Gmail addresses in staging resolve to one inbox: **test alias only, NOT a valid final Staff identity**.
- Staff device must be explicitly approved by an ACTIVE Owner device, using a server-bound signed action and rate-limited invitation; never self-approve or inherit Owner keys.
- Staff corrections for **Asia/Colombo's current business date** need no Owner review, can occur repeatedly. Only Owner can edit older business dates; Staff must be denied server-side even when offline Room data is edited or the device's local clock is changed.
- Replacing a Staff phone must first revoke old device at the authoritative server (block writes immediately and invalidate pending auth grants), then approve the new key. No lost/replaced Staff device should continue syncing.
- Financial writes require idempotent request IDs, replay rejection, server-side workspace/device/role checks, auditable correction history, and conflict resolution; never trust client-reported business date as authorisation truth.

## No-go checks before any Owner registration APK

- Explicitly verify `public.registered_devices` has zero rows; Owner/Staff gate and all invitation/activation grants disabled.
- Review v0.4.3 signing code and keep its **server-anchored monotonic time**; no relaxation of server-issued 60-second TTL or atomic replay prevention.
- Separate debug/test APK application ID; never install over Production app; no credentials or service-role secrets embedded.
- Unit/integration tests for AAL1/stale TOTP, wrong Owner or session, signature forgery, expired/superseded nonce, duplicate key/device, concurrent first-registration, session revocation, lost phone, Staff self-approval and old-date correction.
- Test two physical phones (Owner and actual separately controlled Staff identity) *before* enabling cloud data reads/writes.
- Read-only audit: `supabase/staging/verify_owner_enrollment_not_enabled_READ_ONLY.sql`. No live change is authorised by this review.

## Implementation sequence

1. Confirm hardware attestation availability and policy using the official Android docs and test real Owner device certificate chain handling. Attestation is not established by the successful P-256 signature alone.
2. Design the stable-device identifier and bind it to a fresh Owner registration challenge.
3. Add **dormant**, server-only registration proposal and replay/role/approval tests in disposable PostgreSQL 17; review all grants. Deploy to isolated staging only after the test suite passes.
4. Build a standalone Owner Registration **review** APK that clearly distinguishes **PENDING**, **APPROVED**, **REVOKED**; initial stage may only propose a device.
5. Implement Staff invitation/approval and revocation, then test all business-date and sync rules; switch on permissions incrementally and only with explicit QA.
6. Production migration/cutover only after backups, rollback plan and unchanged existing Owner app data have been verified.

**Reference:** Android's official Key Attestation documentation: https://developer.android.com/privacy-and-security/security-key-attestation — a certificate chain needs trusted server-side validation; public-key possession alone is not device attestation.
