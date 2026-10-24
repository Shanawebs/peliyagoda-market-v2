# V3.5.9 — Owner-controlled read-only staff activation and Login UI preview

**All SQL is REVIEW ONLY. Not deployed to production. Existing Cloud V3.3.7 single writer and Market Pro Dashboard UI/URL/login remain unchanged.**

## Real discovered security gap

Current `member_*_read` and `workspace_*_read` policies on the six live financial/catalog tables check workspace membership with `marketpro_private.is_workspace_member(workspace_id)`, without checking an enrolled device. Adding a staff workspace membership would therefore grant direct PostgREST reads even if the staff phone were disabled. Simply limiting `can_write` cannot prevent that.

## Proposed server-side change

`supabase/proposals/v359_staff_readonly_activation_rls_REVIEW_ONLY.sql`:

- Keeps existing Owner, Editor and Viewer reading existing catalog/financial data **unchanged**, by replacing the six direct SELECT policies with role-aware owner/editor/viewer membership checks. Purchasing staff are denied direct REST reads regardless of knowing workspace ID. The existing authenticated `marketpro_team_pull_page` RPC proposal enforces verified staff user/device and workspace for all actual staff record downloads.
- Has private, default-false rollout gates. The user cannot set them from Android; a reviewed administrator-controlled migration must independently confirm a device-bound authenticated pull RPC and deny-direct-REST policies before the gate can be enabled.
- Owner may call `marketpro_owner_activate_staff_readonly` only with a verified Owner JWT and enrolled Owner device, accepted staff invitation (within 24 hours), still-confirmed staff email and non-duplicate new staff device.
- Activation creates staff `workspace_members` entry and registered staff phone with `enabled=true, can_write=false`. No staff writer can be created by this RPC; current old v1 owner writer is unchanged.
- Existing Owner may revoke staff via `marketpro_owner_revoke_staff_device`, setting `enabled=false, can_write=false`. Owner device cannot be revoked through staff endpoint.
- Function EXECUTE denied for anonymous/public and authorized by authenticated server-side Owner verification. No active server changes at this stage.

### Important further security before production

- Server RPCs are DRAFT and need full audit, access-control tests under real PostgREST JWT context and deployment.
- RLS policy review across all other exposed tables/views, e.g., members and devices. Financial tables explicitly deny Purchasing Staff's direct REST reads and only allow access from authenticated device-bound RPC.
- Staff writer activation is intentionally **not** implemented here. This awaits all revision-aware team-write RPCs, account/device enrollment, catalog UUID mapping, true Android Room pull/merge, offline-midnight reconciliation and reviewed owner consent.
- Existing live Owner UI and Cloud dashboard read behavior must remain exactly as before. Existing financial data never deleted or reset.
- Staff Auth client, encrypted per-staff token storage and owner-only activation UI with real authenticated network calls are NOT deployed.

## Offline Android Staff/Owner screen

`TeamAccessSetupDialog.kt` is a read-only preview reachable from the existing **Security & Team Sync** popup.

- Staff tab shows separate verified account requirements and a **DISABLED** email field. It deliberately does not collect any password or one-time code.
- Owner tab shows invite → claim → read-only activation → later writer cutover, as well as staff phone revocation.
- No fake success notifications, no functional login, no live Supabase auth calls and no new INTERNET permission. `MARKETPRO_LOCAL_PREVIEW=true`, `MARKETPRO_TEAM_PULL_RPC_DEPLOYED=false` and new `MARKETPRO_STAFF_LOGIN_ENABLED=false`.
- The current app header/APK version remains **3.5.0** because this is source-only, not a downloadable v3.5.9 APK.

## Test evidence

`supabase/tests/v359_staff_activation_rls_pg17_local_only.sql` tests **real PostgreSQL RLS checks** by switching to `authenticated` role (not only function text inspection): staff's direct access to six financial/catalog tables is 0; Owner/Viewer legacy readers still see the correct workspace and cannot see other workspace purchases.

Also tests default-false server rollout gate denial, missing reviewed pull RPC, accepted invite/verified staff account, owner-only activation, staff writer disabled, replay denial, staff/Viewer inability to revoke and Owner successful device revoke without affecting the single original writer. Existing Kotlin/Room tests and Kotlin compilation remain part of CI.

## Production readiness

The full two-phone live sync is **NOT READY**. The Staff Phone needs its own Auth UI, protected enrollment, a reviewed server v2 write/read layer, catalog UUID mappings, real automated Room import and corrections, separate Other Expenses cloud table, secure old-day read-only UI and two physical Android devices tested. Do not uninstall v3.3.7, delete any historical records, or alter the current Dashboard.
