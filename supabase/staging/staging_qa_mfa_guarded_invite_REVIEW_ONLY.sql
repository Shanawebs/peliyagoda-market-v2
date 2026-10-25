-- Market Pro QA: Owner MFA + registered-phone invitation guard (STAGING-ONLY REVIEW)
-- Pinned Staging project: qqiheexpbuzohllugxrk.
-- DO NOT DEPLOY TO PRODUCTION OR AUTO-ENABLE ANY RPC.
-- Compatibility: expects the existing PRIVATE pending_team_enrollments vault.
-- This migration issues NO invitations, registers NO devices, and has NO grants
-- to anon/authenticated. Even when deployed, API callers cannot execute it.
BEGIN;

DO $preflight$
BEGIN
  IF NOT EXISTS (
    SELECT 1 FROM public.workspaces
    WHERE id='af77cb66-76b2-492a-a460-06132f468713'::uuid
      AND name='Market Pro Two-Phone QA'
  )
  OR (SELECT count(*) FROM public.workspaces)<>1
  OR (SELECT count(*) FROM public.workspace_members)<>1
  OR (SELECT count(*) FROM public.registered_devices)<>0
  OR (SELECT count(*) FROM marketpro_private.pending_team_enrollments)<>0
  OR EXISTS(
    SELECT 1 FROM pg_proc p JOIN pg_namespace n ON p.pronamespace=n.oid
    WHERE n.nspname='public' AND p.proname IN (
      'marketpro_qa_owner_issue_invite','marketpro_qa_staff_accept_invite'
    )
  )
  THEN
    RAISE EXCEPTION 'STAGING preflight mismatch, refusing Owner invite RPC install';
  END IF;
END;
$preflight$;

CREATE TABLE marketpro_private.qa_invite_gate (
  workspace_id uuid PRIMARY KEY REFERENCES public.workspaces(id),
  enabled boolean NOT NULL DEFAULT false,
  reviewed_at timestamptz
);
REVOKE ALL ON marketpro_private.qa_invite_gate
  FROM PUBLIC, anon, authenticated;
ALTER TABLE marketpro_private.qa_invite_gate ENABLE ROW LEVEL SECURITY;
INSERT INTO marketpro_private.qa_invite_gate(workspace_id, enabled)
VALUES ('af77cb66-76b2-492a-a460-06132f468713'::uuid, false);

-- This SECURITY DEFINER function lives in a private schema, and has no grants
-- to client roles. It checks the TRUE Auth server session, not a phone UUID alone.
CREATE FUNCTION marketpro_private.qa_current_owner_is_fresh(
  p_workspace_id uuid, p_owner_device_id uuid
) RETURNS boolean
LANGUAGE plpgsql VOLATILE SECURITY DEFINER SET search_path=''
AS $owner$
DECLARE
  v_owner uuid := auth.uid();
  v_jwt jsonb := auth.jwt();
  v_sid_text text;
  v_sid uuid;
  v_methods jsonb;
  v_method jsonb;
  v_stamp_text text;
  v_recent boolean := false;
BEGIN
  IF p_workspace_id IS DISTINCT FROM
       'af77cb66-76b2-492a-a460-06132f468713'::uuid
      OR p_owner_device_id IS NULL
      OR v_owner IS NULL
      OR jsonb_typeof(v_jwt)<>'object'
      OR (v_jwt->>'aal') IS DISTINCT FROM 'aal2'
      OR (v_jwt->>'sub') IS DISTINCT FROM v_owner::text
      OR COALESCE(v_jwt->>'is_anonymous','false')<>'false'
  THEN RETURN false; END IF;

  v_sid_text:=v_jwt->>'session_id';
  IF v_sid_text IS NULL OR v_sid_text !~
      '^[a-fA-F0-9]{8}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{12}$'
  THEN RETURN false; END IF;
  v_sid:=v_sid_text::uuid;

  -- Only server-side sessions belonging to the calling Owner count.
  IF NOT EXISTS (
    SELECT 1 FROM auth.sessions s
     WHERE s.id=v_sid AND s.user_id=v_owner
       AND (s.not_after IS NULL OR s.not_after>clock_timestamp())
  ) THEN RETURN false; END IF;

  -- Verify the actual Authenticator enrollment and registered Owner phone.
  IF NOT EXISTS (
    SELECT 1 FROM auth.users u
    JOIN public.workspace_members m ON m.user_id=u.id
    JOIN public.registered_devices d ON d.workspace_id=m.workspace_id
      AND d.user_id=m.user_id
    WHERE u.id=v_owner AND u.email_confirmed_at IS NOT NULL
      AND m.workspace_id=p_workspace_id AND m.role='owner'
      AND d.id=p_owner_device_id AND d.enabled AND d.can_write
      AND EXISTS (
        SELECT 1 FROM auth.mfa_factors f
        WHERE f.user_id=u.id AND f.factor_type='totp'
          AND f.status='verified'
      )
  ) THEN RETURN false; END IF;

  -- Fresh 5-minute TOTP step-up, authenticated by Supabase's signed JWT.
  v_methods:=v_jwt->'amr';
  IF jsonb_typeof(v_methods)<>'array' THEN RETURN false; END IF;
  FOR v_method IN SELECT value FROM jsonb_array_elements(v_methods)
  LOOP
    IF jsonb_typeof(v_method)='object'
       AND v_method->>'method'='totp'
    THEN
      v_stamp_text:=v_method->>'timestamp';
      IF v_stamp_text ~ '^[0-9]{10}$'
      AND v_stamp_text::bigint BETWEEN
        (extract(epoch from clock_timestamp())::bigint - 300)
        AND (extract(epoch from clock_timestamp())::bigint + 30)
      THEN v_recent:=true; END IF;
    END IF;
  END LOOP;
  RETURN v_recent;
