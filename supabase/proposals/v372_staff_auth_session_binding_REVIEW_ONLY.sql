-- MARKET PRO V3.7.2 — STAFF AUTH SESSION + ONE-TIME PAIRING GUARD
-- REVIEW ONLY. NEVER EXECUTE ON LIVE SUPABASE.
-- Requires V358 pending invitation, V359 activated_at/RLS rollout,
-- V354 paged pull, V370 Staff leases, V371 Owner approval.
-- The existing Owner v1 writer, device index and dashboard are untouched.
BEGIN;

-- No device secret or Supabase refresh/access token is saved in this table.
-- Only a signed session_id from a successfully redeemed ONE-TIME pairing code.
CREATE TABLE IF NOT EXISTS marketpro_private.staff_session_bindings(
    workspace_id uuid PRIMARY KEY,
    staff_user_id uuid NOT NULL,
    device_id uuid NOT NULL,
    session_id uuid NOT NULL,
    bound_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    revoked_at timestamptz
);
REVOKE ALL ON marketpro_private.staff_session_bindings
  FROM PUBLIC,anon,authenticated;

-- Strict fail-closed session validation: JWT is already signed/validated
-- by Supabase/PostgREST, but a signed-out session's JWT may still be valid
-- until expiry. Rechecking auth.sessions is necessary for immediate deny.
-- This is NOT hardware attestation or full defense from stolen bearer tokens.
CREATE OR REPLACE FUNCTION marketpro_private.is_verified_staff_session(
 p_workspace_id uuid,p_device_id uuid
) RETURNS boolean
LANGUAGE plpgsql VOLATILE SECURITY DEFINER SET search_path=''
AS $verify_session$
DECLARE
 v_user uuid:=auth.uid();
 v_session_claim text:=auth.jwt()->>'session_id';
 v_session_id uuid;
BEGIN
 IF v_user IS NULL OR
    coalesce((auth.jwt()->>'is_anonymous')::boolean,false) OR
    p_workspace_id IS NULL OR p_device_id IS NULL OR
    v_session_claim IS NULL OR
    v_session_claim !~*
      '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'
 THEN RETURN false; END IF;
 v_session_id:=v_session_claim::uuid;

 RETURN EXISTS(
   SELECT 1 FROM marketpro_private.staff_session_bindings b
   JOIN auth.sessions s ON s.id=b.session_id AND s.user_id=b.staff_user_id
   JOIN marketpro_private.pending_team_enrollments i
     ON i.workspace_id=b.workspace_id AND i.staff_user_id=b.staff_user_id
   WHERE b.workspace_id=p_workspace_id AND b.staff_user_id=v_user
     AND b.device_id=p_device_id AND b.session_id=v_session_id
     AND b.revoked_at IS NULL
     AND (s.not_after IS NULL OR s.not_after>clock_timestamp())
     AND i.intended_device_id=b.device_id
     AND i.status='accepted' AND i.revoked_at IS NULL
     AND i.accepted_at IS NOT NULL AND i.activated_at IS NOT NULL
 );
END;
$verify_session$;
REVOKE ALL ON FUNCTION marketpro_private.is_verified_staff_session(uuid,uuid)
 FROM PUBLIC,anon,authenticated;

-- Invitation must be accepted from the signed in Staff Supabase session.
-- On replacement, the new one-time invitation replaces the prior binding.
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
    v_session_claim text;
    v_session_id uuid;
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
    -- Signed JWT session_id must still belong to an ACTIVE Auth session.
    v_session_claim := auth.jwt()->>'session_id';
    IF v_session_claim IS NULL OR
       v_session_claim !~* '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$' THEN
       RAISE EXCEPTION 'Verified active Supabase session required'
         USING ERRCODE='42501';
    END IF;
    v_session_id := v_session_claim::uuid;
    IF NOT EXISTS (
       SELECT 1 FROM auth.sessions s
       WHERE s.id=v_session_id AND s.user_id=v_user
         AND (s.not_after IS NULL OR s.not_after>clock_timestamp())
    ) THEN
       RAISE EXCEPTION 'Expired or revoked Auth session'
         USING ERRCODE='42501';
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
    INSERT INTO marketpro_private.staff_session_bindings(
      workspace_id,staff_user_id,device_id,session_id,bound_at,revoked_at)
    VALUES(p_workspace_id,v_user,p_staff_device_id,v_session_id,
           clock_timestamp(),NULL)
    ON CONFLICT(workspace_id) DO UPDATE SET
      staff_user_id=EXCLUDED.staff_user_id,
      device_id=EXCLUDED.device_id,
      session_id=EXCLUDED.session_id,
      bound_at=clock_timestamp(),revoked_at=NULL;
    -- CRITICAL: no real membership or registered writer is created here.
    RETURN pg_catalog.jsonb_build_object(
       'invitation_accepted',true,
       'status','awaiting_owner_activation_and_server_cutover',
       'workspace_id',p_workspace_id
    );
