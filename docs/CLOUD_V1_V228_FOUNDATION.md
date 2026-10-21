# Peliyagoda Market Pro — Cloud V1 (V2.28 baseline)

> **2026-10-21 Cloud V1 scope decision (takes precedence over the broader options below):**
> User selected **purchase-data-only synchronization** for the first cloud release. Only `categories` and `items` identifiers needed for purchase meaning, `purchases` and `purchase_additions`, plus workspace/device/membership/sync-operation security metadata will exist in this initial cloud schema.
> **DO NOT create or sync cloud requisitions, daily cash received, invoice metadata/files, Supabase Storage buckets or any photos.** All those remain local on Android. Future expansion is optional and requires a new migration and user approval. The Android package, Room DB and local invoice files are unchanged by Phase 1.

Status: **Purchase-only schema deployed to Supabase DEV and verified (2026-10-21). No purchase data imported. Android sync NOT implemented. Production app unchanged.**
Date: 2026-10-21
GitHub main baseline: `e411b7377596d6756550528f5e2c8860170edbf9`, APK V2.28.0 / applicationId `lk.peliyagoda.marketpro.v228`.
Cloud provider: **Supabase managed PostgreSQL + Auth + RLS + Realtime + private Storage**.
Android: **Room as offline source for UI, WorkManager durable background sync**.
Web: **Next.js authenticated read-only dashboard**.

## Confirmed rollout

- **V1:** One authenticated Android phone writes; the cloud is a secure remote replica, with a read-only web dashboard.
- **Future:** Additional Android devices may write simultaneously. The schema, stable IDs, permissions and operations must support this without redesign, but *multi-writer sync and conflict resolution are NOT enabled in V1*.
- Never let a second device independently upload existing records before explicit enrollment, initial download and synchronization safety tests.
- No anonymous access; users belong to a workspace, not a globally shared single-user schema. Keep the application separate from ReplyXa infrastructure.

## Audited V2.28 Android baseline (read-only on 2026-10-21)

- Room database `peliyagoda-market-pro.db` is **schema version 4**, with additive migrations 1→2, 2→3, 3→4; no destructive fallback in the current builder.
- Tables: `categories(name)`, `items(id: Long, name, category, isActive)`, `catalog_state`, `requisitions(businessDate,kitchen,itemId,quantityGrams)`, `purchases(businessDate,itemId,quantityGrams,priceCentsPerKg)`, `purchase_additions(id: Long,businessDate,itemId,quantityGrams,priceCentsPerKg)`, and `daily_cash_received(businessDate,amountCents)`.
- Preserve existing `Long` item IDs in Room, including archived catalog entries and invoice photo references. Store money as *integer cents* and quantity as *integer grams* everywhere. Do not use floating-point amounts.
- Current JSON restore is a destructive *replacement* inside a transaction after validation. **It must never be called by background cloud sync**; bulk migration/restoration requires explicit preview, backup, reconciliation and user approval.
- Original invoice image files are not in the JSON backup. Treat them as independent critical data. They cannot migrate automatically across differing application IDs.
- Existing APKs have version-specific application IDs and debug CI signing. Before any user-visible cloud migration, select a stable package name, persist release signing securely outside Git, and design an audited legacy data/photo transfer.

## Multi-device-ready identity and authorization model

- Each business is a **workspace** with stable UUID. Each member has a Supabase Auth user ID, workspace membership and role `owner | editor | viewer`.
- For V1, only the owner / specifically enrolled primary Android device can submit writes; dashboard viewers read only. Restrict writer authorization server-side, never from an Android-only feature flag.
- Every replicated row has a UUID primary key or deterministic mapping to a stable UUID. Each local Room row retains its original key and gains an additive local-to-cloud mapping table. **Never use device-local auto-increment IDs as global identifiers.**
- Include `workspace_id`, `updated_at`, `revision` (or equivalent server-controlled version), and soft-delete/tombstone metadata where relevant.
- Every business table uses PostgreSQL RLS and explicitly scoped privileges. Tests must prove anon/non-member cannot read, viewer cannot write, and users from another workspace cannot access data.
- Never embed Supabase `service_role`/secret key, Postgres credentials or private storage credentials in APK/browser. Project publishable/anon key is not a substitute for RLS.

## Proposed remote tables (staging, schema migration pending)

