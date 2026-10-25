# Market Pro — Isolated Hosted Staging: Actual Schema Install Report

**Date:** 2026-10-25. **Target only:** `qqiheexpbuzohllugxrk` (`marketpro-two-phone-staging`, Tokyo / `ap-northeast-1`).

## Confirmed hosted staging resources

- Staging origin: `https://qqiheexpbuzohllugxrk.supabase.co`.
- Staging workspace (random UUID created by PostgreSQL): `af77cb66-76b2-492a-a460-06132f468713`, name **Market Pro Two-Phone QA**, timezone `Asia/Colombo`.
- Existing Owner/Production reference is **different**: `lkoptqhodgjeuwwfihfr` and remains untouched.
- **No Auth accounts, workspace_members, enrolled devices, purchasing records, requisitions, cash, expenses, catalog items, invoice photos or sync operations** were copied or added. Staging has ONE unassigned workspace; no production owner membership or credentials.
- Do not use `CloudSyncConfig.OWNER_USER_ID` or `CloudSyncConfig.WORKSPACE_ID` from the current Android source with this staging server. Staging requires separate Auth user IDs and its own compiled Android application ID and base URL. Previous PR #58 guardrails still intentionally prevent an unsafe APK deployment.

## Actual migrations applied using scoped Supabase migration API

All executed with explicit `project_id=qqiheexpbuzohllugxrk`.

| Hosted version | Migration name | Source |
|---|---|---|
| 20261025165717 | `marketpro_staging_cloud_v1_purchase_only_foundation` | `supabase/migrations/20261021174526_cloud_v1_purchase_only_foundation.sql` |
| 20261025165731 | `marketpro_staging_cloud_v1_requisitions_cash_summary` | `supabase/migrations/20261021180636_expand_requisitions_cash_financial_summary.sql` |
| 20261025165746 | `marketpro_staging_cloud_v1_single_writer_rpc` | `supabase/migrations/20261021183148_secure_single_writer_rpc.sql` |
| 20261025170015 | `marketpro_staging_disable_legacy_owner_writer_rpc` | `supabase/staging/20261025170015_disable_legacy_owner_writer_rpc.sql` |
| 20261025170101 | `marketpro_staging_expenses_schema_owner_read_only` | `supabase/staging/20261025170101_expenses_schema_owner_read_only.sql` |

**Caution:** These are records of changes **already applied to hosted staging**. Do **not** re-run the SQL manually on staging (the foundational files contain regular `CREATE TABLE`). The migration list is authoritative. No existing production migrations or release app code were changed. The fifth finance category's table was added as restricted storage ONLY; the reviewed Staff signed RPCs and Owner TOTP key approval proposals remain **NOT DEPLOYED**, as do all Edge functions.

## Security verification on actual hosted staging

Read-only SQL results:

- Exactly 11 `public` base tables; all 11 with RLS ON.
- `anon` has NO SELECT and NO INSERT/UPDATE/DELETE on any of those tables.
- `authenticated` has NO direct INSERT/UPDATE/DELETE on any of those tables.
- `public.sync_operations`: RLS on, no SELECT policy, completely private.
- `daily_other_expenses`: SELECT only if caller has verified `owner` membership in **that same staging workspace**; no Staff direct read.
- `public.marketpro_apply_change`: `EXECUTE` explicitly revoked from both `anon` and `authenticated` to quarantine the legacy single-writer RPC.
- Supabase Security Advisor subsequently returned **zero WARN/ERROR**. Remaining `INFO`: `sync_operations` has no RLS policy by design, since its private idempotency journal must not be visible to client users. [Official database linter reference](https://supabase.com/docs/guides/database/database-linter?lint=0008_rls_enabled_no_policy).

Server can create additional standalone Auth test accounts later through supported Auth flow, not by inserting fake rows into `auth.users`. **Zero Owner/Staff staging user accounts exist yet.**

## Controlled next phase — NOT yet executed

1. Establish a new confirmed staging-only Owner email/password/Auth account and independent Purchasing Staff Auth account. The owner must verify TOTP enrollment from a real authenticator and login with AAL2; no password/TOTP seed in repo or logs.
2. Bind those **fresh** `auth.users` IDs to the staging-only workspace, then enroll two physically distinct P-256 AndroidKeystore phones only after MFA/session/key fingerprint tests pass.
3. Deploy and audit remaining review-only SQL dependencies, device-challenge ledger and signed Staff Financial RPCs to **staging only**; test same Colombo day unlimited staff edits, past-date Owner-only restrictions, device revoke/replace and idempotent retries.
4. Build a truly separate staging Android application ID and compile-time host/owner/workspace bindings. **Never install a production-targeting APK to test.**
5. Verify both-phones offline-to-cloud, local Room comparison, invoices and financial/PDF totals. Do not enable any Production flags or replace the user's existing Owner phone APK.

**Release decision:** STAGING SCHEMA READY / **TEAM ACCOUNTS AND WRITES BLOCKED**. This is deliberate.
