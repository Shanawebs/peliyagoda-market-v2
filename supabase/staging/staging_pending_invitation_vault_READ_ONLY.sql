-- STAGING ONLY: qqiheexpbuzohllugxrk
-- READ ONLY. Should return all boolean checks TRUE.
SELECT
  (to_regclass('marketpro_private.pending_team_enrollments') IS NOT NULL)
    AS private_invitation_table_exists,
  (SELECT relrowsecurity FROM pg_class
    WHERE oid='marketpro_private.pending_team_enrollments'::regclass)
    AS private_invitation_rls_on,
  ((SELECT count(*) FROM marketpro_private.pending_team_enrollments)=0)
    AS no_invitation_issued,
  (NOT has_table_privilege('anon',
     'marketpro_private.pending_team_enrollments','SELECT'))
    AS anonymous_cannot_read,
  (NOT has_table_privilege('authenticated',
     'marketpro_private.pending_team_enrollments','SELECT'))
    AS staff_cannot_read,
  (NOT has_table_privilege('authenticated',
     'marketpro_private.pending_team_enrollments','INSERT'))
    AS staff_cannot_insert,
  (NOT has_table_privilege('authenticated',
     'marketpro_private.pending_team_enrollments','UPDATE'))
    AS staff_cannot_update,
  (NOT has_table_privilege('authenticated',
     'marketpro_private.pending_team_enrollments','DELETE'))
    AS staff_cannot_delete,
  ((SELECT count(*) FROM pg_policies
      WHERE schemaname='marketpro_private'
        AND tablename='pending_team_enrollments')=0)
    AS no_exposing_policies,
  ((SELECT count(*) FROM public.workspace_members WHERE role='owner')=1)
    AS owner_member_intact,
  ((SELECT count(*) FROM public.workspace_members WHERE role<>'owner')=0)
    AS staff_member_not_activated,
  ((SELECT count(*) FROM public.registered_devices)=0)
    AS no_phone_paired,
  ((SELECT count(*) FROM auth.mfa_factors f
      JOIN public.workspace_members m ON m.user_id=f.user_id
      WHERE m.role='owner'
        AND f.factor_type='totp' AND f.status='verified')=1)
    AS owner_authenticator_verified,
  (NOT EXISTS (
     SELECT 1 FROM pg_proc p JOIN pg_namespace n ON n.oid=p.pronamespace
     WHERE n.nspname='public'
       AND p.proname IN ('marketpro_owner_invite_staff',
         'marketpro_staff_accept_invitation',
         'marketpro_owner_activate_staff_readonly',
         'marketpro_owner_enable_staff_writer')
   )) AS no_active_staff_invite_pairing_or_writer_rpc,
  ((SELECT count(*) FROM pg_policies p
     WHERE p.schemaname='public' AND p.cmd='SELECT'
       AND p.tablename IN ('categories','items','purchases',
                           'purchase_additions','requisitions','daily_cash_received')
       AND p.qual LIKE '%owner%' AND p.qual LIKE '%editor%'
       AND p.qual LIKE '%viewer%' AND p.qual NOT LIKE '%purchasing_staff%')=6)
   AS future_staff_direct_rest_still_blocked;
