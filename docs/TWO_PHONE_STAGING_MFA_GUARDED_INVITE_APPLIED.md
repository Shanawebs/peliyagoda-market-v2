# Market Pro Two-Phone — Owner MFA-Gated Invitation RPC: Hosted Staging

**Status: Staging-only migration APPLIED, all RPC permissions and rollout OFF.**
**Supabase project:** `qqiheexpbuzohllugxrk`; **Production remains:** `lkoptqhodgjeuwwfihfr`.

## Actual completed work

1. Reviewed the present Staging state: 1 isolated workspace, 1 verified MFA Owner, 0 registered phones, 0 Staff workspace members and 0 pending invitations.
2. Implemented two private-to-clients, SECURITY DEFINER APIs:
   - `public.marketpro_qa_owner_issue_invite`: intended Owner-only invitation, checks signed Auth session live against `auth.sessions`, confirmed Auth profile and verified Owner TOTP; requires `aal2` with a TOTP authentication **within the previous 300 seconds**, registered enabled Owner phone, a unique Staff account/device, and disabled-by-default private rollout gate. It stores only SHA-256 of the one-time 256-bit invitation code.
   - `public.marketpro_qa_staff_accept_invite`: requires matching live verified Staff Auth session, unexpired one-use invitation, correct intended phone; changes invitation status to accepted ONLY. It cannot give workspace membership, business records, registered phone, or writing permission.
3. Staging-specific `marketpro_private.qa_invite_gate` created with `enabled=false`, private RLS ON, no policies, and no direct role grants. All new RPC EXECUTE grants explicitly revoked from `PUBLIC`, `anon`, and `authenticated`. **No caller can invoke these RPCs through the client API currently.**
4. GitHub Action passed separate Production Android/source freeze and disposable PostgreSQL 17 simulated Auth regression. Local simulations covered: gate OFF, Owner not enrolled phone, Owner AAL1, stale TOTP, invalid Owner session, wrong Owner phone, SHA256 token storage, rotating invitation, invalid/reused code, mismatched Staff device/session and acceptance without Staff membership.
5. Applied migration on **Staging only**: version `20261025192801`, name `marketpro_qa_mfa_guarded_invitation_dormant`. Hosted read-only checks: gate OFF, 0 invitations, 1 Owner, 0 Staff members, 0 devices, verified TOTP factor, no client EXECUTE rights.

## Security limitations: do not switch the rollout gate on

- **A registered device UUID is NOT cryptographic device proof.** The future Owner device onboarding must bind keys and verify real challenge signatures; no Owner phone is enrolled yet.
- Only a disposable PG17 fixture tested positive invitation/acceptance scenarios. Hosted real Supabase client JWT/PostgREST E2E, real Staff hardware key, owner-authenticated key approval, server RLS for **all** exposed objects, financial atomic write semantics and physical Android devices still need independent testing.
- Staging test Staff Gmail dot alias shares the Owner's recovery mailbox. Use a genuinely independent address when onboarding Purchasing Team in the actual app.
- Do not merge the old V358 `CREATE TABLE` proposal or simultaneously expose legacy RPCs; the pending vault table already exists.
- This is NOT signed Staging APK release, live Staff pairing, full Cloud Sync, or Production deployment. Owner Production app and dashboard are unchanged.
- Supabase Security Advisor currently reports INFO on deliberately unexposed private tables and a pre-existing WARN that leaked-password protection is disabled. Review Auth settings separately; do not reduce RLS to silence INFO.

## Audit

Read-only hosted query: `supabase/staging/verify_mfa_guarded_invite_STAGING_READ_ONLY.sql`.
Disposable PG17 regression: `supabase/tests/staging_qa_mfa_guarded_invite_pg17_local_only.sql`.

## Next blocking steps

Complete isolated staging Android appId/host/Owner-Staff user IDs, provision Owner signed-key device approval and Staff signed-key/one-time pairing, wire server proof to all financial operations, then test separate physical phones and data reconciliation. **Do not authorize Staging client EXECUTE or flip the gate until the full defense has passed.**
