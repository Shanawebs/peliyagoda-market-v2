-- Market Pro QA STAGING ONLY — pending invitation private storage.
-- Exact Supabase project: qqiheexpbuzohllugxrk.
-- Zero invitation rows, zero Staff role/device, no invited users or RPC grants.
-- This is an intentionally INERT building block. DO NOT run on Production.
-- Future V358/V359 proposals must be consolidated: original V358 CREATE TABLE
-- must NOT be executed again, and the callable RPC chain is NOT DEPLOYED.
BEGIN;

DO $preflight$
DECLARE
  v_workspace uuid := 'af77cb66-76b2-492a-a460-06132f468713'::uuid;
  v_owners int;
  v_valid int;
BEGIN
  IF (SELECT count(*) FROM public.workspaces) <> 1
    OR NOT EXISTS(
      SELECT 1 FROM public.workspaces
       WHERE id=v_workspace AND name='Market Pro Two-Phone QA'
         AND timezone='Asia/Colombo'
    )
    OR (SELECT count(*) FROM public.workspace_members) <> 1
    OR (SELECT count(*) FROM public.registered_devices) <> 0
    OR EXISTS (SELECT 1 FROM public.workspace_members WHERE role <> 'owner')
    OR EXISTS (SELECT 1 FROM public.purchases)
    OR EXISTS (SELECT 1 FROM public.requisitions)
    OR EXISTS (SELECT 1 FROM public.daily_cash_received)
    OR to_regclass('marketpro_private.pending_team_enrollments') IS NOT NULL
  THEN
    RAISE EXCEPTION 'Staging invitation vault: nonempty or unexpected QA state';
  END IF;

  SELECT count(*) INTO v_owners
    FROM public.workspace_members m
    JOIN auth.users u ON u.id=m.user_id
   WHERE m.workspace_id=v_workspace AND m.role='owner'
     AND u.email_confirmed_at IS NOT NULL
     AND EXISTS (
       SELECT 1 FROM auth.mfa_factors f
        WHERE f.user_id=m.user_id
          AND f.factor_type='totp' AND f.status='verified'
     );
  IF v_owners<>1 THEN
    RAISE EXCEPTION 'Staging invitation vault: verified MFA Owner missing';
  END IF;

  SELECT count(*) INTO v_valid FROM pg_policies p
   WHERE p.schemaname='public'
     AND p.tablename IN (
       'categories','items','purchases',
       'purchase_additions','requisitions','daily_cash_received'
     )
     AND p.cmd='SELECT'
     AND p.roles=ARRAY['authenticated']::name[]
     AND p.qual LIKE '%owner%'
     AND p.qual LIKE '%editor%'
     AND p.qual LIKE '%viewer%'
     AND p.qual NOT LIKE '%purchasing_staff%'
     AND p.qual NOT LIKE '%is_workspace_member%';
  IF v_valid<>6 THEN
    RAISE EXCEPTION 'Staging invitation vault: six direct REST read gates not verified';
  END IF;

  IF EXISTS (
    SELECT 1 FROM pg_proc p
    JOIN pg_namespace n ON n.oid=p.pronamespace
    WHERE n.nspname='public'
      AND p.proname IN (
         'marketpro_owner_invite_staff',
         'marketpro_staff_accept_invitation',
         'marketpro_owner_activate_staff_readonly',
         'marketpro_owner_enable_staff_writer'
      )
  ) THEN
    RAISE EXCEPTION 'Staging invitation vault: active Staff RPC present unexpectedly';
  END IF;
END;
$preflight$;

-- Name/columns intentionally match reviewed V358 and V359 prototype.
-- No plaintext invitation token or email/password is ever stored.
CREATE TABLE marketpro_private.pending_team_enrollments (
  workspace_id uuid NOT NULL REFERENCES public.workspaces(id),
  staff_user_id uuid NOT NULL REFERENCES auth.users(id),
  inviter_user_id uuid NOT NULL REFERENCES auth.users(id),
  intended_device_id uuid NOT NULL,
  device_label text NOT NULL
    CHECK (char_length(btrim(device_label)) BETWEEN 1 AND 100),
  token_sha256 bytea NOT NULL
    CHECK (octet_length(token_sha256)=32),
  expires_at timestamptz NOT NULL,
  status text NOT NULL DEFAULT 'invited'
    CHECK (status IN ('invited','accepted','revoked')),
  created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  accepted_at timestamptz,
  revoked_at timestamptz,
  activated_at timestamptz,
  CONSTRAINT pending_team_enrollments_pk
    PRIMARY KEY(workspace_id,staff_user_id),
  CONSTRAINT pending_team_enrollments_device_unique
    UNIQUE(intended_device_id),
  CONSTRAINT pending_team_enrollments_inviter_not_staff
    CHECK(inviter_user_id<>staff_user_id),
  CONSTRAINT pending_team_enrollments_expires_after_creation
    CHECK(expires_at>created_at)
);

REVOKE ALL ON marketpro_private.pending_team_enrollments
  FROM PUBLIC,anon,authenticated;
ALTER TABLE marketpro_private.pending_team_enrollments
  ENABLE ROW LEVEL SECURITY;
-- Intentionally no RLS policies: no direct logged-in client reads/writes.
-- No public RPC, no invitations, no Owner/Staff session or device changes.

DO $postcheck$
BEGIN
  IF (SELECT count(*) FROM marketpro_private.pending_team_enrollments)<>0
     OR NOT (
       SELECT relrowsecurity FROM pg_class
       WHERE oid='marketpro_private.pending_team_enrollments'::regclass
     )
     OR has_table_privilege('anon',
          'marketpro_private.pending_team_enrollments','SELECT')
     OR has_table_privilege('authenticated',
          'marketpro_private.pending_team_enrollments','SELECT')
     OR has_table_privilege('authenticated',
          'marketpro_private.pending_team_enrollments','INSERT')
     OR has_table_privilege('authenticated',
          'marketpro_private.pending_team_enrollments','UPDATE')
     OR has_table_privilege('authenticated',
          'marketpro_private.pending_team_enrollments','DELETE')
     OR EXISTS (
       SELECT 1 FROM pg_policies
       WHERE schemaname='marketpro_private'
         AND tablename='pending_team_enrollments'
     )
  THEN
    RAISE EXCEPTION 'Staging invitation vault: unauthorized grants or policies';
  END IF;
END;
$postcheck$;
COMMIT;
