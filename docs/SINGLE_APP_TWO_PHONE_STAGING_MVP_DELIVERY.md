# Market Pro — SINGLE integrated two-phone staging release path

**Decision:** stop releasing standalone security-test APKs. Ship **one shared-source Market Pro app** to Owner and Staff when real two-phone server permissions are live and verified.

## Current implementation on this branch (2026-10-26)
- Added `:stagingMarket`, compiling the existing **full Market Pro UI, Room database and report logic** from `app/src/main/java` and `app/src/main/res` — not a rebuilt toy app.
- Separate `applicationId=lk.peliyagoda.marketpro.staging.fullapp`, Staging host `qqiheexpbuzohllugxrk.supabase.co`, QA workspace `af77cb66-76b2-492a-a460-06132f468713`, and actual QA Owner Auth identity. No service-role key in any APK.
- Default `app` stays the existing Production-host **OFFLINE PREVIEW**, has no INTERNET permission and uses the same Owner/workspace settings as before.
- Existing Cloud Kotlin transports no longer hard-pin Production host. They verify the exact BuildConfig host, so the one source tree can be compiled for either isolated target without redirecting users' Production data.
- In the Staging foundation build, cloud/Staff writer flags remain **OFF**. The foundation is NOT a functioning two-phone Cloud Sync release; no staff data should be entered until the end-to-end signed-device test is complete.
- Hosted Staging has one `PENDING` Owner key (created from the older standalone `stagingOwner` test app). Its private key is **not accessible** from the integrated new app ID. Never fake/migrate that private key, never silently activate this QA trial as a new app's signing key. Need a fresh Owner activation/rotation ceremony specifically for `:stagingMarket`.

## Implement together before next APK delivery

1. **Identity + key**: use the same Android applicationId and Keystore keys throughout Owner and Staff lifecycle; secure TOTP AAL2 Owner authentication; stable install identity. Disable self-registration and staff self-approval, except explicit staging-specific owner invitation.
2. **Owner approval**: reconcile the existing pending QA identity with the main integrated app, require explicit fresh Owner AAL2 and signed key continuity/rotation or a controlled revocation/re-enrollment. Separate attestation verification/fallback security decision; no claims of hardware attestation based on P256 alone.
3. **Staff link/revoke**: individual Auth account, Owner approval of Staff public key, server-authoritative session/role/device for every request, revoke old phone before adding replacement. The current `mywin.7070@gmail.com` dotted test address shares Owner's mailbox; do not treat it as independently controlled in production.
4. **Staging cloud writer endpoints**: existing repo holds many proposals/dormant Kotlin clients. Activate only audited server SQL/Edge functions with immutable write idempotency and replay protection, immediate authoritative revocation, server business-day rule (Asia/Colombo). Staff may edit current business date repeatedly; only Owner may correct previous dates.
5. **Two-phone sync**: online pushes and pull reconciliation of categories/items, kitchen requisitions, purchases/additions, cash and expenses; WorkManager offline queue, retries and conflict resolution. Staff invoices/photos remain local. Reader/dashboard receives updates, read-only.
6. **Single real QA pass**: two physical devices, Owner and Staff sign-in, append purchase, correct same-day, block past-day edit, revoke/re-link Staff phone, test offline restore and data conflicts; verify billing/finance totals and Android 4GB smoothness.
7. **Cutover**: full backup of original Room device and Production Supabase, production-side migration and rollback under explicit Owner acceptance only. Never overwrite currently installed Market Pro APK/data with a Staging test build.

## Release gate (do not bypass)

Do not label any artifact **FINAL Two-Phone Cloud APK** until Staging has both Owner and Staff keys bound to the **integrated app**, actual server writer/puller tests and all financial reconciliation checks PASS.

**One delivery goal:** one Staging integrated APK for real end-to-end testing, then one Production migration/release (not ten more proof-only APKs).
