-- READ ONLY: Market Pro Two-Phone QA hosted Staging, project qqiheexpbuzohllugxrk.
-- All columns should be true. No role activation / changes or secret output.
SELECT
 (SELECT count(*)=1 FROM public.workspaces
   WHERE id='af77cb66-76b2-492a-a460-06132f468713'::uuid
     AND name='Market Pro Two-Phone QA') AS isolated_qa_workspace,
 (SELECT count(*)=1 FROM public.workspace_members WHERE role='owner')
   AS owner_membership_intact,
 (SELECT count(*)=0 FROM public.workspace_members WHERE role<>'owner')
   AS no_staff_membership,
 (SELECT count(*)=0 FROM public.registered_devices)
   AS no_active_or_unregistered_phone,
 (SELECT count(*)=1 FROM auth.mfa_factors f JOIN public.workspace_members m
   ON m.user_id=f.user_id AND m.role='owner'
   WHERE f.factor_type='totp' AND f.status='verified')
   AS verified_owner_totp,
 (SELECT count(*)=1 FROM marketpro_private.qa_invite_gate WHERE enabled=false)
   AS private_invite_gate_disabled,
 (SELECT count(*)=0 FROM marketpro_private.pending_team_enrollments)
   AS no_invites_issued,
 (SELECT relrowsecurity FROM pg_class
   WHERE oid='marketpro_private.qa_invite_gate'::regclass)
   AS rollout_gate_rls_enabled,
 (NOT has_table_privilege('anon','marketpro_private.qa_invite_gate','SELECT'))
   AS anonymous_cannot_read_gate,
 (NOT has_table_privilege('authenticated','marketpro_private.qa_invite_gate','UPDATE'))
   AS staff_cannot_enable_gate,
 (NOT has_function_privilege('authenticated',
 'public.marketpro_qa_owner_issue_invite(uuid,uuid,uuid,uuid,text)','EXECUTE'))
   AS owner_invite_rpc_not_client_executable,
 (NOT has_function_privilege('authenticated',
 'public.marketpro_qa_staff_accept_invite(uuid,uuid,text)','EXECUTE'))
   AS staff_accept_rpc_not_client_executable,
 (NOT has_function_privilege('anon',
 'public.marketpro_qa_owner_issue_invite(uuid,uuid,uuid,uuid,text)','EXECUTE'))
   AS anonymous_cannot_issue_invite,
 (NOT has_function_privilege('anon',
 'public.marketpro_qa_staff_accept_invite(uuid,uuid,text)','EXECUTE'))
   AS anonymous_cannot_accept_invite,
 (NOT has_function_privilege('authenticated',
 'marketpro_private.qa_current_owner_is_fresh(uuid,uuid)','EXECUTE'))
   AS owner_session_helper_private,
 (SELECT count(*)=6 FROM pg_policies p
   WHERE p.schemaname='public'
     AND p.cmd='SELECT'
     AND p.tablename IN
       ('categories','items','purchases','purchase_additions',
        'requisitions','daily_cash_received')
     AND p.qual LIKE '%owner%' AND p.qual LIKE '%viewer%'
     AND p.qual NOT LIKE '%purchasing_staff%')
   AS future_staff_direct_rest_not_opened;
