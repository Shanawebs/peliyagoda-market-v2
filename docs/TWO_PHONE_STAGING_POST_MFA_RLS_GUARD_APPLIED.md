# Market Pro — STAGING Owner MFA → RLS Direct-Read Hardening
Date: 2026-10-26. **Status: applied to separate hosted QA Staging only; not a Staff/Owner mobile release.**

## Confirmed
- Target project **qqiheexpbuzohllugxrk**. Unchanged Production project **lkoptqhodgjeuwwfihfr**.
- Two confirmed synthetic QA Auth accounts. Owner workspace membership: one. Staff role: none. Registered phones: zero.
- Exactly one Owner verified TOTP factor. The green browser AAL2 result was user-observed; **a factor being verified does not mean future requests automatically satisfy fresh AAL2 step-up**. Reauthentication is required for privileged Owner approvals.
- Database migration **20261025190310** `marketpro_staging_post_mfa_direct_rest_read_guard` applied successfully (see `supabase/staging/staging_verified_owner_direct_rest_read_guard.sql`).
- Six RLS SELECT policies now explicitly allow **only owner/editor/viewer membership**; future `purchasing_staff` must NOT use direct REST for catalog/financial reads. The future phone needs a **reviewed, JWT/session/device-signature-bound paginated RPC**.
- Existing `public.marketpro_apply_change` still has **no EXECUTE privilege for authenticated or anon**.
- Zero new devices, zero Staff memberships, zero financial rows written, no Android build/flags changed.

## Verification
- Run the read-only script `supabase/staging/staging_post_mfa_read_guard_verification_READ_ONLY.sql` against the STAGING project.
- The checked six `pg_policies` rows all have RLS enabled and SELECT USING membership criteria excluding Staff.
- Security Advisor currently reports INFO for deliberately private `sync_operations` (no SELECT policy); WARNING for leaked-password protection disabled (see Auth security settings). Neither is a reason to weaken RLS.
- This is a **schema-policy audit, not authenticated browser/JWT PostgREST E2E**. Owner positive and Staff negative test rows with actual JWT remain required.

## Still blocked (must remain OFF)
1. Integrate and exercise the coherent server proposal chain V352–V375 on disposable PostgreSQL17 with migration ordering, private ACLs, RLS and proof-bound P-256 authenticated read/write.
2. Stage-deploy reviewed invitation/session/device key approval and signed financial Edge functions **only after** full integrated tests (including Same-day Colombo unlimited Staff correction, Owner-only earlier days, old/revoked-phone denial).
3. Prepare a separate staging application ID with no production URL/owner UUID/workspace UUID fallback; pass `scripts/verify_staging_isolation.py --mode enforce`.
4. Require actual Owner TOTP **AAL2 step-up at phone approval** and real Staff email identity (the QA Gmail dot alias is intentionally one shared inbox and NOT real Staff recovery independence).
5. Physically test two phones, Room UUID reconciliation, queued offline edits, price and cash totals, last-write conflicts, key replacement/revoke, fresh/failing tokens, and local invoice photo backup.
6. Keep working Owner Production APK, Supabase and web dashboard unchanged. Do not distribute any Staff Live APK until a signed isolated release has passed tests.

## Rollback
The new migration does not change data. A rollback would re-enable the old broad membership-based SELECT and **is unsafe after Staff activation**. Do not use rollback casually; require security review and fail closed.
