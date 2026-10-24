# Market Pro V3.6.8 — Owner Can Revoke or Replace a Staff Phone Any Time

**DRAFT ONLY; NO LIVE Cloud role/device/RLS/financial changes. NO NEW APK.**

## User requirement

Allow the Owner to remove a former Purchasing Team phone at ANY time — lost, damaged, stolen, replaced, or staff member leaving — and connect another phone without deleting Cloud historical purchasing data, requisitions, extra purchases, cash or other expenses. Keep the Owner phone's legacy V3.3.7 Cloud writer, original Dashboard URL/UI/login and invoice photos intact.

## Two safe operations

### Emergency "Disable Old Staff Phone" (new reviewed RPC)
`marketpro_owner_disable_team_phone(workspace,owner_device,staff_user,old_device)`:
- Verified enrolled **Owner** JWT + active writing Owner device required. Staff/Viewer/anonymous can't invoke it.
- Existing **Staff** membership and exact registered phone ownership verified. Owner phone never eligible for this action.
- Atomically sets old phone `enabled=false,can_write=false` and revokes a matching pending invitation token. Audit in a private table.
- May be used **immediately**, even when the replacement phone is not available. Preserves every Cloud data row and staff membership.
- Old phone's JWT can still be valid until expiry, but every audited team read/write RPC checks the registered device `enabled` flag on each request. Under v359 hardened staff REST RLS, staff workspace membership **alone does not expose financial tables**. All other deployed endpoints/views must be audited too.
- Offline local data on an already lost phone CANNOT be erased or recovered remotely. If the phone is available and contains pending uploads/invoice photos, save/backup those first.

### "Replace Team Phone" (new reviewed RPC)
`marketpro_owner_replace_team_phone(workspace,owner_device,staff_user,old_device,new_device,label)`:
- Owner verified again. Staff must already belong to workspace with purchasing_staff role and have verified email; old phone must belong to that same staff account; new UUID must NEVER have been registered.
- Single database transaction: revoke old phone first; preserve membership & historical Cloud rows; replace previous pending invitation hash with a new **random 256-bit one-time code** valid 30 minutes; create private audit entry.
- The new device has NO active Cloud membership/device or writer privilege during invitation.
- If transferring repeatedly, reject an outdated retired phone ID when a newer replacement already exists. A concurrently active other staff phone also blocks transfer to prevent extra simultaneous devices.

### Enroll new phone
1. Staff signs in to their own verified Auth account on new phone; obtains new local device ID, not the Owner's credentials.
2. Staff accepts the short-lived invitation through existing `marketpro_staff_accept_invitation`. Old device cannot claim this new code; code single-use.
3. Owner uses `marketpro_owner_activate_replacement_readonly` after independently audited RLS and device-bound pull gates. This is separate from v359's initial enrollment, which correctly forbids re-adding an existing Staff membership.
4. The new `registered_devices` row is `enabled=true,can_write=false` **initially**. Its future write activation must be independently audited and only then gated to TODAY's corrections on Colombo server date. No unsafe old v1 writer endpoint.
5. Newly enrolled phone can eventually download **Cloud-synced** history (catalog UUIDs must be mapped safely first) and resume staff work. Old revoked phone remains disabled, even if it still has its local app and valid token.

## Implemented files

- `supabase/proposals/v368_owner_replace_team_phone_REVIEW_ONLY.sql`: 3 protected Owner RPCs (disable, atomic replace, read-only activation), private immutable-style audit, no live deployment. No role escalation, no historical table mutation.
- `supabase/tests/v368_team_phone_replacement_pg17_local_only.sql`: isolated disposable PG17 end-to-end test that performs initial enrollment, Owner replacement, revocation, staff claim, gate refusal, activated read-only phone, repeat transfer and emergency disable, stale-old-ID and old-Device REST RLS denial, Owner/Viewer/staff/anonymous protection, and original Owner writer/history preservation.
- `TeamPhoneReplacementPolicy.kt` with JVM tests protects against targeting Owner phone, reusing old ID, malformed UUID/label and ignores the new server network switch unless all other security gates have passed.
- `TeamPhoneManagementPreview.kt` in **Security & Team Sync → Owner Phone** explains exactly how to disable/replace, and warns about old-phone unsynced data. Buttons **DISABLED** until real login/session/network device management is independently deployed; never fake success.
- `MARKETPRO_TEAM_PHONE_TRANSFER_RPC_DEPLOYED=false` in Gradle, on top of the existing `MARKETPRO_LOCAL_PREVIEW=true`, Team Pull and Staff Login flags false. Manifest still strips INTERNET.
- `.github/workflows/marketpro-v368-team-phone-replacement-ci.yml` runs PostgreSQL 17 and Android Kotlin/Compose + Dashboard no-change guard.

## Critical blockers before making it live

- Currently NO live Staff account/enrolled device and NO production SQL migration. v358/v359/v368 SQL proposals, hardened staff RLS, and ALL device-bound read/write RPCs must be deployed **together only after security audit**; do not selectively apply these files.
- Raw app-generated device UUID is not cryptographically strong proof of a physical handset. Hardware-backed enrollment/device authentication and JWT session controls are strongly recommended before actual staff access. A stolen Staff password plus copied UUID is a separate risk from revocation.
- New source file `OwnerTeamPhoneTransferClient.kt` now prepares a fixed-host, Owner JWT-only HTTPS adapter for these three RPCs, behind the fourth default-FALSE server gate. **It is dormant and not invoked by the preview UI**; an interactive Owner-confirmation workflow, new-phone pairing/claim and real two-phone tests must still be integrated before release.
- The Owner Client UI's two management controls are deliberately DISABLED. A real owner-confirmation interaction, new-phone pairing exchange and read-only activation UI must be integrated and tested before enabling them.
- Reconnect cannot transfer **unsynced local-only entries** from a destroyed/lost phone, and an offline revoked phone may still display its locally cached records. Force owner/staff to backup pending outbox and invoice photos if the old device remains accessible, and offer server-sync verification before replacement. Disabling old access prevents *future* server operations only.
- Cloud history preservation doesn't mean new phone can instantly read all data: real owner/staff automatic paged download and Room merge, catalog mapping, purchase/extra lots/requisitions/cash and Other Expenses sync still require full device QA.
- Existing production **V3.3.7 owner phone remains the only active writer**. Do not uninstall, clear App storage, overwrite local history or change original web dashboard while finishing future releases.
