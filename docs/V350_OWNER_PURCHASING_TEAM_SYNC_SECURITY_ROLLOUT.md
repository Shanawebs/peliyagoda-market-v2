# Market Pro V3.5 — two-phone team sync + protection rollout contract

Owner decision 2026-10-24: purchasing team enters daily market transactions on a separately issued Android phone. Owner's Android phone automatically receives those records, and owner can correct entry mistakes. All changes appear on the EXISTING old Cloud Dashboard URL, with its exact existing UI and login intact. Owner wants maximum feasible app/data protection and six-digit authorization.

## Final permission decision from owner (2026-10-24)

- Purchasing team: all valid financial entries, corrections and repeat corrections TODAY only. No daily owner review or approval, regardless of edit count.
- Owner: edits of current and ANY prior business day; old-day editing is owner-exclusive. PIN required for destructive/security actions, not each ordinary edit.
- The day boundary is midnight in `Asia/Colombo`; the **server** must enforce this, not the editable clock on either Android phone.
- Offline operations queued before midnight but delivered after midnight must not silently bypass the rule: server returns a typed conflict/expired-day response, keeps the local unsent change for owner-directed resolution, and never overwrites live data.
- Both apps are read-only on old staff dates, so disable relevant input affordances, but the server check is the final authority. Past-day history remains visible.
- No status-based `Pending Review`, `Approved` or automatic owner locks are required for ordinary same-day corrections.

## Current live state — DO NOT BREAK

- Existing V3.3.7 is the SOLE enrolled Supabase writer, device `d134c7de-8c31-4808-badc-24617d35fdcc`. Working phone→Supabase→old dashboard sync was verified for requisitions, purchases, extra lots, cash and offline retry.
- Current V3.4.1 is a SEPARATE OFFLINE white-popup/Other Expenses preview; it has NO INTERNET permission and has no Cloud login. It does not replace the live app.
- Existing Cloud RPC checks `workspace_members.role = 'owner'`, device.user_id=auth.uid(), and `can_write`. It supports neither staff writers nor server revision-checked two-device corrections. Merely enabling two device rows in the current RPC is unsafe.
- Historical 330+ old demo records and current tests remain; do not erase them or reupload historical Room queues.
- Invoice photos are LOCAL ONLY; JSON backup excludes photos. Do not claim Cloud backup protects images.
- Existing dashboard `https://marketpro-cloud-dashboard.vercel.app/`: **NO design/layout/url/login changes**, and no Vercel redeployment as an implicit part of Android rollout.

## Desired role model (each person uses own Supabase Auth account)

1. OWNER/Manager phone:
   - Can view downloaded requisitions, purchases, addition lots, cash, other expenses, catalog, and status for the shared workspace.
   - Can correct ANY team's amount/quantity/remark after explicit current-revision check and record the corrective action.
   - Can correct today's entries and ANY previous business date's entries without a review queue; owner identity must be authenticated. Owner-only high-risk security actions require a **fresh** 6-digit local PIN AND server-verified owner role. No reusable "PIN verified" boolean for online server permission.
   - App lock on resuming after idle period; biometric optional only when supported. PIN verifier stays in Android Keystore with lockout; no hard-coded universal secret.
2. PURCHASING STAFF phone:
   - Can add AND CORRECT requisitions, purchases, extra lots, daily cash, other expense amounts and remarks **unlimited times on today's Sri Lanka business date**. No owner approval/review for same-day edits.
   - Can VIEW old days but can NEVER enter, amend or backdate an entry from any past date. Previous dates are **read-only on the Purchasing Team phone**; only the Owner account can correct them. Cannot invite/revoke users, edit catalog or delete records.
   - Every write carries staff account subject ID + device UUID + idempotent operation ID; no owner password stored on staff device.
3. VIEWER (optional later): read-only, no mutations.

## End-to-end sync contract

- Room remains local-first. Each local mutation and its durable outbox operation committed in one Room transaction.
- Staff app syncs **up** only as authenticated, enrolled staff writer; server checks verified workspace membership and role per operation.
- Owner phone syncs **down** from same Cloud workspace. Supabase Realtime (RLS) may *signal* an update while the app is in foreground; it must trigger a fresh authenticated pull. Always perform initial full protected snapshot + cursored incremental catch-up on reconnect and app restart. Realtime stream must never be the only source of truth.
- Download/merge maps stable Cloud UUID to local Room item IDs. Never remap on name alone, never clear unsynced local edits, never requeue downloaded rows for upload or create echo-loops.
- EVERY staff or owner correction (including the tenth edit on the same day) sends the same canonical Cloud row UUID and an `expected_revision`. The server recomputes today using `(now() AT TIME ZONE 'Asia/Colombo')::date` under an authenticated transaction, disallows staff writes on earlier/future dates, and changes the row only when the expected revision is current. Audit actor, previous/new values and UTC time, increment revision. Stale edits are CONFLICTS: refresh and retry only when still authorized; never overwrite.
- Do not use last-write-wins or owner-review locks for same-day edits. Staff may keep editing today's entry after an owner correction, PROVIDED they have fetched the latest revision. As soon as Sri Lanka business date rolls over to the next day, staff becomes strictly read-only for that old date. Owner can still correct previous days. All corrections must propagate to both phones and the unchanged web dashboard.
- WorkManager network-constrained retry for pending uploads and periodic catch-up; Android background execution can delay delivery, so call it near-real-time, NOT a guaranteed instant push. Owner app should update while foreground; preserve outbox until ACK.
- Same old web dashboard can keep its ~15 s polling UI; add only required backend data shape/views/RPC to support corrected amounts and Other Expenses. NO frontend visual change.

