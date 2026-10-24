# Market Pro V3.5.8 — Owner-controlled staff invitation staging

**REVIEW-ONLY code in a draft branch. NO live Supabase schema/auth change, NO staff account creation, NO active second writer, NO deployed APK.**

## Confirmed business rules
- Dedicated purchasing phone: add/correct today's purchases, requisitions, extra lots and cash any number of times; previous days read-only.
- Owner phone: see and correct today's and any past day's records.
- All corrections eventually synchronize bidirectionally, with optimistic conflict control. Existing web dashboard URL/login/UI stays unchanged.
- The current Cloud V3.3.7 writer remains the only deployed writer until full physical two-phone QA and backup.

## The important old RLS safety finding
The existing `workspace_members` SELECT RLS authorizes workspace membership without requiring a registered phone. Granting an invited user a membership too soon can expose workspace data **even if that user's phone is disabled**. This version therefore DOES NOT create a workspace membership or device writer when someone accepts an invitation.

## Pending-only enrollment proposal
`supabase/proposals/v358_owner_invite_staff_PENDING_ONLY.sql`:

1. Existing enrolled Owner with enabled writer device calls `marketpro_owner_invite_staff`, specifying a **different, email-verified Auth user** and their intended device UUID/label. Existing Owner/Viewer/Editor accounts cannot be silently reclassified.
2. Server generates a **cryptographically random 256-bit, 64-character hexadecimal one-time invitation token** with 30-minute expiry, stores only SHA-256 hash in private Postgres schema, and returns secret once to Owner.
3. Staff signs into THEIR OWN confirmed Supabase Auth account and calls `marketpro_staff_accept_invitation` with original device UUID and one-time code. Server locks pending row, verifies actor/expiry/code/device, consumes token.
4. Response is `awaiting_owner_activation_and_server_cutover`. Critically, no records are inserted into `workspace_members` or `registered_devices`; no existing writer or old RLS is altered.
5. `marketpro_owner_revoke_staff_invitation` lets enrolled Owner revoke pending OR claimed invitation before activation.
6. Denied: anonymous users, unverified email, different Auth subject, changed Device ID, replayed/stale/revoked code, self-invite, disabled Owner phone, Viewer trying owner API, existing workspace role promotion. No private token table access for `anon` or `authenticated` SQL roles.

**The local app's 6-digit Owner PIN is not used as an enrollment token.** It is not an operating-system uninstall blocker either.

## Android safety policy
`TeamStaffEnrollmentPolicy.kt`: pure, fail-closed staff eligibility gate. An invitation alone/acceptance alone NEVER sets writer permission. Device, authenticated Staff identity, membership, enabled device, server RPC cutover, confirmed email, app gate and non-owner account must ALL independently match before a future client enters writer mode. Tests cover all denials.

## Staging tests
`supabase/tests/v358_owner_staff_invitation_local_only.sql` builds an isolated PostgreSQL 17 mock Auth/user + device/member DB and tests Owner-approved invite, code hashing, expired/wrong/consumed code, verified subject, device mismatch, owner revoke, non-escalation of existing roles, no live memberships or writers added and endpoint/private table grants.
GitHub CI also compiles Kotlin tests; retains old Dashboard freeze.

## Remaining tasks
- Owner supplies actual purchasing staff email/phone login and confirms ownership of their business Android phone. Never ask user to put password/one-time token in ChatGPT chat; sensitive credentials stay inside secure setup UI.
- Do a verified Cloud backup (including separate backup of local invoice photos), sign/upgrade continuity plan.
- Review RLS per device and complete team RPC v2, catalog UUID linking and date/revision enforcement before any enrollment activation on live Supabase.
- Design protected owner approval activation and device revocation for *already-active* staff phones, and session revocation.
- Implement Auth UI/Keystore session for Staff separately from `CloudOwnerAuth`, deep-link/token transfer UX, actual HTTPS calls, Room inbox sync/correction and Other Expenses backend. Compare two real devices on 4GB RAM.
- After separate test environment passes, staged live cutover, preserving old dashboard appearance and old data.

**No APK**: this is source-only; issuing an apparent working Staff APK now would be misleading.
