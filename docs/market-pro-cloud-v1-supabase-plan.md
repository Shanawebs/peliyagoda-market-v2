# Market Pro Cloud V1 — Supabase Implementation Plan

Status: PLANNING / NO PRODUCTION CHANGES
Baseline: Android Market Pro V2.14 (Kotlin, Jetpack Compose, Room, applicationId lk.peliyagoda.marketpro.v214)
Architecture: Android offline-first single writer -> Supabase Postgres -> authenticated read-only Next.js dashboard.
Hosting: Supabase managed; no ReplyXa production infra changes.

## Scope / non-goals
- Android saves locally first as today; network is never required for editing requisitions, purchases or prices.
- User-authenticated asynchronous upload of categories, items, requisitions, original purchases, additional purchase lots. Delete and cleared-to-zero changes must propagate.
- Private, read-only browser dashboard with organization-level authorization, date/category/kitchen filters, totals and item price/qty trends.
- Realtime UI invalidation then refetch, plus query on load/reconnect; no dependence on WebSocket as the only source of truth.
- V1: one authorized Android writer device per organization; future multi-writer requires explicit conflict-resolution design.
- Invoice photos: local-only until Storage phase, then full-original private uploads with separately defined authorization.
- No web edits, no public link to confidential data, no service secret in browser/APK, no automatic deletion of local data.
- No cross-database sharing with ReplyXa.

## Current audited repository facts
- Room tables: categories(name), items(id,name,category), requisitions(businessDate,kitchen,itemId,quantityGrams), purchases(businessDate,itemId,quantityGrams,priceCentsPerKg), purchase_additions(id,businessDate,itemId,quantityGrams,priceCentsPerKg).
- Original purchases can be deleted locally when quantity and price are both zero; sync must represent tombstones/deletes.
- Additional purchase lot's numeric fields may be changed to zero while its row/id remains.
- Invoice images stored in Android app-private filesDir/invoices/<business-date>/item-<local-id>, not included in JSON backup.
- Historical price chart must aggregate extra lots by quantity-weighted unit price, not mean of prices.
- Android applicationId has previously changed per version, so import, photo retention, signing and migration must be planned before stable production identity.
- V1 migration must preserve local IDs through separate remote UUID mapping. Never interpret device-local auto-increment IDs as global.

## Logical data model (to implement as reviewed SQL migrations)
- organizations(id UUID PK, name, timezone)
- organization_members(organization_id UUID, user_id UUID -> auth.users, role ENUM owner/writer/viewer, active, PRIMARY KEY(organization_id,user_id))
- categories(id UUID, organization_id, name, name_normalized, updated_at, deleted_at)
- items(id UUID, organization_id, category_id, name, name_normalized, updated_at, deleted_at)
- requisitions(id UUID, organization_id, item_id, business_date DATE, kitchen ENUM or FK, quantity_grams BIGINT CHECK >=0, revision, deleted_at, UNIQUE org/date/kitchen/item active semantics)
- purchases(id UUID, organization_id, item_id, business_date, quantity_grams BIGINT, price_cents_per_kg BIGINT, revision, deleted_at, UNIQUE org/date/item active semantics)
- purchase_additions(id UUID, organization_id, purchase_id/item_id, business_date, quantity_grams, price_cents_per_kg, revision, deleted_at)
- sync_operations(id UUID PK [idempotency key], organization_id, actor_user_id, device_id, occurred_at, entity_type, entity_id, operation, revision/result). Process with authenticated, scoped server-side RPC or Edge Function transactional logic.
- device_registry(device_id UUID PK, organization_id, authorized_user_id, status)
- invoice_photos(id UUID, organization_id, item_id, business_date, storage_path, captured_at, uploaded_at, checksum) in future storage phase.
- Include indexes on organization_id, business_date, item_id, member user_id, and change tracking. All financial values integer (grams and cents); calculations use integer/decimal, no floats.

## Auth and RLS
- Supabase Auth Email/Password initially; enable email confirmation and account recovery. Disable open organizational self-provisioning.
- Role owner, writer (Android), viewer (Web). V1 no browser write privileges; viewer has SELECT only.
- All client-facing tables: ENABLE ROW LEVEL SECURITY, narrow SQL GRANTs; policies check active membership on organization_id and action/role.
- Do not use published anonymous policies for transactional or invoice data. Test cross-org isolation, unauthenticated access, viewer write denial, revoked account behavior.
- Browser uses NEXT_PUBLIC_SUPABASE_URL + NEXT_PUBLIC_SUPABASE_PUBLISHABLE_KEY only, with @supabase/ssr; Android uses publishable key + signed-in user session. Secrets only server-side in guarded operations. Never commit credentials.
- Use HTTPS, protected session storage, secure cookie-based browser sessions, and private org-scoped Realtime subscriptions.

