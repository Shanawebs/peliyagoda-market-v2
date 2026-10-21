# Market Pro Cloud V1 — Supabase DEV connection / staging runbook

## Supplied Supabase DEV endpoint (non-secret)
- **Project API URL:** `https://lkoptqhodgjeuwwfihfr.supabase.co`
- **Project reference:** `lkoptqhodgjeuwwfihfr`
- **Region:** NOT CONFIRMED (do not infer from endpoint hostname)
- **Hosted schema status:** NOT APPLIED by this branch
- **Project ownership/connectivity:** NOT VERIFIED by the supplied URL alone
- **GitHub branch:** `feature/cloud-v1-v228-foundation`
- **Android version:** V2.28 / Room schema v4
- **Cloud rollout:** first ONE Android writer; web read-only; multiple editing phones later

## GitHub deliverables (all safe, no hosted changes)

- `supabase/migrations/20261021170000_cloud_v1_foundation.sql`: workspaces, auth memberships, registered devices, cloud items/categories, requisitions, purchases, purchase_additions, cash, sync idempotency ledger.
- `supabase/tests/cloud_v1_security.test.sql`: local-only pgTAP tests (owner / viewer / unrelated account; no anonymous access; no direct client writes).
- `.github/workflows/cloud-v1-db-tests.yml`: checks local migration, pgTAP and lint using disposable GitHub Actions Supabase containers.
- The hosted database is NOT modified just because this GitHub branch exists. Its API project URL cannot authenticate SQL administration.

## Security and rollout gates

1. Confirm the GitHub Actions **Cloud V1 - Supabase DB Security Tests** run passes (no hosted access used).
2. Connect the Supabase project securely in the ChatGPT Supabase app/plugin, or run a reviewed Supabase CLI migration workflow using a privately stored access token; **do not paste keys or DB password in chat or GitHub**.
3. **Do not manually paste migration SQL into the Dashboard** if using CLI tracked migrations: manual changes bypass `supabase_migrations.schema_migrations` and create schema history drift.
4. Confirm hosted DEV project is empty / not shared with production before any schema write. Require an explicit deploy checkpoint.
5. Apply migration on DEV only; rerun SQL grants/RLS tests and confirm every test account isolation case. Create a workspace/member/device through a separately reviewed *admin-only onboarding step*.
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