1. `workspaces` — id, name, timezone.
2. `workspace_members` — workspace_id, user_id, role.
3. `registered_devices` — workspace_id, device_id UUID, enabled, can_write, last_seen_at.
4. `categories` — workspace-scoped stable ID/name.
5. `items` — UUID, workspace_id, local ID mapping, category ID, name, is_active.
6. `requisitions` — UUID, workspace_id, business_date, kitchen, item ID, quantity_grams.
7. `purchases` — UUID, workspace_id, business_date, item ID, quantity_grams, price_cents_per_kg.
8. `purchase_additions` — immutable UUID per distinct purchase lot with update/delete support.
9. `daily_cash_received` — workspace_id, business_date, amount_cents.
10. `sync_operations` — operation_id UUID, workspace_id, device_id, payload hash / committed-at; idempotency protection.
11. `invoice_photos` — later-phase private Storage metadata and byte checksum; do not push photos automatically in Phase 1.

Use explicit uniqueness for `(workspace_id,business_date,kitchen,item_id)` on requisitions, `(workspace_id,business_date,item_id)` on initial purchases and cash per date per workspace. Guard composite foreign keys against cross-workspace references. Use server-side revision checks on future writes.

## Android data flow and single-writer safety

1. Android writes to Room as before, instantly and offline.
2. Persist an outbox event **in the same Room transaction** as each business mutation. Include stable entity UUID, operation UUID, snapshot/revision, device UUID and retry state.
3. Background WorkManager with network constraints and exponential retry sends ordered, coalesced operations. On-screen interaction may request earlier flush, but must not block UI thread.
4. Upload initial snapshot through a **separate audited, idempotent import path** before live outbox sync. Map existing local IDs to global UUIDs deterministically / persistently; reconcile row counts, amounts, item references and lot identities.
5. Apply operations server-side transactionally with idempotency, authorization and all dependencies present; mark local op `ACKED` **only after server commit**.
6. Realtime notifications trigger dashboard refetch; initial V1 dashboard is read-only. Show `Last successful sync` and `Pending operations` explicitly.
7. Deletes use persisted tombstones so they are not re-created on retry or future device download. Catalog archiving is not deletion.
8. Do not allow cloud-to-Room overwrite in V1; future secondary device enrollment requires an initial authoritative download and multiwriter revision/conflict protocol.
9. When multiwriter is enabled later: optimistic revisions and server-checked edits; conflicts go to a review state for purchase quantities/prices/cash (never silently last-write-wins for financial data).

## Phase gates

- **Phase 0 (complete):** update Cloud V1 design to V2.28 current schema and confirmed single-writer→multiwriter requirements. No Android code changes and no new APK.
- **Phase 1 (database foundation complete; onboarding not started):** Supabase DEV project is connected. Purchase-only schema (8 tables) deployed with version `20261021174526`, all 8 RLS-enabled, zero data, no photos. 37 local pgTAP assertions and lint passed. Workspace and devices not enrolled; no authenticated sync writer RPC exists yet. Production untouched.
- **Phase 2:** non-destructive Room 4→5 migration (mapping/outbox tables) + Kotlin auth/sync. Test Room migrations and app background/restart; offline queue, duplicate retries and zero-value edits. Maintain 4GB RAM performance.
- **Phase 3:** limited staged import of backed-up data; compare all entity counts, daily cost totals, cash and price histories. Revoke/rollback on failure. No automatic restore.
- **Phase 4:** read-only Web Dashboard, authenticated Realtime and refetch, verify totals against Android.
- **Phase 5:** private, original-byte invoice photo upload with SHA-256 checksums; safe package/signing migration.
- **Future Phase:** enable 2+ authenticated editing devices only after per-device authorization, bootstrap download, conflict tests, cross-device transaction and recovery acceptance.

## Operational / user preparation

- User creates Supabase DEV project first and retains DB password, secret keys and private token securely; never paste secrets in chat or commit them.
- Need the **non-secret Project URL and region** for integration work; do not require these merely to design code.
- Enable routine verified backups. Production rollouts require backups, checks, rollback and an explicit gate.
- Do not touch ReplyXa services, production data, old Android app installation or photo files while developing.
- This plan supersedes the older `feature/cloud-v1-architecture` planning doc based on V2.14; do not merge that diverged branch into current V2.28.
