# Market Pro Stable Device Identity and Secure Recovery (v4.3.3 QA)

## In-place versions (normal case)

- Every release must retain the exact Android package ID `lk.peliyagoda.marketpro.staging.fullapp`, the existing signing certificate, and a strictly increasing `versionCode`.
- Ship through Market Pro → Menu → Check For Updates. **Never uninstall** as an upgrade technique.
- `CloudDeviceIdentity` uses a random UUID persisted in app-private SharedPreferences. Android Keystore P-256 `marketpro_photo_p256_proof_v1` is non-exportable. Both survive same-signer in-place APK updates.
- No APK may include a shared/private photo-signing secret, hard-coded stable private key, device ID reuse by model name alone, or a bypass around the Owner MFA approval flow.

## Reinstall / phone replacement

Android may erase app-private preferences and Android Keystore entries on uninstall. Neither old credentials nor private photo keys should be restored from server or packaged in APK. **Identity cannot safely be proven by matching manufacturer/model.**

The approved v4.3.3 flow is:

1. Team signs in using its own authenticated Team account on a fresh install.
2. Team taps `Request This Phone`. A new UUID is registered **pending**; the Team app proves possession of a newly generated Android Keystore P-256 public key, also saved **pending**. Pending keys cannot access the Box photo gateway.
3. Owner selects the exact pending device UUID. Owner must have an authorized active Owner phone/session and complete a fresh TOTP AAL2 verification not older than five minutes.
4. Owner explicitly taps `Approve Phone + Photo Key` and acknowledges the warning that an old same-Team-account/same-label device will be revoked.
5. The `marketpro_qa_approve_team_phone_and_photo_v433` RPC transaction atomically approves the pending Team UUID and its pinned key. If necessary, it retires a former active same-user/same-label UUID (preserving its historical row, its purchase/photo FKs, and Box originals) and revokes its old photo key.
6. Team executes `Activate / Sync Two-Phone` to download cloud records. Confirm results on actual devices; HTTP 200 alone does not prove Room merge/display.
7. Owner replacement uses `Secure Re-Link Owner Phone` and fresh MFA. The app then registers a new Owner photo key during the same short MFA validity window.

## Safety rules

- Never auto-approve a new or changed key from a staff JWT, device label, hardware serial, Android ID, or package signature alone.
- The gateway requires an approved registered device, an approved pinned photo public key, valid session, signed request and replay nonce.
- All former registered-device and photo-key records remain for history; do not delete purchase, inventory, gallery, Box connection, or audit records to fix sign-in.
- Distinct Team phones may share the same Team account up to the server-defined concurrent device limit. An accidental duplicate model label is not a trusted physical identifier.
- A photo-key rotation after uninstall requires explicit Owner approval. This is a **security guarantee**, not a bug to bypass.
- v4.3.3 inherits the Samsung crash-recovery safety mitigation; automatic photo upload remains paused until crash/root cause and real-device QA are complete.

## Release checklist

- Verify stable app ID and signer against installed v4.3.0/v4.3.1/v4.3.2 signed APKs.
- Verify `versionCode` monotonicity and no uninstall instructions.
- Verify no unauthorized use of the new RPC (`anon` must lack execute rights).
- Verify fresh-MFA failure and missing pending key rejection, and same-label old-device retirement with test fixtures in QA; do not use real financial data as a test fixture.
- Verify phone and Photo Key approval in one Owner action; then verify Team Cloud Pull and date-indexed Gallery.
- Do not publish or change the update channel until QA checks pass.
