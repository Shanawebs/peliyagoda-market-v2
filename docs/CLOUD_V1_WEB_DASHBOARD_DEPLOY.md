# Market Pro Cloud Web — deployed DEV preview

**Deployed:** 2026-10-22.
**Temporary Supabase Edge URL (public login shell, private business records):**

https://lkoptqhodgjeuwwfihfr.supabase.co/functions/v1/marketpro-web/

**Function slug:** `marketpro-web`; Supabase function deployment version 1; verified ACTIVE by Supabase tools.

**IMPORTANT:** This is *not* a Vercel deployment. The Vercel application was installed by the user but its deploy API controls were not exposed to this ChatGPT session. A Vercel project and a `*.vercel.app` URL have **not** been created or verified; don't claim they have.

## Access
1. Open temporary Supabase URL in browser.
2. Sign in with existing, confirmed Market Pro Cloud owner email/password.
3. The browser reads RLS-scoped records with the public publishable key and user JWT; **no write API, secret key, or photo data**.
4. Dashboard shows last available business date by default; date selection is available.
5. While tab is visible, poll every 15 seconds. Supabase Realtime publication did not include these tables on 2026-10-22, so this is **near-real-time**, not WebSocket push.
6. Original invoice photos are excluded, and the V2.31 Android device/app is unchanged.

## Code / tests
- GitHub branch: `feature/cloud-v1-web-dashboard`, source in `web/marketpro-dashboard`.
- GitHub CI test `marketpro-web-dashboard-check.yml` validates JS module syntax, half-up integer-cent parity and absence of admin API secrets. All checks passed in run 39739707382.
- Edge Function currently serves **the same public HTML/CSS/JS modules** bundled into one static Deno function, with no query on behalf of anonymous visitors.
- Supabase Function settings: `verify_jwt=false` **only to allow loading a public login page**; every data request still requires a Supabase Auth JWT and the existing workspace RLS policies. CSP headers restrict client scripts, styles and data API origin.

## Before moving to Vercel
- Import the GitHub repository to a Vercel project with root directory `web/marketpro-dashboard`, framework Other.
- The web source exists on feature branch **not** Android main; select the feature branch as deployed source, don't merge unfinished Android changes into production main.
- Verify Vercel-assigned URL and user login on both phone and desktop; then switch to Vercel-hosted domain if preferred.
- Never change Supabase device enrollment or grant anonymous database access to fix hosting problems.
- HTTP reachability from this execution environment could not be tested (outbound HTTP/DNS unavailable); the Supabase function deployment and bundled assets are verified via hosted administration. Have an actual browser verify end-to-end sign-in.
