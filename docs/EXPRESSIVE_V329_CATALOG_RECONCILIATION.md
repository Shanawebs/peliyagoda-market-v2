# Expressive v3.2.9 — Catalog Reconciliation Checkpoint

## Screenshot-verified 23 Oct 2026

- Market Pro Expressive v3.2.8 Owner Auth Verified; phone writer Not Enrolled (expected safe state).
- Phone Room: categories 3, items 171, requisitions 0, purchases 0, purchase additions 0, cash 0.
- Cloud: categories 3, items 184, requisitions 105, purchases 26, purchase additions 10, cash 2.
- Cloud independent SQL: **171 ACTIVE + 13 ARCHIVED = 184 total items**.
- Cloud item names and categories for all 171 active items were compared to the versioned `MarketCatalog.kt` curated list: **171 exact name/category matches, 0 differences**.
- This proves the shipped curated catalog source matches remote active records. It does NOT prove all phone-local edited entries match; v3.2.9 confirms the actual Room records at runtime.
- Existing Cloud data remains owned by existing V2.31 writer, with one active writer; historical rows are not absent merely because the new Android sandbox has none.

## v3.2.9 safe feature

- Read the active/archived Cloud catalog using the authenticated owner's JWT, RLS, bounded GET and exact count header.
- Compare CURRENT phone Room item names + categories against the cloud list; display match count, archived count and limited mismatch names. Cloud 13 archived remain preserved and are not silently reactivated.
- Reject missing/unknown categories, truncated API responses, duplicated normalized active records and mid-audit count inconsistencies.
- Show the existing finance record count differences but never pretend that counts alone establish date/totals parity.
- Keep Android app ID unique and isolated; never activate writer or send any mutation to Supabase.

## Next actual synchronization milestone (NOT implemented yet)

1. Fetch protected historical Cloud snapshot of categories, ALL 184 active+archived items and associated requisitions, purchases, lots, cash.
2. Validate referential integrity and map every Cloud item UUID to a deterministic local Room item ID. Do not assume SQLite autoincrement IDs equal server UUIDs.
3. Show date-wise quantity/price/cash reconciliation and backups before **explicit user-approved** Room import.
4. Use a single Room transaction for local import and cloud UUID-mapping insertion. Preserve archived historical items, additions and item relationships, and carry out a rollback on mismatch. Never delete invoice photos.
5. Keep Cloud uploads disabled until the newly imported Room state and the remote financial totals agree. Explicitly handle the existing V2.31 single writer, enrollment authority, queued events and key rotation, not by simply activating another writer.
6. Test idempotent retry, offline edits, rapid consecutive changes, date changes, stale token refresh, duplicate operation IDs and signed update/rollback scenarios on actual 4GB Android hardware.

## Security and privacy

No new Supabase schema, table policies or Cloud records were changed. The preview is read-only and no service-role key or photo data are embedded in the APK.
