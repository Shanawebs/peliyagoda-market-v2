# Market Pro V3 — Fresh Transaction Cloud Sync Acceptance Test

Decision: 2026-10-23 (Asia/Colombo)
Test business date: 2026-10-23

## Non-negotiable preservation constraints
- Keep ALL existing Supabase records including the original 330 demo records; do not delete, truncate, overwrite, reset or reimport.
- User confirmed old V2 records are demo. Archival/deletion is OPTIONAL LATER only after a separately verified backup, review of which records belong to each test run, and explicit approval.
- Keep CURRENT dashboard unchanged in code, UI, URL, login and workspace:
  https://marketpro-cloud-dashboard.vercel.app/
- Android V3 must send new entries into existing Supabase project and workspace, never a separate workspace or dashboard.
- Do not count the old 330 rows as a successful V3 upload; original V3.34 is read-only and has no uploader.

## Read-only baseline checked 2026-10-23
- Categories 3, items 184, requisitions 105, purchases 26, additions 10, daily_cash_received 2.
- Existing sync_operations total at audit: 331, latest applied_at 2026-10-22 14:23:52+00.
- Relevant 8 table sizes including indexes: ~808 kB; no current operational reason to delete demo records for capacity.
- Original V2.31 device writer remains enabled server-side. The test V3 app is not enrolled.
- Today's phone-originated V3 auto-sync test is NOT yet conducted.

## Prerequisites to testing
1. Android app release explicitly supports existing-workspace attach (NO legacy empty-workspace initial import), with correct authenticated owner and device enrollment.
2. Controlled retirement of the old writer or server-authenticated single-writer switchover; do not allow both old and new writers simultaneously.
3. Safely bind the existing Cloud catalog IDs for selections, without bulk-queuing/restoring historical demo purchases.
4. Confirm empty/preserved local unsent queue on the newly installed test app and offline-first journal transactions.
5. CI JVM/unit tests, APK signing/install verification, read-only Supabase checks, and live UI-preservation diff guard pass. User receives the actual tested APK prior to any phone input.
6. Current dashboard remains on its existing 15-second visible-tab refresh mechanism (near-real-time, not instant guaranteed).

## Actual phone-driven smoke test ONLY AFTER these prerequisites
A. Create exactly ONE new requisition on test date 2026-10-23 for Main Kitchen, one existing catalog item and quantity; compare with original web dashboard.
B. Enter exactly ONE purchase (same catalog item, 1.0 kg at LKR 100 per kg, clearly demo); verify pending -> accepted -> pending zero; compare dashboard date, quantity, rate and expense.
C. Enter a test cash-received amount (e.g. LKR 500) for that date; compare cash received and balance.
D. Turn mobile data OFF, enter a SECOND test edit; verify Room saves and pending remains. Restore connectivity; verify eventual Cloud ACK and dashboard refresh without clicking Sync Now.
E. Re-edit the purchase once and confirm the existing Cloud UUID is updated without creating a duplicate entry.
F. Use the OLD dashboard URL in a signed-in browser tab. Dashboard visible-tab refresh polls around every 15 seconds; measure the actual latency instead of promising instant propagation.
G. Read-only audit totals and operations ledger after test. Preserve all older values and records.

## Go / no-go
- Fail if old rows are removed or overwritten, new rows duplicate, two writers activate, invoice photos upload, owner credentials leak, sync stalls, or original dashboard source/UI changes.
- Do not claim the test PASSED until actual user-phone writes and read-only Cloud/dashboard verification occur.

## Revision-safe scope
This document is the test plan ONLY. It does not enroll devices, modify Cloud data, deploy web UI, build APK or enable upload.
