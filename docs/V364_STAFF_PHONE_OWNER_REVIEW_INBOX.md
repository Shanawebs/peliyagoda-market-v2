# Market Pro V3.6.4 — Staff Phone → Owner Catalog Review Inbox

**DRAFT SOURCE CODE ONLY. No live Supabase migration, staff user/device, APK or background sync.**

## End-user goal

Purchasing Staff Phone and Owner Phone must eventually share **verified canonical Cloud UUIDs** for catalog, purchases, requisitions, additional lots and cash without attaching financial history to a wrong Room item. Staff can freely correct today's entries, older days remain Owner-only. Original V3.3.7 writer and existing Dashboard URL/login/visual UI unchanged.

## New server proposal — isolated/private only

`supabase/proposals/v364_staff_candidate_owner_review_REVIEW_ONLY.sql`:

1. `marketpro_staff_submit_catalog_candidate` accepts a self-declared Room record candidate from an authenticated, enrolled and enabled `purchasing_staff` device. It validates workspace, cloud category/item UUID, current Cloud revision, canonical positive Room ID (items), local category/name/active fields and a 64-digit SHA-256 local fingerprint. Stores **pending only** in a PRIVATE schema. This does NOT map anything or write financial data.
2. Staff retry while pending for the *same local key and Cloud UUID* rotates candidate UUID so a stale Owner selection becomes invalid. Changed local IDs, consumed approvals or identity collisions cannot be silently overwritten. Candidate expires after 24 hours.
3. `marketpro_owner_list_catalog_candidates` reads this protected queue only to an authenticated Owner's registered, enabled **writer phone**, returns up to 50 rows per cursor page, filtered by workspace and **currently enabled staff devices**. It exposes the submitting staff account and device ID, local name/category/active flag, local key, Cloud UUID/revision and fingerprint for explicit human review. This is not a publicly accessible table or REST row.
4. `marketpro_owner_approve_submitted_catalog` takes only **candidate UUID**, not arbitrary user-supplied local IDs/fingerprints. It verifies Owner auth/device, candidate still pending/unexpired, source staff device still active, Cloud item/category still belongs to workspace with the **same revision** and same Cloud category reference; atomically writes the existing private owner approval record (v362) and marks candidate approved. Staff cannot call this endpoint.
5. **Security fix:** explicitly revoke authenticated EXECUTE on the earlier v362 direct `marketpro_owner_approve_catalog_link` function, which accepted arbitrary local IDs/fingerprints. Do NOT publish v362 on its own; future staged deployment must run migrations in order with all new role/device/RLS policies audited first.
6. This workflow NEVER grants a second writer, changes data/catalog identities or sends a photo. Server-side permissions still depend on the existing **unreleased** staff enrollment/RLS and new team RPC proposals.

`supabase/tests/v364_staff_candidate_owner_review_pg17_local_only.sql` validates the actual PostgreSQL 17 function behavior: staff submit pending, spoofed/disabled phone and viewer/anonymous denial, stale revision and cross-workspace refusal, exact Owner paged view, digest preservation, changed candidate UUID on resubmission, consumption, replay rejection, previous insecure v362 RPC permission revoked, and original sole Owner writer unchanged.

## Android foundations

`StaffCatalogCandidatePolicy.kt` builds a read-only validated **draft** from Cloud Inbox and current Room candidate data: explicit workspace/device UUID, Item/Category local key, Cloud UUID/revision, SHA-256 fingerprint and current local name/category/active fields. Rejects pending uploads, deleted rows, invalid IDs and unverified workspace. Staff must later submit via separately authenticated secure HTTPS (not yet integrated).

`OwnerRemoteCandidatePolicy` classifies the future Owner-side authenticated server records **for a specific enrolled staff device**. It is not a certificate, hardware attestation or proof that the staff phone wasn't tampered with. Owner needs to visually confirm all fields and server-trusted device/session source.

`OwnerCatalogReviewDialog.kt` now has **This Phone** and **Staff Phone** tabs. This Phone displays actual read-only local Room↔Inbox candidates from V3.6.3. Staff Phone truthfully displays **Not Yet Connected**, with no fabricated staff items. Approval remains **DISABLED** until real Owner auth, remote candidate transport and server RLS/RPC cutover are verified.

JVM tests `StaffCatalogCandidatePolicyTest.kt` exercise trusted IDs/fields, a real Room-int vs Cloud-UUID separation, deleted/stale/pending candidate rejection, and wrong staff device/bogus digest refusal. CI runs Kotlin/Compose compilation and PG17 independently and checks old dashboard is untouched.

## Remaining blockers before full two-phone sync

- **Real Staff authentication and phone enrollment is still off**. Server-side proposals NOT deployed live. `MARKETPRO_LOCAL_PREVIEW=true`, Staff auth and team RPC flags false; preview Manifest lacks INTERNET. There is no automatic network upload/download, no joined Writer, no running approved mapping.
- **A submitted device UUID can be forged by someone who possesses staff credentials.** Real secure hardware-backed device attestation or additional signed enrollment proof would strengthen this; accepted candidates are *staff-declared evidence*, never automatically trusted as physical truth.
- Actual Staff-side snapshot/HTTP call and Owner-side authenticated RPC fetch + review presentation + audited button + local approval delivery/confirmation are not activated.
- Owner must compare staff-provided local details against canonical Cloud item/category, not rely on name-only matching; client must recheck the **current** Room fingerprint and local pending edits before applying an approved link.
- Device revocation, RLS for all tables/views, dynamic catalog revision concurrency, re-sync and collision-resolution UI, purchase addition/Other Expenses backend, offline-midnight test cases, existing Room backup & photo backup, signing continuity and two real 4GB phone trials remain required.
- Old V3.3.7 must remain installed. This draft does not modify original V3.3.7 app, production Cloud SQL/users/records, or web Dashboard.

**No V3.6.4 APK**: partial code not a functioning live sync release.
