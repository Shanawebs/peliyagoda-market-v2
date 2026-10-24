-- MARKET PRO V3.6.8 -- OWNER REVOKE / REPLACE STAFF DEVICE
-- REVIEW ONLY. DO NOT DEPLOY TO PRODUCTION. Requires v358 + v359 secure RLS,
-- Auth, enrollment and device-bound pull/write RPC review.
-- Old device denied immediately at DB transaction commit. Unsynced offline
-- entries on old handset CANNOT be recovered automatically.
BEGIN;

CREATE TABLE IF NOT EXISTS marketpro_private.staff_device_transfer_audit (
    transfer_id uuid PRIMARY KEY DEFAULT extensions.gen_random_uuid(),
    workspace_id uuid NOT NULL,
    staff_user_id uuid NOT NULL,
    old_device_id uuid NOT NULL,
    new_device_id uuid,
    changed_by uuid NOT NULL,
    action text NOT NULL CHECK(action IN ('revoke','replace','activate')),
    changed_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    reason text NOT NULL CHECK(length(reason) BETWEEN 1 AND 120)
);
REVOKE ALL ON marketpro_private.staff_device_transfer_audit
    FROM PUBLIC,anon,authenticated;

-- Can be called immediately if the old phone is lost, stolen or being retired
-- and the replacement phone has not been purchased yet.
CREATE OR REPLACE FUNCTION public.marketpro_owner_disable_team_phone(
 p_workspace_id uuid,p_owner_device_id uuid,p_staff_user_id uuid,
 p_old_device_id uuid
) RETURNS jsonb
LANGUAGE plpgsql SECURITY DEFINER SET search_path=''
AS $fn$
DECLARE
 v_owner uuid;
 v_changed integer;
BEGIN
 v_owner:=marketpro_private.require_enrolled_owner(p_workspace_id,p_owner_device_id);
 IF p_staff_user_id IS NULL OR p_old_device_id IS NULL OR
    p_staff_user_id=v_owner OR p_old_device_id=p_owner_device_id THEN
   RAISE EXCEPTION 'Refusing to disable the Owner or invalid staff phone'
     USING ERRCODE='42501';
 END IF;
 PERFORM pg_catalog.pg_advisory_xact_lock(
    pg_catalog.hashtextextended(p_workspace_id::text,0)
 );
 IF NOT EXISTS(
   SELECT 1 FROM public.workspace_members m
   JOIN public.registered_devices d ON d.workspace_id=m.workspace_id
      AND d.user_id=m.user_id
   WHERE m.workspace_id=p_workspace_id AND m.user_id=p_staff_user_id
     AND m.role='purchasing_staff' AND d.id=p_old_device_id
 ) THEN
    RAISE EXCEPTION 'Phone is not a member of this Staff account'
      USING ERRCODE='42501';
 END IF;

 UPDATE public.registered_devices
 SET enabled=false,can_write=false
 WHERE workspace_id=p_workspace_id AND user_id=p_staff_user_id
   AND id=p_old_device_id AND (enabled OR can_write);
 GET DIAGNOSTICS v_changed=ROW_COUNT;
 -- The current device may have an outstanding, accepted or invited token
 -- tied to a previously authorized phone. Invalidate it without deleting data.
 UPDATE marketpro_private.pending_team_enrollments
 SET status='revoked',revoked_at=clock_timestamp()
 WHERE workspace_id=p_workspace_id AND staff_user_id=p_staff_user_id
   AND intended_device_id=p_old_device_id AND status IN ('invited','accepted');
 IF v_changed=1 THEN
   INSERT INTO marketpro_private.staff_device_transfer_audit(
      workspace_id,staff_user_id,old_device_id,new_device_id,changed_by,
      action,reason
   ) VALUES (
      p_workspace_id,p_staff_user_id,p_old_device_id,NULL,v_owner,
      'revoke','Owner disabled a team device'
   );
 END IF;
 RETURN pg_catalog.jsonb_build_object(
    'old_device_disabled',true,
    'changed',v_changed=1,
    'old_device_id',p_old_device_id,
    'cloud_history_retained',true,
    'unsynced_old_phone_data_must_be_backed_up_before_retirement',true
 );
