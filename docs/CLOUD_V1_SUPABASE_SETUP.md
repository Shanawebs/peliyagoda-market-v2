> **Latest cloud scope / hosted state (2026-10-21):** Supabase DEV has two migrations. The first created the purchase foundation (8 tables). The second `20261021180636_expand_requisitions_cash_financial_summary` added requisitions, daily cash, and a read-only derived financial summary. **10 RLS tables + 1 view, zero user rows and zero invoice uploads.** Supersedes earlier purchase-only scope in this historical runbook. Android sync is not deployed. See `docs/CLOUD_V1_EXPANDED_SCOPE.md`.

# Market Pro Cloud V1 — Supabase DEV connection / staging runbook

## Supplied Supabase DEV endpoint (non-secret)
- **Project API URL:** `https://lkoptqhodgjeuwwfihfr.supabase.co`
- **Project reference:** `lkoptqhodgjeuwwfihfr`
- **Region:** NOT CONFIRMED (do not infer from endpoint hostname)
- **Hosted schema status:** APPLIED to the dedicated Supabase DEV project on 2026-10-21; verified 8 public tables, all with RLS; all 0 rows. Android purchase data has NOT been uploaded.
- **Project ownership/connectivity:** Confirmed with connected Supabase account; project ACTIVE_HEALTHY; Tokyo region (ap-northeast-1).
- **GitHub branch:** `feature/cloud-v1-v228-foundation`
- **Android version:** V2.28 / Room schema v4
- **Cloud rollout:** first ONE Android writer; web read-only; multiple editing phones later

## GitHub deliverables (all safe, no hosted changes)

- `supabase/migrations/20261021174526_cloud_v1_purchase_only_foundation.sql`: workspaces, membership, devices, categories/items reference tables, **purchases and additional purchase lots only**, sync idempotency ledger; NO requisition/cash/photo tables.
- `supabase/tests/cloud_v1_security.test.sql`: local-only pgTAP tests (owner / viewer / unrelated account; no anonymous access; no direct client writes).
- `.github/workflows/cloud-v1-db-tests.yml`: checks local migration, pgTAP and lint using disposable GitHub Actions Supabase containers.
- Initial DEV migration was deliberately applied through an authenticated Supabase connector, AFTER local tests passed. Hosted migration history version: `20261021174526` / `cloud_v1_purchase_only_foundation`. No production database touched. No Android upload sync exists yet.

## Verified hosted DEV state

- Supabase migration reported success; hosted `public` has eight new RLS-enabled tables, no data rows, no cloud-photo buckets created.
- GitHub local CI for purchase-only migration/tests: success, including 37 pgTAP assertions and SQL lint.
- Supabase Security Advisor reported one informational `rls_enabled_no_policy` for `public.sync_operations`: **intentional deny-all** (there are no client grants or policies). All user-readable tables have workspace-limited read policies; no direct client write grants.
- NEVER run this initial migration a second time on the same hosted project. Additional future changes require a new versioned migration.

## Security and rollout gates

1. Confirm the GitHub Actions **Cloud V1 - Supabase DB Security Tests** run passes (no hosted access used).
2. Connect the Supabase project securely in the ChatGPT Supabase app/plugin, or run a reviewed Supabase CLI migration workflow using a privately stored access token; **do not paste keys or DB password in chat or GitHub**.
3. **Do not manually paste migration SQL into the Dashboard** if using CLI tracked migrations: manual changes bypass `supabase_migrations.schema_migrations` and create schema history drift.
4. Completed for initial foundation: hosted DEV project was checked empty before applying the first schema. Obtain a new deploy checkpoint for future SQL changes.
5. Completed: initial DEV migration applied; metadata, grants, RLS and empty rows read back. Local CI role-isolation tests passed. A separately reviewed *admin-only onboarding step* is still pending for creation of workspace/member/device.
6. Implement Phase 2 device-authenticated, idempotent transactional write RPC, Room 4→5 outbox migration and safe snapshot import only after DB security acceptance.
7. Keep V2.28 installed. Version-specific package names and CI debug signing require an explicit signed app/data/photo migration strategy.

## Never do the following

- Never upload live purchase records as initial seed/test fixtures.
- Never embed `service_role`, `secret`, personal access token, or database password in APK, web bundle, test files or repo.
- Never run `supabase db reset` on a *linked/hosted* database; `db reset` is allowed in disposable local CI only.
- Never import JSON backup by calling the existing destructive `MarketRepository.restore()` in background sync.
- Never silently treat cloud as source of truth and overwrite the current Room DB.
- Never infer a device's global item identity from local auto-increment `Long` ID.

## Acceptance before an APK release

- Offline Android edits continue to save instantly and require no network.
- Outbox replay is idempotent; fail/retry does not double purchase lots or cash.
- Numeric values remain integer grams and cents (no floating point corruption).
- A stranger cannot read workspace A's rows; viewer cannot write; only registered primary writer can submit server-authenticated operations.
- All original invoice photos and historical/archived item IDs remain intact.
- GitHub Actions Android build plus local 4GB RAM device tests remain mandatory.
