# Peliyagoda Market Pro — Cloud Web Dashboard (DEV)

**Status:** final two-phone staging dashboard source. Hosting must be deployed separately; GitHub branch by itself is NOT a live URL.

## Scope

- **Login:** Supabase Auth email/password. No new registrations or cloud write UI.
- **Data:** catalog, kitchen requisitions, initial purchases, additional lots and daily cash. Daily total expenses and cash balance come from Supabase's RLS-protected `daily_financial_summary` view.
- **Refresh:** visible browser tabs poll every 5 seconds; manual Refresh and business date filter are also supported. This is *near-real-time*, NOT an always-open Realtime WebSocket. This staging build intentionally uses bounded 5-second polling rather than an always-open Realtime WebSocket.
- **Photos:** NO invoice photos, storage uploads, file paths or attachments.
- **Security:** Supabase public publishable key only, never service-role or secret keys. Workspace-scoped RLS is enforced by Supabase. No unauthenticated data.
- **Architecture:** native browser ES modules + CSS; no npm runtime packages or framework, optimized for mobile and lower memory.

## Deploy to Vercel

1. In Vercel, **Add New → Project → Import Git Repository**, select `Shanawebs/peliyagoda-market-v2`.
2. Set **Root Directory** to `web/marketpro-dashboard`; select **Framework Preset: Other**. No build command is needed for these static files. Publish directory is `.`.
3. Final staging source files live on `feature/marketpro-final-two-phone-sync-v410`, **not on main**. Do not use the repository's default `main` branch, which does not yet include the dashboard. In Vercel project Git settings, choose this branch as the Production Branch if supported, or explicitly create a branch-based deployment. Never merge Android cloud DEV changes into production `main` solely to deploy this website.
4. Deploy, then verify the assigned `*.vercel.app` address in a logged-out browser and a signed-in owner browser. Do **not** guess or advertise a deployment URL until Vercel confirms it.
5. For private production use, set the Site URL, allowed redirect URLs and any future Auth email confirmation callback in Supabase Auth. This dashboard currently uses password sign-in only (no email redirect).

## Test

Run `node --test web/tests/marketpro-dashboard.test.mjs` from repository root, plus `node --check` against the four browser `.mjs` modules. GitHub Actions automatically validates this.
Tests live outside the deployed web root in `web/tests` and contain non-sensitive sample data.

## Operational cautions

- The source GitHub repository is public, therefore dashboard assets/source, and **only a Supabase publishable key**, are public. Secrets do not belong in frontend code.
- RLS must remain on and only authorized workspace members have SELECT. `daily_financial_summary` uses `security_invoker=true`.
- The production Android app remains unchanged. This dashboard has **no API write methods**.
- Item/catalog pagination currently caps at 1000. A warning replaces potentially truncated values; add proper pagination before exceeding this size.
- Financial cards reflect the last successful visible refresh; errors do not silently claim a successful update.
