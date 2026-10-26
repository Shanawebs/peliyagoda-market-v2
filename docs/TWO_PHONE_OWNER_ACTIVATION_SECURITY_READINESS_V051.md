# Market Pro two-phone QA — Owner Activation Readiness (review only)

**2026-10-26 | Staging only**: `qqiheexpbuzohllugxrk`.
**DO NOT DEPLOY ACTIVATION** to QA or Production from this document. Existing Production `lkoptqhodgjeuwwfihfr` unchanged.

## Grounded evidence

- Real Android v0.4.3 Owner phone completed P-256 remote signature / one-time challenge PASS.
- Real Android v0.5.0 Owner phone completed explicit `Request Owner Registration (Pending)` consent and server receipt PASS.
- Hosted Staging read-only SQL: exactly **one** Owner pending proposal, one cryptographically verified and consumed challenge, 60-second nonce, `status=pending`, `enabled=false`, `can_write=false`, `hardware_attestation_verified=false`. `public.registered_devices=0`; Staff members 0; Staff and Owner activation gates OFF.
- **Pending intake turned OFF again** immediately after successful submission; this did not delete pending record or enable any business access.
- App's per-install ID is a private UUID **not** a hardware identifier; the P-256 proof establishes possession of the corresponding signing key, **not** hardware attestation or authority to write.
- Existing v0.5.0 key is generated with `KeyGenParameterSpec` **without** `setAttestationChallenge`. Consequently the pending proof must NOT be labeled hardware attested retroactively.

## Required review before first Owner activation

1. **Separate attestation / risk policy.** For hardware attestation, issue a fresh server nonce for a *new* AndroidKeyStore signing key created using `setAttestationChallenge`. A separate QA-only app retrieves `KeyStore.getCertificateChain()` and transmits the chain over TLS. Keep private keys non-exportable, never show chain/keys in screenshots.
2. **Server-side verification**, not on the phone: chain signatures, Google-approved trust root (including the root newly used from Feb 2026), revocation status, challenge binding to this Staging Owner and pending app/device record, leaf SPKI match, key security level `TrustedEnvironment` or `StrongBox`, key purposes/digests and package/application identity where available. Use a well-maintained attestation verification library rather than accepting client-supplied `hardware_backed=true` booleans. If the hardware cannot attest, record `unverified`; any exception must be a separately authorized risk-based fallback, not silently treated as verified. Official guide: https://developer.android.com/privacy-and-security/security-key-attestation.
3. **Key continuity:** because the existing pending P-256 key had no attestation challenge, require a new fresh Owner AAL2 session AND old pending key signature authorizing rotation/binding of the new attested public key for the same pending record. Reject changes to workspace, Owner, pending device UUID or stale sessions. No client-triggerable unrestricted key overwrite.
4. **Human approval:** an explicit `Approve Owner Device` confirmation shown with readable device label and neutral fingerprint hint, challenge-bound to the signed-in Owner AAL2 session and the independently verified attested key (or acknowledged fallback). Fresh reauthentication / TOTP required. A success proof test is *not* consent to activation.
5. **Atomic inactive-to-active transition:** build a dedicated service-only PostgreSQL function with session/Owner checks and a single transaction that compares the pending record, signed approval, attestation outcome and nonce. Unique Owner key/device constraints and transactional locks must handle concurrent requests without approving two Owner devices. Activation should be a separately auditable transition; do **not** reuse the `pending_verify` endpoint.
6. **Device authorization of every operation:** until fully implemented and tested, neither `enabled` nor `can_write` grants by themselves may permit finance/Cloud Sync. Signed per-operation requests must bind workspace, Owner, device ID, request body hash, nonce/idempotency key, server-validated Auth session, minimum role and authoritative device enabled/non-revoked state.
7. **Revocation and replacement:** disabling/revoking Owner or Staff device must block all subsequent server writes immediately, including from a previously issued JWT; check authoritative database state for every write, invalidate pending challenges and idempotent leases. Lost/replaced device re-enrollment requires fresh proof, explicit Owner approval and a clear replacement path.
8. **Offline conflict rules:** apply `Asia/Colombo` business date on server. Staff can correct **current Sri Lanka business day** repeatedly without Owner review; any earlier date is immutable from Staff devices and editable by active Owner only. Offline queued edits cannot bypass authorization on reconnection.

## No-Go test matrix

- Unverified attestation or invalid chain/root/CRL/challenge; wrong key; spoofed device fingerprint; unsigned Owner approval
- AAL1, stale/missing MFA, inactive/revoked Owner Auth session
- Expired/superseded/replayed nonce or duplicate/concurrent enrollments
- Staff registering/approving itself; dotted/undotted Gmail test aliases being mistaken for independent ownership
- Owner phone uninstall or app-data clearance and attempted unauthorized key takeover
- Old Staff phone attempting writes after server revocation; Team phone replacement
- Staff editing prior business date by changing phone clock or spoofing payload date; replay and offline sync conflicts
- Business writers enabled before full registration security, rollback/backup and two-physical-phone E2E tests

## Current action/status

**Completed**: physical Owner Pending QA record verified, Pending intake closed; Owner/Staff activation OFF.
**Next development**: separate **attestation feasibility probe** on the real Owner handset, then safely propose activation in disposable PG17; all activation flags OFF until real-device and server verification tests pass. This doc authorizes no deployment.

For an Android handset lacking Google hardware attestation, do not claim the device is compromised; report that hardware validation was unavailable and evaluate a separately approved security fallback.
