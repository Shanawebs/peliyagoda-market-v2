# TWO-PHONE FINANCIAL SECURITY V3 — REVIEW ONLY

2026-10-25. Not deployed to live Supabase or Android Owner phones.

## Implemented development security

Four additional finance types now have domain-separated 16-line ECDSA P-256 signatures bound to the exact financial entity type, operation ID, entity ID, current Staff JWT session, phone ID, Owner-approved public key, single-use 60-second nonce, expected revision and server SHA256 of the strictly allowlisted JSONB payload.

- Requisition: business date, approved item UUID, kitchen, quantity.
- Purchase addition: date, approved item UUID, quantity, price per kg. Each addition is an individual lot.
- Daily cash received: business date and amount, unique day.
- Daily other expenses: business date, four money amounts and remarks, unique day.

The existing v2 signed Purchase remains a separately audited endpoint.
Authenticated Edge verifies Android DER ECDSA signature and invokes a service-role-only SQL transaction. The transaction rechecks active paired JWT session, Staff device, approved key, current Staff lease, challenge expiry, signed payload, TODAY in Asia/Colombo and optimistic server revision. It commits the new financial row and consumes the exact nonce atomically, or rolls back both. A lost network acknowledgment uses a fresh nonce with the original operation ID, returning the original revision without duplicate totals.

An Android v3 signed-financial HTTPS adapter and AndroidKeyStore signing method are included but are dormant. Existing Android Staff Worker still defaults to UnavailableStaffWriteTransport. All deployment gates remain false, Android preview remains offline, and Live Owner V1 records and Vercel dashboard are untouched.

## Tested

- PG17 disposable database: create, same-day correct, signed retry/dedup, amount tampering, cross-type confusion, Staff yesterday denial, revoked phone and no direct financial RPC bypass.
- Node 22: signed P-256 DER signatures for all four types, domain separation, nonce/session/device/type/payload/operation modification rejected.
- Deno: Edge Function typecheck.
- Kotlin: Android 16-line financial proof and no v1/v2 downgrade.
- Live freeze guard: no production schema migration, App preview flags or dashboard changes.

## Remaining no-go blockers

Owner-approved P-256 key enrollment/proof of possession; production Staff RLS hardening; staging verification with real Supabase JWT and two physical phones; connect approved transports after audited rollout; Owner Room catalog and existing historical UUID reconciliation; full cloud dashboard including expenses; two-phone offline conflicts/midnight tests; encrypted full backups including photos; final signed APK link.

Do not deploy review-only SQL, enable Staff login/write switches or overwrite the original Owner phone yet.