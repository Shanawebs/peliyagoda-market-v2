# Cloud V1 — Expanded Records, No Photos (2026-10-21)

## Explicit user-approved scope
- **Kitchen requisitions:** Main Kitchen / Indian Kitchen / Pastry Kitchen (displayed Bar/Pastry).
- **Purchase records:** initial purchased grams and price cents per kg.
- **Additional purchase lots:** independent quantities and unit prices.
- **Daily cash received** recorded in cents.
- **Total expenses** and **cash balance** displayed via an RLS-protected daily SQL view, not duplicate stored totals.
- **Items/categories** only as needed to associate requisitions and purchases.

## Financial parity with current Android V2.28
`MarketMath.billCents` uses exact integer inputs and BigDecimal HALF_UP on **each** lot:
`round(quantityGrams * priceCentsPerKg / 1000)` cents. The cloud view performs exactly this rounding on **each lot**, then sums initial and additional costs. Cash Balance = Cash Received − Total Expenses; negative values are allowed. The summary includes requisition-only, cash-only and purchase-only dates. Cloud numbers remain exact NUMERIC cents with no floating-point imprecision.

## Invoice photo exclusion
**Do not sync original invoice images, file paths, attachments, photo metadata, or create any Supabase Storage bucket.** Photo capture and original-quality storage continue on the Android phone. The existing JSON backup DOES NOT include invoice images.

## Deployment order & safety
1. Preserve original DEV migration `20261021174526_cloud_v1_purchase_only_foundation.sql`.
2. Apply only the **new additive** `20261021180500_expand_requisitions_cash_financial_summary.sql` on DEV, after local migrations and security tests pass.
3. Check table RLS, caller read permissions, cross-workspace access, no direct write grants, view security invoker, and photo bucket absence.
4. Data is NOT uploaded automatically by creating tables. Android current version remains unchanged; no new APK built here.
5. Next milestone is authenticated single-phone sync using add-only Room migration, outbox, idempotent RPC, WorkManager, historical ID mapping, and validated snapshot import. Multi-phone later.

Cloud schema status and actual hosted migration version must be checked and recorded after deploy.
