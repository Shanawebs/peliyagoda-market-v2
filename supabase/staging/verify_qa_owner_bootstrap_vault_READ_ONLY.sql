-- READ ONLY: isolated Market Pro Owner Key Bootstrap STAGING.
-- Project: qqiheexpbuzohllugxrk. Expect all booleans true.
SELECT
 (SELECT count(*)=1 FROM public.workspace_members WHERE role='owner') AS owner_exists,
 (SELECT count(*)=0 FROM public.workspace_members WHERE role<>'owner') AS staff_unassigned,
 (SELECT count(*)=1 FROM auth.mfa_factors f
   JOIN public.workspace_members m ON m.user_id=f.user_id
   WHERE m.role='owner' AND f.factor_type='totp' AND f.status='verified')
   AS owner_totp_registered,
 (SELECT count(*)=0 FROM public.registered_devices) AS no_owner_or_staff_phone,
 (SELECT count(*)=0 FROM marketpro_private.qa_owner_bootstrap_gate WHERE enabled)
   AS owner_bootstrap_disabled,
 (SELECT count(*)=0 FROM marketpro_private.qa_owner_bootstrap_challenges)
   AS no_enrollment_challenges,
 (SELECT count(*)=0 FROM marketpro_private.qa_owner_device_public_keys)
   AS no_owner_keys_registered,
 (SELECT count(*)=0 FROM marketpro_private.pending_team_enrollments)
   AS no_staff_invites,
 (SELECT count(*)=0 FROM marketpro_private.qa_invite_gate WHERE enabled)
   AS staff_invite_disabled,
 (NOT has_table_privilege('authenticated',
    'marketpro_private.qa_owner_bootstrap_gate','UPDATE'))
   AS client_cannot_enable_bootstrap,
 (NOT has_table_privilege('authenticated',
    'marketpro_private.qa_owner_bootstrap_challenges','INSERT'))
   AS client_cannot_issue_challenge,
 (NOT has_table_privilege('authenticated',
    'marketpro_private.qa_owner_bootstrap_challenges','SELECT'))
   AS client_cannot_read_challenge,
 (NOT has_table_privilege('authenticated',
    'marketpro_private.qa_owner_device_public_keys','INSERT'))
   AS client_cannot_register_key,
 (NOT has_table_privilege('authenticated',
    'marketpro_private.qa_owner_device_public_keys','SELECT'))
   AS client_cannot_read_keys,
 (NOT has_table_privilege('anon',
    'marketpro_private.qa_owner_device_public_keys','SELECT'))
   AS anonymous_cannot_read_keys,
 (SELECT bool_and(c.relrowsecurity) FROM pg_class c
    JOIN pg_namespace n ON n.oid=c.relnamespace
    WHERE n.nspname='marketpro_private'
      AND c.relname IN ('qa_owner_bootstrap_gate',
         'qa_owner_bootstrap_challenges','qa_owner_device_public_keys'))
   AS all_new_tables_rls_enabled,
 (SELECT count(*)=0 FROM pg_policies
    WHERE schemaname='marketpro_private'
      AND tablename IN ('qa_owner_bootstrap_gate',
         'qa_owner_bootstrap_challenges','qa_owner_device_public_keys'))
   AS no_exposing_client_rls_policies,
 (NOT has_function_privilege('authenticated',
   'public.marketpro_qa_owner_issue_invite(uuid,uuid,uuid,uuid,text)','EXECUTE'))
   AS staff_invitation_still_blocked;