END;
$fn$;
REVOKE ALL ON FUNCTION public.marketpro_owner_disable_team_phone(
  uuid,uuid,uuid,uuid
) FROM PUBLIC,anon;
GRANT EXECUTE ON FUNCTION public.marketpro_owner_disable_team_phone(
  uuid,uuid,uuid,uuid
) TO authenticated;

-- One atomic owner action: disable old phone FIRST, issue a fresh short-lived
-- one-time invitation to one new device ID, keep same verified Staff user.
CREATE OR REPLACE FUNCTION public.marketpro_owner_replace_team_phone(
 p_workspace_id uuid,p_owner_device_id uuid,p_staff_user_id uuid,
 p_old_device_id uuid,p_new_device_id uuid,p_new_device_label text
) RETURNS jsonb
LANGUAGE plpgsql SECURITY DEFINER SET search_path=''
AS $fn$
DECLARE
 v_owner uuid;
 v_token text;
 v_expires timestamptz:=clock_timestamp()+interval '30 minutes';
 v_verified boolean;
 v_last_old uuid;
 v_last_new uuid;
BEGIN
 v_owner:=marketpro_private.require_enrolled_owner(
   p_workspace_id,p_owner_device_id
 );
 IF p_staff_user_id IS NULL OR p_old_device_id IS NULL OR
    p_new_device_id IS NULL OR p_staff_user_id=v_owner OR
    p_old_device_id=p_owner_device_id OR p_new_device_id=p_owner_device_id OR
    p_new_device_id=p_old_device_id OR
    p_new_device_label IS NULL OR
    length(btrim(p_new_device_label)) NOT BETWEEN 1 AND 100 THEN
   RAISE EXCEPTION 'Invalid replacement target or device label'
     USING ERRCODE='22023';
 END IF;
 PERFORM pg_catalog.pg_advisory_xact_lock(
    pg_catalog.hashtextextended(p_workspace_id::text,0)
 );
 SELECT email_confirmed_at IS NOT NULL INTO v_verified
 FROM auth.users WHERE id=p_staff_user_id;
 IF v_verified IS DISTINCT FROM true THEN
   RAISE EXCEPTION 'Staff account must have verified email'
     USING ERRCODE='42501';
 END IF;
 IF NOT EXISTS(
    SELECT 1 FROM public.workspace_members m
    JOIN public.registered_devices d
      ON d.workspace_id=m.workspace_id AND d.user_id=m.user_id
    WHERE m.workspace_id=p_workspace_id AND m.user_id=p_staff_user_id
      AND m.role='purchasing_staff' AND d.id=p_old_device_id
 ) OR EXISTS (
    SELECT 1 FROM public.registered_devices
    WHERE id=p_new_device_id
 ) THEN
   RAISE EXCEPTION 'Staff/old phone mismatch or new device identity already used'
     USING ERRCODE='42501';
 END IF;
 -- Only the most recently replaced pair is eligible for reissue.
 -- Without this guard a twice-retired UUID could be passed when no staff
 -- device is currently enabled, bypassing the replacement chain.
 SELECT a.old_device_id,a.new_device_id INTO v_last_old,v_last_new
 FROM marketpro_private.staff_device_transfer_audit a
 WHERE a.workspace_id=p_workspace_id AND a.staff_user_id=p_staff_user_id
   AND a.action='replace'
 ORDER BY a.changed_at DESC,a.transfer_id DESC LIMIT 1;
 IF v_last_old IS NOT NULL AND p_old_device_id NOT IN (v_last_old,v_last_new) THEN
   RAISE EXCEPTION 'Stale retired Staff device cannot start a new replacement'
     USING ERRCODE='42501';
 END IF;
 -- Cannot replace an unrelated active staff device using a stale old ID;
 -- if the old phone was disabled earlier, no new active device may exist.
 IF EXISTS (
    SELECT 1 FROM public.registered_devices
    WHERE workspace_id=p_workspace_id AND user_id=p_staff_user_id
      AND id<>p_old_device_id AND enabled
 ) THEN
   RAISE EXCEPTION 'Different current Staff phone must be disabled first'
     USING ERRCODE='42501';
 END IF;

 UPDATE public.registered_devices
 SET enabled=false,can_write=false
 WHERE workspace_id=p_workspace_id AND user_id=p_staff_user_id
   AND id=p_old_device_id;
 -- Purge previous pending token hash by replacing it, never return
 -- plaintext of an earlier invitation. Existing membership is NOT removed.
 v_token:=pg_catalog.encode(extensions.gen_random_bytes(32),'hex');
 INSERT INTO marketpro_private.pending_team_enrollments(
    workspace_id,staff_user_id,inviter_user_id,intended_device_id,
    device_label,token_sha256,expires_at,status,created_at,
    accepted_at,revoked_at,activated_at
 ) VALUES (
    p_workspace_id,p_staff_user_id,v_owner,p_new_device_id,
    btrim(p_new_device_label),
    extensions.digest(pg_catalog.convert_to(v_token,'UTF8'),'sha256'),
    v_expires,'invited',clock_timestamp(),NULL,NULL,NULL
 )
 ON CONFLICT (workspace_id,staff_user_id)
 DO UPDATE SET inviter_user_id=EXCLUDED.inviter_user_id,
   intended_device_id=EXCLUDED.intended_device_id,
   device_label=EXCLUDED.device_label,
   token_sha256=EXCLUDED.token_sha256,
   expires_at=EXCLUDED.expires_at,
   status='invited',created_at=clock_timestamp(),
   accepted_at=NULL,revoked_at=NULL,activated_at=NULL;

 INSERT INTO marketpro_private.staff_device_transfer_audit(
   workspace_id,staff_user_id,old_device_id,new_device_id,changed_by,
   action,reason
 ) VALUES (
   p_workspace_id,p_staff_user_id,p_old_device_id,p_new_device_id,v_owner,
   'replace','Owner issued replacement phone invitation'
 );
 RETURN pg_catalog.jsonb_build_object(
  'old_device_disabled',true,'replacement_pending',true,
  'new_device_not_active',true,'new_device_can_write',false,
  'new_device_id',p_new_device_id,'expires_at',v_expires,
  'one_time_code',v_token,
  'cloud_history_retained',true,
  'unsynced_old_phone_data_must_be_backed_up_before_retirement',true
 );