END;
$owner$;
REVOKE ALL ON FUNCTION
  marketpro_private.qa_current_owner_is_fresh(uuid,uuid)
  FROM PUBLIC,anon,authenticated;

-- Dormant staging API: future review must connect Owner hardware-key proof,
-- pairing + ACL tests before changing the private rollout gate and EXECUTE.
CREATE FUNCTION public.marketpro_qa_owner_issue_invite(
  p_workspace_id uuid, p_owner_device_id uuid,
  p_staff_user_id uuid, p_staff_device_id uuid,
  p_staff_device_label text
) RETURNS jsonb
LANGUAGE plpgsql VOLATILE SECURITY DEFINER SET search_path=''
AS $invite$
DECLARE
  v_owner uuid:=auth.uid();
  v_token text;
  v_expiry timestamptz;
BEGIN
  IF NOT marketpro_private.qa_current_owner_is_fresh(
       p_workspace_id,p_owner_device_id
  ) OR NOT EXISTS(
      SELECT 1 FROM marketpro_private.qa_invite_gate
       WHERE workspace_id=p_workspace_id AND enabled
  ) THEN
    RAISE EXCEPTION 'Owner invitation not enabled / fresh Owner MFA and phone required'
      USING ERRCODE='42501';
  END IF;

  IF p_staff_user_id IS DISTINCT FROM
       'b805598b-421f-447b-aa4c-ffb66f721338'::uuid
     OR p_staff_device_id IS NULL
     OR p_staff_device_id=p_owner_device_id
     OR p_staff_device_label IS NULL
     OR char_length(btrim(p_staff_device_label)) NOT BETWEEN 1 AND 100
     OR p_staff_device_label ~ '[[:cntrl:]]'
  THEN RAISE EXCEPTION 'Invalid QA Staff or device' USING ERRCODE='22023';
  END IF;

  PERFORM pg_catalog.pg_advisory_xact_lock(
    pg_catalog.hashtextextended(p_workspace_id::text,0)
  );

  IF NOT EXISTS(
    SELECT 1 FROM auth.users
     WHERE id=p_staff_user_id AND email_confirmed_at IS NOT NULL
  ) OR EXISTS(
    SELECT 1 FROM public.workspace_members
     WHERE user_id=p_staff_user_id AND workspace_id=p_workspace_id
  ) OR EXISTS (
    SELECT 1 FROM public.registered_devices WHERE id=p_staff_device_id
  ) THEN
    RAISE EXCEPTION 'Staff identity not verified or device already enrolled'
      USING ERRCODE='42501';
  END IF;

  v_token:=pg_catalog.encode(extensions.gen_random_bytes(32),'hex');
  v_expiry:=clock_timestamp()+interval '30 minutes';

  INSERT INTO marketpro_private.pending_team_enrollments(
    workspace_id,staff_user_id,inviter_user_id,intended_device_id,
    device_label,token_sha256,expires_at,status,
    created_at,accepted_at,revoked_at,activated_at
  )
  VALUES(
    p_workspace_id,p_staff_user_id,v_owner,p_staff_device_id,
    btrim(p_staff_device_label),
    extensions.digest(pg_catalog.convert_to(v_token,'UTF8'),'sha256'),
    v_expiry,'invited',clock_timestamp(),NULL,NULL,NULL
  )
  ON CONFLICT (workspace_id,staff_user_id) DO UPDATE SET
    inviter_user_id=EXCLUDED.inviter_user_id,
    intended_device_id=EXCLUDED.intended_device_id,
    device_label=EXCLUDED.device_label,
    token_sha256=EXCLUDED.token_sha256,
    expires_at=EXCLUDED.expires_at,
    status='invited',created_at=clock_timestamp(),
    accepted_at=NULL,revoked_at=NULL,activated_at=NULL;

  RETURN pg_catalog.jsonb_build_object(
    'invitation_created',true,'one_time_code',v_token,
    'expires_at',v_expiry,'staff_phone_active',false,
    'staff_can_read',false,'staff_can_write',false
  );
