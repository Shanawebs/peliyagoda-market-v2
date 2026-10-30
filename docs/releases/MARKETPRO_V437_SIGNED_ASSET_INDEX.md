# Market Pro v4.3.7 — Signed In-App Update Release Anchor

This branch is a **release metadata anchor only**. It intentionally has
no Android source changes and **must not be used to rebuild the APK**.

The exact signed Android APK was built and tested from the separate source
commit below, not from this release-anchor branch.

- QA source commit: `191d562d1f9ac597c4152f2c1f2b7c137b38ff59`
- GitHub Actions build: `40930735941` — successful
- APK filename: `MarketPro-v4.3.7-signed.apk`
- Package ID: `lk.peliyagoda.marketpro.staging.fullapp`
- Android versionCode: `437`; versionName `4.3.7`
- APK size: `13,107,264` bytes
- SHA-256: `07670c24aec98b781a6e0d647dc69fa209757c24d026f9b38137d4b5caa06ca5`
- Signing certificate SHA-256: `0a617edb7520deb0255cfc13ee0b2ee8382e2d8e6cb7f8e1dff68b4fbd130c4a`

The app's older updater reads only the first 10 GitHub releases. This
recent non-workflow commit lets GitHub show a release asset in that feed.
It is required solely for compatibility and preserves the exact QA-signed
binary, with no edits to live app, Supabase data, linked devices or Box.
