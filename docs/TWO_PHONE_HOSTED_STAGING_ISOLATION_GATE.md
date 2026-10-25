# Market Pro Two-Phone — Hosted Staging Isolation Gate

**Review-only status, 25 October 2026.** No hosted staging project created, no deployment, no Android live switch, no production database modification.

## Why the current Android review build must NOT be configured against a new hosted stage merely by changing its URL

Today the cloud base URL and **existing Market Pro Supabase project ref** are embedded in `app/build.gradle.kts`; **multiple Android HTTP clients independently hardcode the existing hostname**. `CloudSyncConfig.OWNER_USER_ID` and `WORKSPACE_ID` contain real current-workspace UUIDs in source. Repointing just the URL or supplying a different publishable key cannot reliably isolate staging and may either send traffic to production or fail unexpectedly.

We therefore implemented `scripts/verify_staging_isolation.py`, a read-only preflight with two modes. `--mode audit` prints a JSON report of precisely which production literals and missing staging app configuration still block deployment. `--mode enforce` **exits code 2** unless every check passes. Today the real code is intentionally expected to FAIL the enforce gate, and CI verifies that it fails. Passing CI means we found and BLOCKED a dangerous staging setup, not that hosted staging is ready.

## Proposed staging manifest schema (NONSECRET)

```json
{
  "project_ref": "zzzzzzzzzzzzzzzzzzzz",
  "supabase_url": "https://zzzzzzzzzzzzzzzzzzzz.supabase.co",
  "owner_user_id": "33333333-3333-4333-8333-333333333333",
  "workspace_id": "11111111-1111-4111-8111-111111111111",
  "application_id": "lk.peliyagoda.marketpro.staging.isolated"
}
```

Values shown are **FICTIONAL**, for repeatable CI tests only, not a working Supabase project. Never include `service_role`, password, refresh/JWT, MFA secret, owner real IDs or project keys in the manifest; the validator only accepts five non-secret fields and rejects all others.

Checks:
- Require an exact HTTPS `<projectref>.supabase.co` origin, not a redirect URL, userinfo, custom port, query, path or lookalike hostname.
- Reject using the production Supabase ref, production Owner UUID, production workspace UUID or existing compiled application ID.
- Find every production hardcoded project hostname, Owner and Workspace UUID in app Cloud Kotlin files and build configuration; staging `--mode enforce` fails until *all* are replaced by separate compile-time staging bindings.
- Check current Android review build remains `MARKETPRO_LOCAL_PREVIEW=true` and Staff Login, Pairing, Owner MFA, Staff Key, writer approval, Server signed finance and auto upload flags remain false.
- The script performs **no network requests** and never touches databases/APKs.

## Verified isolated Auth tests from previous draft PR

The previous draft PR #57 passed real *local-only* Supabase GoTrue Owner Auth AAL1 → Authenticator-TOTP AAL2, token refresh with AAL2 preserved, separate Staff AAL1 login, with the Docker Supabase service isolated to 127.0.0.1. No hosted Supabase production contacted. This is not a real Android-device staging test.

## Next before actual hosted test

1. **Get explicit organization choice and any fee agreement.** Only one available Supabase organization is currently visible, named `marketpro-cloud-deν`. Never assume that its sole existing hosted project is disposable. Do not call `create_project` without the Owner's organization selection and the platform's cost-confirmation flow.
2. Create a **separate Supabase project** within the chosen organization and only if any quoted charge is explicitly accepted. Never use the existing active Market Pro project.
3. Build a genuinely separate Android staging `applicationId`, add stage-specific Owner and Workspace IDs, use a single compile-time authority for stage ref/host and make CI `--mode enforce` PASS **before** any network build is installed. No hardcoded production fallback.
4. Apply reviewed migrations and Edge functions to staging *only*, with fresh synthetic catalog/kitchen data, Auth accounts and Row Level Security. Verify Owner TOTP factor, Staff pairing, signed proof, current Colombo business day, unrestricted same-day correction and Owner-only old days, revoked-phone proof denial.
5. Preserve separate Owner Room/Invoice photo encrypted backups. Perform physical two-phone tests, verify complete currency and kitchen PDF reconciliation and only then consider final signed APK cutover.

Useful official docs: https://supabase.com/docs/guides/deployment/branching and https://supabase.com/docs/guides/auth/auth-mfa .

**No release authorization is implied by this PR, even when all its CI jobs PASS.**