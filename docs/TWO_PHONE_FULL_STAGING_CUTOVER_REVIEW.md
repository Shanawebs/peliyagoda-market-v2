# Two-Phone Market Pro — consolidated migration staging review

**25 October 2026. Review-only / NEVER APPLIED to live `lkoptqhodgjeuwwfihfr`.**

The connected Supabase organization currently lists **only ONE project**, which
already holds real Owner data (184 Items, 29 Purchases, one active Owner writer).
It is NOT a safe disposable staging project. No second user/staff account,
credential, role, migration, table, or RLS policy was added to live.

## Real migration compatibility smoke test

`supabase/tests/two_phone_full_migration_pg17_staging_only.sql` constructs a fresh
PostgreSQL 17 database with the three EXACT Owner Cloud V1 migration files, and
then replays the reviewed team SQL in this dependency order:

1. `v352_team_write_gate`
2. `v353_team_purchase_rpc`
3. `v354_team_financial_rpc`
4. `v354_team_paged_pull`
5. `v358_owner_invite_staff`
6. `v359_staff_readonly_activation_rls` (RLS **before** any Staff member)
7. `v362_owner_approved_catalog_links`
8. `v364_staff_candidate_owner_review`
9. `v367_staff_approved_catalog_fetch`
10. `v368_owner_replace_team_phone`
11. `v370_dual_channel_staff_writer`
12. `v371_owner_staff_write_approval`
13. `v372_staff_auth_session_binding`
14. `v375_staff_device_challenge_ledger`
15. `two_phone_other_expenses`
16. `two_phone_other_expenses_pull`
17. `two_phone_atomic_proof_financial_write` (**removes all direct Staff v2 finance RPC EXECUTE grants**)
18. `two_phone_owner_staff_key_enrollment`
19. `two_phone_daily_summary_expense_reconciliation`
20. `two_phone_signed_staff_pull` (closes copied-JWT read bypass)

The test then inserts a NEW mock Owner, Staff, registered phones, category,
Item, Purchase, Additional Purchase, Cash and four Other Expenses amounts.
It checks the old Owner writer privilege, uniqueness of one enabled `can_write`
device, Staff direct REST RLS denial for ALL seven financial/catalog resources,
Owner direct REST entitlement, view `security_invoker=true`, RPC ACL and private
service-only proof. **No Staff rollout gates are enabled by migration.**

## Cash balance mismatch fixed in reviewed SQL

The current V1 `daily_financial_summary` view correctly uses invoker RLS but
ONLY sums Purchases and Additional Purchases. It omits four Other Expenses
fields. The new reviewed override preserves the original six summary columns
and types, appends breakdown, adds all four charges to `total_expenses_cents`
and subtracts them from `cash_balance_cents`. Exact decimal-cent test example:
- First purchase: 30,000 cents
- Additional lot: 14,000 cents
- Transport: 1,200; Labor: 500; Meals/Drinks: 300; Others: 100 cents
- All spends: 46,100 cents; cash received: 75,000 cents;
  cash balance: **28,900 cents**.

No invoice photographs are uploaded.

## Single-transaction cutover proof (additional completed test)

The source-accurate, **202 KB STAGING-ONLY** SQL bundle
`supabase/release_candidates/two_phone_atomic_cutover_STAGING_ONLY.sql`
contains ALL 20 proposals in their original dependency order, with each
standalone BEGIN/COMMIT relocated into ONE outer transaction.
It refuses to start without an explicit disposable-session sentinel.
`supabase/tests/two_phone_atomic_cutover_pg17_staging_only.sql` verifies
the **same real Owner V1 baseline and all security/cash-balance tests**
against that atomic bundle, not just the individually replayed proposals.

Node `tests/two_phone_atomic_bundle_integrity.test.mjs` ensures no source
SQL changed or was reordered without updating the audited atomic bundle.
PostgreSQL 17 CI has confirmed both sequential and one-transaction replay.

## GO / NO-GO before any real cutover

This is only a **disposable PostgreSQL schema test, not a real Supabase project**
with real Auth JWT gateway/Edge deploy or real phones. For a safe deployment:

- Provision a physically separate **STAGING Supabase project** intentionally:
  confirm billing/limits first. Use genuine Owner/Staff authenticated accounts,
  real JWT session revocation, PostgREST RLS and signed Edge requests.
- **Review and approve the single atomic cutover bundle** already prepared
  and tested in disposable PostgreSQL. It must NOT be deployed on Live simply
  because a schema-only test passed. Before future staging cutover, audit its
  inputs and versioned dependencies, then confirm transaction semantics with
  real Supabase Auth/PostgREST and freeze writer/reader enrollments during it.
  There must be NO interval when Staff membership is possible with broad
  original public read policies, or old direct Staff RPC privileges survive.
- All eligible public views must be `security_invoker` or inaccessible to
  Staff. Audit grants, default PUBLIC function EXECUTE, authenticated role
  membership, rollout-gate defaults and purchase/catalog UUID mapping.
- **STAFF READ PROOF CODE PREPARED, NOT DEPLOYED:** original live `marketpro_team_pull_page` checks
  signed Auth JWT session and registered device UUID but DOES NOT require a
  fresh Android Keystore challenge signature on each read. A copied Staff
  bearer token plus known device UUID may still bypass physical key possession
  while the session is active. A reviewed per-page proof-bound Edge+SQL+Android path is now staged and
  protected by default-OFF Android flag; real Supabase staging and physical
  device verification remain mandatory before activating Staff Pull.
  This is a SECURITY NO-GO, despite passing role-level RLS checks.
- Existing Owner v3.3.7 and production data stay on the one original device
  while Staff Phone gets its own registered JWT and independently approved key.
- Verify conflict resolution for old Room records, all four Other Expenses,
  Cash balance, Additional Purchase Lots, Owner historical corrections,
  Staff today unlimited corrections, revocation/replacement and photos.
- Backup live SQL/database and both phone JSON+photo files before staging
  and live operation. Exercise rollback/restore and freeze existing Owner APK
  ID/signing before any signed final release.

This is **NO-GO** for any live SQL migration and **NO-GO** for final APK until
real Staging Supabase + two physical devices end-to-end have passed.
