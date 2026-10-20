# Market Pro Cloud V1 — Architecture and Delivery Plan

Status: **Architecture baseline / read-only audit completed. Implementation not yet deployed.**
Date: 2026-10-20
Existing application baseline: Android V2.14; `applicationId = lk.peliyagoda.marketpro.v214`.
Deployment decision: **Supabase managed Postgres + Auth + Realtime; Android Room offline-first; Next.js read-only dashboard**.
Branch is planning-only. **Do not merge or modify production without an explicit safety gate.**

## 1. Objectives / Non-goals

- Every locally saved catalog, kitchen requisition, initial purchase, and additional purchase syncs to the workspace's Supabase database.
- Android remains usable offline. Persistent, idempotent, retryable sync after connectivity resumes.
- Password-protected read-only web dashboard; filtering by business date, category, item, kitchen; quantity and weighted-market-price trends; completed, shortage, pending and final-cost views.
- V1 single Android writer / multiple read-only web viewers. No editing from web, no anonymous access, no cloud overwrite of Room.
- Invoice originals stay local until a tested private Storage migration; later opt-in original-quality cloud upload and export.
- Never depend on the ReplyXa production database or services.

## 2. Code audit (2026-10-20, main)

- Room schema version 2; core entities: `categories(name)`, `items(id: Long, name, category)`, `requisitions(date,kitchen,itemId,quantityGrams)`, `purchases(date,itemId,quantityGrams,priceCentsPerKg)`, `purchase_additions(id: Long,date,itemId,quantityGrams,priceCentsPerKg)`.
- Requisition kitchen canonical strings in `MarketConstants.kitchens`; preserve persisted names and use display aliases only in UI.
- Application ID changes per version (`*.v214` currently); GitHub Actions uses `:app:assembleDebug` and ephemeral debug signing. In-place updates cannot be guaranteed without stable package and signing key.
- JSON backup schema version 2 includes records but **not** app-private original invoice files.
- Current repository has no WorkManager or cloud/outbox dependencies.
- Values are stored as integer grams and integer cents-per-kg. Keep identical units in Supabase; do not use floats for money.

## 3. Cloud architecture

Android Kotlin/Compose --> Room transactional write + `sync_outbox` --> foreground debounced dispatch + network-constrained unique WorkManager --> Supabase authenticated API/RPC --> PostgreSQL.

Web Next.js/TypeScript --> Supabase Auth --> workspace-scoped, read-only queries and authenticated Realtime notifications --> refetch summaries/charts. Use secure SSR or server-verified auth; RLS is mandatory regardless of frontend.

Suggested production hostname: `marketpro.ceycodex.com` (DNS and hosting not configured yet).

## 4. Proposed database (schema to version under supabase/migrations; do NOT apply yet)

All business tables require `workspace_id uuid not null`, supporting indexes and RLS. No anonymous grants.

| Table | Essential fields | Constraints |
|---|---|---|
| workspaces | id uuid PK, name, timezone, created_at | single initial workspace |
| workspace_members | workspace_id, user_id (auth.users), role (owner/editor/viewer) | unique(workspace_id,user_id) |
| categories | id uuid PK, workspace_id, name, updated_at | unique(workspace_id, lower(name)) via normalized indexed field |
| items | id uuid PK, workspace_id, category_id, name, updated_at, deleted_at | stable UUID, unique active name per workspace |
| requisitions | id uuid PK, workspace_id, business_date, kitchen, item_id, quantity_grams bigint, updated_at, deleted_at | unique(workspace_id,date,kitchen,item_id) |
| purchases | id uuid PK, workspace_id, business_date, item_id, quantity_grams bigint, price_cents_per_kg bigint, updated_at, deleted_at | unique(workspace_id,date,item_id) |
| purchase_additions | id uuid PK, workspace_id, business_date, item_id, quantity_grams bigint, price_cents_per_kg bigint, updated_at, deleted_at | distinct lot ID, retain zero-valued edits |
| sync_operations | workspace_id, operation_id uuid PK, source_device_id, entity_name, entity_id, applied_at | replay/duplicate protection |
| invoice_photos (later) | id uuid PK, workspace_id, date, item_id, storage_path, size, checksum, captured_at | private bucket only |

Create a stable cloud UUID for each local entity on first migration and persist mappings locally. Use a durable local device ID; never assume local `Long` IDs globally unique. Snapshot-import migration must be idempotent.

Access model:
- owner/editor: authenticated write via restrictive RLS or authenticated RPC; viewer: SELECT only.
- workspace ownership comes from server-side membership, NEVER trust caller-supplied workspace_id without RLS.
- `service_role` / secret API keys NEVER ship in APK or browser.
- Test grants AND policies for anon, owner, viewer and unrelated account. Index membership and workspace filters.
- No public invoice bucket or indefinitely shared photo URL.

## 5. Android sync design

