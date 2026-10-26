-- READ ONLY. Supabase project: qqiheexpbuzohllugxrk.
-- Must return TRUE in every column before anyone considers releasing Android staging.
SELECT
 (SELECT count(*)=1 FROM public.workspaces
    WHERE id='af77cb66-76b2-492a-a460-06132f468713'::uuid
      AND name='Market Pro Two-Phone QA') as qa_workspace_only,
 (SELECT count(*)=1 FROM public.workspace_members WHERE role='owner')
    as owner_membership_unchanged,
 (SELECT count(*)=1 FROM auth.mfa_factors f
    JOIN public.workspace_members m ON m.user_id=f.user_id
    WHERE m.role='owner' AND f.factor_type='totp' AND f.status='verified')
    as owner_mfa_verified,
 (SELECT count(*)=0 FROM public.workspace_members WHERE role<>'owner')
    as no_staff_membership,
 (SELECT count(*)=0 FROM public.registered_devices)
    as no_phone_activated,
 (SELECT count(*)=0 FROM marketpro_private.qa_owner_bootstrap_gate
    WHERE enabled) as owner_bootstrap_gate_off,
 (SELECT count(*)=0 FROM marketpro_private.qa_owner_bootstrap_challenges)
    as no_active_owner_challenge,
 (SELECT count(*)=0 FROM marketpro_private.qa_owner_device_public_keys)
    as no_owner_key_registered,
 (SELECT count(*)=0 FROM marketpro_private.qa_invite_gate
    WHERE enabled) as staff_invitation_gate_off,
 (SELECT count(*)=0 FROM marketpro_private.pending_team_enrollments)
    as no_staff_invitation,
 (NOT has_function_privilege('authenticated',
    'public.marketpro_qa_owner_issue_bootstrap(uuid,uuid,text)','EXECUTE'))
    as owner_issue_client_rpc_still_off,
 (NOT has_function_privilege('authenticated',
    'public.marketpro_qa_service_owner_bootstrap_material(uuid,uuid,uuid)','EXECUTE'))
    as material_private_to_service,
 (NOT has_function_privilege('authenticated',
    'public.marketpro_qa_service_owner_bootstrap_commit(uuid,uuid,uuid)','EXECUTE'))
    as commit_private_to_service,
 (NOT has_function_privilege('anon',
    'public.marketpro_qa_service_owner_bootstrap_commit(uuid,uuid,uuid)','EXECUTE'))
    as anon_cannot_commit,
 (NOT has_function_privilege('authenticated',
    'public.marketpro_qa_owner_issue_invite(uuid,uuid,uuid,uuid,text)','EXECUTE'))
    as staff_invitation_rpc_still_off;
