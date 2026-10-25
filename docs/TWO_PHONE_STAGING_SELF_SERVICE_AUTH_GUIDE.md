# Market Pro Two-Phone — Hosted Staging Self-Serve Auth Setup (REVIEW ONLY)

**Scope:** only `marketpro-two-phone-staging` (`qqiheexpbuzohllugxrk`). The original Owner production Supabase project and Android phone are NEVER touched by this account registration page.

## How it works

The single-file `tools/staging-auth/marketpro-staging-auth-setup.html` runs locally in a browser. It contains **only** the staging Supabase HTTPS origin and the project's PUBLIC `sb_publishable` key, not service-role credentials or any password, JWT, email address, refresh token, verification link or OTP secret. When you press a button, it uses native browser `fetch` directly to Supabase GoTrue `/auth/v1/signup`, `/auth/v1/token?grant_type=password` or `/auth/v1/user`. No analytics, third-party scripts, cookies, login redirect, localStorage or sessionStorage. Password input clears after every request and is not written to browser storage by code.

The browser file's network access is restricted by a restrictive CSP connect-src to **only** `https://qqiheexpbuzohllugxrk.supabase.co`. We deliberately do not deploy a public password-collection site, nor redirect through an unrelated third-party HTML previewer. CI simulates signup, signin, confirmed/unconfirmed account checks and checks for never persisting passwords.

### Actual user flow

1. Visit the **separate staging Supabase Dashboard**, `https://supabase.com/dashboard/project/qqiheexpbuzohllugxrk/auth/providers`, and confirm **Email provider + Confirm Email** are enabled. Hosted projects normally require confirmation by default; do not assume this without seeing settings. Review Auth → URL Configuration → Site URL. The default localhost redirect might show a broken page AFTER the Auth server has already confirmed the account; if so re-open the local setup HTML and sign in. For a seamless custom redirect we will need an explicitly approved HTTPS site and allow-listed URL.
2. Download the exact GitHub HTML from its **Raw** source and open the downloaded file directly in your own mobile/desktop browser. Never open it through an unknown HTML preview service, proxy or publicly hosted mirror; it receives passwords in memory.
3. Enter the Owner's chosen testing email and a **unique 12+ character staging-only password** known only to the Owner. Select **Create Account**, then verify the email using the inbox.
4. Return to the downloaded file, reenter the same email and password, select **Sign In + Check Email Verification**. Only an actually confirmed Auth profile with exact matching email is marked verified; record the **staging-only Auth User UUID** (not the session/token).
5. Repeat for the Owner-controlled **Staff test alias** with its **own independent password**. Confirm both actual staging UUIDs differ. The dotted and undotted Gmail aliases deliberately share the same inbox and recovery channel: **test-only**, NEVER release a real Purchasing Staff device this way.
6. After both actual Auth profiles are confirmed, use scoped Supabase read-only tool calls to inspect new `auth.users` and then assign the Owner staging workspace role via separately reviewed migration; Staff invite/pairing and Owner TOTP AAL2 setup are later steps. No SQL inserts into `auth.users` and no direct Owner-account password sharing.

**Crucial:** An Auth user ID is not a workspace role. Merely signing in does NOT activate Owner/Staff ability to read any workspace data or financial records. Staging SQL currently has all 11 tables protected by RLS and zero direct Auth-user table write grants. The legacy Owner RPC has EXECUTE revoked to avoid bypassing future signed-proof checks. Do **not** turn on Staff Worker/network BuildConfig flags or register a phone until the full staging-backed server policy/crypto E2E passes.

## Limitations

- GitHub CI tests the actual HTML script via a fake browser DOM/controlled fetch. An earlier CI run tested real **local-only** GoTrue Owner TOTP + separate dotted synthetic identities. The downloaded HTML has **not** been tested on every Android browser or against actual hosted Auth.
- A local `file://` or `content://` page may be blocked by browser restrictions or environment-specific CORS. If it fails, do NOT disable browser web protections or paste a password into ChatGPT. The official Supabase Dashboard **Authentication → Users → Add user** is the alternative for manual setup; verify email status and use unique test passwords.
- Supabase's built-in email sender is rate-limited and may require verified senders/SMTP configuration. If confirmation email does not arrive, check Auth Logs or SMTP settings. Do not repeatedly submit.
- Separate credentials do **not** equal separate Gmail recovery. In real production, Purchasing Staff must use a genuinely independent email account.

## Verification and isolation

- Local HTML source: `tools/staging-auth/marketpro-staging-auth-setup.html`
- Contract test: `tests/staging_auth_portal_contract.test.mjs`
- CI: `.github/workflows/marketpro-staging-selfserve-auth-ci.yml`
- No changes to Android app source/BuildConfig, original Supabase migration files, staging database state, Edge functions, or Dashboard. The new code will only send real hosted signup/login requests **when the Owner interacts with the local HTML form**; this PR itself performs no hosted writes.
