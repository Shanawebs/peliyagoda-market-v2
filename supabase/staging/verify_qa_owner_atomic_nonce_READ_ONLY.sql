-- Staging QA ONLY, project qqiheexpbuzohllugxrk. READ ONLY.
-- All checks must return true. No account, phone, key or token values emitted.
SELECT
 (SELECT count(*)=1 FROM public.workspace_members WHERE role='owner')
   AS exactly_one_owner,
 (SELECT count(*)=1 FROM auth.mfa_factors f
    JOIN public.workspace_members m ON m.user_id=f.user_id
    WHERE m.role='owner' AND f.factor_type='totp' AND f.status='verified')
   AS owner_totp_verified,
 (SELECT count(*)=0 FROM public.workspace_members WHERE role<>'owner')
   AS staff_not_authorized,
 (SELECT count(*)=0 FROM public.registered_devices)
   AS no_phone_activated,
 (SELECT count(*)=0 FROM marketpro_private.qa_owner_bootstrap_gate WHERE enabled)
   AS bootstrap_switch_off,
 (SELECT count(*)=0 FROM marketpro_private.qa_owner_bootstrap_challenges)
   AS no_live_challenges,
 (SELECT count(*)=0 FROM marketpro_private.qa_owner_device_public_keys)
   AS no_approved_keys,
 (SELECT count(*)=0 FROM marketpro_private.pending_team_enrollments)
   AS no_staff_invitations,
 (SELECT count(*)=0 FROM marketpro_private.qa_invite_gate WHERE enabled)
   AS staff_invites_still_off,
 (NOT has_function_privilege('authenticated',
     'public.marketpro_qa_owner_issue_bootstrap(uuid,uuid,text)','EXECUTE'))
   AS no_owner_client_issue_permission,
 (NOT has_function_privilege('authenticated',
     'public.marketpro_qa_service_owner_bootstrap_material(uuid,uuid,uuid)','EXECUTE'))
   AS staff_cannot_get_challenge_material,
 (NOT has_function_privilege('authenticated',
     'public.marketpro_qa_service_owner_bootstrap_commit(uuid,uuid,uuid)','EXECUTE'))
   AS staff_cannot_commit_challenge,
 (NOT has_function_privilege('anon',
     'public.marketpro_qa_service_owner_bootstrap_commit(uuid,uuid,uuid)','EXECUTE'))
   AS anonymous_cannot_commit,
 (NOT has_function_privilege('authenticated',
     'marketpro_private.qa_owner_bootstrap_auth(uuid)','EXECUTE'))
   AS owner_auth_helper_private,
 has_function_privilege('service_role',
     'public.marketpro_qa_service_owner_bootstrap_commit(uuid,uuid,uuid)','EXECUTE')
   AS trusted_server_can_commit_after_external_ecdsa,
 (NOT has_function_privilege('authenticated',
     'public.marketpro_qa_owner_issue_invite(uuid,uuid,uuid,uuid,text)','EXECUTE'))
   AS staff_invitation_unavailable;
