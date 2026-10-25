# Market Pro — Real Supabase Owner Authenticator MFA Client

**2026-10-25. REVIEW-ONLY BRANCH. Not a live deployment or final two-phone APK.**

## Added in this development slice

The existing future Owner/Staff Team pairing screen now contains a feature-gated **Owner Authenticator setup/verification** panel. It uses documented Supabase GoTrue endpoints directly through the same encrypted Keystore-protected CloudOwnerAuth client that performs Owner password sign-in.

1. Use Owner's enrolled verified Supabase Auth account, not the Staff account. Confirm it via GET /auth/v1/user.
2. Discover verified Authenticator factors via the server's /auth/v1/user factors array; ignore unverified, phone and unrelated factor types.
3. On first setup, POST /auth/v1/factors using factor_type=totp. Render the one-time secret in Owner-only volatile Compose memory for manual setup in an Authenticator app. FLAG_SECURE temporarily prevents screenshots and Recent Apps preview while the seed is visible.
4. POST /auth/v1/factors/{factorId}/challenge, then POST /auth/v1/factors/{factorId}/verify with the six-digit code entered by the real Owner. Never write the code, setup secret or factor response to preferences or logs. Canceling setup can DELETE only a confirmed *unverified* matching factor, never a verified/backup factor.
5. On a genuine Supabase success, verify response tokens are present. Locally check the returned JWT's subject, AAL2, valid session ID and totp AMR as a quick sanity check, then re-fetch /auth/v1/user with the **new JWT** to independently confirm Owner account identity before persisting the rotated access+refresh pair using AES256-GCM Android Keystore encryption.
6. Shared process-wide Owner Auth mutex serializes current-account refresh, sign-in and TOTP upgrade across the Owner auth client instances so an older AAL1 refresh does not overwrite the newly verified AAL2 encrypted session.
7. On Staff Public Key approval, the Owner RPC client reads the updated encrypted Auth session. The existing server-side `marketpro_owner_approve_staff_key_mfa` STILL independently checks Owner enrolled device, currently live auth.sessions, AAL2 and Authenticator-TOTP AMR timestamp within 300 seconds of **PostgreSQL server time**. Local decoded JWT content alone is NOT trusted for authorization.

## Security controls and limitations

- The new BuildConfig flag MARKETPRO_OWNER_TOTP_MFA_AUTH_DEPLOYED is **false** by default. Previous Team pull, Owner key approval, Staff login/write and outbox auto-upload flags remain false. No INTERNET in Android local preview.
- A locally verified MFA token does NOT approve Staff public key, does NOT activate phone access, does NOT grant Writer lease and does NOT cause any expense write.
- Owner identity profile, bearer JWT, TOTP password/code and secret are never printed to logs. UI locally displays setup seed only until confirmation/cancel; ensure the Authenticator account is backed up securely separately.
- Enrollment and verification require Supabase MFA configuration. AAL2 login after signup may still depend on real Android device auth/account environment and stage deployments. No real production credentials were used in code tests.
- Security-relevant APIs were verified against current Supabase Auth official docs: https://supabase.com/docs/guides/auth/auth-mfa/totp and https://supabase.com/docs/guides/auth/auth-mfa.

## CI coverage

Production freeze protects the existing Owner Room/Android manifest and financial Supabase production migrations. Android `:app:testDebugUnitTest` compiles CloudOwnerAuth MFA client, Compose Owner panel, session upgrade, factor policy unit tests and previous signature finance code. Node authentic ECDSA signature, Deno authenticated Edge and local throwaway PostgreSQL17 MFA session guards all regress.

## Still blocked for Live

Independent actual two-phone Supabase Auth staging tests including TOTP enrollment, AAL2 refresh, device re-pairing and key approvals; signed Android deploy configuration and physical device screens; Staff signed finance outbox upload activation and conflict tests; staff direct REST RLS cutover audit; Owner Room Cloud UUID reconciliation; Cloud dashboard financial totals and encrypted full backup/restore including private invoice photos. DO NOT run review-only SQL or install the preview APK as a replacement for existing Market Pro.
