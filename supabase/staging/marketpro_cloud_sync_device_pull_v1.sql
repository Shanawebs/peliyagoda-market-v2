-- Staging-only Market Pro cloud sync v1: Owner/Team phone enrollment + paginated read.
-- Project: qqiheexpbuzohllugxrk / workspace: af77cb66-76b2-492a-a460-06132f468713
-- Single Android APK; multiple Team phones; no production changes.
-- IMPORTANT: Gate defaults OFF; this does NOT activate financial uploads.
BEGIN;
DO $guard$
BEGIN
 IF (SELECT count(*) FROM public.workspaces WHERE
 id='af77cb66-76b2-492a-a460-06132f468713'::uuid
 AND name='Market Pro Two-Phone QA')<>1
 OR (SELECT count(*) FROM public.registered_devices)<>0
 OR (SELECT count(*) FROM public.workspace_members WHERE role='owner')<>1
 OR (SELECT count(*) FROM public.workspace_members WHERE role='editor')<>1
 OR (SELECT count(*) FROM marketpro_private.qa_owner_pending_devices WHERE status='pending' AND enabled=false AND can_write=false)<>1
 OR to_regclass('marketpro_private.qa_cloud_sync_v1_gate') IS NOT NULL
 THEN RAISE EXCEPTION 'Cloud Sync staging-only guard failed'; END IF;
END;$guard$;

CREATE TABLE marketpro_private.qa_cloud_sync_v1_gate (
 workspace_id uuid PRIMARY KEY REFERENCES public.workspaces(id),
 intake_enabled boolean NOT NULL DEFAULT false,
 pull_enabled boolean NOT NULL DEFAULT false
);
INSERT INTO marketpro_private.qa_cloud_sync_v1_gate(workspace_id)
 VALUES('af77cb66-76b2-492a-a460-06132f468713');
REVOKE ALL ON marketpro_private.qa_cloud_sync_v1_gate FROM PUBLIC,anon,authenticated;
ALTER TABLE marketpro_private.qa_cloud_sync_v1_gate ENABLE ROW LEVEL SECURITY;

-- No client can INSERT directly into registered_devices. Enroll only through
-- this role-bound, live-session-checked, rate/quantity limited RPC.
CREATE FUNCTION public.marketpro_qa_cloud_request_phone(
 p_workspace_id uuid,p_device_id uuid,p_device_label text
) RETURNS jsonb LANGUAGE plpgsql VOLATILE SECURITY DEFINER SET search_path=''
AS $phone$
DECLARE
 v_uid uuid:=auth.uid();
 v_session uuid;
 v_role text;
 v_current public.registered_devices%ROWTYPE;
 v_gate boolean;
