# Market Pro v4.2.0 Stable — Phase Closure

Date: 2026-10-28
Baseline: v4.1.5 persistent-key signed APK.
QA acceptance: User confirmed successful Owner Phone, Team Phone and Cloud Dashboard synchronization on the previous signed build.

## Released scope
- Approved Owner / Team phone linking, logins and role-based permissions.
- Team may correct the current business day; historical records are Owner-only.
- Offline-first Room storage, durable outbox, financial/cash/other-expenses sync and safe cloud merge.
- Kitchen requisitions, purchase records, dashboard, JSON backup/restore, PDF reports.
- Existing isolated Supabase cloud workspace and web dashboard unchanged.

## Android package
- Application ID: lk.peliyagoda.marketpro.staging.fullapp (unchanged)
- Version code: 420 (previous: 415); version name: 4.2.0.
- Non-debuggable release APK; code and resource shrinking deferred for behavioral stability.
- Same protected stable signing certificate as v4.1.5.
- No destructive migration, no phone/account re-enrollment, no new cloud schema deployment.

## CI release gates
- Existing Android unit tests, production isolation policy, staging SQL and dashboard tests.
- Generate signed release-variant APK.
- Validate version code, package, internet permission, non-debuggable flag and signing certificate.
- Download published v4.1.5 signed APK and verify same package + signing certificate and higher version code.
- Publish GitHub release only after all gates succeed.

## Installation and rollback precautions
1. Use app menu to export JSON backup outside app storage.
2. Install v4.2.0 APK directly over installed v4.1.5, do not uninstall or clear storage.
3. Verify account login, Owner/Team sync and existing cloud entries.
4. Retain previous APK and JSON backup. Android generally blocks an in-place downgrade to a lower version code.
5. Later features and bug fixes require new scoped changes and version increments.

## Scope boundary
This is the stable channel for the **verified isolated staging workspace**, not a migration or switch of the separate production app and production database. Physical install of the new release still needs an Owner/Team device smoke test.
