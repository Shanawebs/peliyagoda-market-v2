# Market Pro V3.6.5 — Dormant Android Staff Candidate / Owner Review HTTP Integration

**Status: DRAFT source + isolated CI only. Not a production APK. NO live Supabase schema/row changes.**

## Business outcome we're building toward

Purchasing Staff Phone can enter and correct TODAY's items, requisitions, extra purchases and cash without approval. Previous dates remain staff read-only; Owner Phone can correct any date. After secure sync, these changes appear on both Android phones and on the EXISTING unchanged read-only Cloud Dashboard. Preserve the sole V3.3.7 writer, all old Room data and invoice photos.

## What THIS milestone actually delivers

- `CatalogReviewNetworkPolicy.kt`: pure actor/phone/workspace/session access guard, explicit STAFF submit vs OWNER list/approve, cursor/page constraints, toString redaction. Requires four independent flags enabled before network access: `MARKETPRO_LOCAL_PREVIEW=false`, `MARKETPRO_TEAM_PULL_RPC_DEPLOYED=true`, `MARKETPRO_STAFF_LOGIN_ENABLED=true`, `MARKETPRO_CATALOG_REVIEW_RPC_DEPLOYED=true`.
- `HttpsCatalogReviewRpcTransport.kt`: HTTPS, fixed exact project host, no redirects, no arbitrary RPC names; POSTs ONLY the three proposed v3.6.4 functions: `marketpro_staff_submit_catalog_candidate`, `marketpro_owner_list_catalog_candidates`, `marketpro_owner_approve_submitted_catalog`. Transient JWT Authorization header, public publishable Supabase key, strict 8KB request/256KB response, timeouts, generic 4xx/5xx errors with NO sensitive server body logging. NO hardcoded password or service-role key.
- `CatalogReviewNetworkCoordinator.kt`: uses the **separately encrypted, verified Staff Auth Session** from v3.6.0 or the existing **verified Owner Auth Session**. Cloud app device identity is compared against JWT/session device context; server must still independently verify membership/enrolled device per request. Staff prepares typed candidate via `StaffCatalogCandidatePolicy` and uploads pending-only. Owner fetches a page of server-returned candidates, verifies workspace, strict candidate UUID ordering, expiry envelope, local fingerprint agreement and record structure. Owner can only approve the candidate ID from the **most recently verified page**, with re-confirmation handled in the UI. The server RPC independently validates Owner JWT/phone, Staff active device, candidate still pending and Cloud revision.
- `RemoteStaffCatalogReviewPanel.kt`: a real Compose Owner review UI behind the four off-by-default feature flags. It shows Staff Device/User ID, Local Item ID, Category, Cloud UUID, Revision and fingerprint. Owner must explicitly fetch, select and confirm. When disabled (the current build), it says **'Staff Phone Review Inbox — Not Yet Connected'** and NEVER shows the Owner Phone catalog as a fake Staff Phone record.
- `OwnerCatalogReviewDialog.kt` routes Staff Phone tab to this panel. The pre-existing **This Phone** view and its always-disabled local `Approve Mapping` button are left unchanged. Unlike This Phone, the new remote panel will use server authentication when and only when the release is formally activated.
- `StaffCatalogCandidatePolicy.kt` (v3.6.4) and its tests are retained: Staff phone's record is only *self-declared evidence*, not crypto hardware attestation.
- `CatalogReviewNetworkPolicyTest.kt` ensures all deployment gates are required, Staff cannot perform Owner actions, Owner cannot act as Staff, physical device/workspace mismatches fail, malformed page/cursor denied and Kotlin cannot print the access token.
- CI `marketpro-v365-staff-owner-network-ci.yml` includes existing Android Kotlin/Compose tests, Postgres 17 isolated candidate approval tests, compile, four-off switch verification and strict original Dashboard source freeze.

## Essential limitations: this is not finished two-phone syncing

1. v352–v364 Supabase Security/Invitation/RLS and financial team RPC SQL proposals are **REVIEW ONLY** and MUST NOT be deployed independently or in an unsafe order. There is no active second registered Cloud writer, no live staff login/account or Owner candidate queue. A privileged external security review is still required before any production cutover.
2. `MARKETPRO_LOCAL_PREVIEW=true`, `MARKETPRO_TEAM_PULL_RPC_DEPLOYED=false`, `MARKETPRO_STAFF_LOGIN_ENABLED=false`, `MARKETPRO_CATALOG_REVIEW_RPC_DEPLOYED=false`. The preview Manifest strips INTERNET, and the running app has NO reference to automatically start the transport. Thus it cannot contact Supabase.
3. Owner remote candidate tab only fetches when manually requested and release gates are enabled. Full authenticated **Staff Room snapshot → submit** from a UI/worker is not wired yet; the coordinator accepts vetted snapshot inputs but is dormant. Do not infer that Staff Phone already uploads.
4. Owner approval only writes the private server approval record. Staff phone must **re-download** approved mappings and recheck its *current* Room item fingerprint before creating a canonical UUID↔local Item ID identity. Financial bidirectional Merge and outbox still remain offline/unactivated.
5. Changing staff data after candidate upload causes its fingerprint to become stale. Owner human review is NOT a proof of physical device ownership; enrollment requires secure sessions, strengthened device attestations and revocation.
6. Other Expenses Cloud table and purchase-addition UUID mapping/financial merge remain missing. Old-day staff read-only is enforced in draft server policies only, not in a live dual-phone install.
7. Need real two-phone QA including 4GB Android performance, midnight Asia/Colombo edits, offline corrections, partial network failures, token expiry, owner revocation, catalog history and invoice photo backups, signing and data preservation.
8. The production V3.3.7 is the **only active writer**. Do not uninstall it or clear storage. The original Web Dashboard URL/login/visual design and existing Cloud rows must remain unchanged.

This PR provides a source-level end-to-end **network adapter and Owner UI preparation**, not a deployed Cloud workflow or APK.
