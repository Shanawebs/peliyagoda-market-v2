# Peliyagoda Market Pro — V2 (Android / Kotlin)

An offline-first kitchen requisition and market-purchasing application.

## 📱 Android Studio එකෙන් Run කරන්න

1. ZIP එක Extract කරන්න.
2. Android Studio (recent version with JDK 17+) විවෘත කර **Open** තෝරන්න.
3. `PeliyagodaMarketV2` folder එක තෝරන්න. `app` folder එක විතරක් තෝරන්න එපා.
4. Android SDK 35 සහ Build Tools 35 install වෙලා තිබිය යුතුයි. Prompt එකක් ආවොත් install කරන්න.
5. Internet තියෙද්දි **Sync Project with Gradle Files** කරන්න. මුල් වරට Gradle wrapper jar සහ Gradle 8.11.1 බාගත වේ.
6. Android phone එක USB debugging සමඟ connect කර **Run ▶** තෝරන්න.
7. APK එක සෑදීමට **Build → Build App Bundle(s) / APK(s) → Build APK(s)** තෝරන්න.
   නැත්නම් Terminal එකේ `./gradlew assembleDebug` / Windows `gradlew.bat assembleDebug`.
8. Result: `app/build/outputs/apk/debug/app-debug.apk`.

**වැදගත්:** මෙහි Android SDK/Gradle ලබාගැනීමට නොහැකි වූ නිසා මෙම project එකේ Android APK build/smoke test අවසන් කර නැත. Android Studio එකේ first build එකේදී error එකක් ලැබුණොත් error text එක සම්පූර්ණයෙන් එවන්න; අවශ්‍ය files සම්පූර්ණයෙන් නිවැරදි කර දෙන්නම්.

## Features

- Dashboard එකෙන් Kitchens තුනේ එකතු කළ A–Z Consolidated List.
- Main Kitchen, Indian Kitchen, Pastry Kitchen වල requisitions.
- Search සහ Vegetables/Fruits master catalog. අලුත් Item එකක් එකතු කළ හැකියි.
- Date navigation සහ Calendar picker.
- Market purchase quantity, price/kg, line total, daily total, shortage/extra.
- Excel-compatible UTF-8 CSV report (NOT `.xlsx`).
- Local SQLite database via Room. No Internet permission, cloud account, or login.
- Full JSON backup / restore using Android's Save/Open document dialogs. Restore replaces existing records only after explicit confirmation.
- Quantity stored as grams (integer); unit price as cents of LKR (integer) to prevent floating-point errors.

## Existing App Safety

**The new app uses `lk.peliyagoda.marketpro`, not the older app's package.**
- Installing V2 will not overwrite the older Peliyagoda Market app.
- V2 does **not** automatically import old APK data. APKs don't contain the phone's user database.
- Keep the old app and its records until data has been exported and migrated intentionally.

## Record security & backup

- Database lives in app-private Android storage; no Internet permission is declared.
- Automatic Android backup and device transfer are disabled intentionally.
- Uninstalling the app can remove its private database. Regularly use **More → Backup all records (JSON)** to save an external copy.
- **More → Restore backup (JSON)** replaces existing data after confirmation. The JSON is validated first, and changes run in one Room database transaction.

## Developer architecture

- Kotlin, Jetpack Compose, Material 3, Room SQLite, ViewModel, Coroutines/Flow.
- Core data: `ItemEntity`, `RequisitionEntity`, `PurchaseEntity`.
- Business date keys use ISO dates like `2026-10-19`.
- Unique requisition: `date + kitchen + item`, unique purchase: `date + item`.
- All kitchen requests for an item aggregate to a single purchasing entry.
- `MarketMath` handles parsing and subtotal rounding.
- CSV export can be opened in Excel, but creates no formulas/styles. `.xlsx` can be added as a later phase.
- Debug build is for testing only. Play Store/distribution requires review, UI/device testing, signing, and a private release keystore.

## Example calculation

| Item | Required | Purchased | Price/kg | Difference | Total |
| --- | ---: | ---: | ---: | ---: | ---: |
| Beans | 12 kg | 13 kg | Rs 150 | +1 kg extra | Rs 1,950 |
| Beetroot | 20 kg | 19 kg | Rs 120 | -1 kg shortage | Rs 2,280 |
| **Total** | 32 kg | 32 kg | — | 0 kg net | **Rs 4,230** |

The screenshot example's `92 kg` total included other kitchen items; this table covers only the two shown lines.

## Notes on the Gradle wrapper

This source-only ZIP cannot bundle a pre-fetched Gradle wrapper JAR. The supplied `gradlew` and `gradlew.bat` scripts download the pinned wrapper JAR from Gradle's tagged official GitHub source (`v8.11.1`) when first run. Internet connectivity is required initially. If your company firewall restricts GitHub, use a trusted network to download the wrapper JAR or create the Gradle wrapper with a local Gradle 8.11.1 installation.

## Out of scope of V2 starter

- Multi-user synchronization, cloud database and authentication.
- Supplier management, approvals, purchase-order workflows.
- Direct `.xlsx` / PDF export.
- Import of old app's SharedPreferences data (requires a safe migration from the old app).
- Tested release APK; on-device testing needs Android Studio/SDK.