END;
$f$;

REVOKE ALL ON FUNCTION public.marketpro_staff_accept_invitation(
 uuid,uuid,text) FROM PUBLIC,anon;
GRANT EXECUTE ON FUNCTION public.marketpro_staff_accept_invitation(
 uuid,uuid,text) TO authenticated;

-- Every Staff financial write still requires private lease AND live
-- Auth session + matching one-time pairing, plus the V371 rollout gate.
CREATE OR REPLACE FUNCTION marketpro_private.require_dual_channel_writer(
    p_workspace_id uuid,p_device_id uuid
) RETURNS text
LANGUAGE plpgsql SECURITY DEFINER SET search_path=''
AS $guard$
DECLARE
    v_user uuid := auth.uid();
    v_role text;
BEGIN
    IF v_user IS NULL OR coalesce((auth.jwt()->>'is_anonymous')::boolean,false)
       OR p_workspace_id IS NULL OR p_device_id IS NULL THEN
        RAISE EXCEPTION 'Verified User, Workspace and Device required'
          USING ERRCODE='42501';
    END IF;
    SELECT m.role INTO v_role
    FROM public.workspace_members m
    JOIN public.registered_devices d
      ON d.workspace_id=m.workspace_id AND d.user_id=m.user_id
    WHERE m.workspace_id=p_workspace_id AND m.user_id=v_user
      AND d.id=p_device_id AND d.enabled
      AND (
        (m.role='owner' AND d.can_write)
        OR
        (m.role='purchasing_staff' AND NOT d.can_write
          AND EXISTS (
            SELECT 1 FROM marketpro_private.staff_write_leases l
            JOIN marketpro_private.team_rollout_gate g
              ON g.workspace_id=l.workspace_id
            WHERE l.workspace_id=m.workspace_id
              AND l.staff_user_id=m.user_id AND l.device_id=d.id
              AND l.enabled
              AND marketpro_private.is_verified_staff_session(
                   p_workspace_id,p_device_id
              )
              AND g.staff_direct_rest_restricted
              AND g.team_pull_rpc_reviewed AND g.team_writes_reviewed
          ))
      )
    LIMIT 1;
    IF v_role IS NULL THEN
      RAISE EXCEPTION 'Workspace writer not authorized for this device'
        USING ERRCODE='42501';
    END IF;
    RETURN v_role;
END;
$guard$;
REVOKE ALL ON FUNCTION marketpro_private.require_dual_channel_writer(uuid,uuid)
 FROM PUBLIC,anon,authenticated;

-- Owner can only approve a Staff writer after verified session pairing.
CREATE OR REPLACE FUNCTION public.marketpro_owner_enable_staff_writer(
    p_workspace_id uuid,p_owner_device_id uuid,
    p_staff_user_id uuid,p_staff_device_id uuid
) RETURNS jsonb
LANGUAGE plpgsql SECURITY DEFINER SET search_path=''
AS $enable$
DECLARE
    v_owner uuid;
    v_old marketpro_private.staff_write_leases%ROWTYPE;
    v_has_previous boolean;
