-- Market Pro V3.5.8 — STAFF ENROLLMENT INVITATION (REVIEW ONLY).
-- NEVER EXECUTE ON THE LIVE SUPABASE PROJECT BEFORE APP/RLS CUTOVER.
-- Preserves original v3.3.7 owner writer, original RLS, all legacy data and dashboard.
--
-- Deliberate two-phase safety: preparing/claiming invitation does NOT create
-- public.workspace_members or public.registered_devices. The pre-existing
-- workspace SELECT policy allows *any* member to read, so prematurely creating
-- a staff membership would already expose data even with can_write=false.
BEGIN;
CREATE SCHEMA IF NOT EXISTS marketpro_private;

CREATE TABLE marketpro_private.pending_team_enrollments (
    workspace_id uuid NOT NULL,
    staff_user_id uuid NOT NULL,
    inviter_user_id uuid NOT NULL,
    intended_device_id uuid NOT NULL,
    device_label text NOT NULL CHECK (length(btrim(device_label)) BETWEEN 1 AND 100),
    token_sha256 bytea NOT NULL,
    expires_at timestamptz NOT NULL,
    status text NOT NULL DEFAULT 'invited'
      CHECK (status IN ('invited','accepted','revoked')),
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    accepted_at timestamptz,
    revoked_at timestamptz,
    PRIMARY KEY (workspace_id, staff_user_id),
    UNIQUE (intended_device_id)
);
REVOKE ALL ON marketpro_private.pending_team_enrollments
    FROM PUBLIC, anon, authenticated;

CREATE OR REPLACE FUNCTION marketpro_private.require_enrolled_owner(
    p_workspace_id uuid, p_owner_device_id uuid
) RETURNS uuid
LANGUAGE plpgsql SECURITY DEFINER SET search_path=''
AS $f$
DECLARE
    v_actor uuid := auth.uid();
BEGIN
    IF v_actor IS NULL OR
       coalesce((auth.jwt()->>'is_anonymous')::boolean,false) OR
       p_workspace_id IS NULL OR p_owner_device_id IS NULL THEN
       RAISE EXCEPTION 'Verified owner login and device required' USING ERRCODE='42501';
    END IF;
    IF NOT EXISTS (
        SELECT 1 FROM public.workspace_members AS m
        JOIN public.registered_devices AS d
          ON d.workspace_id=m.workspace_id AND d.user_id=m.user_id
        WHERE m.workspace_id=p_workspace_id AND m.user_id=v_actor
          AND m.role='owner' AND d.id=p_owner_device_id
          AND d.enabled AND d.can_write
    ) THEN
       RAISE EXCEPTION 'Only registered workspace owner may invite or revoke'
           USING ERRCODE='42501';
    END IF;
    RETURN v_actor;
END;
$f$;

REVOKE ALL ON FUNCTION marketpro_private.require_enrolled_owner(uuid,uuid)
  FROM PUBLIC, anon, authenticated;

CREATE OR REPLACE FUNCTION public.marketpro_owner_invite_staff(
    p_workspace_id uuid,
    p_owner_device_id uuid,
    p_staff_user_id uuid,
    p_staff_device_id uuid,
    p_staff_device_label text
) RETURNS jsonb
LANGUAGE plpgsql SECURITY DEFINER SET search_path=''
AS $f$
DECLARE
    v_owner uuid;
    v_verified boolean;
    v_token text;
    v_expiry timestamptz := clock_timestamp() + interval '30 minutes';
BEGIN
    v_owner := marketpro_private.require_enrolled_owner(
        p_workspace_id,p_owner_device_id
    );
    IF p_staff_user_id IS NULL OR p_staff_device_id IS NULL OR
       p_staff_user_id=v_owner OR
       p_staff_device_label IS NULL OR
       length(btrim(p_staff_device_label)) NOT BETWEEN 1 AND 100 THEN
       RAISE EXCEPTION 'Invalid staff identity/device/label' USING ERRCODE='22023';
    END IF;
    -- Only a verified existing Supabase Auth user may receive an invitation.
    SELECT (email IS NOT NULL AND email_confirmed_at IS NOT NULL)
       INTO v_verified FROM auth.users WHERE id=p_staff_user_id;
    IF v_verified IS DISTINCT FROM true THEN
       RAISE EXCEPTION 'Staff Auth email not confirmed' USING ERRCODE='42501';
    END IF;
    -- No self-escalation and no reassignment of existing workspace roles.
    IF EXISTS (
       SELECT 1 FROM public.workspace_members
       WHERE workspace_id=p_workspace_id AND user_id=p_staff_user_id
    ) OR EXISTS (
       SELECT 1 FROM public.registered_devices
       WHERE id=p_staff_device_id
    ) THEN
       RAISE EXCEPTION 'Staff user/device already enrolled; separate transfer required'
           USING ERRCODE='23505';
    END IF;
    PERFORM pg_catalog.pg_advisory_xact_lock(
       pg_catalog.hashtextextended(p_workspace_id::text,0)
    );
    v_token := pg_catalog.encode(extensions.gen_random_bytes(32),'hex');
    INSERT INTO marketpro_private.pending_team_enrollments(
       workspace_id,staff_user_id,inviter_user_id,intended_device_id,
       device_label,token_sha256,expires_at,status,created_at,accepted_at,revoked_at
    ) VALUES (
       p_workspace_id,p_staff_user_id,v_owner,p_staff_device_id,
       btrim(p_staff_device_label),
       extensions.digest(pg_catalog.convert_to(v_token,'UTF8'),'sha256'),
       v_expiry,'invited',clock_timestamp(),NULL,NULL
    )
    ON CONFLICT (workspace_id,staff_user_id)
    DO UPDATE SET
       inviter_user_id=EXCLUDED.inviter_user_id,
       intended_device_id=EXCLUDED.intended_device_id,
       device_label=EXCLUDED.device_label,
       token_sha256=EXCLUDED.token_sha256,
       expires_at=EXCLUDED.expires_at,
       status='invited',
       created_at=clock_timestamp(),
       accepted_at=NULL,revoked_at=NULL;
    -- Token returned ONCE to owner only; never stored plaintext in DB/logs.
    RETURN pg_catalog.jsonb_build_object(
       'invitation_created',true,
       'staff_user_id',p_staff_user_id,
       'intended_device_id',p_staff_device_id,
       'expires_at',v_expiry,
       'one_time_code',v_token
    );
