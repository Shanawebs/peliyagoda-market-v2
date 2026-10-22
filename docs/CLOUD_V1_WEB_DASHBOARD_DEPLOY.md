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

## Vercel static deployment (2026-10-22)

- **Production alias assigned:** https://marketpro-cloud-dashboard.vercel.app/
- Vercel project: `marketpro-cloud-dashboard`; deployment ID `dpl_3sNPLWLpKXLZSHRBzSyMA1Xc4otD`; server state **READY**, static source files verified present in Vercel deployment.
- Source branch/commit deployed: `feature/cloud-v1-web-dashboard` at `7caabe704a9efd4d7b28dfd09230b96680af5eb3`.
- Deployment used **direct Vercel API file upload**, because Vercel Git-linked project creation is still denied by team-scope permission (403). Future Git pushes will **not** automatically redeploy this site until the Git integration is linked correctly.
- Vercel project root directory reset to project root for inline file deploy, rather than monorepo Git source root `web/marketpro-dashboard`. Git-linked setup in the future should restore that root setting.
- Vercel SSO deployment protection was disabled for this project so visitors can see the public login screen; Supabase Auth and workspace RLS still gate private records. No anonymous business data permitted.
- Vercel deployment API reports READY and Vercel file listing confirms index.html, JS and CSS. Independent public HTTP fetch and end-to-end user login could NOT be verified from available environment (network/access limitations), so ask user to open the URL in browser and report any error.
- This note supersedes the earlier 'Vercel not deployed' status above. Old Supabase Edge web URL displayed HTML as text due Edge Function response limitations and should not be used.


## Cloud Web Dashboard UI update — 2026-10-22

- User-requested changes deployed: larger Kitchen Requisitions and Purchases table fonts; per-purchased-item Kitchen Required total and per-kitchen breakdown; new Charts tab with category/item selection, weighted-average daily item price Rs./kg and purchased quantity kg, synchronized scrolling.
- **Live production URL:** https://marketpro-cloud-dashboard.vercel.app/
- **GitHub PR:** https://github.com/Shanawebs/peliyagoda-market-v2/pull/1 (squash merged)
- **Production source branch/commit:** \`feature/cloud-v1-web-dashboard\` at \`5115f25adfd74e710afd41dd93e03d6b2b56b99c\`.
- **Production deployment:** \`dpl_HbB7P3KCbidPMY9arp1iU8iH8ZHm\`; Vercel reports \`READY\`, aliases include \`marketpro-cloud-dashboard.vercel.app\`.
- **Previous production rollback candidate:** \`dpl_3sNPLWLpKXLZSHRBzSyMA1Xc4otD\`.
- **Preview deployment:** \`dpl_5Eocesiikm4kdcJdrYGboGPH2cNx\` (\`READY\`).
- Node 22 GitHub Actions \`validate-web\` passed on the source branch and merged base branch, with regression coverage for unique per-item kitchen requirements, quantity-weighted price, and missing-day handling.
- **Hosting model unchanged:** Vercel static deployment from inline files; no active GitHub auto-deploy integration. Future GitHub commits alone do **not** update the deployed dashboard.
- **Scope unchanged:** read-only web views, no backend migrations, no RLS modifications, and no change to Android V2.31 writer or Android APK.
- **Verification scope:** GitHub unit/security checks and Vercel deployment status/file delivery metadata were validated. This session did not have an authenticated browser session for owner-only end-to-end data testing; manually verify signed-in production tabs.
