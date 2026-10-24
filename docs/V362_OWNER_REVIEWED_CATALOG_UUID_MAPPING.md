# Market Pro V3.6.2 — Owner-reviewed existing catalog ID mapping

**STATUS: DRAFT SOURCE + ISOLATED TESTS ONLY. Not deployed to Supabase and not present in live V3.3.7.**

## Purpose

Existing Android Room item IDs are local auto-increment integers. The 184 current Cloud items have canonical UUIDs (READ-ONLY Supabase inspection). A name-based match is not proof of identity and could silently attach purchases, recipes, requisitions, or price history to the wrong item. **The Owner, rather than Purchasing Staff, must approve each ambiguous existing item mapping.**

## Proposed owner review and device approval protocol

1. Download authenticated Cloud catalog category/item snapshots to an **isolated Cloud Inbox**; do not directly import/overwrite preexisting Room data.
2. On each phone, identify candidate preexisting local item / category, using the UUID and current local snapshot. Display **name, category, active status, canonical Cloud item and actual target phone** to Owner, with an explicit review decision. A matching item name is never sufficient for approval.
3. After Owner reviews details, a separate **Owner-authenticated, enrolled Owner phone** invokes the *proposed* `marketpro_owner_approve_catalog_link` RPC to store a private approval tied to target device, workspace, catalog UUID, current Cloud revision, local key and SHA-256 digest of actual staff-phone local item fields. Only the Server can issue an approval; a local PIN, QR, or UI state is NOT authorization.
4. Target phone, logged into its own enrolled staff account, fetches its own approval via the proposed `marketpro_device_catalog_link_approval` RPC. Server enforces Auth JWT subject + enabled device + workspace + role, and **24-hour server-side approval expiry**. There are no broad direct SELECT grants on the private approvals table.
5. `OwnerCatalogLinker`, **not wired to any Activity or Worker**, compares the authenticated approval and staged Cloud record against the *current* local Item/Category, normalized fingerprint, Cloud revision, valid current category UUID→local category link, pending outbox operations, both existing ID directions and physical target device. Reject any difference, UUID collision, missing owner proof, stale revision or Cloud deletion.
6. On valid owner approval, **ONLY** `cloud_entity_ids` and a per-link revision checkpoint are written in one Room transaction, never the original `items`, `categories`, `purchases`, `requisitions`, `purchase_additions`, `daily_cash_received`, `cloud_sync_outbox` or invoice photos.
7. A linked item then becomes eligible for the previously drafted revision-safe Purchase/Requisition merge policy, but that future merger remains disabled until true end-to-end QA.

## Implemented and tested

- `supabase/proposals/v362_owner_approved_catalog_links_REVIEW_ONLY.sql`: private immutable owner approval table with one-to-one per-device unique local/Cloud mappings; enrolled Owner-only approval and enrolled recipient-only read RPC, SHA-256 digest, Cloud row revision and 24-hour expiry. No live changes.
- `supabase/tests/v362_owner_catalog_approval_local_only.sql`: isolated PostgreSQL 17 checks for Owner approval, Staff read on designated active device, Viewer/anonymous/disabled/wrong device denies, stale Cloud revision, cross-workspace request, duplicate local ID mapping and duplicate canonical Cloud UUID, expiry and existing sole Owner writer unchanged.
- `OwnerCatalogLinkPolicy.kt`: fail-closed independently testable policy for server-attested owner review, authenticated device, current local fingerprint, immutable Cloud revision, category identity, outbox and reverse identity conflict protection. Its SHA-256 fingerprint uses length-prefixed canonical fields (name, category, active state) rather than ambiguous concatenation.
- `OwnerCatalogLinker.kt`: dormant transaction for existing local category/item linking to verified Cloud UUID. It does **not** automatically map preseeded names or mutate financial history. It is also locked behind the existing false Staff Login / Team RPC flags and offline preview flag.
- `OwnerCatalogLinkPolicyTest.kt`: Kotlin unit tests for each conflict/expiry/field update/invalid device and idempotent already-linked case.
- CI workflow `.github/workflows/marketpro-v362-catalog-owner-approval-ci.yml` runs PostgreSQL 17 + Kotlin security regression suites and verifies the old web Dashboard source directory has not changed.

## Not implemented / critical production gates

- **Owner review UI is NOT implemented** yet. Its design must present a clear match and real physical device; never put staff email passwords/one-time secrets in ChatGPT, code, or logs.
- A device-generated UUID is not hardware attestation. Device enrollment and protected Auth sessions / owner activation must be independently audited in the backend before any permission is granted.
- The proposed approval RPCs are NOT deployed to live Supabase; no staff account or staff device is registered. The Android downloader / Auth adapter and Room merge have not been activated.
- The in-memory `serverAuthenticatedApproval` flag is only an interface contract for a **future trusted, authenticated RPC response**. It does not verify a signature itself and MUST NEVER be populated from untrusted UI/deep links/QR payloads. Date of server expiry is authoritative at RPC read time, not phone time.
- Backdated staff financial edits remain forbidden; today's unlimited staff corrections require the full server-side write RPC + working app integration from previous drafts. This change only maps catalog IDs.
- Other Expenses Cloud schema/summary, addition-lot safe merge, fully functional Two-Phone automatic sync, owner correction propagation, user conflict handling, App signing continuity and two actual 4GB Android phone tests all remain unfinished.
- Existing **V3.3.7** production writer, all Cloud data (including historical records), local photos, original Dashboard URL/login/UI stay unchanged. **Do not uninstall the working app.**

**No V3.6.2 APK was published**, because this branch is source-only and intentionally disabled, not a working sync release.
