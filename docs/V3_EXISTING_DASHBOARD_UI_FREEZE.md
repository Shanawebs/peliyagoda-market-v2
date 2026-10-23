# Market Pro V3: Keep Existing Cloud Dashboard 100% Unchanged

Decision recorded: 2026-10-23
Status: Architecture lock / implementation pending. This file is not a claim that V3 auto-sync is already enabled.

## Non-negotiable acceptance contract

- The EXISTING dashboard URL remains `https://marketpro-cloud-dashboard.vercel.app/`.
- Existing sign-in, visual layout, styling, navigation, charts, calendar, PDF actions, and user-facing content stay unchanged.
- No new dashboard, subdomain, visible workspace selector, login form or extra sign-in.
- The dashboard continues reading its EXISTING Supabase project and EXISTING authenticated workspace (`e46b90c5-ed6a-4952-82e9-8582fac06d89`).
- Leave `web/marketpro-dashboard/` unmodified in the V3 Android auto-sync feature; CI enforces this against the V3.34 baseline commit.
- The V3 Android app must send fresh purchase, kitchen requisition, extra purchase-lot, cash and catalog changes using the already authenticated workspace, so existing dashboard queries naturally display them.
- Existing web app polls about every 15 seconds while visible. This is *near-real-time* with network latency, not an instant push guarantee. Do not promise zero latency.

## DEMO DATA clarification

User confirmed ALL old V2 entries are TEST/DEMO ONLY. No requirement to upload, duplicate, or reconcile all 330 legacy records into the V3 app.
This does NOT give permission to erase existing Supabase rows or uninstall old phone apps.
For the first safe integration, keep existing Supabase demo rows as-is. They may remain visible alongside new test purchases on the unchanged dashboard; never silently include them in a production accounting report without clear differentiation.
Separately consider a future reversible cleanup/archival only with explicit user approval; never perform it as a prerequisite for Android sync.
Do NOT create a second workspace for this milestone, because the current dashboard chooses a single workspace membership and requires no UI/data-routing changes if V3 uses the existing one.

## Android V3 design constraints

1. V3 must use the existing Supabase project / workspace and original owner sign-in, with only the public publishable key in the APK.
2. No bulk reupload of the 330 restored demo rows. Do NOT call legacy `enableAfterOwnerConfirmation()`: it requires an empty workspace and queues every historical row.
3. Retire/restrict the old V2.31 writer authorization before authorizing the V3 writer; enforce one active writer per workspace server-side and require an authenticated, owner-verified handover.
4. For existing Cloud catalog rows, download/match stable Supabase UUIDs without creating duplicate items/categories. For NEW local business entries, generate stable UUIDs and durable local outbox entries atomically with the Room write.
5. Use authenticated `marketpro_apply_change` RPC with registered-device checks, and require server ACK before deleting the matching pending operation. Never bypass RLS; never publish service_role keys.
6. New entries sync immediately on connectivity; offline edits remain queued and retry with WorkManager. UI must remain responsive on 4GB Android devices.
7. Test exactly one new requisition, purchase, extra lot and cash entry end-to-end: local committed -> RPC accepted -> Supabase row present -> existing dashboard displays the same amount/date/item on its natural refresh.
8. Exercise duplicates, retries, conflict/revision handling, Android background restrictions, token expiry, app restart and loss of connectivity. Prevent the 330 demo records from unintentionally being reuploaded or overwritten.
9. Maintain the V3.34 separate-app local backup/AndroidKeyStore safety. Never uninstall or clear data to "fix" sync, and verify signing compatibility for any in-place upgrade.

## Release gates

- An automated CI diff must show zero changes under `web/marketpro-dashboard/`.
- Existing dashboard deployment and Vercel project must not be redeployed as a side effect of this milestone.
- A separate approved and tested Android branch / signed APK is required for the actual sync release.
- No live database mutation in this architecture checkpoint.
