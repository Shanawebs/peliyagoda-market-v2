# V3.36 Fresh-Only Upload Safety Gate (source-only)

Decision context: the user confirms existing V2 entries are demo and asks to keep all old Cloud data, use the SAME dashboard and upload NEW V3 phone transactions only. Existing web UI and backend schema remain untouched.

## Existing Cloud facts (read-only 2026-10-23)
- All old financial and catalog records remain in Supabase. Data retention is NOT a blocker; no deletion authorized.
- The Cloud ALREADY contains a purchase and Main Kitchen requisition for 2026-10-23 on the same master item (අමු මිරිස්). Using that same day and item for a brand-new test row would conflict with the existing unique business-key constraints.
- Database unique constraints include: purchases(workspace_id,business_date,item_id), requisitions(workspace_id,business_date,kitchen,item_id), cash(workspace_id,business_date). The first two safely reject a duplicate business key instead of silently updating another row with a different record UUID.
- Original V2.31 server writer is enabled; current V3 preview is locked.
- The EXISTING dashboard at https://marketpro-cloud-dashboard.vercel.app/ refreshes around 15 seconds while visible and has NO approved UI changes.

## New pure Kotlin gate

FreshOnlyUploadPolicy.kt assesses a single candidate BEFORE a future upload adapter. The candidate must:
1. Be one of purchase/requisition/extra-lot/cash, upsert only; catalog modifications and deletions are not part of the first smoke test.
2. Be generated AFTER an explicitly approved writer enrollment, with durable fresh-owned UUID provenance, not a bulk-restored demo journal.
3. Have no match to a protected historical Cloud UUID OR historical semantic business key.
4. Use a Cloud item UUID that has been validated against existing remote catalog identities.
5. Not collide with any current server-owned semantic business key unless it is an idempotent retry of the *same new row UUID*.
6. Pass the separate owner, single-writer-handover and Cloud snapshot prerequisites.

The policy is source-only and HAS NOT BEEN CONNECTED TO a live worker. Do not interpret unit tests as proof of actual live sync.

## Required implementation adapter BEFORE production

- Persist fresh-owned UUID origin and approval checkpoint in Room in the same atomic transaction as the business edit and outbox row. A UI checkbox or wall-clock timestamp is NOT trusted provenance.
- GET current Cloud business-key owner under authenticated RLS, and check the server UUID before attempting any insert. Server RPC must revalidate under the same serialized transaction (TOCTOU protection). The existing legacy RPC does not yet enforce an immutable historical baseline.
- Verify the old registered writer is retired and the new writer enrolled atomically with actual server owner authorization.
- Existing workspace/catalog IDs are matched by read-only audit; do not use the old empty-workspace bootstrap or upload the 330 legacy rows.
- Authenticate in the V3 app, then schedule WorkManager immediate network-constrained retries, preserving server idempotency and matching operation ACK rules.
- Test new phone -> existing Cloud database -> UNMODIFIED old web dashboard plus offline edits, conflict/failure recovery. Upload NONE until all gates pass.

## Current deliverable boundaries
- NO Android APK built or installed.
- NO server RPC/DB/schema/writer access modified.
- NO Cloud transaction inserted.
- NO files under web/marketpro-dashboard/ modified. CI is intended to check this.
- NO source change has enabled V3 background upload.