## Android Offline Sync
1. Migrate Room schema with additive local_uuid, local_version or corresponding mapping; add sync_outbox and sync_metadata; migration tested with old app DB fixture.
2. Write business record and durable outbox event atomically in Room transaction. Coalesce rapid edits by key but preserve correct ordering and deletion; never silently drop earlier events before server ACK.
3. On active app changes trigger immediate upload where possible; WorkManager durable retry with NetworkType.CONNECTED and exponential backoff for background.
4. Authenticate as authorized writer. Upload idempotent operations in bounded ordered batches with stable operation UUID, revision and org/device scope; confirm each receipt before marking ACK.
5. Handle 401/403 as user-action-needed (do not destroy queue); transient errors retry; conflict/revision mismatch visible in Sync Status; dead-letter after diagnostic checks, never silently discard.
6. First onboarding: read-only backup + reconciliation preview + explicit import confirmation. Deduplicate, map old auto-increment IDs to cloud UUID, import all dates/categories/lots and tombstones consistently.
7. V1 is single-writer; local Room remains UI source of truth. No automatic cloud-to-Android overwrites. Multi-device writes deferred until defined conflict policy.
8. Display Last synced, Pending operations, Retry/Sync now, error state; optionally Wi-Fi-only policy for photographs.
9. Reconciliation checks row counts, per-date required KG, purchased KG, total costs, categories and additional lot counts.

## Web dashboard (separate Next.js TypeScript project)
- Auth-protected /login and /dashboard pages. Server-side session validation; read-only viewer role enforced by RLS.
- Date picker (Asia/Colombo business date), category/kitchen filters; total required, purchased, shortage, extra, completed; price totals and invoice status.
- Item detail with historic date series: sum qty across original and additional lots and weighted average unit price by quantity per date. Distinguish no-price and no-purchase entries.
- Real-time Broadcast channels authorized by org, used to invalidate cached queries; on reconnect refetch from DB; optional short fallback refresh if socket disconnected.
- Charts must support horizontal swipe on phone, axis pinned and accessible tooltips. Desktop responsive layout.
- No editing, public report tokens, or CSV/PDF exports until verified in separate phases.

## Invoice Storage (later Phase)
- Private Supabase Storage bucket (never public). Original unmodified JPEG upload, checksum, size limits, retry and resumable strategy for large files.
- Web uses authenticated access or short-lived signed URLs; never store permanent public image URLs.
- Retain old local photos; JSON backup alone is insufficient. Photos and Postgres DB require independent backup strategy.

## Rollout order and acceptance gates
A. Provision dev Supabase project. Owner controls credentials, region closest/appropriate, Free limits reviewed; Auth configured and staging accounts established.
B. Create SQL migrations, minimal org/member schema, RLS + grants, tests, typed DB client. No real data yet.
C. Implement Android additive Room migrations + outbox + writer Auth + Sync Status with demo records only. CI and offline/online/failure tests pass.
D. Import real historical data only after backup and record-count/bill reconciliation. Old app remains installed with invoices retained.
E. Build Next.js read-only owner/viewer dashboard, auth and Realtime refetch; pass read-only and cross-org tests.
F. Private invoice upload and backup; postpone until core stable.
G. Stable Android package identity and signing/update migration planned and validated on an actual handset before replacing production application.

## Required tests
- Airplane mode -> enter purchases -> close/reopen app -> queue persists -> reconnect -> dashboard matches exactly.
- Repeated retries produce exactly one effective operation per UUID; no duplicate extra lots.
- Qty/price cleared to zero and original purchase deletes sync reliably; local editable extra lot IDs retained.
- Rapid 1->12->0 edits preserve final value; Android process killed mid-upload does not lose transactions.
- Unauthorized user cannot read any organization's data, signed-in viewer cannot write, revocation invalidates access.
- Reconciliation across categories, kitchen summaries, line costs, shortages and weighted price graphs.
- User refresh/reconnect/sleep -> browser refetches; no event-loss dependency.
- App upgrade with local database and high-resolution invoice photos retained through planned package/signing migration.
- Supabase Free pause / outage -> Android stays functional, queued ops resume when cloud recovers.

## Backups and cost
- Free tier may pause after inactivity; scheduled backups are NOT guaranteed for free projects: export Postgres regularly and separately back up Storage objects and original Android invoice photos.
- Monitor quotas for DB, egress, storage and Realtime. Do not assume 24/7 availability on Free; review paid tier for production reliability.
- Do not activate cloud credentials, change app package identity, alter ReplyXa or push to main without explicit reviewed acceptance.
- All DDL/scripts versioned in GitHub, CI tests for RLS and migrations; GitHub Actions deploy only after approval.

## Work to unblock
1. User creates or connects an authorized Supabase project; no passwords or secret keys pasted into chats.
2. Obtain non-secret Project URL and publishable key (or use connected Supabase plugin), project region and name.
3. Confirm first sync is ONE Android writer device and owner/read-only dashboard viewers (recommended V1).
4. Prepare actual SQL migration and RLS tests on a staging Supabase project, before app APK modification.
5. Confirm existing Android data migration strategy before first production upload.

## Official references
- https://developer.android.com/topic/architecture/data-layer/offline-first
- https://developer.android.com/develop/background-work/background-tasks/persistent/getting-started/define-work
- https://supabase.com/docs/guides/database/postgres/row-level-security
- https://supabase.com/docs/guides/auth/quickstarts/nextjs
- https://supabase.com/docs/guides/realtime/subscribing-to-database-changes
- https://supabase.com/docs/guides/storage/buckets/fundamentals
- https://supabase.com/docs/guides/platform/backups
- https://supabase.com/docs/guides/local-development/cli-workflows
