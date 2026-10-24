# Market Pro V3.5.2 — Server-enforced business-day access (REVIEW ONLY)

## Confirmed requirements

- Owner's phone: can view and correct ALL historical days, and can enter/edit today's records.
- Purchasing team's dedicated phone: can enter and correct data UNLIMITED times for the **current Sri Lanka business date** with NO Owner approval or queue. All earlier days are view-only to purchasing team. Neither role can write a future business date.
- Changes must eventually sync both ways (staff → Supabase → owner's Room; Owner correction → Supabase → staff's Room) while retaining the EXACT existing dashboard UI and URL.
- Keep the already-working V3.3.7 single-writer device in service until independently tested Staff enrollment + multi-writer RPC upgrade is complete.
- The existing V3.4.1/V3.5.0 security preview package has no INTERNET permission and is not an installed app upgrade.

## Existing database findings (READ-ONLY inspection)

- Production Postgres v17 has `workspace_members.role` limited to owner/editor/viewer, not `purchasing_staff`.
- `registered_devices` stores user/workspace/device and `enabled`/`can_write`.
- Financial entities `purchases`, `requisitions`, `purchase_additions`, `daily_cash_received` ALREADY contain bigint `revision`; same-day corrections can use it for concurrency.
- Read RLS already allows authenticated workspace members via `marketpro_private.is_workspace_member(workspace_id)`. Registered device read policy reveals each user's OWN registered device.
- Current `public.marketpro_apply_change` accepts only owner+registered device, does NOT require `expected_revision`, and must remain unchanged until cutover. **Never grant a staff device the existing v1 owner account credentials**. A second writer cannot safely reuse the old protocol.
- `sync_operations` already stores unique operation_id, hash, actor_user_id and device ID. Its entity_type CHECK does not yet include `daily_other_expenses`; that is later work.

## Draft SQL private helper

File: `supabase/proposals/v352_team_write_gate_REVIEW_ONLY.sql`.

The helper `marketpro_private.authorize_team_financial_write` uses *actual auth.uid()* and current auth JWT, server registered device membership, and `clock_timestamp() AT TIME ZONE 'Asia/Colombo'` to decide the allowed actor. It accepts only:
- create: no existing locked row; business date = today for Staff, <= today for Owner
- correct: real locked prior record has SAME business date as proposed; staff today only; Owner any past/today date
- anonymous/unregistered/disabled staff/viewer roles: **reject**
- future date for anyone: **reject**

Security DEFERS ALL direct staff/owner writes: this function is NOT the actual data writer. It is `SECURITY DEFINER SET search_path=''` under a PRIVATE non-exposed schema; EXECUTE explicitly denied to `PUBLIC`, `anon` and `authenticated`. This approach follows documented Supabase precautions. A future controlled privileged RPC v2 is the ONLY caller, executed under authenticated user identity and the same transaction. It MUST read the old row with `FOR UPDATE` and supply the ACTUAL locked row's business_date to the helper; the client cannot supply that value.

**Do not execute this file on live Supabase**. It is a proposal, NOT a migration. It includes a constraint expansion allowing 'purchasing_staff' without removing existing legacy roles. The schema name is existing private schema. All server DDL and user creation require separately tested, audited deployment.

## The still-missing atomic RPC v2

A proper `marketpro_apply_team_change` must:
1. Verify Auth subject, registered device, member role, and reject anonymous JWT.
2. Serialize per workspace for safe cross-device operations (or equivalently row-lock and unique-key protect); if idempotency op exists, ACK only identical actor/device/UUID/action/hash.
3. `SELECT ... FOR UPDATE` existing record. Use locked row's original business_date; never allow staff moving a historical row into today. Compute TODAY from server inside same transaction.
4. On NEW row require new canonical UUID and absent semantic business key. On CORRECTION require `expected_revision == locked.revision`, increment revision once, retain original UUID and immutable date/item semantic key. Never use last-write-wins.
5. Reject unapproved historic edits, deleted/dangling item IDs, wrong workspace, negative quantity/cost, invoice photo/path payloads, deletion by staff, all unintended entities and extra keys.
6. Commit change + actor/device audit/op hash atomically; return new revision for Room checkpoint. Old RPC remains for existing V3.3.7 until client migration plans are approved.
7. Support staff Auth user membership enrolment via owner-only authenticated admin path. Do not let staff self-enrol or set its own role/device status.
8. Deploy owner-phone authenticated RLS-protected snapshot/download with cursor, safely map canonical Cloud UUID and revisions into Room without wiping unsent edits. Old dashboard visually unchanged.
9. Add new `daily_other_expenses` with correct RLS and aggregation only after explicit approval and UI-preserving server design.

## Verified isolated tests

`supabase/tests/v352_team_write_gate_local_only.sql` creates a fake Auth/JWT and `workspace_members` / `registered_devices` table in an ephemeral PostgreSQL 17 container. The workflow tests:
- 20 same-day staff corrections and staff create
- Staff previous-day create/correct denied
- Staff masquerading as owner device denied
- Disabled phone denied
- Historic record cannot be moved to today
- Owner old-day correction allowed
- Owner future-day denied
- Viewer and anonymous users denied
- Helper cannot be invoked by `anon`/`authenticated` roles

The source-only branch has NO new staff member, NO service key in APK, NO database changes and NO alteration to `web/marketpro-dashboard/`.

## Deployment gates before next phase

1. Owner backup export and existing cloud count/snapshot (no deletes).
2. Dedicated staff Auth identity and device ID, separate from owner.
3. Implement and test complete RPC v2 in throwaway PostgreSQL and security/permission review.
4. Build owner-downloader and staff uploader Android APKs with reproducible signing/update migration (don't lose working live app).
5. Verify two real physical phones: repeated same-day edits, last-minute-midnight cutoff, offline queue crossing midnight, concurrent corrections, owner old-day edit and Cloud Dashboard figures.
6. Only then owner-approved staged cloud deployment with rollback plan.
