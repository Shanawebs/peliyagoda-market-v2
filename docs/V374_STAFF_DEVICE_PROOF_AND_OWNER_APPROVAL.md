# Market Pro V3.7.4 — Staff Phone Device Proof and Owner Write Approval

**2026-10-24, REVIEW ONLY / OFFLINE SECURITY PREVIEW.** New Android code, policy tests and CI only; zero live Supabase migrations, API deployments, enrolled Staff users or signed Staff APKs.

## Implemented

1. `StaffDeviceProofPolicy.kt` defines a short-lived, domain-separated P-256 ECDSA challenge transcript bound to *workspace, Staff Auth user, registered device UUID, verified session UUID, challenge UUID, cryptographically random 256-bit server nonce, operation purpose, and validity timestamps*. Rejects future, expired, malformed, wrong-user/device/session/purpose, nonce and overly long (>120 s) challenges. JVM unit tests sign with a generated EC private key, verify public key, and reject tampering/replay across fields.
2. `StaffDeviceProofKeyStore.kt` is a dormant AndroidKeyStore adapter for a nonexportable per-install Staff P-256 private key. Only the public SPKI and fingerprint can leave the device, while signing occurs in Keystore. Reports `KeyInfo.isInsideSecureHardware` **accurately**; software-backed results are possible and not misrepresented as attested. No private key or JWT is serialized.
3. `OwnerStaffWriterReviewPolicy.kt` requires an exact Owner/Staff/device target match, a separate checkbox review and a typed `APPROVE`, `PAUSE` or `REVOKE` phrase. Every action has independent BuildConfig server gates and cannot target Owner or a changed device.
4. `OwnerStaffWriterApprovalPanel.kt` displays a two-step intent confirmation. It uses the already existing server-authenticated `ownerApproveStaffWriter`, `ownerDisableStaffWriter`, and `OwnerTeamPhoneTransferClient.disableOldPhone`. **Pause Writes ≠ Full Revoke**; the UI warns that lost phones need full server device revocation and previously downloaded/offline photos cannot be remotely erased. Stale/changed target cancels the action. The UI only reports success if its server RPC acknowledged it.
5. The original Owner login and Staff one-time pairing flow are not changed, and this panel remains disabled with all preview flags false, manifest INTERNET removed, and separate preview package name. Staff Financial Writer activation still requires an authenticated Owner action against the audited server, never a local flag.

## Explicit limitations (stop before production)

- **Key signing is NOT yet connected to any server challenge or key registration API.** A phone-generated challenge would not provide anti-replay. The backend must issue a unique random nonce, save a short TTL/one-time state, bind to the JWT `auth.sessions.id` and expected registered device/user/workspace, verify ECDSA P-256 SHA-256 using previously Owner-approved public SPKI, atomically consume nonce, reject old keys/device, and audit attempts. A client-provided `hardwareBacked` bool is *not* server-trusted hardware attestation. Consider Play Integrity/Android Key Attestation for actual hardware identity with fraud assessment; never require StrongBox on every 4GB RAM phone.
- AndroidKeyStore key presence and proof-of-possession prevent use of only copied Device UUID/JWT once the server validates the proof; without that server verification, the **existing V3.7.2 session guard** is still the only check. A fully compromised/rooted phone or stolen bearer token can still pose risk; device signing reduces exposure only after complete verifier deployment.
- Owner `APPROVE` is explicit user confirmation, not additional server-side authentication. Owner authenticated session and Owner device check must remain authoritative. Further step-up verification (Owner PIN/biometric and/or re-auth) should be considered for high-impact role changes.
- The private SQL functions V3.5.2–V3.7.2 are **review-only**, real PostgREST signed JWT and Staff RLS/direct REST hardening are not deployed, and `other_expenses` is missing from Cloud data. These must be implemented and verified before enabling Staff login or writing.
- Offline data on retired Staff Phone and invoice photos remain local and can be lost; full phone disable does not remotely wipe anything. Room backup / outbox reconciliation and two physical Android QA remain release blockers.
- The old Owner Phone v3.3.7 writer, live Dashboard, production financial/catalog rows and 1-owner-writer unique index are untouched. Preview version caption is intentionally still 3.5.0 because no V3.7.4 release was shipped.

## Readiness gate

CI must pass Kotlin/Android build + full unit tests, original V372 PostgreSQL 17 session regression, and unchanged preview/database/dashboard source checks. These tests prove offline code safety only, not live server authorization or physical-phone correctness.
