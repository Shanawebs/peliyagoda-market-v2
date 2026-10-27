# Market Pro — Single-APK Cloud Sync Phase 1 (2026-10-27)

**User product rule:** ONE Android APK for Owner and all Team phones. Owner = one active phone with past-day corrections; Team = many phones, current Asia/Colombo date edits only. The read-only dashboard is a separate web UI, not a second Android app.

## Delivered to QA Staging

- **Staging Supabase only:** project `qqiheexpbuzohllugxrk`, workspace `af77cb66-76b2-492a-a460-06132f468713`. No production Supabase changes.
- Added `marketpro_private.qa_cloud_sync_v1_gate` with default `intake_enabled=false`, `pull_enabled=false`; tested against temporary PostgreSQL 17 with both gates on and verified live against hosting with gates initially off.
- `marketpro_qa_cloud_request_phone`: binds request to verified Auth member/session. Fresh Owner TOTP AAL2 creates at most one active Owner device. Team devices are requested as **pending, NOT auto-approved**. Requests are limited to eight per Auth member (existing devices count).
- `marketpro_qa_cloud_decide_team_phone`: only the active Owner device with fresh AAL2 can approve/unlink an `editor` Team device. Team devices remain `can_write=false` because legacy `registered_devices_one_enabled_writer_idx` remains intentionally unchanged.
- `marketpro_qa_cloud_list_phones`: Owner may list all workspace requests; Team sees only its own. User ID, label and phone statuses only, no email/password/token.
- `marketpro_team_pull_page`: JWT/session/device-aware keyset pages, exact seven nonphoto types — `category`, `item`, `purchase`, `purchase_addition`, `requisition`, `daily_cash_received`, `daily_other_expenses`. Revocation immediately blocks subsequent download.
- **Android same app**: `stagingMarket` v4.0.2, app ID `lk.peliyagoda.marketpro.staging.fullapp`. Added unified Cloud Phone Management panel (request / refresh / Owner approve / unlink) and **Download Cloud Updates (Safe Stage)** button. It downloads each entity through the existing bounded `TeamPullHttpTransport` and stages verified pages into Room Inbox shadow using `CloudInboxStager`; **does not merge visible Market/Dashboard values**.
- Existing Owner/Team local date edit guards remain in `MarketRepository` and Kotlin unit tests. Staging Team/Owner login roles are from authenticated Supabase membership, not a caller-provided role string.
- PostgreSQL 17 test: single Owner, Owner MFA reject AAL1, Team pending, no self-approve, Owner listing vs Team listing, Owner approval, all seven pull types, revoked Team read denied. Hosted data stayed empty.
- GitHub CI **40460948776 PG17 PASS** and **40460948796 Full Android + tests PASS**. Verified **20,391,091 byte** APK in GitHub prerelease:
  https://github.com/Shanawebs/peliyagoda-market-v2/releases/download/marketpro-unified-cloud-phone-pull-v4.0.2/MarketPro-Unified-CloudPhonePull-Staging-v4.0.2-debug.apk
- **After CI PASS,** Staging-only intake + pull gates turned ON transactionally for on-device QA. Immediately verified: 0 registered devices, Owner test account 1, Team test account 1, 0 purchases, old pending QA Owner key preserved and inactive, anonymous pull false and legacy financial RPC direct access false.

## Test on two actual phones

1. Install the single **Staging** Android package on the Owner and Team devices. **Do not uninstall or overwrite the currently working Production Market Pro**. Staging shares app ID with earlier v4.0.1 but new CI debug signatures may differ; if installing over v4.0.1 fails, **backup that staging test data before uninstalling only the old staging package**. Never uninstall the older pending key test app if retaining its QA identity.
2. On Owner device, use already verified Owner login and finish **Owner Authenticator/TOTP MFA**, then open `Account & Phone` and `Request This Phone`. Server authorizes a single active Owner device.
3. On Team device, use a verified Team test account through **Team Login** in the same APK. Request phone; initial status must be PENDING. Team cannot approve itself or use Cloud Pull before Owner approval.
4. On Owner, `Refresh Linked Phones`, then `Approve Team Phone`. Owner can later `Unlink Team Phone` to revoke Cloud Pull immediately. Team Phone may then use `Download Cloud Updates (Safe Stage)`; expected zero records before catalog/upload goes live.
5. Never share Owner/Staff passwords, Authenticator codes or JWTs in screenshots. Staging Team dotted Gmail identity shares the Owner's inbox; it is a temporary QA user ONLY, not suitable for production staff credential ownership.

## NOT DONE / non-negotiable remaining items

- **Financial Cloud writer**: idempotent, conflict-safe, role-and-current-Colombo-date-enforced server operations for purchases/requisitions/extra lots/cash/expenses. The old single-writer index must remain until audited dual-writer authorisation is implemented. Staff `can_write=false` is NOT evidence of permission to upload.
- **Catalog identity sync & Room visible merge**: local seeded catalog IDs vs cloud UUIDs require verified linking, revision and conflict handling. Current `Download Cloud Updates` stages shadow data only.
- **Automatic two-way sync**: WorkManager upload retries + periodic pull, owner correction propagation, staff prior-date server deny, replacement/unlink, cloud dashboard visibility. No invoice photos in cloud.
- **End-to-end on two physical phones** and 4GB Android responsiveness, plus backup/cutover/rollback. No production release or database migration yet.

## Security advisory notes

Supabase advisory flags four intentionally exposed SECURITY DEFINER RPCs. Their `EXECUTE` is allowed only for `authenticated`, never `anon` or `PUBLIC`, with explicit membership, fresh Auth session, appropriate MFA and active device checks inside functions. This is reviewed as a deliberate staging design, not a no-policy public write. Private gate table has RLS enabled and no client policy by design. HaveIBeenPwned leaked-password protection warning predates this staging feature.

**This is Phase 1, NOT full Cloud Sync. Do not label any Staging APK FINAL.**
