# Peliyagoda Market V2 - Build APK with GitHub Actions

## How this works

This GitHub Actions workflow runs on GitHub servers. Your PC/Android Studio is not needed.

1. Create a NEW PRIVATE repository named `peliyagoda-market-v2` in GitHub. Do NOT use the production ReplyXa repository.
2. Upload the **contents** of this project folder to the root of that repository, preserving the directory structure (including `.github/workflows/build-apk.yml`). Uploading the ZIP itself does not trigger an Android build.
3. Open the repository's **Actions** tab. Allow Actions if GitHub asks.
4. Select **Build Peliyagoda Market V2 APK** and check the latest run. You can also select **Run workflow**.
5. On a successful run, find **Artifacts > PeliyagodaMarketV2-debug-apk** and download the ZIP. Extract `app-debug.apk` from it and install it on the Android phone.

## Notes

- This workflow downloads SDK 35, Gradle 8.11.1 and uses Java 17.
- The project has NOT been compiled in this environment. A CI build may reveal errors requiring further fixes.
- This creates a debug-signed APK intended for personal testing, not a Play Store release.
- The new V2 app has a different package ID, and does not migrate data from the old installed app.
- Never upload production credentials, keystores, passwords or private customer data to the repository.
