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


## Overview V3 release — 2026-10-22

- **User request:** financial history and overview financial trend both display the 10 most recent recorded business-day summary entries; financial chart gets wider desktop area, daily activity narrower; stronger contrast and larger typography throughout the dashboard.
- **Published production URL:** https://marketpro-cloud-dashboard.vercel.app/
- **GitHub PR:** https://github.com/Shanawebs/peliyagoda-market-v2/pull/2 (merged)
- **Published source SHA:** \`1bf6e881b3f7df453d9d3f884ce6f7b279969e3f\`, source branch \`feature/cloud-v1-web-dashboard\`.
- **Production deployment:** \`dpl_3tLzBh4WEyEgMU7MiwabTBeKZato\`, READY with production domain assigned.
- **Verified preview:** \`dpl_GPt2UPEFa8zsH9Zkqo9c7BPXKfix\`, READY.
- **Rollback candidate:** previous production \`dpl_HbB7P3KCbidPMY9arp1iU8iH8ZHm\`.
- **Tests:** Node 22 GitHub Actions validate-web succeeded on new feature branch and merged branch; latest-ten selector has regression coverage.
- **Scope:** no changes to Supabase records, auth/RLS, database schema, price calculations, or Android APK. History view limitation is visual only and does not discard data; item price history Charts tab still uses its own original source.
- **Deployment architecture:** direct static upload/redeploy to Vercel because GitHub auto-deploy integration remains unlinked; Git commits alone do not publish the website.
- **Limitations:** Vercel deployment metadata and CI tests verified, not signed-in browser visual/user-data end-to-end testing.

## V4 — PDF preview and recorded-date calendar (2026-10-22)

- New PDF Report button after Charts opens a printable completed-purchases-by-category report (Sinhala item labels, required/bought quantities, category subtotals, completed-only total, all-lots cash spent and cash balance).
- Print / Save as PDF uses the browser print dialog with A4 CSS, no third-party JS, no photo uploads, and no storage writes.
- Native browser date input UI replaced by an accessible custom calendar showing green dots on dates with recorded workspace financial summaries; month markers fetched using authenticated RLS queries.
- GitHub PR: https://github.com/Shanawebs/peliyagoda-market-v2/pull/3 (merged).
- Release commit: 5c48d2bd4eceebc9746b2361648856b66224f8ab.
- Production URL: https://marketpro-cloud-dashboard.vercel.app/
- Production deployment: dpl_8kN5Mom8niTJkGSzgtoSxNLZ6X6V — READY, alias assigned.
- Verified preview: dpl_B4vFTatG3zwHrjKVbkNFMZqfGzbo — READY.
- Rollback candidate: dpl_3tLzBh4WEyEgMU7MiwabTBeKZato (previous V3).
- Verification: Node 22 GitHub Actions passed; deployment READY. Owner-authenticated end-to-end viewing/printing must still be manually checked.
- No database changes, RLS changes, Android changes, or new cloud write access. Static Vercel direct deploy; Git push alone does not auto-deploy.
