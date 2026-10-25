-- Staging-only read-only verification. NO INSERT/UPDATE/DELETE/DDL.
-- Run only against Supabase project qqiheexpbuzohllugxrk.
WITH protected AS (
  SELECT tablename, policyname, cmd, roles, qual
  FROM pg_policies
  WHERE schemaname = 'public'
    AND tablename IN (
      'categories','items','purchases','purchase_additions',
      'requisitions','daily_cash_received'
    )
), policy_count AS (
  SELECT
    count(*) AS all_policies,
    count(*) FILTER (
      WHERE cmd='SELECT'
      AND roles=ARRAY['authenticated']::name[]
      AND qual LIKE '%owner%'
      AND qual LIKE '%editor%'
      AND qual LIKE '%viewer%'
      AND qual NOT LIKE '%purchasing_staff%'
      AND qual NOT LIKE '%is_workspace_member%'
    ) AS restricted
  FROM protected
)
SELECT
 (SELECT all_policies=6 AND restricted=6 FROM policy_count)
   AS six_direct_rest_read_policies_protected,
 (SELECT count(*)=1 FROM public.workspace_members WHERE role='owner')
   AS exactly_one_staging_owner,
 (SELECT count(*)=0 FROM public.workspace_members WHERE role <> 'owner')
   AS no_staff_access_activated,
 (SELECT count(*)=0 FROM public.registered_devices)
   AS no_phone_paired,
 (SELECT count(*)=1 FROM auth.mfa_factors f
    JOIN public.workspace_members m ON m.user_id=f.user_id AND m.role='owner'
    WHERE f.status='verified' AND f.factor_type='totp')
   AS owner_totp_verified,
 (SELECT count(*)=1 FROM public.workspaces
    WHERE id='af77cb66-76b2-492a-a460-06132f468713'::uuid
      AND name='Market Pro Two-Phone QA' AND timezone='Asia/Colombo')
   AS isolated_staging_workspace,
 (SELECT NOT has_function_privilege('authenticated',p.oid,'EXECUTE')
      AND NOT has_function_privilege('anon',p.oid,'EXECUTE')
    FROM pg_proc p JOIN pg_namespace n ON n.oid=p.pronamespace
    WHERE n.nspname='public' AND p.proname='marketpro_apply_change')
   AS old_writer_blocked;
