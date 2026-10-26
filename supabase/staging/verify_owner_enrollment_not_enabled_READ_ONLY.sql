-- READ ONLY / no side effects. Run ONLY in project qqiheexpbuzohllugxrk.
-- All booleans MUST be true before drafting Owner enrollment rollout.
-- This test alone is NOT evidence of phone attestation or authorization.
SELECT
 (SELECT count(*)=1 FROM public.workspaces
   WHERE id='af77cb66-76b2-492a-a460-06132f468713'::uuid
     AND name='Market Pro Two-Phone QA') AS isolated_qa_workspace,
 (SELECT count(*)=1 FROM public.workspace_members WHERE role='owner')
   AS exactly_one_owner,
 (SELECT count(*)=1 FROM auth.mfa_factors f
   JOIN public.workspace_members m ON m.user_id=f.user_id
   WHERE m.role='owner' AND f.factor_type='totp' AND f.status='verified')
   AS owner_mfa_enrolled,
 (SELECT count(*)=0 FROM public.workspace_members WHERE role<>'owner')
   AS staff_membership_off,
 (SELECT count(*)=0 FROM public.registered_devices)
   AS no_owner_or_staff_phone_registered,
 (SELECT count(*)=0 FROM marketpro_private.qa_owner_device_public_keys)
   AS no_owner_key_activated_or_staged,
 (SELECT count(*)=0 FROM marketpro_private.qa_owner_bootstrap_challenges)
   AS no_owner_bootstrap_challenge,
 (SELECT count(*)=0 FROM marketpro_private.qa_owner_bootstrap_gate WHERE enabled)
   AS owner_bootstrap_gate_disabled,
 (SELECT count(*)=0 FROM marketpro_private.qa_invite_gate WHERE enabled)
   AS staff_invite_gate_disabled,
 (SELECT count(*)=0 FROM marketpro_private.pending_team_enrollments)
   AS staff_invitations_empty,
 (NOT has_function_privilege('authenticated',
  'public.marketpro_qa_owner_issue_bootstrap(uuid,uuid,text)','EXECUTE'))
   AS client_owner_bootstrap_issue_not_granted,
 (NOT has_function_privilege('anon',
  'public.marketpro_qa_owner_issue_bootstrap(uuid,uuid,text)','EXECUTE'))
   AS anon_owner_bootstrap_issue_not_granted,
 (NOT has_function_privilege('authenticated',
  'public.marketpro_qa_service_owner_bootstrap_commit(uuid,uuid,uuid)','EXECUTE'))
   AS client_owner_bootstrap_commit_not_granted,
 (NOT has_function_privilege('authenticated',
  'public.marketpro_qa_service_remote_proof_issue(uuid,uuid,uuid,text)','EXECUTE'))
   AS client_qa_proof_issue_not_granted,
 (NOT has_function_privilege('authenticated',
  'public.marketpro_qa_service_remote_proof_consume(uuid,uuid,uuid)','EXECUTE'))
   AS client_qa_proof_consume_not_granted,
 (NOT has_function_privilege('authenticated',
  'public.marketpro_qa_owner_issue_invite(uuid,uuid,uuid,uuid,text)','EXECUTE'))
   AS client_staff_invite_not_granted,
 (SELECT count(*)=0 FROM marketpro_private.qa_owner_remote_proof_trials
  WHERE consumed_at IS NULL AND expires_at>clock_timestamp())
   AS no_live_unconsumed_qa_proof;
