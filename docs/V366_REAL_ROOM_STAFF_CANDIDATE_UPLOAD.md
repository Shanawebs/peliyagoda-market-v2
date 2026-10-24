# Market Pro V3.6.6 — Staff Phone REAL Room Catalog → Owner Candidate Upload

**Status: SOURCE-ONLY, offline-gated, security CI. NOT a deployed Staff application, NOT a finished two-phone synchronization APK.**

## Existing user goal

Purchasing Staff Android phone should enter/correct TODAY's purchases, requisitions, extras and cash without Owner approval, while prior days remain read-only to Staff; Owner may correct past days; both phones and the unchanged web Dashboard should eventually show the same financial entries. All historical data, local invoice photos and sole live V3.3.7 writer must be protected.

## Actual V3.6.6 improvements

### Read the REAL phone, not caller-supplied item details

- `StaffRoomCandidateReader.prepare(type, localKey, cloudUuid, physicalDeviceId)` reads the exact selected local item/category from **Staff phone Room** in one `withTransaction`, together with already-validated Cloud Inbox shadows, pending outbox state and both directions of Cloud UUID ↔ local-key mapping.
- For Items it validates canonical positive local Item ID, existing Item record, exact Cloud Item ID/workspace/revision, nondeleted Cloud Item, verified same-workspace Cloud Category shadow and exact category name, active status and unambiguous name match; rejects ambiguous local duplicate names (case-insensitive), changed item name/category/active flag.
- For Categories it validates the actual existing local Category keyed by its normalized name and its Cloud Category's canonical UUID/revision.
- Rejects missing untrusted/deleted/stale local/remote records, unsent offline edits, already-linked records, or any competing UUID-to-key association. It never alters Room rows or enqueues Cloud outbox writes.
- Computes SHA-256 only from CURRENT Room name/category/active values via `StaffCatalogCandidatePolicy.draft`, rather than treating a UI text field or pre-computed fingerprint as trusted.
- **Important**: a self-declared Staff Room snapshot is candidate evidence only, never a replacement for Owner review or hardware attestation.

### Only validated candidates can be uploaded

- `CatalogReviewNetworkCoordinator.staffSubmitFromRoom(type, localKey, cloudUuid)` is the only public staff upload method. The older arbitrary `staffSubmit(inputs)` network path was removed. It first authenticates through encrypted, separate Staff Auth, verifies workspace/physical phone, then loads actual Room data. It rereads for any change before HTTP, submits to the **pending-only** team RPC and validates server's candidate UUID/status/cloud revision acknowledgement, then rereads again after HTTP. A change or conflict causes fail-closed feedback, and no local mapping or financial mutation.
- If a network or postflight failure occurs, an Owner server candidate MAY already exist. UI correctly tells Staff to refresh before resubmitting. Owner's server approval and Staff's final future UUID mapping BOTH separately revalidate identity/revision/fingerprint; no claim of atomic HTTP+Room transaction is made.
- Existing v3.6.5 HTTPS transport still sends only the whitelisted `marketpro_staff_submit_catalog_candidate` RPC, exact Supabase HTTPS origin, JWT in Authorization Header, bounds and timeouts. **Server RPC is NOT deployed to live.**

### Staff candidate picker UI

- `StaffCatalogCandidateUploadPanel.kt` is accessible from **Security & Team Sync → Staff Login & Owner Activation Preview → Staff Phone**. It reads a bounded list of actual Room-vs-Cloud Inbox review candidates (up to 60 shown) and shows Local Item Key, name/category, Cloud UUID/revision. Staff deliberately selects one candidate and confirms before it is submitted.
- The Preview shows an honest empty state when no staged Cloud data exists; it never populates example/fake Cloud items. The **Submit** and confirmation buttons remain **disabled** when the four server/login feature gates are off.
- If someday enabled after separately audited server + real enrollment, the coordinator independently rereads CURRENT Room data after UI selection. Any stale UI selection is not accepted.

### Tests

- `StaffRoomCandidateDecisionTest.kt` tests trusted/same-workspace snapshots, deleted remote, local missing, duplicate name, category mismatch/missing category, active flag mismatch, pending offline edit, Cloud UUID collision and already-linked duplicate.
- `.github/workflows/marketpro-v366-staff-room-evidence-ci.yml` compiles Kotlin/Compose and Room code, runs Kotlin unit regressions + PostgreSQL 17 isolated Owner-only/Staff-only candidate submission security tests from v364, checks all compile-time flags disabled and original Dashboard directory unchanged.

## Deliberate boundaries

- The current Android development APK has `MARKETPRO_LOCAL_PREVIEW=true`, `MARKETPRO_STAFF_LOGIN_ENABLED=false`, `MARKETPRO_TEAM_PULL_RPC_DEPLOYED=false`, `MARKETPRO_CATALOG_REVIEW_RPC_DEPLOYED=false`. Preview Android Manifest strips INTERNET permission, and no live Staff credentials or second writer exist.
- This source branch does NOT deploy v352–v364 database proposals. Every server role/RLS/SECURITY DEFINER RPC and device-bound JWT check still needs coordinated independent security audit and correct activation order.
- There is not yet a durable retry/outbox for pending candidate review submission (a standard purchase outbox must not be reused for catalog approvals); no worker auto-submits candidates, and retries require a fresh Room check.
- Client unit tests and Gradle compilation do not replace instrumented **real Room concurrency/migration tests** on a physical Android device; one Room transaction cannot atomically cover a network call. Future integration must test races, token refresh, revoked Staff phone, 4GB RAM phone, offline reconnect, signed upgrade and actual Owner response.
- Owner-approved mapping must be retrieved by enrolled Staff device, and the current item fingerprint must be rechecked before the final canonical UUID↔local mapping. Real two-phone financial sync is **still NOT complete**: addition lots, Other Expenses Cloud support, financial write/revision merging, date/permission cutover and full Owner correction propagation remain open.
- Existing V3.3.7 phone remains sole live writer. Do not uninstall/clear its storage; preserve old dashboard visual design/URL/login, all original Cloud history and invoice photos.

**No APK published for V3.6.6**: a compiled but offline-disabled development preview must not be presented as working cloud two-phone sync.
