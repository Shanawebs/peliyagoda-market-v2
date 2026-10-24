# Market Pro — Owner-Approved Staff Device Key Enrollment

**24 October 2026 — Draft for review, not deployed.** Owner v3.3.7 remains the sole live Cloud writer.

## Actual new code

- `marketpro-staff-key-enrollment` Edge endpoint accepts a valid signed Staff JWT only after invite acceptance, active Auth Session, Owner read-only activation, registered Staff device, restrictive rollout gates. It rejects SPKI that cannot be imported as valid P-256; SQL verifies membership/session independently.
- Staff Android phone generates/loads an AndroidKeyStore P-256 non-exportable signing key, sends only the **public SPKI**, receives a **60-second one-time `pair` challenge**, confirms the Server SHA256 fingerprint is an exact match, signs the domain-separated canonical Android transcript, and submits the DER P-256 signature to Edge.
- Edge verifies actual ECDSA signature against that unapproved SPKI, then a **service_role-only** RPC marks proof verified (one time). A key proof does not create permissions or authorize writes.
- Owner can inspect the proof-verified pending fingerprint via a separate authenticated Owner RPC. To approve, Owner must compare with the fingerprint displayed on the Staff Phone, check a confirmation control and explicitly type **APPROVE KEY**; Owner endpoint re-verifies the Staff device/session, signed proof and exact pending fingerprint under a workspace transaction lock before inserting/rotating the approved key. It returns **staff_writer_enabled=false**. Staff cannot approve its own key.
- Lost phone Owner revocation disables Staff device, invalidates staff JWT session binding, Owner-approved key, Staff writer lease, accepted invite and pending one-use challenges atomically; Owner's legacy writer remains unchanged. This does not remotely wipe an offline lost phone or recover unsynced data.
- Kotlin includes pending-key Staff HTTPS client, Owner fingerprint-review/approve/revoke RPC clients, and pure unit tests. All are guarded by `MARKETPRO_STAFF_KEY_ENROLLMENT_RPC_DEPLOYED=false`, and pairing/login/network are also disabled in current preview.
- PG17 integration verifies absent proof cannot be approved, Staff cannot self-approve, wrong fingerprint or missing typed confirmation rejected, unapproved key never activates, approved read-only key does not become Staff writer, challenge replay rejected and revoked phone cannot register/prove. JS tests verify real Android-compatible ECDSA P-256 `pair` transcript, swapped public key/session/device and expiry; Deno Edge checked.

## Important caveats / go-live blockers

1. **No production server changes.** All SQL resides under `supabase/proposals/` and is REVIEW ONLY. The Edge endpoint is source code, not deployed. Supabase live private Staff table/roles/RLS, approved key registration and service RPCs are not active.
2. Android Keystore P-256 private key is not exportable but **hardware-backed / StrongBox attestation is not established**; the UI must never claim a signed assertion proves a trusted physical handset. Additional Play Integrity/Key Attestation assurance is a separate security control.
3. Typed confirmation is a *human comparison*, not a cryptographic owner-signed device authorization; Owner's account/device is authenticated on the server. A lost phone with unsynced local records needs offline backup/reconciliation; no remote wipe.
4. The pending pairing flow requires preexisting read-only Staff phone/session, so publish the complete, audited Team RLS migration and ensure the enrolled Staff account cannot directly enumerate other workspace's rows *before creating first Staff membership*. Review all direct PostgREST RPC execute grants and nested SECURITY DEFINER functions.
5. The reviewed atomic finance proof grant and Android HTTPS upload still must pass **real Supabase staging** signed-JWT two-physical-device end-to-end tests, including different-session token, device theft, key rotation, phone replacement and Sri Lanka business-date rollover.
6. Owner key approval UI is still a **client RPC adapter**, not a finished production Compose workflow. Do not enable the feature toggle or install as final APK until an explicit matching-fingerprint Owner screen and two-step confirmation are verified on devices.
7. Exact historic Item UUID mappings, Additional Purchase Lots and Cash Received/Other Expenses reconciliation, Dashboard equality, backups including actual invoice photo bytes and signed APK are still required.

**NO-GO** for production Staff Auto Sync/Final Signed APK yet. This review-only increment closes the secure key proof and Owner approval API gap, not the full two-phone project.
