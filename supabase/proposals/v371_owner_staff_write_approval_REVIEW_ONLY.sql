-- MARKET PRO V3.7.1: OWNER-APPROVED STAFF WRITE LEASES — REVIEW ONLY
-- DO NOT APPLY TO LIVE SUPABASE. Full RLS/auth/session, Android, and
-- two-physical-phone cutover remain NOT deployed.
-- Requires reviewed v358 pending_team_enrollments, v359 rollout gates and
-- read-only activation, v368 replacement, v370 private staff_write_leases.
-- Owner legacy writer and registered_devices_one_enabled_writer_idx unchanged.
BEGIN;
CREATE TABLE IF NOT EXISTS marketpro_private.staff_writer_activation_audit (
    id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    workspace_id uuid NOT NULL,
    staff_user_id uuid NOT NULL,
    old_device_id uuid,
    new_device_id uuid,
    owner_user_id uuid NOT NULL,
    action text NOT NULL CHECK (action IN ('grant','revoke')),
    changed_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
REVOKE ALL ON marketpro_private.staff_writer_activation_audit
  FROM PUBLIC,anon,authenticated;

-- Enforce the private ADMIN-controlled gate ON EVERY financial write, not
-- only when a grant is issued. Turning the gate off invalidates active
-- Staff leases immediately without disabling the legacy Owner phone.
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
              AND l.enabled AND g.staff_direct_rest_restricted
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

-- Only the verified, enrolled Owner on the actual Owner device may grant
-- one already ACTIVATED, accepted, verified Staff phone its v2 write lease.
-- Staff phones remain can_write=false, preserving the original v1 restriction.
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
 uuid,uuid,uuid,uuid) FROM PUBLIC,anon,authenticated;
GRANT EXECUTE ON FUNCTION public.marketpro_owner_enable_staff_writer(
 uuid,uuid,uuid,uuid) TO authenticated;

-- Owner may revoke Staff WRITING without revoking read-only workspace access.
-- For LOST/STOLEN phones also call V368 disable_team_phone: it disables all
-- device-bound RPCs. This routine never changes the legacy Owner's device.
CREATE OR REPLACE FUNCTION public.marketpro_owner_disable_staff_writer(
    p_workspace_id uuid,p_owner_device_id uuid,
    p_staff_user_id uuid,p_staff_device_id uuid
) RETURNS jsonb
LANGUAGE plpgsql SECURITY DEFINER SET search_path=''
AS $revoke$
DECLARE
    v_owner uuid;
    v_lease marketpro_private.staff_write_leases%ROWTYPE;
    v_changed boolean:=false;
BEGIN
    v_owner:=marketpro_private.require_enrolled_owner(
      p_workspace_id,p_owner_device_id);
    IF p_staff_user_id IS NULL OR p_staff_device_id IS NULL
       OR p_staff_user_id=v_owner OR p_staff_device_id=p_owner_device_id THEN
      RAISE EXCEPTION 'Cannot disable Owner or unspecified phone'
        USING ERRCODE='42501';
    END IF;
    PERFORM pg_catalog.pg_advisory_xact_lock(
      pg_catalog.hashtextextended(p_workspace_id::text,0));

    SELECT * INTO v_lease FROM marketpro_private.staff_write_leases
      WHERE workspace_id=p_workspace_id FOR UPDATE;
    IF NOT FOUND OR v_lease.staff_user_id<>p_staff_user_id
       OR v_lease.device_id<>p_staff_device_id THEN
      RAISE EXCEPTION 'No matching Staff write authorization found'
        USING ERRCODE='42501';
    END IF;
    IF v_lease.enabled THEN
      UPDATE marketpro_private.staff_write_leases
        SET enabled=false,revoked_at=clock_timestamp()
      WHERE workspace_id=p_workspace_id;
      v_changed:=true;
      INSERT INTO marketpro_private.staff_writer_activation_audit(
        workspace_id,staff_user_id,old_device_id,new_device_id,
        owner_user_id,action
      ) VALUES (
        p_workspace_id,p_staff_user_id,p_staff_device_id,
        NULL,v_owner,'revoke'
      );
    END IF;
    RETURN pg_catalog.jsonb_build_object(
      'staff_write_disabled',true,'changed',v_changed,
      'staff_read_only_device_still_enabled',true,
      'disable_device_separately_if_lost',true,
      'legacy_owner_writer_unchanged',true
    );
END;
$revoke$;
REVOKE ALL ON FUNCTION public.marketpro_owner_disable_staff_writer(
 uuid,uuid,uuid,uuid) FROM PUBLIC,anon,authenticated;
GRANT EXECUTE ON FUNCTION public.marketpro_owner_disable_staff_writer(
 uuid,uuid,uuid,uuid) TO authenticated;
COMMIT;