END;
$f$;

REVOKE ALL ON FUNCTION public.marketpro_owner_invite_staff(
  uuid,uuid,uuid,uuid,text
) FROM PUBLIC,anon;
GRANT EXECUTE ON FUNCTION public.marketpro_owner_invite_staff(
  uuid,uuid,uuid,uuid,text
) TO authenticated;

CREATE OR REPLACE FUNCTION public.marketpro_staff_accept_invitation(
    p_workspace_id uuid,
    p_staff_device_id uuid,
    p_one_time_code text
) RETURNS jsonb
LANGUAGE plpgsql SECURITY DEFINER SET search_path=''
AS $f$
DECLARE
    v_user uuid := auth.uid();
    v_row marketpro_private.pending_team_enrollments%ROWTYPE;
    v_verified boolean;
BEGIN
    IF v_user IS NULL OR
       coalesce((auth.jwt()->>'is_anonymous')::boolean,false) THEN
       RAISE EXCEPTION 'Verified staff login required' USING ERRCODE='42501';
    END IF;
    IF p_workspace_id IS NULL OR p_staff_device_id IS NULL OR
       p_one_time_code IS NULL OR
       length(p_one_time_code) <> 64 OR
       p_one_time_code !~ '^[0-9a-f]{64}$' THEN
       RAISE EXCEPTION 'Invalid enrollment request' USING ERRCODE='22023';
    END IF;
    SELECT email_confirmed_at IS NOT NULL INTO v_verified
    FROM auth.users WHERE id=v_user;
    IF v_verified IS DISTINCT FROM true THEN
        RAISE EXCEPTION 'Verified staff email required' USING ERRCODE='42501';
    END IF;
    -- The staff account alone cannot select an invitation or change its role.
    SELECT * INTO v_row
    FROM marketpro_private.pending_team_enrollments
    WHERE workspace_id=p_workspace_id AND staff_user_id=v_user
    FOR UPDATE;
    IF NOT FOUND OR
       v_row.intended_device_id IS DISTINCT FROM p_staff_device_id OR
       v_row.status <> 'invited' OR v_row.expires_at <= clock_timestamp() OR
       v_row.token_sha256 IS DISTINCT FROM
         extensions.digest(pg_catalog.convert_to(p_one_time_code,'UTF8'),'sha256') THEN
       RAISE EXCEPTION 'Enrollment invitation invalid, expired or consumed'
           USING ERRCODE='42501';
    END IF;
    UPDATE marketpro_private.pending_team_enrollments
    SET status='accepted',accepted_at=clock_timestamp()
    WHERE workspace_id=p_workspace_id AND staff_user_id=v_user;
    -- CRITICAL: no real membership or registered writer is created here.
    RETURN pg_catalog.jsonb_build_object(
       'invitation_accepted',true,
       'status','awaiting_owner_activation_and_server_cutover',
       'workspace_id',p_workspace_id
    );
END;
$f$;

REVOKE ALL ON FUNCTION public.marketpro_staff_accept_invitation(
  uuid,uuid,text
) FROM PUBLIC,anon;
GRANT EXECUTE ON FUNCTION public.marketpro_staff_accept_invitation(
  uuid,uuid,text
) TO authenticated;

CREATE OR REPLACE FUNCTION public.marketpro_owner_revoke_staff_invitation(
    p_workspace_id uuid,p_owner_device_id uuid,p_staff_user_id uuid
) RETURNS jsonb
LANGUAGE plpgsql SECURITY DEFINER SET search_path=''
AS $f$
DECLARE
    v_rows integer;
BEGIN
    PERFORM marketpro_private.require_enrolled_owner(
       p_workspace_id,p_owner_device_id
    );
    IF p_staff_user_id IS NULL THEN
       RAISE EXCEPTION 'Missing staff ID' USING ERRCODE='22023';
    END IF;
    UPDATE marketpro_private.pending_team_enrollments
    SET status='revoked',revoked_at=clock_timestamp()
    WHERE workspace_id=p_workspace_id AND staff_user_id=p_staff_user_id
      AND status IN ('invited','accepted');
    GET DIAGNOSTICS v_rows=ROW_COUNT;
    RETURN pg_catalog.jsonb_build_object('revoked',v_rows=1);
END;
$f$;

REVOKE ALL ON FUNCTION public.marketpro_owner_revoke_staff_invitation(
  uuid,uuid,uuid
) FROM PUBLIC,anon;
GRANT EXECUTE ON FUNCTION public.marketpro_owner_revoke_staff_invitation(
  uuid,uuid,uuid
) TO authenticated;
COMMIT;
