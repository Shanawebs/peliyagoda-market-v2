# V3.7.2: Owner-issued One-Time Pairing Bound to Auth Session

**Review only, 2026-10-24. No live project changes, Staff APK or database migration.**

## Security finding and source of truth

Supabase's signed access JWT exposes a `session_id` corresponding to `auth.sessions.id`. A valid JWT may remain usable until expiration **even after logout**; sensitive RPCs should test that an associated Auth session still exists. The real Supabase project was verified to expose `auth.sessions.id`, `user_id` and optional `not_after` columns. See Supabase docs https://supabase.com/docs/guides/auth/sessions .

Existing device UUID + Auth user ID alone were not enough to distinguish a second phone logged into the **same Staff account**. This phase adds authenticated session binding as an additional server-side control; this does **not** cryptographically prove hardware identity and does not protect if the **entire signed JWT/refresh token is stolen**.

## Review-only implementation

- `marketpro_private.staff_session_bindings` contains only the Staff account, workspace, registered device UUID and current session UUID; never plaintext invitation, password, access/refresh token or secrets.
- `marketpro_staff_accept_invitation` now requires a live matching `auth.sessions` row AND a fresh, unused, exact 64-character owner-issued one-time code. Consuming the code and recording the signed JWT's `session_id` are atomic. A second login session cannot rebind the device by merely copying its UUID.
- `marketpro_private.is_verified_staff_session` fail-closed: checks JWT `session_id` syntax, matches `auth.uid()`, live `auth.sessions` row / `not_after`, the current accepted/activated invitation, revoked status, exact device and matching paired UUID. It is in private schema, not directly invokable by authenticated or anonymous API callers.
- Overrides V371 write guard and Owner enable RPC to require live paired Staff session on EVERY operation and Owner approval. The existing Owner v1 writer is unaffected.
- Overrides the device-bound paginated `marketpro_team_pull_page` to require the same session for Staff **read** access. Otherwise a secondary login with copied device UUID could read nonphoto workspace data even without a writing lease.
- Owner emergency stop / Staff phone revocation invalidates the registered device; Owner re-pairing with a different phone first requires v368 disable/replace and another fresh one-time invitation.
- Disposable PG17 CI runs prior V370/V371 full financial suite, then tests unpaired JWT, wrong code, reused code, copied old device ID, other session of same account, missing/malformed session claim, pending Owner activation, signed-out JWT with deleted `auth.sessions` row, session timeout, unrelated Viewer read access and old Owner single-writer index.

## Deployment blockers (critical)

**Still not production-ready.** All proposals are review-only local PG17, and real PostgREST signed JWTs, private schema privileges, RLS/direct REST read isolation, transactional revoke race testing, Owner UI confirmations and two physical phones must be independently audited. Private session binding is effective **only if the actual Staff Android client signs in to Supabase and the audited server rollout is deployed**. Do not deploy this proposal alone or enroll Staff while legacy member RLS remains broad.

Hardware-bound key attestation / proof of possession remains future defense against full stolen bearer token replay. Android Keystore-backed keys with server signature verification, safe loss/recovery, refresh-token secure storage, offline queue semantics and replacement phone diagnostics are still required before claiming strong device identity. Separate Staff account from Owner credentials, keep the original dashboard unchanged, and test other-expenses cloud data.
