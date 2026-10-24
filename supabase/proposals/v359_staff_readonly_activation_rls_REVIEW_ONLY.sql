-- MARKET PRO V3.5.9 — REVIEW-ONLY STAFF READ-ONLY ACTIVATION + DIRECT REST READ GATE
-- DO NOT EXECUTE ON LIVE SUPABASE. This file is for isolated PostgreSQL tests.
-- Requires v358 pending invitation RPC, and v354 team-pull RPC rollout.
-- The legacy owner-only v3.3.7 writer and dashboard visual UI must remain unchanged.
BEGIN;

-- The private gates are changed only via an audited ADMINISTRATOR-controlled
-- migration after v2 RPC/RLS review; no public function may toggle them.
CREATE TABLE IF NOT EXISTS marketpro_private.team_rollout_gate (
    workspace_id uuid PRIMARY KEY,
    staff_direct_rest_restricted boolean NOT NULL DEFAULT false,
    team_pull_rpc_reviewed boolean NOT NULL DEFAULT false,
    team_writes_reviewed boolean NOT NULL DEFAULT false,
    updated_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
REVOKE ALL ON marketpro_private.team_rollout_gate FROM PUBLIC,anon,authenticated;

-- Existing constraint is enforced in live Cloud; do NOT replace roles with staff
-- only or remove compatibility for legacy owner, editor, viewer.
ALTER TABLE public.workspace_members
  DROP CONSTRAINT workspace_members_role_check;
ALTER TABLE public.workspace_members
  ADD CONSTRAINT workspace_members_role_check
    CHECK (role IN ('owner','editor','viewer','purchasing_staff'));

-- Legacy Owner/Editor/Viewer read access is preserved, but Staff gets no direct
-- PostgREST SELECT. STAFF CAN ONLY READ via audited authenticated, device-bound
-- marketpro_team_pull_page RPC after the separate future server cutover.
-- The qualified outer-table workspace_id is critical to prevent an accidental
-- all-workspaces access control mistake when joining workspace_members.
DROP POLICY IF EXISTS member_categories_read ON public.categories;
CREATE POLICY member_categories_read ON public.categories FOR SELECT
TO authenticated USING (EXISTS (
  SELECT 1 FROM public.workspace_members AS wm
  WHERE wm.workspace_id = categories.workspace_id
    AND wm.user_id = (SELECT auth.uid())
    AND wm.role IN ('owner','editor','viewer')
));

DROP POLICY IF EXISTS member_items_read ON public.items;
CREATE POLICY member_items_read ON public.items FOR SELECT
TO authenticated USING (EXISTS (
  SELECT 1 FROM public.workspace_members AS wm
  WHERE wm.workspace_id = items.workspace_id
    AND wm.user_id = (SELECT auth.uid())
    AND wm.role IN ('owner','editor','viewer')
));

DROP POLICY IF EXISTS workspace_requisitions_read ON public.requisitions;
CREATE POLICY workspace_requisitions_read ON public.requisitions FOR SELECT
TO authenticated USING (EXISTS (
  SELECT 1 FROM public.workspace_members AS wm
  WHERE wm.workspace_id = requisitions.workspace_id
    AND wm.user_id = (SELECT auth.uid())
    AND wm.role IN ('owner','editor','viewer')
));

DROP POLICY IF EXISTS member_purchases_read ON public.purchases;
CREATE POLICY member_purchases_read ON public.purchases FOR SELECT
TO authenticated USING (EXISTS (
  SELECT 1 FROM public.workspace_members AS wm
  WHERE wm.workspace_id = purchases.workspace_id
    AND wm.user_id = (SELECT auth.uid())
    AND wm.role IN ('owner','editor','viewer')
));

DROP POLICY IF EXISTS member_purchase_additions_read ON public.purchase_additions;
CREATE POLICY member_purchase_additions_read ON public.purchase_additions FOR SELECT
TO authenticated USING (EXISTS (
  SELECT 1 FROM public.workspace_members AS wm
  WHERE wm.workspace_id = purchase_additions.workspace_id
    AND wm.user_id = (SELECT auth.uid())
    AND wm.role IN ('owner','editor','viewer')
));

DROP POLICY IF EXISTS workspace_cash_read ON public.daily_cash_received;
CREATE POLICY workspace_cash_read ON public.daily_cash_received FOR SELECT
TO authenticated USING (EXISTS (
  SELECT 1 FROM public.workspace_members AS wm
  WHERE wm.workspace_id = daily_cash_received.workspace_id
    AND wm.user_id = (SELECT auth.uid())
    AND wm.role IN ('owner','editor','viewer')
));

ALTER TABLE marketpro_private.pending_team_enrollments
  ADD COLUMN IF NOT EXISTS activated_at timestamptz;

CREATE OR REPLACE FUNCTION public.marketpro_owner_activate_staff_readonly(
   p_workspace_id uuid, p_owner_device_id uuid, p_staff_user_id uuid
) RETURNS jsonb
LANGUAGE plpgsql SECURITY DEFINER SET search_path=''
AS $activate$
DECLARE
  v_owner uuid;
  v_invite marketpro_private.pending_team_enrollments%ROWTYPE;
  v_gate marketpro_private.team_rollout_gate%ROWTYPE;
  v_email_confirmed boolean;
BEGIN
  v_owner := marketpro_private.require_enrolled_owner(
    p_workspace_id,p_owner_device_id
  );
  IF p_staff_user_id IS NULL OR p_staff_user_id=v_owner THEN
    RAISE EXCEPTION 'Different verified staff user required' USING ERRCODE='42501';
  END IF;
  PERFORM pg_catalog.pg_advisory_xact_lock(
    pg_catalog.hashtextextended(p_workspace_id::text,0)
  );
  SELECT * INTO v_gate
    FROM marketpro_private.team_rollout_gate
   WHERE workspace_id=p_workspace_id FOR UPDATE;
  IF NOT FOUND OR NOT v_gate.staff_direct_rest_restricted
     OR NOT v_gate.team_pull_rpc_reviewed THEN
    RAISE EXCEPTION 'Staff activation blocked until server policies and pull API are audited'
       USING ERRCODE='42501';
  END IF;
  SELECT * INTO v_invite
    FROM marketpro_private.pending_team_enrollments
   WHERE workspace_id=p_workspace_id AND staff_user_id=p_staff_user_id
   FOR UPDATE;
  IF NOT FOUND OR v_invite.status<>'accepted' OR
     v_invite.activated_at IS NOT NULL OR
     v_invite.accepted_at IS NULL OR
     v_invite.accepted_at < clock_timestamp()-interval '24 hours' THEN
    RAISE EXCEPTION 'Staff invitation not freshly accepted or already activated'
      USING ERRCODE='42501';
  END IF;
  SELECT email_confirmed_at IS NOT NULL INTO v_email_confirmed
  FROM auth.users WHERE id=p_staff_user_id;
  IF v_email_confirmed IS DISTINCT FROM true OR
     EXISTS (SELECT 1 FROM public.workspace_members
              WHERE workspace_id=p_workspace_id AND user_id=p_staff_user_id) OR
     EXISTS (SELECT 1 FROM public.registered_devices
              WHERE id=v_invite.intended_device_id) THEN
    RAISE EXCEPTION 'Staff identity/device already used or no longer verified'
      USING ERRCODE='42501';
  END IF;
  -- This read-only enrollment is not a writer. Existing v1 owner RPC is
  -- unchanged; staff only becomes writer with future separately reviewed RPC.
  INSERT INTO public.workspace_members(workspace_id,user_id,role)
  VALUES (p_workspace_id,p_staff_user_id,'purchasing_staff');
  INSERT INTO public.registered_devices(
    id,workspace_id,user_id,device_label,enabled,can_write
  ) VALUES (
    v_invite.intended_device_id,p_workspace_id,p_staff_user_id,
    v_invite.device_label,true,false
  );
  UPDATE marketpro_private.pending_team_enrollments
  SET activated_at=clock_timestamp()
  WHERE workspace_id=p_workspace_id AND staff_user_id=p_staff_user_id;
  RETURN pg_catalog.jsonb_build_object(
    'activated',true, 'staff_role','purchasing_staff',
    'read_only',true,'can_write',false
  );
END;
$activate$;

REVOKE ALL ON FUNCTION public.marketpro_owner_activate_staff_readonly(
  uuid,uuid,uuid
) FROM PUBLIC,anon;
GRANT EXECUTE ON FUNCTION public.marketpro_owner_activate_staff_readonly(
  uuid,uuid,uuid
) TO authenticated;

CREATE OR REPLACE FUNCTION public.marketpro_owner_revoke_staff_device(
   p_workspace_id uuid,p_owner_device_id uuid,p_staff_user_id uuid
) RETURNS jsonb
LANGUAGE plpgsql SECURITY DEFINER SET search_path=''
AS $revoke$
DECLARE
   v_owner uuid;
   v_changed integer;
BEGIN
   v_owner := marketpro_private.require_enrolled_owner(
      p_workspace_id,p_owner_device_id
   );
   IF p_staff_user_id IS NULL OR p_staff_user_id=v_owner THEN
      RAISE EXCEPTION 'Cannot revoke owner device via staff endpoint'
        USING ERRCODE='42501';
   END IF;
   PERFORM pg_catalog.pg_advisory_xact_lock(
      pg_catalog.hashtextextended(p_workspace_id::text,0)
   );
   IF NOT EXISTS (
     SELECT 1 FROM public.workspace_members
     WHERE workspace_id=p_workspace_id AND user_id=p_staff_user_id
       AND role='purchasing_staff'
   ) THEN
     RAISE EXCEPTION 'Target is not an enrolled purchasing staff account'
       USING ERRCODE='42501';
   END IF;
   UPDATE public.registered_devices SET enabled=false,can_write=false
   WHERE workspace_id=p_workspace_id AND user_id=p_staff_user_id
     AND (enabled OR can_write);
   GET DIAGNOSTICS v_changed=ROW_COUNT;
   RETURN pg_catalog.jsonb_build_object('revoked_devices',v_changed);
END;
$revoke$;

REVOKE ALL ON FUNCTION public.marketpro_owner_revoke_staff_device(
  uuid,uuid,uuid
) FROM PUBLIC,anon;
GRANT EXECUTE ON FUNCTION public.marketpro_owner_revoke_staff_device(
  uuid,uuid,uuid
) TO authenticated;

COMMIT;