BEGIN
 IF p_workspace_id IS DISTINCT FROM 'af77cb66-76b2-492a-a460-06132f468713'::uuid
 OR p_device_id IS NULL OR p_device_label IS NULL
 OR length(btrim(p_device_label)) NOT BETWEEN 1 AND 100
 OR v_uid IS NULL OR COALESCE((auth.jwt()->>'is_anonymous')::boolean,false)
 THEN RAISE EXCEPTION 'Invalid phone request' USING ERRCODE='42501';END IF;
 BEGIN v_session:=(auth.jwt()->>'session_id')::uuid;
 EXCEPTION WHEN others THEN
  RAISE EXCEPTION 'Invalid Auth session' USING ERRCODE='42501'; END;
 PERFORM pg_catalog.pg_advisory_xact_lock(pg_catalog.hashtextextended(p_workspace_id::text,0));
 SELECT role INTO v_role FROM public.workspace_members
 WHERE workspace_id=p_workspace_id AND user_id=v_uid AND role IN ('owner','editor');
 IF v_role IS NULL OR NOT EXISTS(
    SELECT 1 FROM auth.users u JOIN auth.sessions s ON s.user_id=u.id
    WHERE u.id=v_uid AND u.email_confirmed_at IS NOT NULL
      AND s.id=v_session AND (s.not_after IS NULL OR s.not_after>clock_timestamp())
 ) OR NOT EXISTS (SELECT 1 FROM marketpro_private.qa_cloud_sync_v1_gate
    WHERE workspace_id=p_workspace_id AND intake_enabled)
 THEN RAISE EXCEPTION 'Phone enrollment disabled' USING ERRCODE='42501';END IF;
 IF v_role='owner' AND (
  auth.jwt()->>'aal'<>'aal2'
  OR NOT EXISTS (SELECT 1 FROM auth.mfa_factors WHERE user_id=v_uid
         AND factor_type='totp' AND status='verified')
 ) THEN RAISE EXCEPTION 'Owner requires MFA' USING ERRCODE='42501';END IF;

 SELECT * INTO v_current FROM public.registered_devices
 WHERE workspace_id=p_workspace_id AND id=p_device_id FOR UPDATE;
 IF FOUND THEN
  IF v_current.user_id<>v_uid THEN
   RAISE EXCEPTION 'Device identity already claimed' USING ERRCODE='42501';END IF;
  RETURN jsonb_build_object('ok',true,'status',
     CASE WHEN v_current.enabled THEN 'approved' ELSE 'pending' END,
     'enabled',v_current.enabled,'can_write',v_current.can_write);
 END IF;
 IF (SELECT count(*) FROM public.registered_devices
     WHERE workspace_id=p_workspace_id AND user_id=v_uid)>=8
 THEN RAISE EXCEPTION 'Too many phone requests' USING ERRCODE='42501';END IF;
 IF v_role='owner' AND EXISTS(
     SELECT 1 FROM public.registered_devices d
     JOIN public.workspace_members m ON m.workspace_id=d.workspace_id
        AND m.user_id=d.user_id
     WHERE d.workspace_id=p_workspace_id AND m.role='owner' AND d.enabled)
 THEN RAISE EXCEPTION 'Owner already has one active phone' USING ERRCODE='42501';END IF;

 INSERT INTO public.registered_devices(
   id,workspace_id,user_id,device_label,enabled,can_write
 ) VALUES(p_device_id,p_workspace_id,v_uid,btrim(p_device_label),
   v_role='owner',v_role='owner');
 RETURN jsonb_build_object('ok',true,'status',
    CASE WHEN v_role='owner' THEN 'approved' ELSE 'pending' END,
    'enabled',v_role='owner','can_write',v_role='owner');
END;$phone$;
REVOKE ALL ON FUNCTION public.marketpro_qa_cloud_request_phone(uuid,uuid,text)
 FROM PUBLIC,anon,authenticated;
GRANT EXECUTE ON FUNCTION public.marketpro_qa_cloud_request_phone(uuid,uuid,text)
 TO authenticated;