BEGIN
    v_owner:=marketpro_private.require_enrolled_owner(
       p_workspace_id,p_owner_device_id);
    IF p_staff_user_id IS NULL OR p_staff_device_id IS NULL
       OR p_staff_user_id=v_owner OR p_staff_device_id=p_owner_device_id THEN
        RAISE EXCEPTION 'Invalid Staff account or phone'
          USING ERRCODE='42501';
    END IF;
    PERFORM pg_catalog.pg_advisory_xact_lock(
      pg_catalog.hashtextextended(p_workspace_id::text,0));

    IF NOT EXISTS (
      SELECT 1 FROM marketpro_private.team_rollout_gate g
      WHERE g.workspace_id=p_workspace_id
        AND g.staff_direct_rest_restricted
        AND g.team_pull_rpc_reviewed AND g.team_writes_reviewed
    ) THEN
        RAISE EXCEPTION 'Staff write rollout not independently audited'
          USING ERRCODE='42501';
    END IF;
    IF NOT EXISTS (
      SELECT 1 FROM auth.users u
      JOIN public.workspace_members m
        ON m.user_id=u.id AND m.workspace_id=p_workspace_id
      JOIN public.registered_devices d
        ON d.user_id=m.user_id AND d.workspace_id=m.workspace_id
      JOIN marketpro_private.pending_team_enrollments i
        ON i.workspace_id=m.workspace_id AND i.staff_user_id=m.user_id
      WHERE u.id=p_staff_user_id AND u.email IS NOT NULL
        AND u.email_confirmed_at IS NOT NULL
        AND m.role='purchasing_staff'
        AND d.id=p_staff_device_id AND d.enabled AND NOT d.can_write
        AND i.intended_device_id=d.id
        AND i.status='accepted' AND i.revoked_at IS NULL
        AND i.accepted_at IS NOT NULL AND i.activated_at IS NOT NULL
        AND i.expires_at>i.accepted_at
    ) THEN
        RAISE EXCEPTION 'Verified accepted and activated Staff phone required'
          USING ERRCODE='42501';
    END IF;
    -- A copied device ID is not enough. The Staff invitation must have
    -- been accepted by a real current Supabase Auth session for this device.
    IF NOT EXISTS (
      SELECT 1 FROM marketpro_private.staff_session_bindings b
      JOIN auth.sessions s ON s.id=b.session_id AND s.user_id=b.staff_user_id
      WHERE b.workspace_id=p_workspace_id
        AND b.staff_user_id=p_staff_user_id AND b.device_id=p_staff_device_id
        AND b.revoked_at IS NULL
        AND (s.not_after IS NULL OR s.not_after>clock_timestamp())
    ) THEN
      RAISE EXCEPTION 'Staff phone has no active paired Auth session'
        USING ERRCODE='42501';
    END IF;
    -- Exactly one Staff phone per workspace, even across different accounts.
    IF EXISTS (
      SELECT 1 FROM public.registered_devices d
      JOIN public.workspace_members m
        ON m.workspace_id=d.workspace_id AND m.user_id=d.user_id
      WHERE m.workspace_id=p_workspace_id AND m.role='purchasing_staff'
        AND d.enabled AND d.id<>p_staff_device_id
    ) THEN
        RAISE EXCEPTION 'Another active Staff phone must be revoked first'
          USING ERRCODE='42501';
    END IF;
    SELECT * INTO v_old FROM marketpro_private.staff_write_leases
    WHERE workspace_id=p_workspace_id FOR UPDATE;
    v_has_previous:=FOUND;

    IF v_has_previous AND v_old.enabled
       AND v_old.staff_user_id=p_staff_user_id
       AND v_old.device_id=p_staff_device_id THEN
       RETURN pg_catalog.jsonb_build_object(
         'writer_enabled',true,'already_enabled',true,
         'staff_device_id',p_staff_device_id,
         'legacy_owner_writer_unchanged',true);
    END IF;
    IF v_has_previous AND v_old.enabled
       AND v_old.device_id<>p_staff_device_id
       AND EXISTS (
         SELECT 1 FROM public.registered_devices d
         WHERE d.id=v_old.device_id AND d.workspace_id=p_workspace_id
           AND d.enabled
       ) THEN
       RAISE EXCEPTION 'Existing active Staff writer must be disabled'
          USING ERRCODE='42501';
    END IF;

    INSERT INTO marketpro_private.staff_write_leases (
      workspace_id,staff_user_id,device_id,enabled,granted_at,revoked_at
    ) VALUES (
      p_workspace_id,p_staff_user_id,p_staff_device_id,true,
      clock_timestamp(),NULL
    ) ON CONFLICT (workspace_id)
    DO UPDATE SET staff_user_id=EXCLUDED.staff_user_id,
                  device_id=EXCLUDED.device_id,
                  enabled=true,granted_at=clock_timestamp(),revoked_at=NULL;

    INSERT INTO marketpro_private.staff_writer_activation_audit(
      workspace_id,staff_user_id,old_device_id,new_device_id,
      owner_user_id,action
    ) VALUES (
      p_workspace_id,p_staff_user_id,
      CASE WHEN v_has_previous THEN v_old.device_id ELSE NULL END,
      p_staff_device_id,v_owner,'grant'
    );
    RETURN pg_catalog.jsonb_build_object(
      'writer_enabled',true,'already_enabled',false,
      'staff_device_id',p_staff_device_id,
      'legacy_owner_writer_unchanged',true);
END;
$enable$;
REVOKE ALL ON FUNCTION public.marketpro_owner_enable_staff_writer(
 uuid,uuid,uuid,uuid) FROM PUBLIC,anon;
GRANT EXECUTE ON FUNCTION public.marketpro_owner_enable_staff_writer(
 uuid,uuid,uuid,uuid) TO authenticated;

