-- Market Pro 4.2.1 STAGING: scoped requisition tombstone RPC.
-- This creates an additional function. Existing data and v4.2.0 writer remain untouched.
CREATE OR REPLACE FUNCTION public.marketpro_qa_requisition_delete_v421(
 p_workspace_id uuid, p_device_id uuid, p_operation_id uuid,
 p_entity_type text, p_entity_id uuid, p_expected_revision bigint,
 p_payload jsonb
) RETURNS jsonb LANGUAGE plpgsql SECURITY DEFINER SET search_path TO ''
AS $function$
DECLARE
 v_uid uuid:=auth.uid();
 v_session uuid;
 v_role text;
 v_today date:=(clock_timestamp() AT TIME ZONE 'Asia/Colombo')::date;
 v_day date;
 v_prev public.requisitions%ROWTYPE;
 v_hash text;
 v_receipt marketpro_private.qa_finance_sync_receipts_v1%ROWTYPE;
 v_revision bigint;
BEGIN
 IF p_workspace_id IS DISTINCT FROM 'af77cb66-76b2-492a-a460-06132f468713'::uuid
 OR v_uid IS NULL OR p_device_id IS NULL OR p_operation_id IS NULL
 OR p_entity_type IS DISTINCT FROM 'requisition'
 OR p_entity_id IS NULL OR p_expected_revision IS NULL
 OR p_expected_revision NOT BETWEEN 1 AND 2147483647
 OR p_payload IS NULL OR jsonb_typeof(p_payload)<>'object'
 OR octet_length(p_payload::text)>512
 OR COALESCE((auth.jwt()->>'is_anonymous')::boolean,false)
 OR (SELECT count(*) FROM jsonb_object_keys(p_payload))<>3
 OR NOT (p_payload ?& ARRAY['business_date','kitchen','item_id'])
 OR p_payload ? 'deleted_at'
 THEN RAISE EXCEPTION 'Invalid requisition delete request' USING ERRCODE='42501'; END IF;

 BEGIN v_session:=(auth.jwt()->>'session_id')::uuid;
 EXCEPTION WHEN others THEN
  RAISE EXCEPTION 'Invalid auth session' USING ERRCODE='42501';
 END;

 -- Serialize with the existing v4.2.0 finance writer to avoid concurrent
 -- writes to the same workspace while validating revision and idempotency.
 PERFORM pg_catalog.pg_advisory_xact_lock(
  pg_catalog.hashtextextended(p_workspace_id::text,0));
 SELECT m.role INTO v_role FROM public.workspace_members m
 JOIN public.registered_devices d ON d.workspace_id=m.workspace_id
  AND d.user_id=m.user_id
 JOIN auth.sessions s ON s.user_id=m.user_id
 JOIN auth.users u ON u.id=m.user_id
 WHERE m.workspace_id=p_workspace_id AND m.user_id=v_uid
  AND m.role IN ('owner','editor') AND d.id=p_device_id AND d.enabled
  AND s.id=v_session AND (s.not_after IS NULL OR s.not_after>clock_timestamp())
  AND u.email_confirmed_at IS NOT NULL;

 IF v_role IS NULL OR NOT EXISTS(
   SELECT 1 FROM marketpro_private.qa_finance_sync_v1_gate
   WHERE workspace_id=p_workspace_id AND enabled)
 THEN RAISE EXCEPTION 'Phone revoked or finance sync disabled'
  USING ERRCODE='42501'; END IF;
 IF v_role='owner' AND (
  (auth.jwt()->>'aal') IS DISTINCT FROM 'aal2'
  OR NOT EXISTS(SELECT 1 FROM auth.mfa_factors f
   WHERE f.user_id=v_uid AND f.factor_type='totp' AND f.status='verified'))
 THEN RAISE EXCEPTION 'Owner authenticator required'
  USING ERRCODE='42501'; END IF;

 IF (p_payload->>'business_date') IS NULL
  OR (p_payload->>'kitchen') IS NULL OR (p_payload->>'item_id') IS NULL
  OR p_payload->>'kitchen' NOT IN
   ('Main Kitchen','Indian Kitchen','Pastry Kitchen')
 THEN RAISE EXCEPTION 'Invalid kitchen/date/item key'
  USING ERRCODE='22023'; END IF;
 v_day:=(p_payload->>'business_date')::date;
 IF v_day IS NULL OR v_day>v_today
  OR (v_role='editor' AND v_day<>v_today)
 THEN RAISE EXCEPTION 'Staff only today, Owner may change prior dates'
  USING ERRCODE='42501'; END IF;

 -- The delete operation is deliberately distinct from a standard UPSERT:
 -- replaying the same UUID must match exactly the original operation.
 v_hash:=encode(extensions.digest(convert_to(
   'requisition|delete|'||p_entity_id::text||'|'||
   p_expected_revision::text||'|'||p_payload::text,'UTF8'),'sha256'),'hex');
 SELECT * INTO v_receipt FROM marketpro_private.qa_finance_sync_receipts_v1
 WHERE operation_id=p_operation_id FOR UPDATE;
 IF FOUND THEN
  IF v_receipt.workspace_id<>p_workspace_id OR v_receipt.actor_user_id<>v_uid
   OR v_receipt.device_id<>p_device_id OR v_receipt.entity_id<>p_entity_id
   OR v_receipt.entity_type<>'requisition' OR v_receipt.payload_sha256<>v_hash
  THEN RAISE EXCEPTION 'Operation UUID replay differs'
   USING ERRCODE='23505'; END IF;
  RETURN jsonb_build_object('accepted',true,'replayed',true,
    'operation_id',p_operation_id,'revision',v_receipt.resulting_revision);
 END IF;

 SELECT * INTO v_prev FROM public.requisitions
 WHERE workspace_id=p_workspace_id AND id=p_entity_id FOR UPDATE;
 IF NOT FOUND OR v_prev.deleted_at IS NOT NULL
  OR v_prev.revision<>p_expected_revision
  OR v_prev.business_date<>v_day
  OR v_prev.kitchen<>p_payload->>'kitchen'
  OR v_prev.item_id<>(p_payload->>'item_id')::uuid
 THEN RAISE EXCEPTION 'Requisition changed; review before delete'
  USING ERRCODE='40001'; END IF;
 UPDATE public.requisitions
 SET deleted_at=clock_timestamp(),
  revision=revision+1,updated_at=clock_timestamp()
 WHERE id=p_entity_id AND workspace_id=p_workspace_id
  AND revision=p_expected_revision AND deleted_at IS NULL
 RETURNING revision INTO v_revision;
 IF v_revision IS DISTINCT FROM p_expected_revision+1
 THEN RAISE EXCEPTION 'Concurrent requisition change'
  USING ERRCODE='40001'; END IF;

 INSERT INTO public.sync_operations(
  operation_id,workspace_id,device_id,actor_user_id,
  entity_type,entity_id,operation_type,payload_hash)
 VALUES(p_operation_id,p_workspace_id,p_device_id,v_uid,
  'requisition',p_entity_id,'delete',v_hash);
 INSERT INTO marketpro_private.qa_finance_sync_receipts_v1(
  operation_id,workspace_id,actor_user_id,device_id,
  entity_type,entity_id,payload_sha256,resulting_revision)
 VALUES(p_operation_id,p_workspace_id,v_uid,p_device_id,
  'requisition',p_entity_id,v_hash,v_revision);
 RETURN jsonb_build_object('accepted',true,'replayed',false,
  'operation_id',p_operation_id,'revision',v_revision);
END;$function$;

REVOKE ALL ON FUNCTION public.marketpro_qa_requisition_delete_v421(
 uuid,uuid,uuid,text,uuid,bigint,jsonb) FROM PUBLIC,anon,authenticated;
GRANT EXECUTE ON FUNCTION public.marketpro_qa_requisition_delete_v421(
 uuid,uuid,uuid,text,uuid,bigint,jsonb) TO authenticated;
