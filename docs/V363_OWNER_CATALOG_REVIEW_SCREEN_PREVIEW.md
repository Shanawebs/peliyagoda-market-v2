# Market Pro V3.6.3 — Owner Catalog Review Screen (read-only preview)

**DRAFT development code. NOT a production Owner approval, not deployed to Supabase, no APK.**

## User-approved direction

The Owner needs to compare each Cloud category/item (canonical UUID) against a specific phone's pre-existing Room catalog record before approving a link. *Matching a name does not establish identity.* Wrong links would corrupt financial history. Owner + staff should eventually sync data both ways with staff unlimited edits to TODAY only, Owner edits on past dates, and the existing Web Dashboard unchanged.

## Implemented

- `OwnerCatalogReviewDialog.kt` is a responsive Jetpack Compose Material 3 **read-only review screen** reachable from the current offline preview: **Security & Team Sync → Staff Login & Owner Activation Preview → Owner Phone → Review Cloud / Phone Item Mapping**.
- Tabs for Categories and Items; local search; scrollable list; tap a record for the Cloud UUID, revision, local item ID, local/category details and a clearly worded risk/review status.
- Uses **actual local Room items/categories and already received Cloud Inbox shadow snapshots**. NO artificial sample items or fake "approved" results. If the Inbox is empty, it explicitly says no downloaded Cloud catalog is available.
- `CatalogReviewReader.kt`: bounded read-only operation fetching at most 250 staged items and categories (184 items in current live catalog) and scanning existing phone Items/UUID identities. Detects same-name **candidates** but does NOT automatically bind names, and checks whether the catalog category UUID has a verified two-way mapping.
- `CatalogReviewPolicy.kt`: pure and tested failure/status classification. Conditions include workspace + UUID + revision checks, deleted/untrusted server shadow, local item missing/ambiguous, category mismatch, category UUID unlinked, unsent local edit, mismatched UUID direction, and pre-existing link. Every same-name only candidate displays **"Possible Match — Owner Review Required"**, NOT `APPROVED`.
- The **"Approve Mapping — Requires Secure Server"** button is **disabled** unconditionally. No owner token/password entry, no catalog writes, no network activity, no Supabase RPC. It doesn't even persist a local approval checkbox. The existing `OwnerCatalogLinker` from v3.6.2 remains dormant.
- `CatalogReviewPolicyTest.kt`: tests absence of automatic approval on name match, category/active mismatch, missing item, pending outbox, deletion, identity collision, untrusted shadow, already-bound link and category-first ordering.
- GitHub CI compiles Android Compose and Kotlin policy tests, uses disposable PostgreSQL 17 for previous owner-only approval constraints, verifies **all** old Dashboard source files remain untouched.

## Essential remaining work before real Owner approval

1. Verify the **authenticity of phone catalog mapping candidate**: local staff device itself must present/snapshot its Room ID, name, category and SHA-256 fingerprint to owner through an authenticated device-bound server process. An Owner reading only their OWN phone's Room database does NOT prove what exists on the Staff phone. Screen currently previews the *phone on which it is opened*, not an authorized cross-device review inbox.
2. Build separate verified Owner secure session and a tamper-resistant approval request transport to `marketpro_owner_approve_catalog_link`; server RPC from v3.6.2 is **NOT deployed**. Owner must make an explicit, independently verified choice and see target phone and details, with audit + revocation.
3. After server cutover, staff phone should use independently authenticated `marketpro_device_catalog_link_approval` to download approval and recheck current Room data and fingerprints atomically; owner approval from this preview screen NEVER counts.
4. Current v3.5.x/v3.6.x backend team-write/read RPC, account enrollment, RLS, device membership, Staff auth adapter and catalog merge all remain unactivated. Real two-way download/Room merge is not complete. Other Expenses Cloud schema and purchase-addition safe merge remain missing.
5. Device enrollment does not prove hardware ownership; threat model/attestation and session revocation require review.
6. Preserve V3.3.7 original owner writer, Cloud data, invoice images, original read-only Dashboard URL/UI/login and all user inputs; test coordinated upgrade/rollback on two real Android phones and a 4GB device.

No file under `web/marketpro-dashboard/` changed. Do not uninstall working v3.3.7. New source does not alter any live DB table, live record, writer, user or dashboard.
