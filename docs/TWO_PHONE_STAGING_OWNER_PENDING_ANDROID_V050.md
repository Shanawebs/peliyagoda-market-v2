# Market Pro Owner PENDING Registration — QA v0.5.0

**Scope:** `qqiheexpbuzohllugxrk` isolated QA only.
**Production:** `lkoptqhodgjeuwwfihfr` and the existing Owner Android app remain unchanged.

## Deployed staged services
- Added 3 private RLS-protected tables: `qa_owner_pending_gate`, `qa_owner_pending_challenges`, `qa_owner_pending_devices`.
- A pending device is forever `status=pending`, `enabled=false`, `can_write=false`, `hardware_attestation_verified=false` under database CHECK constraints. No `public.registered_devices` insertion, Staff membership, data writes or activation possible through this flow.
- 3 service_role-only RPCs: `marketpro_qa_service_pending_owner_issue`, `marketpro_qa_service_pending_owner_material`, `marketpro_qa_service_pending_owner_commit`. Authenticated and anonymous users cannot execute them directly.
- Fresh Owner password+TOTP/AAL2 and live session, explicit confirmation, 6 challenges/day cap, 60s expiry, nonce rotation and atomic one-time commit.
- Separate `marketpro-owner-pending-registration-v1` signing domain to prevent replay of earlier Owner QA proof transcripts.
- Edge `marketpro-qa-owner-bootstrap` version 5, `verify_jwt=true`. Strict `pending_issue` and `pending_verify` intents and no other pending privileges.

## Production and QA controls
- GitHub Actions `40363822618`: **5/5 jobs SUCCESS**. PG17 replay/session/device checks, actual ECDSA P256 signatures with negative test cases, Deno TypeScript, production freeze, Gradle/Kotlin Android unit tests, valid debug signing, package and manifest checks.
- Only the **PENDING REQUEST INTAKE GATE** was enabled in Staging after CI; Owner/Staff activation gates remain OFF. Live read-back immediately after opening intake: **0 pending requests, 0 registered phones, 0 Staff users/invitations, 0 activated writers**.
- This is not a full E2E physical-device result. The pending request must still be tested using the QA APK below.

## Exact direct APK and test steps
- Direct APK: https://github.com/Shanawebs/peliyagoda-market-v2/releases/download/marketpro-staging-owner-pending-v0.5.0/MarketPro-Staging-OwnerPending-v0.5.0-debug.apk
- App ID: `lk.peliyagoda.marketpro.staging.pendingowner`, separate from production and prior Staging test apps.
- Install; sign in with **STAGING Owner password only**, verify fresh 6-digit Authenticator TOTP, tap **Request Owner Registration (Pending)**, read the explicit confirmation and tap **Submit Pending Request** within 120 seconds.
- Expected result: `PASS ✓ Owner Phone Request: PENDING`, `NOT activated • No Staff Access • No Purchase Sync`.
- **Never share OTP, login password, raw JWT, private key, nonce or signature.** A sanitized result screenshot is enough.
- A stable UUID is stored privately in no-backup app storage and bound to the app's AndroidKeyStore P256 key; this is an **app install identifier, not a verified hardware device identity**. Clearing data/uninstalling this debugging package destroys its key/ID and can strand pending QA records. Do not uninstall if a pending record was created.
- This debug key is not a production signing identity and is not suitable for production enrollment/cutover.

## Next security gates
1. After physical PASS, verify the corresponding pending row and `registered_devices=0` in Staging using a read-only SQL audit.
2. Independently verify hardware attestation chain, revocation and Android security level (or documented risk-based fallback) before any future activation.
3. Define a separate first-Owner activation/revocation flow with additional approval, server-enforced writer constraints, and signed device-authenticated operations.
4. Invite the Staff user under their own actual email (QA dotted aliases share a single mailbox and are NOT independent Staff credentials), approve/revoke their own phone, enforce Asia/Colombo current-day Staff corrections / Owner-only previous days, then test two phones and offline sync.
5. Production APK and data remain frozen until back-up, rollback, and two-phone end-to-end tests.

**No production cutover, finance API or Staff writes have been enabled.**