## Full protection — what's technically possible

- Local 6-digit PIN protected by Android Keystore HMAC, fresh PIN challenge for Restore/Delete/Archive/Catalog changes/staff registration/device revocation. Retain failed-attempt throttle, never commit PIN or shared hash to GitHub. Server requires Owner authentication separately.
- Android OS uninstall CANNOT be intercepted or made to ask app-specific PIN on every normal consumer Android. Do not use accessibility tricks or misleading persistent notifications to claim otherwise.
- Company-owned **fully managed Android Enterprise device owner** can apply `DevicePolicyManager.setUninstallBlocked()` through an authorised DPC/MDM. Provision and consent BEFORE use, and confirm the device belongs to business. Some provisioning flows may require reset/re-enrollment: never reset an active data-carrying phone without independent verified backup. The purchasing phone is the appropriate candidate; personal owner phone remains normal.
- Safe fallback for ANY normal Android: encrypted auth tokens stored with Android Keystore, auto-lock PIN, remote device revocation, daily/exportable off-device backups for non-synced photos, automatic cloud syncing, pending change warning and explicit backup reminders. A locally saved JSON export is not a secure *off-device* backup until actually copied to a safe second location.
- Session revocation on lost staff phone; remote revoke operates on authenticated device registration and rotates staff credentials, then downloads latest authoritative Cloud state to replacement device.
- Allow owner to remove a managed device policy when necessary; do not secretly prevent authorised device owner from maintenance/uninstalling.

## Server rollout gates (NOT EXECUTED)

1. Authoritative database backup/snapshot and immutable old business totals; no deletions.
2. Create a distinct staff Supabase Auth account once owner has identified staff email/phone. Staff does not use owner credentials.
3. SQL design and security audit for workspace role `purchasing_staff`, two registered devices and owner-only corrections; consistent `registered_devices.user_id = auth.uid()` check.
4. Server-side RPC v2 must support `expected_revision`, compute authoritative current date in Asia/Colombo and enforce: staff unlimited writes to TODAY only; owner edits past or today; no future dates; no approval/review workflow. Recheck date+revision and update audit within the same locked transaction; reject stale edits with a typed conflict status.
5. Separate whitelisted operations, SELECT RLS for both roles, true server-side restrictions, NO service_role key shipped in APK, security-definer search_path fixed and execute privileges narrow.
6. Owner-device download engine, safe Room schema migration, correction UI, conflict feedback and testing on 4GB phones; same signing identity/Device UUID upgrade strategy. Do NOT change installed V3.3.7 app's package or clear storage to upgrade.
7. Web financial summary backend includes Other Expenses when Cloud table exists; no old dashboard UI change.
8. Test: staff edits today's purchase repeatedly (at least 10 changes), owner receives/corrects, staff sees correction and may edit again with latest revision; switch server date across midnight and verify yesterday becomes view-only for staff on BOTH online RPC and downloaded UI; owner can correct yesterday, staff cannot; simulated clock tampering cannot bypass. Offline after-midnight backlog needs a policy/owner reconciliation, not silent overwrite.
9. Recovery: lost/uninstalled staff phone → revoke device → reinstall login with new device identity → safe full pull. Local invoice photos require separate actual backup/export.

## This initial branch's ACTUAL deliverables

- `TeamAccessPolicy.kt` pure Kotlin two-role/three-role permission matrix.
- `TeamRevisionPolicy` rejects stale revisions and duplicate business keys; no owner review required for today's staff corrections. `TeamAccessPolicy` additionally fails closed without verified server date and denies all past-day financial edits to staff.
- Unit tests for the rules.
- New Security & Team Sync in-app informational dialog and existing Keystore PIN setup/verification. Existing Restore PIN guard continues. This preview cannot alter Android system uninstall.
- **No live multiwriter RPC, no Cloud phone-downloader, no two-phone APK deployment, no server changes.** The old Cloud Phone continues working unchanged.

## Critical launch prerequisites from the owner

- Dedicated purchasing phone available with a separate staff login/contact, and confirmation whether the company fully manages it (for optional uninstall-block MDM).
- Owner phone V3.3.7 local backup verified; photos preserved independently.
- Separate staff phone physical enrolment ID after installing a future tested staff APK, not the existing V3.3.7 writer ID.
