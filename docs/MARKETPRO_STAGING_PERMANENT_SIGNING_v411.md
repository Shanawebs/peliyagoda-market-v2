# Market Pro v4.1.1 — Permanent Staging APK Signing

This branch is STAGING QA ONLY; do not merge it to production without two-phone acceptance testing.

## One-time setup

1. Securely download and retain `MarketPro-Staging-Permanent-Signing-Kit-v4.1.1.zip` supplied privately by the release owner. Store backups in two secure locations.
2. Open the repository's Settings > Secrets and variables > Actions. Create four **repository secrets** using the values from the private `MarketPro-Staging-GitHub-Secrets-v4.1.1.txt` file:
   - `MARKETPRO_STAGING_KEYSTORE_BASE64`
   - `MARKETPRO_STAGING_STORE_PASSWORD`
   - `MARKETPRO_STAGING_KEY_ALIAS`
   - `MARKETPRO_STAGING_KEY_PASSWORD`
3. Run the `Market Pro Stable Signed Staging Update v4.1.1` workflow on `feature/marketpro-stable-signed-updates-v411`. The workflow must fail if any secret is missing, the APK version/package is wrong, or the signing SHA-256 fingerprint differs.
4. Download the signed APK from the published **prerelease** only after all workflow checks pass.

## Permanently pinned STAGING signer

Certificate SHA-256: `0a617edb7520deb0255cfc13ee0b2ee8382e2d8e6cb7f8e1dff68b4fbd130c4a`.

Package ID: `lk.peliyagoda.marketpro.staging.fullapp`.

Version Code 411, Version Name `4.1.1-staging`.

**Never** put signing keys, passwords, or a base64 keystore into source control or an issue. The repository is public. Never use this QA key as the production application's release key.

## Future OTA / APK updates

Use the **same keystore, alias, and key passwords** for every subsequent STAGING release. Keep the package ID unchanged. Increase `versionCode` strictly; update `marketProDisplayVersion`. Copy/update the locked workflow's version checks and keep certificate fingerprint pinning. An Android device will update the installed app without clearing Room if and only if the signing lineage, app ID, version rules, and migration are compatible.

**Migration boundary**: Old v4.0.2 and v4.1.0 staging APKs have different, ephemeral debug signing certificates. They **cannot** update in place to v4.1.1. Confirm phone-local JSON backup and approved Cloud/Owner pairing recovery before any uninstall. The JSON backup covers local records; it does not guarantee restoration of authentication or device approval. The current staging Cloud binder may reject imported finance/catalog records, so do not restore a backup into an already-enrolled phone until that migration flow is explicitly tested.

Do not mark two-phone sync as production-ready without Owner/Team physical-device verification and operation receipt checks.
