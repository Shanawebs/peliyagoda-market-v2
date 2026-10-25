# Market Pro Two-Phone — Owner Key Approval UI with Fresh MFA

Status: REVIEW-ONLY on 2026-10-25, not deployed. Owner v1 phone and Supabase production financial records remain unchanged.

## New security step-up

Staff key approval is more sensitive than simply reading a pending device fingerprint. The previous review-only public.maketpro_owner_approve_staff_key (actual identifier: marketpro_owner_approve_staff_key) is now revoked from every Android-accessible PostgREST role. The replacement is public.marketpro_owner_approve_staff_key_mfa.

After confirming existing enrolled Owner JWT and registered Owner device, the server requires (1) a correctly formatted session_id claim (2) exactly matching live auth.sessions entry for that Owner, not a revoked/signed-out session (3) a validated Supabase MFA assurance claim aal=aal2 (4) an authenticator/TOTP entry in signed JWT amr where the timestamp lies within server NOW - 300 seconds and NOW + 15 seconds. A password-only login, stale factor, forged session, wrong role and an expired user session are all denied. The helper is called before and again under the workspace advisory lock, then existing exact fingerprint/Staff pending candidate validations remain mandatory.

Supabase Auth official docs: https://supabase.com/docs/guides/auth/auth-mfa (amr entries include method and timestamp); https://supabase.com/docs/guides/auth/jwt-fields.

## UI integrated into existing Team pairing screen

- Staff pairing UI now also renders a feature-gated Staff Phone Public Key proof button. After the server verifies possession, this physical phone displays the full 64-character SHA256 fingerprint for Owner comparison; no private EC key export, no writer grant.
- Owner pairing UI includes a feature-gated pending public-key review. The Owner must type the full SHA256 fingerprint read from the Staff physical phone, acknowledge that physical cross-check and type APPROVE KEY. It does not silently approve by copying the server candidate text. Changing Staff UUID clears the staged intent.
- Submission fetches the current server candidate again and compares challenge, fingerprint, Staff user and phone before invoking the MFA-only Owner approval RPC. Receipt requires owner_mfa_verified=true, key_approval_only=true and writer_approved=false.
- All preview variants keep the Staff key enrollment and related Staff Cloud flags OFF, and Android permission INTERNET removed. Buttons cannot perform external actions in offline preview.

## CI tests

- Disposable PostgreSQL 17: actual session, no AAL, AAL1, missing/future/stale/malformed TOTP AMR, forged session ID, correct fresh AAL2/TOTP, old Owner key-approval function EXECUTE denied to authenticated, no implicit Staff writer lease, post-logout denial.
- Deno authenticated Staff key Edge and Node ECDSA P256 candidate key proof.
- Android full Kotlin compile/unit suite validates Owner typed fingerprint, registered device, stale candidate, Staff key challenge and new Compose panels.
- Production freeze checks no existing production Android manifest, feature flags, SQL migrations or read-only Cloud Dashboard changes.

## Still required before production

**The Android Owner Authenticator/TOTP factor enrollment, real Auth factor challenge-and-verify UI and refreshed AAL2 access token are NOT yet wired to this approval screen.** The panel instructs recent TOTP MFA and the PostgreSQL backend refuses approvals without it; no fallback is allowed. A current Owner AAL1 token will fail closed. This new screen does not invent MFA success.

Also outstanding: real staging Auth JWTs and physical two-phone trial, audited RLS and production enrollment grants, Staff signed financial transport activation in Worker, synchronized catalog UUID reconciliation, two-way Cloud finance+Dashboard reconciliation, backups of purchases and invoice photos, offline midnight conflicts, replace/revoke and final downloadable signed APK.

Do not deploy review-only SQL, enable Staff write flags or replace the existing Owner installation merely because mocked PG17 security tests pass.