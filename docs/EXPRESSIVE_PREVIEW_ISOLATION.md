# Market Pro Expressive — Independent Android UI Prototype

## Source integrity
- Source baseline: Market Pro 2.36, branch `feature/cloud-v236-detailed-sinhala-help`, commit `a687e626ea131ae255bfb768bbf92f51871f812a`.
- This prototype lives **only** on `prototype/marketpro-expressive-v300`.
- Neither the 2.36 branch nor `main` is modified or merged.
- Existing 2.36 users can keep their installed app.

## Separate Android identity
- Prototype application ID: `lk.peliyagoda.marketpro.expressivepreview`
- Prototype version: `3.0.0-expressive-preview`
- Separate Android sandbox, preferences, Room DB and invoice photos.
- Different package means Android does **not** silently migrate old data.
- Never uninstall existing Market Pro 2.36 to try the prototype.

## Scope
- UI-only Material 3 Expressive theme (premium light blue, teal, white and navy), typography, shape scale and reusable section/metric cards.
- Distinct preview header, updated navigation highlight, and restyled global surfaces.
- Purchase and requisition logic, item/category IDs, aggregation, history, PDFs, Excel and JSON backup code are inherited unchanged from 2.36.
- Preview Cloud Owner Login / Sync dialogs are intentionally blocked to avoid production cloud writes. This is not a production-ready migration.
- No Room schema changes and no auto-restore/auto-import.

## Build and quality gates
- `gradle --no-daemon :app:testDebugUnitTest :app:assembleDebug`.
- APK from dedicated CI, never built from / merged onto 2.36.
- Distinct preview APK optionally published as a prerelease after passing tests.

## Review/acceptance criteria
1. Install alongside 2.36; verify old app starts and old records remain intact.
2. Review Dashboard, Kitchens, Market, Catalog, Chart, Calendar, PDF, Excel, Help and Restore PIN UI.
3. Use *test* records in preview; verify sums and file exports; test small phones and 4 GB RAM devices.
4. Check input keyboard focus, scrolling, sticky category rows, large text and Sinhala rendering.
5. Verify photo capture, date switching, app restart, backup/restore using **test backup** only.
6. Do not switch to the new app until UI is approved, cloud/backup migration is independently tested and signed production upgrade compatibility is verified.

## Rollback
- Keep 2.36 installed and untouched until validation is complete.
- Prototype can be uninstalled without deleting 2.36 data because the app IDs are different.
