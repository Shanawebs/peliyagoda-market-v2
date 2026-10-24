# Market Pro V3.4.0 — Other Expenses via existing Cash Received popup

## Confirmed user UI placement

- Do **not** create an Other Expenses tile/card on the Android Dashboard.
- Keep the **Market tab → Cash Received** entry point and existing Cash Received amount field / auto-save.
- Replace the single "Total Spent" summary with:
  1. **Purchase Expenses** — sum of all original purchases AND additional purchase lots, from existing consolidated rows
  2. **Other Expenses** — a tappable expandable row which opens four independent fields directly inside the SAME scrolling popup: **Transport**, **Labor Charge**, **Meals and Drinks**, **Others**
- Put **Special Remarks** in a separate multiline box below the four inputs. All amounts/remarks are date-specific, editable, and auto-saved. Empty amount = zero.
- Final **Cash Balance = Cash Received − Purchase Expenses − Other Expenses**; negative values red.
- In the **Android Dashboard's existing top PDF**: preserve grouping by item category and subtotals; below those, add a section with Other Expenses four category rows, Remarks, Grand Total Expenses and correctly calculated Cash Balance. No new PDF button or Dashboard expense card.

## Android implementation (isolated UI/PDF preview)

- Room migration v5→6 is additive, preserving all existing Room/Cloud identity tables.
- New daily_other_expenses has one row per business date, four integer-cent amounts and one remark (500 Unicode chars max).
- ViewModel updates one category at a time under the same write mutex; old Cash Received auto-save stays unchanged.
- JSON backup new schemaVersion 5 includes Other Expenses; read supports previous versions 1..4 with zeros. Existing enrolled Cloud restore is still BLOCKED.
- PDF receipt and preview include separate Purchase Expenses and Other Expenses, non-item remarks, grand expense amount and cash balance.
- Kotlin tests cover 4-category independence, zero, max value, cash arithmetic and Sinhala remarks.

## Deployment safety

**Do not attempt to install this preview as an update of v3.3.7**. The original v3.3.7 is already enrolled as Supabase writer, and GitHub Actions ephemeral debug signing may be incompatible with a new build. Preview is a NEW package `lk.peliyagoda.marketpro.otherexpenses.preview.v340`, named Market Pro Preview v3.4.0.
- No INTERNET permission. Cloud login/sync menu options removed, Cloud engine and repository disabled. Old writer registration untouched.
- Original web Dashboard UI/URL/login and existing Supabase data completely unchanged.
- Android Cloud Sync for NEW daily_other_expenses is NOT supported in this preview. Do not claim the old Cloud Dashboard's existing expense total reflects those new amounts.
- A later production-ready update requires a stable signing strategy preserving the live writer's local identity, additive backend API/schema/authorization and review of cloud dashboard finance aggregation. The old dashboard's visual UI must remain identical.

## Verification gates

- CI Kotlin/Room unit tests pass.
- GitHub CI produces standalone APK, verifies package/version/label and absence of Android INTERNET permission.
- A diff shows 0 changes under `web/marketpro-dashboard/`.
- Verify in a test installation that: Cash popup expands and scrolls above keyboard, four amounts/remarks persist per date, other expense total and Cash Balance are correct, PDF includes all four categories and Sinhala remarks, and original Cloud writer/data are unaffected.
