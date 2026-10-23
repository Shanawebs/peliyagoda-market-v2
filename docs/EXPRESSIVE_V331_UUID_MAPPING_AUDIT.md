# Market Pro Expressive v3.3.1 — UUID Mapping Audit (strictly read-only)

## User-confirmed backup provenance

The user explicitly confirmed that the historical financial records seen on the Expressive phone came from a **manual JSON Restore**, NOT automatic Supabase sync.

The V4 JSON backup preserves item local IDs, business dates, kitchen requisitions, purchases, extra lot IDs, and daily cash. It does **not** export `cloud_entity_ids` or `cloud_sync_outbox` or photo files. `MarketRepository.restoreBackup()` clears existing local identities and outbox in an UNENROLLED database and calls `CloudChangeJournal.record()` on every restored entity. This produces **new random UUIDs** and pending operations. They cannot be uploaded into the existing, nonempty Supabase workspace without a mapping migration.

## v3.3.0 confirmed on physical Android phone

The user sent an actual-screen report showing:
- 171/171 active item names and categories match, 13 archived cloud items retained.
- 105/105 requisitions, 26/26 purchases, 10/10 additional purchase lots, 2/2 daily cash financial fingerprints match exactly.
- Room has 184 total items including archived; Cloud also has 184.
- Owner authenticated; Expressive device NOT ENROLLED; upload still LOCKED.

This is **content parity**, NOT identity parity.

## v3.3.1 precise scope

- Keep all prior Android apps untouched, separate application ID `lk.peliyagoda.marketpro.expressivepreview.v331`.
- Read the already-existing `cloud_entity_ids` and pending outbox count, never change either.
- Fetch the authenticated, workspace-scoped Supabase category/item/financial UUIDs with GET and restrictive RLS.
- Match by unique business key ONLY as a *candidate for audit*, not as an actual mapping write.
- For each type: same UUID, different UUID, no local UUID, missing local/remote item, and ambiguous duplicate semantic keys.
- Treat additional purchase lots with equal date, item, grams, and unit price as intrinsically ambiguous. Never assign a UUID by row ordering.
- Never log, copy to clipboard, display, or transmit full UUID mapping/token data to the user or ChatGPT.
- Include pending local operations so a restored database cannot be mistakenly treated as safe to upload.
- Cloud writer status stays un-enrolled; Cloud Sync dialog and writer RPC must stay unavailable.

## Device-specific review instructions

1. Install v3.3.1 WITHOUT uninstalling previous apps.
2. Because it has a new application ID, its Room database is a separate sandbox. To compare *restored* transactions, create a verified backup of the previously-tested dataset and use v3.3.1's **Restore Backup (JSON)** feature with the appropriate local authorization. This affects only v3.3.1 local data. Backup restore generates its own random UUIDs.
3. Sign in to existing owner and use **Cloud Connection (Read-Only) > Check My Cloud Connection**.
4. Audit Cloud UUID mappings and pending operations. Expect different UUIDs after restore. Never interpret matching record content as proof of a safe writer migration.
5. Do not activate the preview writer. No cloud data is modified.

## Later migration (not implemented or approved)

For a *real* identity migration, require a consistent cloud snapshot (stable revision), backup of old authorized writer's local identity/outbox, explicit conflict strategy for archived/deleted entities, lot ambiguity proof by original IDs, transactional Room UUID mapping changes, new unique operation IDs, crash/retry tests and controlled **single-writer** transition. A second writer must not be permitted by merely matching row counts or UUIDs.

## Security and verified exclusions

No new Supabase schema or policies; no DELETE/POST/PUT/PATCH, no enrollment RPC, no CloudSyncScheduler in preflight path. No invoice photo upload. No financial data changes. Existing writer and web dashboard remain unchanged.