1. Keep Room as system-of-record for editing; write business row + outbox operation in a single Room transaction. Do not lose updates when app closes.
2. Outbox columns: `operation_id uuid`, entity + stable entity ID, operation/upsert/delete, snapshot/version, created_at, attempts, next_attempt_at, sync_state. Coalesce rapid edits to latest state while preserving ordering and deletion semantics.
3. When app is open, debounce rapid numeric keystrokes (~750-1500ms) and enqueue flush; WorkManager handles restart-safe retries (network-connected, backoff) in background. Do not promise exact background scheduling latency.
4. Sync categories -> items -> requisitions -> purchases -> additions; ensure FK dependencies. Use idempotent upsert/RPC keyed by stable UUID and operation ID, ACK only after server commit.
5. Reconnect flushes queued events and obtains server ACK/high-water cursor. Expose Sync status `Synced / Pending / Offline / Retry` with last-success timestamp. Never mark `Synced` on mere enqueue.
6. Server is initially read-only mirror of single writing Android device: no two-device editing and no automatic cloud-to-local overwrite. Extending to multiwriter requires explicit conflict resolution.
7. Support deletes/tombstones and backup restore as separately approved, audited bulk-import flows; never silently overwrite workspace data.
8. Photos are queued separately in Phase 4. Original JPEG bytes unchanged. Verify checksums after upload; mark remote-only on server ACK.

## 6. Read-only Next.js dashboard

- Routes: `/login`, `/dashboard`, `/purchases`, `/items/[id]`, `/trends`, `/reports`.
- Summary: required/purchased, shortages, pending/completed, daily final amounts, last successful sync.
- Date/category/kitchen filters; per-item purchased quantity and weighted average price by business day; CSV/XLSX/PDF with authenticated data and no public report URLs.
- Price cost math must match Android exactly: `billCents = round(quantity_grams * price_cents_per_kg / 1000)` per purchase lot; then sum individual lot costs. Confirm Android rounding helper before implementation.
- Initial Realtime approach: restricted Postgres Changes on minimal tables for V1 plus refetch-on-event; evaluate private Broadcast as usage grows. Enforce authorization on every subscription.
- Display `Last synced at`; do not imply instant updates when offline.

## 7. App identity and historical migration (critical)

- Freeze V2.14 as source-of-truth until imports verified.
- Establish ONE final package name and a persistent private signing keystore stored securely as GitHub Actions secrets / encrypted key distribution, never in repository. Debug keystores from separate CI jobs are not reliable for future updates.
- New stable app is a one-time migration/install; import verified JSON backup and build explicit original-quality photo export/import before retiring old app.
- Because Android sandboxes private files per package, do not promise invoice photos will migrate automatically between V2.14 and the new stable package.
- Verify totals, categories, count of requisitions/initial/additional purchases and sample price charts before enabling live sync. Make a rollback backup.
- Plan Android Room schema migration version 2->3 (outbox + UUID mappings + sync state) and migration tests; no destructive fallback.

## 8. Delivery milestones and acceptance gates

**Phase 0 — Read-only audit and architecture (done):** review Kotlin Room/backup/APK pipeline, choose security and data ownership model. This planning document only.

**Phase 1 — Supabase foundation (pending user connection/project):**
- User creates `marketpro-cloud-dev` in their Supabase organization, selects suitable region, enables Auth.
- Add version-controlled SQL migrations + RLS grants/policies + pgTAP negative cross-account tests + sample seed. No production uploads yet.
- Acceptance: anonymous/other-user reads fail; owner reads and writes own workspace; viewer writes fail.

**Phase 2 — Android Room outbox + Auth + sync (staging):**
- Local migration with preserved records, email/password Auth, authenticated idempotent upload, connectivity fallback and observable sync status.
- Acceptance: airplane-mode edit -> pending -> restore connectivity -> upload exactly once; app force-stop/restart works; repeated saves update one remote row, no duplicates, zeroed prices sync; no UI slowdown.

**Phase 3 — Web dashboard read-only:**
- Next.js/TypeScript UI with role-restricted login and live views. Deploy to authenticated HTTPS hostname.
- Acceptance: 2 browsers see changed data after mobile cloud ACK; totals match Android on 5 mixed-lot examples; non-member cannot query workspace.

**Phase 4 — Original-quality invoice photos and verified migration:**
- Private Supabase Storage, secure upload/retry, checksums, image linkage, authenticated viewer/signed URLs, photo export/import for legacy versions, storage usage monitoring.
- Acceptance: byte checksum matches, no data loss after restart, no anonymous photo reads; migration reconciled before removing old APK.

## 9. Operational controls

- Separate Supabase dev vs production projects before go-live; manual approval for production schema changes.
- Backups: export regular verified logical snapshots + Storage archive; document restore rehearsal; monitor free-tier use and pauses.
- Keep secrets in platform-managed environment variables / GitHub secrets, never logs or chat.
- Logs should include operation IDs and error codes, not invoice image contents or auth tokens.
- Rollback: old V2.14 untouched until cloud V1 acceptance; do not uninstall/clear app data.
- CI: Kotlin unit tests + migration tests + RLS tests + Android build, Next.js typecheck/lint/build, smoke/denied-access tests.
- No changes to ReplyXa services or databases.

## 10. User action needed before implementation

1. Open https://supabase.com/dashboard and create development project `marketpro-cloud-dev` in a suitable Asia region. Save database password privately.
2. Connect Supabase integration in ChatGPT if available, or share only the non-secret **Project URL** and selected region. **Never paste service_role/secret key, database password or personal access token into chat.**
3. Confirm first cloud writer is a single phone, and read-only browser viewers authenticate with separate invited user accounts if needed.

Next engineering deliverable after project exists: `supabase/migrations/<timestamp>_marketpro_schema.sql` + RLS tests and a tested staging-first apply plan. No remote write is authorized by this document.
