-- Market Pro V3.6.9 | READ-ONLY live activation preflight.
-- NO schema changes, NO data writes, NO role/device actions, NO invitations.
-- Run using a privileged read-only Supabase SQL session, or psql.
-- SQL checks are necessary but do NOT replace RLS/physical-phone audits.
WITH devices AS (
 SELECT
  count(*) FILTER (WHERE m.role='owner' AND d.enabled AND d.can_write) AS owner_writers,
  count(*) FILTER (WHERE m.role='purchasing_staff' AND d.enabled AND d.can_write) AS staff_writers,
  count(*) FILTER (WHERE m.role='purchasing_staff' AND d.enabled) AS active_staff_devices
 FROM public.registered_devices d
 JOIN public.workspace_members m
 ON m.workspace_id=d.workspace_id AND m.user_id=d.user_id
), member_roles AS (
 SELECT count(*) FILTER (WHERE role='purchasing_staff') AS staff_members
 FROM public.workspace_members
), policy_state AS (
 SELECT
  count(*) FILTER (WHERE cmd='SELECT') AS six_select_policies,
  count(*) FILTER (WHERE cmd='SELECT' AND
    coalesce(qual,'') LIKE '%marketpro_private.is_workspace_member(%')
    AS unrestricted_membership_reads
 FROM pg_catalog.pg_policies
 WHERE schemaname='public'
 AND tablename IN ('categories','items','purchases','purchase_additions',
                   'requisitions','daily_cash_received')
), role_state AS (
 SELECT EXISTS (
  SELECT 1 FROM pg_catalog.pg_constraint c
  JOIN pg_catalog.pg_class t ON c.conrelid=t.oid
  JOIN pg_catalog.pg_namespace n ON t.relnamespace=n.oid
  WHERE n.nspname='public' AND t.relname='workspace_members'
  AND c.contype='c'
  AND pg_catalog.pg_get_constraintdef(c.oid) LIKE '%purchasing_staff%'
 ) AS staff_role_allowed
), rpc_state AS (
 SELECT
  count(*) FILTER (WHERE proname='marketpro_apply_change')>0 AS legacy_write,
  count(*) FILTER (WHERE proname='marketpro_team_pull_page')>0 AS staff_pull,
  count(*) FILTER (WHERE proname='marketpro_apply_team_purchase_change')>0 AS staff_purchase,
  count(*) FILTER (WHERE proname='marketpro_apply_team_financial_change')>0 AS staff_finance,
  count(*) FILTER (WHERE proname='marketpro_owner_invite_staff')>0 AS staff_invite,
  count(*) FILTER (WHERE proname='marketpro_staff_accept_invitation')>0 AS staff_accept,
  count(*) FILTER (WHERE proname='marketpro_owner_activate_staff_readonly')>0 AS initial_activation,
  count(*) FILTER (WHERE proname='marketpro_owner_disable_team_phone')>0 AS revoke_phone,
  count(*) FILTER (WHERE proname='marketpro_owner_replace_team_phone')>0 AS replace_phone,
  count(*) FILTER (WHERE proname='marketpro_owner_activate_replacement_readonly')>0 AS replace_activation
 FROM pg_catalog.pg_proc p
 JOIN pg_catalog.pg_namespace n ON p.pronamespace=n.oid
 WHERE n.nspname='public'
), facts AS (
 SELECT * FROM devices CROSS JOIN member_roles CROSS JOIN policy_state
 CROSS JOIN role_state CROSS JOIN rpc_state
), checks AS (
 SELECT 10 AS seq,'legacy_single_owner_writer' AS check_id,
 owner_writers=1 AND staff_writers=0 AS passed,
 'Owner='||owner_writers||', Staff Writers='||staff_writers AS observed
 FROM facts
 UNION ALL SELECT 20,'staff_role_schema_ready',staff_role_allowed,
 'Supported='||staff_role_allowed FROM facts
 UNION ALL SELECT 30,'staff_direct_rest_isolated',
 six_select_policies>=6 AND unrestricted_membership_reads=0,
 'Policies='||six_select_policies||', Broad='||unrestricted_membership_reads
 FROM facts
 UNION ALL SELECT 40,'device_bound_staff_pull_rpc',staff_pull,
 'Function exists='||staff_pull FROM facts
 UNION ALL SELECT 50,'revision_safe_staff_purchase_rpc',staff_purchase,
 'Function exists='||staff_purchase FROM facts
 UNION ALL SELECT 60,'revision_safe_staff_finance_rpc',staff_finance,
 'Function exists='||staff_finance FROM facts
 UNION ALL SELECT 70,'staff_invite_claim_activation_rpcs',
 staff_invite AND staff_accept AND initial_activation,
 'Invite='||staff_invite||', Claim='||staff_accept||
 ', Activate='||initial_activation FROM facts
 UNION ALL SELECT 80,'owner_phone_replacement_rpcs',
 revoke_phone AND replace_phone AND replace_activation,
 'Revoke='||revoke_phone||', Replace='||replace_phone||
 ', Activate='||replace_activation FROM facts
 UNION ALL SELECT 90,'rollout_gate_exists',
 pg_catalog.to_regclass('marketpro_private.team_rollout_gate') IS NOT NULL,
 'Gate='||coalesce(pg_catalog.to_regclass('marketpro_private.team_rollout_gate')::text,'missing')
 FROM facts
 UNION ALL SELECT 100,'staff_member_enrolled',
 staff_members>=1 AND active_staff_devices>=1 AND staff_writers=0,
 'Staff Members='||staff_members||', Active Devices='||active_staff_devices
 FROM facts
 UNION ALL SELECT 110,'other_expenses_cloud_ready',
 pg_catalog.to_regclass('public.other_expenses') IS NOT NULL,
 'Cloud table='||coalesce(pg_catalog.to_regclass('public.other_expenses')::text,'missing')
 FROM facts
 UNION ALL SELECT 120,'legacy_writer_rpc_still_present',legacy_write,
 'Function exists='||legacy_write FROM facts
)
SELECT check_id,CASE WHEN passed THEN 'PASS' ELSE 'BLOCKED' END AS status,
 observed FROM checks ORDER BY seq;