END;
$invite$;
REVOKE ALL ON FUNCTION
  public.marketpro_qa_owner_issue_invite(uuid,uuid,uuid,uuid,text)
  FROM PUBLIC,anon,authenticated;

-- Staff can consume an existing, unexpired code only from a LIVE Auth session.
-- Acceptance is NOT activation and grants NO workspace/phone/financial access.
CREATE FUNCTION public.marketpro_qa_staff_accept_invite(
  p_workspace_id uuid, p_staff_device_id uuid, p_code text
) RETURNS jsonb
LANGUAGE plpgsql VOLATILE SECURITY DEFINER SET search_path=''
AS $accept$
DECLARE
  v_actor uuid:=auth.uid();
  v_jwt jsonb:=auth.jwt();
  v_session_text text;
  v_session uuid;
  v_invite marketpro_private.pending_team_enrollments%ROWTYPE;
BEGIN
  IF p_workspace_id IS DISTINCT FROM
      'af77cb66-76b2-492a-a460-06132f468713'::uuid
    OR v_actor IS DISTINCT FROM 'b805598b-421f-447b-aa4c-ffb66f721338'::uuid
    OR p_staff_device_id IS NULL OR p_code !~ '^[a-f0-9]{64}$'
    OR COALESCE(v_jwt->>'is_anonymous','false')<>'false'
    OR (v_jwt->>'sub') IS DISTINCT FROM v_actor::text
    OR NOT EXISTS(
       SELECT 1 FROM marketpro_private.qa_invite_gate
       WHERE workspace_id=p_workspace_id AND enabled
    )
  THEN
    RAISE EXCEPTION 'Staff invitation not authorized' USING ERRCODE='42501';
  END IF;

  v_session_text:=v_jwt->>'session_id';
  IF v_session_text IS NULL OR
     v_session_text !~ '^[a-fA-F0-9]{8}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{12}$'
  THEN RAISE EXCEPTION 'Live Staff Auth session required' USING ERRCODE='42501';
  END IF;
  v_session:=v_session_text::uuid;
  IF NOT EXISTS (
    SELECT 1 FROM auth.sessions s
    JOIN auth.users u ON u.id=s.user_id
    WHERE s.id=v_session AND s.user_id=v_actor
      AND (s.not_after IS NULL OR s.not_after>clock_timestamp())
      AND u.email_confirmed_at IS NOT NULL
  ) THEN RAISE EXCEPTION 'Staff session expired or unverified'
      USING ERRCODE='42501';
  END IF;

  PERFORM pg_catalog.pg_advisory_xact_lock(
    pg_catalog.hashtextextended(p_workspace_id::text,0)
  );
  SELECT * INTO v_invite FROM marketpro_private.pending_team_enrollments
    WHERE workspace_id=p_workspace_id AND staff_user_id=v_actor
    FOR UPDATE;
  IF NOT FOUND OR v_invite.status<>'invited'
    OR v_invite.revoked_at IS NOT NULL
    OR v_invite.intended_device_id IS DISTINCT FROM p_staff_device_id
    OR v_invite.expires_at<=clock_timestamp()
    OR v_invite.token_sha256 IS DISTINCT FROM
       extensions.digest(pg_catalog.convert_to(p_code,'UTF8'),'sha256')
  THEN RAISE EXCEPTION 'Staff invitation invalid, expired, or consumed'
       USING ERRCODE='42501';
  END IF;

  UPDATE marketpro_private.pending_team_enrollments
    SET status='accepted', accepted_at=clock_timestamp()
    WHERE workspace_id=p_workspace_id AND staff_user_id=v_actor;
  RETURN pg_catalog.jsonb_build_object(
    'accepted',true,'awaiting_owner_device_and_public_key_approval',true,
    'staff_phone_active',false,'staff_can_read',false,'staff_can_write',false
  );
END;
$accept$;
REVOKE ALL ON FUNCTION
  public.marketpro_qa_staff_accept_invite(uuid,uuid,text)
  FROM PUBLIC,anon,authenticated;

-- Explicitly verify the backend remains non-callable and OFF.
DO $postcheck$
BEGIN
  IF (SELECT count(*) FROM marketpro_private.qa_invite_gate WHERE enabled) <> 0
     OR (SELECT count(*) FROM marketpro_private.pending_team_enrollments) <> 0
     OR has_function_privilege('authenticated',
        'public.marketpro_qa_owner_issue_invite(uuid,uuid,uuid,uuid,text)','EXECUTE')
     OR has_function_privilege('authenticated',
        'public.marketpro_qa_staff_accept_invite(uuid,uuid,text)','EXECUTE')
     OR has_function_privilege('anon',
        'public.marketpro_qa_owner_issue_invite(uuid,uuid,uuid,uuid,text)','EXECUTE')
     OR has_function_privilege('anon',
        'public.marketpro_qa_staff_accept_invite(uuid,uuid,text)','EXECUTE')
  THEN
    RAISE EXCEPTION 'QA rollout must remain disabled / RPCs private';
  END IF;
END;
$postcheck$;
COMMIT;