END;
$fn$;
REVOKE ALL ON FUNCTION public.marketpro_owner_replace_team_phone(
 uuid,uuid,uuid,uuid,uuid,text
) FROM PUBLIC,anon;
GRANT EXECUTE ON FUNCTION public.marketpro_owner_replace_team_phone(
 uuid,uuid,uuid,uuid,uuid,text
) TO authenticated;

-- Existing v359 activation intentionally rejects already-enrolled users.
-- Replacements use this separate, guarded READ-ONLY activation path. This
-- does not turn on staff writing to old v1 unsafe RPC.
CREATE OR REPLACE FUNCTION public.marketpro_owner_activate_replacement_readonly(
 p_workspace_id uuid,p_owner_device_id uuid,p_staff_user_id uuid
) RETURNS jsonb LANGUAGE plpgsql SECURITY DEFINER SET search_path=''
AS $fn$
DECLARE
 v_owner uuid;
 v_gate marketpro_private.team_rollout_gate%ROWTYPE;
 v_invite marketpro_private.pending_team_enrollments%ROWTYPE;
 v_verified boolean;
BEGIN
 v_owner:=marketpro_private.require_enrolled_owner(
     p_workspace_id,p_owner_device_id
 );
 IF p_staff_user_id IS NULL OR p_staff_user_id=v_owner THEN
   RAISE EXCEPTION 'Valid Staff account required' USING ERRCODE='42501';
 END IF;
 PERFORM pg_catalog.pg_advisory_xact_lock(
    pg_catalog.hashtextextended(p_workspace_id::text,0)
 );
 SELECT * INTO v_gate FROM marketpro_private.team_rollout_gate
 WHERE workspace_id=p_workspace_id FOR UPDATE;
 IF NOT FOUND OR NOT v_gate.staff_direct_rest_restricted
    OR NOT v_gate.team_pull_rpc_reviewed THEN
   RAISE EXCEPTION 'Staff RLS and device-bound pull not audited for deployment'
     USING ERRCODE='42501';
 END IF;
 IF NOT EXISTS (
    SELECT 1 FROM public.workspace_members
    WHERE workspace_id=p_workspace_id AND user_id=p_staff_user_id
      AND role='purchasing_staff'
 ) OR EXISTS (
    SELECT 1 FROM public.registered_devices
    WHERE workspace_id=p_workspace_id AND user_id=p_staff_user_id
      AND enabled
 ) THEN
   RAISE EXCEPTION 'Existing Staff role required with no other active phone'
     USING ERRCODE='42501';
 END IF;
 SELECT * INTO v_invite
 FROM marketpro_private.pending_team_enrollments
 WHERE workspace_id=p_workspace_id AND staff_user_id=p_staff_user_id
 FOR UPDATE;
 IF NOT FOUND OR v_invite.status<>'accepted'
    OR v_invite.activated_at IS NOT NULL
    OR v_invite.accepted_at IS NULL
    OR v_invite.accepted_at < clock_timestamp()-interval '24 hours'
    OR v_invite.expires_at <= v_invite.accepted_at
    OR EXISTS(SELECT 1 FROM public.registered_devices
       WHERE id=v_invite.intended_device_id)
    OR NOT EXISTS (
       SELECT 1 FROM marketpro_private.staff_device_transfer_audit a
       WHERE a.workspace_id=p_workspace_id
         AND a.staff_user_id=p_staff_user_id
         AND a.new_device_id=v_invite.intended_device_id
         AND a.action='replace'
    ) THEN
   RAISE EXCEPTION 'Replacement claim invalid, stale or used'
     USING ERRCODE='42501';
 END IF;
 SELECT email_confirmed_at IS NOT NULL INTO v_verified
 FROM auth.users WHERE id=p_staff_user_id;
 IF v_verified IS DISTINCT FROM true THEN
   RAISE EXCEPTION 'Staff email no longer verified'
     USING ERRCODE='42501';
 END IF;

 INSERT INTO public.registered_devices(
    id,workspace_id,user_id,device_label,enabled,can_write
 ) VALUES(
    v_invite.intended_device_id,p_workspace_id,p_staff_user_id,
    v_invite.device_label,true,false
 );
 UPDATE marketpro_private.pending_team_enrollments
 SET activated_at=clock_timestamp()
 WHERE workspace_id=p_workspace_id AND staff_user_id=p_staff_user_id;
 INSERT INTO marketpro_private.staff_device_transfer_audit(
   workspace_id,staff_user_id,old_device_id,new_device_id,changed_by,
   action,reason
 )
 SELECT p_workspace_id,p_staff_user_id,a.old_device_id,
   v_invite.intended_device_id,v_owner,'activate',
   'Owner enabled read-only replacement Staff phone'
 FROM marketpro_private.staff_device_transfer_audit a
 WHERE a.workspace_id=p_workspace_id
   AND a.staff_user_id=p_staff_user_id
   AND a.new_device_id=v_invite.intended_device_id
   AND a.action='replace'
 ORDER BY a.changed_at DESC LIMIT 1;
 RETURN pg_catalog.jsonb_build_object(
   'replacement_activated',true,
   'new_device_id',v_invite.intended_device_id,
   'new_device_enabled',true,'new_device_can_write',false,
   'old_phone_stays_disabled',true,'cloud_history_retained',true
 );
END;
$fn$;
REVOKE ALL ON FUNCTION public.marketpro_owner_activate_replacement_readonly(
 uuid,uuid,uuid
) FROM PUBLIC,anon;
GRANT EXECUTE ON FUNCTION public.marketpro_owner_activate_replacement_readonly(
 uuid,uuid,uuid
) TO authenticated;
COMMIT;
