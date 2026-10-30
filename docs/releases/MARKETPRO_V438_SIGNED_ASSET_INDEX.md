# Market Pro v4.3.8 — Signed In-App Update Release Anchor

This is a **docs-only GitHub release feed anchor**. Do not rebuild the APK
from this branch: the exact signed Android binary was compiled, unit-tested,
and signature-checked from the following immutable source instead.

- App source commit: `be702c39dcd444d60748404236d97046059b1320`
- GitHub Actions signed QA run: `41006863907`, success
- Signed APK file from artifact: `MarketPro-v4.3.8-DistinctCloudPhotos-QA-signed.apk`
- Release asset name: `MarketPro-v4.3.8-signed.apk`
- Application ID: `lk.peliyagoda.marketpro.staging.fullapp`
- Version: `versionCode=438`, `versionName=4.3.8`
- SHA-256 APK: `677baebf7018021edeba44c6376440f56e8627baf35df4ed6f331b4f0061410b`
- Expected stable certificate SHA-256:
  `0a617edb7520deb0255cfc13ee0b2ee8382e2d8e6cb7f8e1dff68b4fbd130c4a`

The update preserves the same signed package, local purchases, device keys,
existing synced photos, stable Cloud/Box photo API and team/owner roles.
It adds visually distinct, checksum-verified Cloud photo thumbnails,
unique Photo 1/2 captions, seconds-resolution Colombo times, individual
file sizes and short immutable identifiers.

No workflow or application source files are part of this anchor commit.
This recent documentation commit permits the legacy Android updater to
find the new GitHub Release among the first 10 items in the releases feed.