-- Owner can approve/revoke any Team phone from the one active Owner phone.
-- Team can never self-approve, unlink another user, or activate writer access.
-- Staff can_write stays FALSE because the old single-owner-writer unique index
-- is intentionally preserved. Independent Team sync writer will use a separate
-- server-authorized lease after financial RPC end-to-end validation.
CREATE FUNCTION public.marketpro_qa_cloud_decide_team_phone(
 p_workspace_id uuid,p_owner_device_id uuid,p_team_device_id uuid,p_approve boolean
) RETURNS jsonb LANGUAGE plpgsql VOLATILE SECURITY DEFINER SET search_path=''
AS $decide$
DECLARE v_uid uuid:=auth.uid();v_session uuid;v_changed integer;
BEGIN
 IF p_workspace_id IS DISTINCT FROM 'af77cb66-76b2-492a-a460-06132f468713'::uuid
 OR p_owner_device_id IS NULL OR p_team_device_id IS NULL OR p_approve IS NULL
 OR v_uid IS NULL OR COALESCE((auth.jwt()->>'is_anonymous')::boolean,false)
 OR auth.jwt()->>'aal'<>'aal2'
 THEN RAISE EXCEPTION 'Owner approval denied' USING ERRCODE='42501';END IF;
 BEGIN v_session:=(auth.jwt()->>'session_id')::uuid;
 EXCEPTION WHEN others THEN
  RAISE EXCEPTION 'Invalid Auth session' USING ERRCODE='42501'; END;
 PERFORM pg_catalog.pg_advisory_xact_lock(pg_catalog.hashtextextended(p_workspace_id::text,0));
 IF NOT EXISTS(SELECT 1 FROM auth.sessions s
   WHERE s.id=v_session AND s.user_id=v_uid
     AND (s.not_after IS NULL OR s.not_after>clock_timestamp()))
 OR NOT EXISTS(SELECT 1 FROM public.workspace_members m
   WHERE m.workspace_id=p_workspace_id AND m.user_id=v_uid AND m.role='owner')
 OR NOT EXISTS(SELECT 1 FROM auth.mfa_factors f
   WHERE f.user_id=v_uid AND f.factor_type='totp' AND f.status='verified')
 OR NOT EXISTS(SELECT 1 FROM public.registered_devices d
   WHERE d.workspace_id=p_workspace_id AND d.id=p_owner_device_id
    AND d.user_id=v_uid AND d.enabled AND d.can_write)
 THEN RAISE EXCEPTION 'Owner phone is not active' USING ERRCODE='42501';END IF;
 UPDATE public.registered_devices d SET enabled=p_approve,can_write=false
 FROM public.workspace_members m
 WHERE d.id=p_team_device_id AND d.workspace_id=p_workspace_id
 AND m.workspace_id=d.workspace_id AND m.user_id=d.user_id
 AND m.role='editor';
 GET DIAGNOSTICS v_changed=ROW_COUNT;
 IF v_changed<>1 THEN
  RAISE EXCEPTION 'Unrecognized Team phone' USING ERRCODE='42501';END IF;
 RETURN jsonb_build_object('ok',true,'device_id',p_team_device_id,
  'enabled',p_approve,'can_write',false);
END;$decide$;
REVOKE ALL ON FUNCTION public.marketpro_qa_cloud_decide_team_phone(uuid,uuid,uuid,boolean)
 FROM PUBLIC,anon,authenticated;
GRANT EXECUTE ON FUNCTION public.marketpro_qa_cloud_decide_team_phone(uuid,uuid,uuid,boolean)
 TO authenticated;

-- Existing Kotlin TeamPullHttpTransport expects this stable RPC contract.
-- Cloud row staging and local revision merge remain client-side conflict-safe.
CREATE FUNCTION public.marketpro_team_pull_page(
 p_workspace_id uuid,p_device_id uuid,p_entity_type text,
 p_after_updated_at timestamptz DEFAULT NULL,p_after_id uuid DEFAULT NULL,
 p_page_size integer DEFAULT 35
) RETURNS jsonb LANGUAGE plpgsql VOLATILE SECURITY DEFINER SET search_path=''
AS $pull$
DECLARE
 v_uid uuid:=auth.uid();v_session uuid;v_table text;v_rows jsonb;
 v_count integer;v_more boolean;v_last_at timestamptz;v_last_id uuid;