-- Secure even READ-ONLY Staff pulls: otherwise a second login plus copied
-- device UUID could bypass the new writer-only session guard.
CREATE OR REPLACE FUNCTION public.marketpro_team_pull_page(
 p_workspace_id uuid,
 p_device_id uuid,
 p_entity_type text,
 p_after_updated_at timestamptz DEFAULT NULL,
 p_after_id uuid DEFAULT NULL,
 p_page_size integer DEFAULT 50
) RETURNS jsonb
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = ''
AS $pull$
DECLARE
 v_user uuid := auth.uid();
 v_table text;
 v_rows jsonb;
 v_count integer;
 v_more boolean;
 v_next_updated timestamptz;
 v_next_id uuid;
 v_today date := (clock_timestamp() AT TIME ZONE 'Asia/Colombo')::date;
BEGIN
 IF v_user IS NULL OR coalesce((auth.jwt()->>'is_anonymous')::boolean,false) THEN
  RAISE EXCEPTION 'Authenticated user required for download' USING ERRCODE='42501';
 END IF;
 IF p_workspace_id IS NULL OR p_device_id IS NULL OR
    p_entity_type NOT IN ('category','item','requisition','purchase',
                          'purchase_addition','daily_cash_received') OR
    p_entity_type IS NULL OR
    p_page_size IS NULL OR p_page_size NOT BETWEEN 1 AND 100 OR
    ((p_after_updated_at IS NULL) <> (p_after_id IS NULL)) THEN
  RAISE EXCEPTION 'Invalid pull request' USING ERRCODE='22023';
 END IF;
 -- Serialize with Owner replacement/revoke actions before reading rows.
 PERFORM pg_catalog.pg_advisory_xact_lock(
   pg_catalog.hashtextextended(p_workspace_id::text,0)
 );
 -- Readers must be known users and enrolled devices, not arbitrary members.
 -- can_write is deliberately NOT required for a read-only phone.
 IF NOT EXISTS (
  SELECT 1 FROM public.workspace_members m
  JOIN public.registered_devices d
    ON d.workspace_id=m.workspace_id AND d.user_id=m.user_id
  WHERE m.workspace_id=p_workspace_id AND m.user_id=v_user
    AND m.role IN ('owner','purchasing_staff','viewer')
    AND d.id=p_device_id AND d.enabled=true
    AND (m.role<>'purchasing_staff' OR
      marketpro_private.is_verified_staff_session(p_workspace_id,p_device_id))
 ) THEN
  RAISE EXCEPTION 'Unenrolled or revoked reader' USING ERRCODE='42501';
 END IF;

 -- Closed allowlist: NO invoice photo bytes, no raw server Auth information.
 v_table := CASE p_entity_type
  WHEN 'category' THEN 'categories'
  WHEN 'item' THEN 'items'
  WHEN 'requisition' THEN 'requisitions'
  WHEN 'purchase' THEN 'purchases'
  WHEN 'purchase_addition' THEN 'purchase_additions'
  WHEN 'daily_cash_received' THEN 'daily_cash_received'
 END;
 EXECUTE pg_catalog.format(
   'SELECT coalesce(pg_catalog.jsonb_agg(to_jsonb(r) ORDER BY r.updated_at,r.id),
                     ''[]''::jsonb)
    FROM (
      SELECT * FROM public.%I
      WHERE workspace_id=$1 AND
            ($2::timestamptz IS NULL OR
             (updated_at,id) > ($2::timestamptz,$3::uuid))
      ORDER BY updated_at ASC,id ASC
      LIMIT $4
    ) AS r',v_table
 ) INTO v_rows USING p_workspace_id,p_after_updated_at,p_after_id,p_page_size+1;
 v_count := pg_catalog.jsonb_array_length(v_rows);
 v_more := v_count > p_page_size;
 IF v_more THEN
  v_rows := v_rows - (v_count - 1);
 END IF;
 v_count := pg_catalog.jsonb_array_length(v_rows);
 IF v_count>0 THEN
  v_next_updated := (v_rows->(v_count-1)->>'updated_at')::timestamptz;
  v_next_id := (v_rows->(v_count-1)->>'id')::uuid;
 ELSE
  v_next_updated := p_after_updated_at;
  v_next_id := p_after_id;
 END IF;
 RETURN pg_catalog.jsonb_build_object(
  'entity_type',p_entity_type,'rows',v_rows,'has_more',v_more,
  'next_updated_at',v_next_updated,'next_id',v_next_id,
  'server_today_colombo',v_today,
  'server_time_utc',clock_timestamp()
 );
END;
$pull$;
REVOKE ALL ON FUNCTION public.marketpro_team_pull_page(
 uuid,uuid,text,timestamptz,uuid,integer) FROM PUBLIC,anon;
GRANT EXECUTE ON FUNCTION public.marketpro_team_pull_page(
 uuid,uuid,text,timestamptz,uuid,integer) TO authenticated;
COMMIT;
