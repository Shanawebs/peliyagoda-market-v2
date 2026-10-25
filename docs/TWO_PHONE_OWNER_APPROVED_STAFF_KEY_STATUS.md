# MARKET PRO — OWNER APPROVED STAFF DEVICE KEY (REVIEW ONLY)

Date: 2026-10-25. This is not live and MUST NOT be executed against production Supabase.

## Intended phone setup

1. The existing Owner phone invites a separate Staff account and Staff device UUID with a one-time code.
2. Staff redeems the code in their authenticated Supabase session. Owner activates that device READ ONLY first.
3. Android generates or reuses a NONEXPORTABLE EC P-256 signing key in Android Keystore and submits ONLY its public SPKI bytes to the new Staff enrollment Edge API.
4. PostgreSQL issues a random 60-second challenge strictly bound to the current paired Staff session, enabled Staff device, SPKI-derived SHA-256 fingerprint and an Owner-enabled read-only membership.
5. Staff Android signs the 10-line `marketpro-staff-key-enrollment-v1` challenge transcript with the matching private key. Edge independently verifies its ECDSA DER signature and calls service_role-only SQL to stage the PROVEN candidate fingerprint. This does NOT grant write access.
6. Owner fetches the pending fingerprint and candidate ID, physically compares the COMPLETE 64-character fingerprint shown on the actual Staff phone, types it in, explicitly checks the phone and confirms `APPROVE KEY`. The server revalidates Owner account/device, Staff Auth session and current paired device and atomically replaces the approved public key. Staff writer lease is STILL disabled.
7. A separate Owner Staff Writer approval grants a lease. Each real financial write still requires a newly signed proof and atomic server-side validation.

Phone replacement: Owner disables/replaces the old Staff phone first. The new phone starts READ ONLY, creates its OWN private key, stages new candidate, gets a separate fingerprint approval and later a separate write lease. Disabled phone cannot enroll a key, retrieve proof or write. Existing financial data remains attached to UUIDs, not a device-specific Room integer.

## Source in this Draft PR

- SQL proposal: `supabase/proposals/two_phone_owner_approved_staff_key_REVIEW_ONLY.sql`
- Edge: `supabase/functions/marketpro-staff-key-enrollment/index.ts` and shared WebCrypto module
- Android: one-time Keystore signature policy, feature-gated Staff enrollment HTTPS client and separate Owner fingerprint review/approval methods
- Tests: disposable PostgreSQL 17, Node real P-256 signatures, Deno typecheck, Android/JVM signatures and hardened production freeze

## Strict NO-GO

- No new production SQL migrations, Supabase RLS, Staff accounts or deployed Edge changes; Owner app and dashboard intact.
- `MARKETPRO_STAFF_KEY_ENROLLMENT_DEPLOYED=false` and all other Team Staff activation flags remain disabled; preview APK remains offline and without INTERNET.
- The Owner review Android methods are not wired to a live UI until full cutover. User-visible `hardwareBacked` is a local observation, NOT remote attestation. The Owner verification via existing Owner JWT/device UUID still requires an independently audited stronger Owner fresh authorization policy before turning on a sensitive production key-approval RPC.
- Real Auth JWT+key enrollment staging integration, key rotation and Staff phone revoke/replacement tests on physical phones, approved catalog UUID mapping, full two-way sync/Worker wiring, historical Owner corrections and backups remain mandatory.
- Only after full staging signoff should the review SQL move into an audited migration, Edge endpoints be deployed and a signed new APK be linked directly in ChatGPT.

Green CI confirms behavior under mock/test conditions, not an already-working live two-phone app.