BEGIN
 IF p_workspace_id IS DISTINCT FROM 'af77cb66-76b2-492a-a460-06132f468713'::uuid
 OR p_device_id IS NULL OR p_entity_type NOT IN (
  'category','item','requisition','purchase','purchase_addition',
  'daily_cash_received','daily_other_expenses')
 OR p_entity_type IS NULL OR p_page_size NOT BETWEEN 1 AND 50
 OR ((p_after_updated_at IS NULL)<>(p_after_id IS NULL))
 OR v_uid IS NULL OR COALESCE((auth.jwt()->>'is_anonymous')::boolean,false)
 THEN RAISE EXCEPTION 'Invalid Cloud pull request' USING ERRCODE='42501';END IF;
 BEGIN v_session:=(auth.jwt()->>'session_id')::uuid;
 EXCEPTION WHEN others THEN
  RAISE EXCEPTION 'Invalid Auth session' USING ERRCODE='42501'; END;
 IF NOT EXISTS (SELECT 1 FROM marketpro_private.qa_cloud_sync_v1_gate
  WHERE workspace_id=p_workspace_id AND pull_enabled)
 OR NOT EXISTS(SELECT 1 FROM public.workspace_members m
  JOIN public.registered_devices d ON d.workspace_id=m.workspace_id
   AND d.user_id=m.user_id
  JOIN auth.sessions s ON s.user_id=m.user_id
  WHERE m.workspace_id=p_workspace_id AND m.user_id=v_uid
   AND m.role IN ('owner','editor') AND d.id=p_device_id AND d.enabled
   AND s.id=v_session AND (s.not_after IS NULL OR s.not_after>clock_timestamp()))
 THEN RAISE EXCEPTION 'Phone not authorized for Cloud download' USING ERRCODE='42501';END IF;
 v_table:=CASE p_entity_type
  WHEN 'category' THEN 'categories'
  WHEN 'item' THEN 'items'
  WHEN 'requisition' THEN 'requisitions'
  WHEN 'purchase' THEN 'purchases'
  WHEN 'purchase_addition' THEN 'purchase_additions'
  WHEN 'daily_cash_received' THEN 'daily_cash_received'
  WHEN 'daily_other_expenses' THEN 'daily_other_expenses' END;
 EXECUTE pg_catalog.format(
  'SELECT coalesce(jsonb_agg(to_jsonb(r) ORDER BY r.updated_at,r.id),''[]''::jsonb)
   FROM (SELECT * FROM public.%I WHERE workspace_id=$1 AND
     ($2::timestamptz IS NULL OR
      (updated_at,id)>($2::timestamptz,$3::uuid))
     ORDER BY updated_at,id LIMIT $4) AS r',v_table)
 INTO v_rows USING p_workspace_id,p_after_updated_at,p_after_id,p_page_size+1;
 v_count:=jsonb_array_length(v_rows);
 v_more:=v_count>p_page_size;
 IF v_more THEN v_rows:=v_rows-(v_count-1);END IF;
 v_count:=jsonb_array_length(v_rows);
 IF v_count>0 THEN
  v_last_at:=(v_rows->(v_count-1)->>'updated_at')::timestamptz;
  v_last_id:=(v_rows->(v_count-1)->>'id')::uuid;
 ELSE v_last_at:=p_after_updated_at;v_last_id:=p_after_id;END IF;
 RETURN jsonb_build_object('entity_type',p_entity_type,'rows',v_rows,
   'has_more',v_more,'next_updated_at',v_last_at,'next_id',v_last_id,
   'server_today_colombo',(clock_timestamp() AT TIME ZONE 'Asia/Colombo')::date,
   'server_time_utc',clock_timestamp());
END;$pull$;
REVOKE ALL ON FUNCTION public.marketpro_team_pull_page(
 uuid,uuid,text,timestamptz,uuid,integer)
 FROM PUBLIC,anon,authenticated;
GRANT EXECUTE ON FUNCTION public.marketpro_team_pull_page(
 uuid,uuid,text,timestamptz,uuid,integer) TO authenticated;

DO $post$
BEGIN
 IF (SELECT count(*) FROM public.registered_devices)<>0
 OR (SELECT count(*) FROM marketpro_private.qa_cloud_sync_v1_gate
     WHERE intake_enabled OR pull_enabled)<>0
 OR has_function_privilege('anon','public.marketpro_qa_cloud_request_phone(uuid,uuid,text)','EXECUTE')
 OR has_function_privilege('anon','public.marketpro_qa_cloud_decide_team_phone(uuid,uuid,uuid,boolean)','EXECUTE')
 OR has_function_privilege('anon','public.marketpro_team_pull_page(uuid,uuid,text,timestamptz,uuid,integer)','EXECUTE')
 OR NOT (SELECT relrowsecurity FROM pg_class WHERE oid='marketpro_private.qa_cloud_sync_v1_gate'::regclass)
 THEN RAISE EXCEPTION 'Cloud Sync rollout guard failed';END IF;
END;$post$;
COMMIT;
