# V2.32 Reliability Audit — Safe Development Checkpoint
Date: 2026-10-22
Source branch: feature/cloud-v1-android-sync-v231
Working branch: feature/cloud-v1-reliability-v232

## Live-state protection

The installed **V2.31** Android app is actively synced and must be preserved. Never uninstall it, clear app data, replace it with a GitHub debug build, reset Room, disconnect the device, or prune financial records.

This branch is **TEST-ONLY**; it does NOT generate or distribute an installable upgrade artifact. Android `applicationId` intentionally remains V2.31 for source compatibility, but ephemeral GitHub debug signing keys mean built APKs are NOT safe in-place upgrades.

The original V2.28 and separate V2.30 apps must also remain unchanged. No invoice JPGs or photo uploads are introduced.

## Completed reliability change

- Concurrency: `CloudSyncRunGate` uses process-wide `Mutex` to serialize full upload + conditional ACK operations across periodic and immediate WorkManager invocations. Room transactions still end before network calls; no network call is performed inside a Room transaction.
- Worker batch size remains 1..100, preserving memory limits for 4GB devices.
- The server RPC's op UUID / idempotency ledger remains authoritative across retries and process restarts; conditional local ACK prevents older network responses from deleting more recent local edits.
- JVM concurrency tests cover overlap and gate release after simulated failure.
- Dedicated test-only GitHub workflow compiles Kotlin and runs Android unit tests but publishes **no APK**.
- All changes are only on a feature branch; GitHub `main` and installed V2.31 do not change.

## Current cloud cross-check

Initial import confirmed by screenshot: pending queue **0** as of 2026-10-22 12:05 Sri Lanka time. Supabase DEV independently shows 330 accepted operations:
3 categories + 184 items + 105 requisitions + 26 purchases + 10 extra lots + 2 cash entries.
Cloud Storage photo objects: 0. Last server operation `2026-10-22 06:34:33Z` matches phone last successful upload.

This verifies initial upload completion, **not** numeric parity between Android and Supabase. The user should compare October 20 and 21 totals before considering financial reconciliation fully passed.

## Security review gates

- `public.marketpro_apply_change` is a deliberately authenticated, privileged (`SECURITY DEFINER`) RPC. Supabase Security Advisor warns that signed-in users may execute it. Review its row ownership, device enrollment and property allowlist before production; do not weaken RLS to suppress the warning.
- `public.sync_operations` has RLS enabled with no client-facing policy, deliberately denying ledger access.
- Supabase Auth leaked-password protection is reported as disabled. Enable it in the project's Auth settings after reviewing current availability and compatibility.
- No service-role keys should ever be embedded in APKs; only the public Supabase publishable key is compiled into the client.

## Stable APK signing — blocking release gate

The previous GitHub debug builds used CI-generated ephemeral keys; previous V2.29 and V2.30 app signatures were inconsistent. A same-`applicationId` upgrade to installed V2.31 **cannot be safely signed using an unrelated key**. NEVER tell the owner to uninstall the current phone app to install V2.32.

Required before any upgrade:
1. Establish a stable private signing key, kept outside GitHub source and client assets, with controlled encrypted backup and release access.
2. Compare SHA-256 signer certificate fingerprint of the installed V2.31 APK with the proposed upgrade. An in-place upgrade is permitted only if Android signatures match.
3. If old signing private key is unavailable (likely for ephemeral CI build), use a controlled **parallel installation + verified cloud data reconciliation and one-time UUID-aware migration**, not APK replacement. Local invoice photos remain in the old package.
4. Test that already-uploaded records cannot be duplicated or overwritten by a new installation's unrelated local UUID mapping. New devices must be explicitly enrolled and previous writer access disabled atomically.
5. Test the app on a real 4GB RAM phone, network loss, 15-minute periodic scheduling, force-stop/restart, two simultaneous workers, rapid edits/deletes, and token expiry.

## Pending

- Confirm V2.32 CI success.
- Do NOT deploy any SQL changes to hosted Supabase in this milestone.
- Do NOT merge to main.
- Do NOT distribute the debug APK to the user as an upgrade.
