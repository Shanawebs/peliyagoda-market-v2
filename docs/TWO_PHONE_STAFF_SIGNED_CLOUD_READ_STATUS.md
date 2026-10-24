# Signed Staff Cloud Read — One P-256 Proof per Page

**25 Oct 2026 — REVIEW ONLY. Not deployed to Supabase.**

The previously proposed `marketpro_team_pull_page` was bound to
the signed Supabase Auth JWT and registered Staff device UUID, but did not
validate actual possession of the Staff Phone's AndroidKeyStore P-256 key.
A stolen bearer token plus copied device UUID could therefore read data,
although the public finance tables' direct RLS is Staff-restricted.

## New audited read design

- Explicit direct RPC split: `marketpro_team_pull_page` continues serving
  already enrolled Owner/Viewer with their original **signed-JWT** logic,
  but the **Staff role is excluded**. No Staff can use the bearer-only RPC.
- Signed Staff `marketpro_staff_issue_read_page_challenge` validates Staff's
  **current Auth session**, active registered Staff handset, Owner-approved
  signing key and read rollout gates; it generates 256-bit one-time nonce
  with 60-second expiration. SQL privately stores SHA-256 of the exact
  (workspace, Staff, phone, entity type, page-size, updated_at/UUID cursor)
  parameters before Android signs the challenge.
- Dedicated authenticated `marketpro-staff-signed-read` Edge Function checks
  live JWT Staff claims, retrieves still-active/approved public key material,
  verifies real P-256 `SHA256withECDSA` signature (strict DER→P1363),
  invokes **service_role-only** `marketpro_service_pull_signed_staff_page`.
- Within one PostgreSQL transaction under same workspace lock as lost-phone
  revocation and Owner replacement, Server compares exact committed page
  intent, rechecks current session/key/physical device, consumes the nonce
  once, and returns **only one workspace-scoped allowlisted page** (7 types;
  no invoice photos, Storage access or credential data). A different page,
  cursor, session or duplicate nonce cannot be used.
- The Android `TeamPullHttpTransport` no longer allows Staff to call
  legacy direct RPC: it routes Staff to `StaffSignedTeamPullTransport` and
  fails closed if any of the new prerequisites is off. Owner stays on its
  existing transport. Phone compares Owner-approved SPKI fingerprint
  with local Keystore key, signs short-lived read transcript and receives
  the same JSON response expected by `CloudInboxStager`.
- `MARKETPRO_STAFF_READ_PROOF_ENFORCED=false` remains OFF, alongside old
  Staff Login/Pull/Write flags, `MARKETPRO_LOCAL_PREVIEW=true`, and
  Android manifest INTERNET permission remains removed.
- The existing 20-source atomic cutover is updated, and deterministic
  `two_phone_atomic_bundle_integrity.test.mjs` proves SQL sources stay
  exactly in sync with the tested atomic bundle.

## Non-negotiable release caveats

- Tests simulate trusted Edge AFTER crypto verification in PostgreSQL;
  Node tests verify ECDSA crypto; Deno compiles the handler. They do NOT
  prove live PostgREST permissions or two actual handset E2E security.
- The existing project has real Owner data; it is not disposable staging.
  Nothing was deployed live and no new billable Supabase project created.
- Hardware attestation, stolen unlocked physical Staff phone and physical
  screen-lock access are separate concerns. Keystore software-backed keys
  are possible; private key cannot be exported but may still be *used*
  on a stolen unlocked phone.
- Each Staff page requires two network round trips and a new Server nonce,
  so 4GB-phone performance / battery usage require genuine testing.
- Exact catalog UUID and legacy Room mappings, queued corrections, Owner
  historical edits, lost-phone replacement and cash/Other Expenses/PDF
  reconciliation still require real staging and two actual Android devices.
- Any new migration MUST be an audited atomic cutover with RLS and old Staff
  direct RPC bypass eliminated before Staff accounts are activated.

**GO/NO-GO remains NO-GO for Live Cloud cutover and signed final APK.**
