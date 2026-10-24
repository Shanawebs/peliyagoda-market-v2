# Two-Phone Android Signed Finance Transport — Review Only

**2026-10-24 — Android/SQL/Edge protocol development, NOT deployed.**

## Code in this branch

- `StaffSignedFinanceHttpTransport` implements a **single fixed-origin HTTPS** `/functions/v1/marketpro-staff-finance` client, using only the Staff Auth user's short-lived bearer JWT and the public publishable project key; **NO service_role key** in the Android APK.
- `issue_finance`: sends strictly whitelisted, immutable financial payload and exact outbox operation/entity UUID, revision and registered device. The Edge backend invokes the JWT-authenticated challenge issuer with the Staff current Auth session.
- SQL issuer now returns `key_fingerprint_sha256` for the **currently Owner-approved** per-device public P-256 SPKI, in addition to the existing 60-second one-use challenge. Android compares this with the local AndroidKeyStore non-exportable key's fingerprint *before signing*. Rotated/unapproved keys fail closed. No self-approval or false hardware attestation claim is made.
- Android checks signed JWT payload claims locally for consistency only (JWT signature validation and `auth.sessions` are strictly server-side), and requires exact server Staff user/workspace/physical Device UUID/JWT session ID, write purpose, valid challenge timestamp and fingerprint.
- `submit_finance`: signs the v375 canonical challenge transcript with AndroidKeyStore `SHA256withECDSA` P-256 (DER/base64url) and submits the **same immutable JSON body** with one-use challenge and signature. Backend WebCrypto verifies it and calls the one-transaction SQL finance/proof RPC; operation, revision, and server ACK must match before Room outbox acknowledgement.
- Fixed origin HTTPS/no redirects, strict bounded 8KB requests and 32KB responses, short timeouts, no bearer/signature/nonce logging, no file paths/photos, no token in URL.
- `StaffFinancialOutboxCoordinator` now switches to this transport only if **all six release gates** are enabled. Current `MARKETPRO_LOCAL_PREVIEW=true`, Staff Login/Pull/Finance RPC/Device Proof/Auto Upload flags all **false**, INTERNET permission removed. Default transport remains `UnavailableStaffWriteTransport` in this preview.
- Kotlin JUnit covers wrong JWT/session/phone/workspace/challenge purpose/key rotation, expiry and strict server financial ACK. PostgreSQL test checks returned Owner-approved fingerprint and retains nonce replay, Staff today-only, stale revision transaction rollback and direct v2 RPC bypass denial. Edge Deno/WebCrypto remain verified.

## Release blockers — important

1. **No production Cloud rollout** or real device E2E test. No Staff public key is registered as Owner-approved in Live Supabase, which still has only the older Owner RPC. No live financial migration or Staff permissions were applied.
2. Consolidate and independently review ALL pending Team SQL, RLS, Auth roles, direct REST read restrictions, Storage/permissions and key/nonce endpoints. At go-live, old direct financial v2 RPC execute rights MUST be revoked in the same migration as proof-bound finance.
3. Owner-approved key registration and hardware attestation/identity transfer are not yet available, and phone software keystore may not be hardware-backed; test on actual devices. An installed phone with an unapproved signing key correctly cannot upload.
4. Exact original 184 master item Cloud UUID links and local Room records must be reconciled without wiping Owner data; staff-paired account, Staff revocation/replacement, offline/outbox business date crossing Asia/Colombo midnight and genuine read/write permissions must be verified on a **staging Supabase project** using **two physical Android phones**.
5. Dashboard totals, kitchen requisitions, purchases/additions, cash and all four Other Expenses amounts must match before a signed final APK and direct ChatGPT download link is released. Invoice photo bytes remain **local-only**; a JSON backup alone does not contain them.

**GO/NO-GO: NO-GO for a public Staff APK or production cutover.** This is a compiled protocol preview, not a promise that live Cloud Sync is already active.
