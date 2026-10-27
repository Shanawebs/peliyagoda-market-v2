-- MARKET PRO: QA-only, gated financial auto-sync writer; DO NOT use on Production.
-- Workspace af77cb66-76b2-492a-a460-06132f468713 in qqiheexpbuzohllugxrk.
-- ONE Android app for Owner and multiple Team phones.
-- TEST FIRST IN DISPOSABLE PostgreSQL 17; gate defaults OFF even after install.
BEGIN;

DO $guard$
BEGIN
 IF (SELECT count(*) FROM public.workspaces WHERE
    id='af77cb66-76b2-492a-a460-06132f468713'::uuid
    AND name='Market Pro Two-Phone QA')<>1
 OR (SELECT count(*) FROM public.workspace_members WHERE role='owner')<>1
 OR (SELECT count(*) FROM marketpro_private.qa_cloud_sync_v1_gate)<>1
 OR to_regclass('marketpro_private.qa_finance_sync_v1_gate') IS NOT NULL
 OR to_regclass('marketpro_private.qa_finance_sync_receipts_v1') IS NOT NULL
 THEN RAISE EXCEPTION 'Finance staging preflight failed'; END IF;
END;$guard$;

CREATE TABLE marketpro_private.qa_finance_sync_v1_gate(
 workspace_id uuid PRIMARY KEY REFERENCES public.workspaces(id),
 enabled boolean NOT NULL DEFAULT false
);
INSERT INTO marketpro_private.qa_finance_sync_v1_gate(workspace_id,enabled)
 VALUES('af77cb66-76b2-492a-a460-06132f468713',false);
ALTER TABLE marketpro_private.qa_finance_sync_v1_gate ENABLE ROW LEVEL SECURITY;
REVOKE ALL ON marketpro_private.qa_finance_sync_v1_gate
 FROM PUBLIC,anon,authenticated;

CREATE TABLE marketpro_private.qa_finance_sync_receipts_v1(
 operation_id uuid PRIMARY KEY,
 workspace_id uuid NOT NULL REFERENCES public.workspaces(id),
 actor_user_id uuid NOT NULL REFERENCES auth.users(id),
 device_id uuid NOT NULL,
 entity_type text NOT NULL,
 entity_id uuid NOT NULL,
 payload_sha256 text NOT NULL CHECK(payload_sha256 ~ '^[a-f0-9]{64}$'),
 resulting_revision bigint NOT NULL CHECK(resulting_revision > 0),
 applied_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
CREATE INDEX qa_finance_sync_receipts_workspace_time_idx
 ON marketpro_private.qa_finance_sync_receipts_v1(workspace_id,applied_at DESC);
ALTER TABLE marketpro_private.qa_finance_sync_receipts_v1 ENABLE ROW LEVEL SECURITY;
REVOKE ALL ON marketpro_private.qa_finance_sync_receipts_v1
 FROM PUBLIC,anon,authenticated;

-- Exposed RPC is INTENTIONALLY SECURITY DEFINER: it enforces live auth.sessions,
-- workspace role, active APPROVED device, a separate OFF-by-default finance
-- gate, server-authoritative Colombo business day, revision locks, JSON field
-- whitelists and idempotency INSIDE this one atomic DB transaction.
CREATE FUNCTION public.marketpro_qa_finance_apply_v1(
 p_workspace_id uuid,p_device_id uuid,p_operation_id uuid,
 p_entity_type text,p_entity_id uuid,p_expected_revision bigint,
 p_payload jsonb
) RETURNS jsonb
LANGUAGE plpgsql VOLATILE SECURITY DEFINER SET search_path=''
AS $apply$
DECLARE
 v_uid uuid:=auth.uid();
 v_session uuid;
 v_role text;
 v_today date:=(clock_timestamp() AT TIME ZONE 'Asia/Colombo')::date;
 v_day date;
 v_old_day date;
 v_old_rev bigint;
 v_new_rev bigint;
 v_hash text;
 v_prior marketpro_private.qa_finance_sync_receipts_v1%ROWTYPE;
 v_table text;
 v_allowed text[];
 v_required text[];
 v_key text;
 v_rows integer;
 v_item uuid;
 v_amount bigint;
BEGIN
 IF p_workspace_id IS DISTINCT FROM 'af77cb66-76b2-492a-a460-06132f468713'::uuid
 OR v_uid IS NULL OR p_device_id IS NULL OR p_operation_id IS NULL
 OR p_entity_id IS NULL OR p_expected_revision IS NULL
 OR p_expected_revision NOT BETWEEN 0 AND 2147483647
 OR p_payload IS NULL OR jsonb_typeof(p_payload)<>'object'
 OR octet_length(p_payload::text)>2048
 OR p_entity_type NOT IN ('purchase','purchase_addition','requisition',
                           'daily_cash_received','daily_other_expenses')
 OR p_entity_type IS NULL
 OR COALESCE((auth.jwt()->>'is_anonymous')::boolean,false)
 THEN RAISE EXCEPTION 'Invalid finance request' USING ERRCODE='42501'; END IF;

 BEGIN v_session:=(auth.jwt()->>'session_id')::uuid;
 EXCEPTION WHEN others THEN
  RAISE EXCEPTION 'Invalid Auth session' USING ERRCODE='42501'; END;

 PERFORM pg_catalog.pg_advisory_xact_lock(
   pg_catalog.hashtextextended(p_workspace_id::text,0));

 SELECT m.role INTO v_role FROM public.workspace_members m
 JOIN public.registered_devices d ON d.workspace_id=m.workspace_id
  AND d.user_id=m.user_id
 JOIN auth.sessions s ON s.user_id=m.user_id
 JOIN auth.users u ON u.id=m.user_id
 WHERE m.workspace_id=p_workspace_id AND m.user_id=v_uid
   AND m.role IN ('owner','editor')
   AND d.id=p_device_id AND d.enabled
   AND s.id=v_session AND (s.not_after IS NULL OR s.not_after>clock_timestamp())
   AND u.email_confirmed_at IS NOT NULL;
 IF v_role IS NULL OR NOT EXISTS(
   SELECT 1 FROM marketpro_private.qa_finance_sync_v1_gate
   WHERE workspace_id=p_workspace_id AND enabled
 ) THEN RAISE EXCEPTION 'Finance Sync is disabled or device revoked'
  USING ERRCODE='42501'; END IF;
 IF v_role='owner' AND (
   (auth.jwt()->>'aal') IS DISTINCT FROM 'aal2'
   OR NOT EXISTS(SELECT 1 FROM auth.mfa_factors f
      WHERE f.user_id=v_uid AND f.factor_type='totp' AND f.status='verified')
 ) THEN RAISE EXCEPTION 'Owner MFA required' USING ERRCODE='42501';END IF;

 v_day:=(p_payload->>'business_date')::date;
 IF v_day IS NULL OR v_day>v_today OR
   (v_role='editor' AND v_day<>v_today)
 THEN RAISE EXCEPTION 'Team may edit only Colombo today; Owner may edit past'
  USING ERRCODE='42501'; END IF;

 v_allowed:=CASE p_entity_type
 WHEN 'purchase' THEN ARRAY['business_date','item_id','quantity_grams','price_cents_per_kg']
 WHEN 'purchase_addition' THEN ARRAY['business_date','item_id','quantity_grams','price_cents_per_kg']
 WHEN 'requisition' THEN ARRAY['business_date','item_id','kitchen','quantity_grams']
 WHEN 'daily_cash_received' THEN ARRAY['business_date','amount_cents']
 WHEN 'daily_other_expenses' THEN ARRAY['business_date','transport_cents',
  'labor_charge_cents','meals_drinks_cents','others_cents','remarks'] END;
 v_required:=v_allowed;
 IF p_payload ? 'deleted_at' OR
  (SELECT count(*) FROM jsonb_object_keys(p_payload))<>array_length(v_required,1)
 THEN RAISE EXCEPTION 'Unexpected finance payload fields' USING ERRCODE='22023';END IF;
 FOREACH v_key IN ARRAY v_required LOOP
  IF NOT (p_payload ? v_key) OR p_payload->v_key='null'::jsonb
  THEN RAISE EXCEPTION 'Required finance field missing' USING ERRCODE='22023';END IF;
 END LOOP;
 FOR v_key IN SELECT jsonb_object_keys(p_payload) LOOP
  IF NOT v_key=ANY(v_allowed)
  THEN RAISE EXCEPTION 'Finance property not allowed' USING ERRCODE='22023';END IF;
 END LOOP;

 IF p_entity_type IN ('purchase','purchase_addition','requisition') THEN
  v_item:=(p_payload->>'item_id')::uuid;
  IF v_item IS NULL OR NOT EXISTS(
    SELECT 1 FROM public.items WHERE id=v_item AND workspace_id=p_workspace_id
      AND is_active AND deleted_at IS NULL
  ) THEN RAISE EXCEPTION 'Cloud item UUID not approved'
   USING ERRCODE='42501';END IF;
  v_amount:=(p_payload->>'quantity_grams')::bigint;
  IF v_amount<0 OR v_amount>1000000000 THEN
   RAISE EXCEPTION 'Invalid quantity' USING ERRCODE='22023';END IF;
 END IF;
 IF p_entity_type IN ('purchase','purchase_addition') THEN
  v_amount:=(p_payload->>'price_cents_per_kg')::bigint;
  IF v_amount<0 OR v_amount>1000000000000 THEN
   RAISE EXCEPTION 'Invalid price' USING ERRCODE='22023';END IF;
 ELSIF p_entity_type='daily_cash_received' THEN
  v_amount:=(p_payload->>'amount_cents')::bigint;
  IF v_amount<0 OR v_amount>1000000000000 THEN
   RAISE EXCEPTION 'Invalid cash' USING ERRCODE='22023';END IF;
 ELSIF p_entity_type='daily_other_expenses' THEN
  FOREACH v_key IN ARRAY ARRAY['transport_cents','labor_charge_cents',
       'meals_drinks_cents','others_cents'] LOOP
   v_amount:=(p_payload->>v_key)::bigint;
   IF v_amount<0 OR v_amount>1000000000000 THEN
    RAISE EXCEPTION 'Invalid expense' USING ERRCODE='22023';END IF;
  END LOOP;
  IF length(p_payload->>'remarks')>500 THEN
   RAISE EXCEPTION 'Remarks too long' USING ERRCODE='22023';END IF;
 END IF;
 IF p_entity_type='requisition' AND
    length(btrim(p_payload->>'kitchen')) NOT BETWEEN 1 AND 80
 THEN RAISE EXCEPTION 'Invalid kitchen' USING ERRCODE='22023';END IF;

 v_hash:=encode(extensions.digest(convert_to(
   p_entity_type||'|'||p_entity_id::text||'|'||
   p_expected_revision::text||'|'||p_payload::text,'UTF8'),'sha256'),'hex');
 SELECT * INTO v_prior FROM marketpro_private.qa_finance_sync_receipts_v1
 WHERE operation_id=p_operation_id FOR UPDATE;
 IF FOUND THEN
  IF v_prior.workspace_id<>p_workspace_id OR v_prior.actor_user_id<>v_uid
  OR v_prior.device_id<>p_device_id OR v_prior.entity_id<>p_entity_id
  OR v_prior.entity_type<>p_entity_type OR v_prior.payload_sha256<>v_hash
  THEN RAISE EXCEPTION 'Operation UUID reused with different data'
   USING ERRCODE='23505'; END IF;
  RETURN jsonb_build_object('accepted',true,'replayed',true,
    'operation_id',p_operation_id,'revision',v_prior.resulting_revision);
 END IF;

 v_table:=CASE p_entity_type WHEN 'purchase' THEN 'purchases'
  WHEN 'purchase_addition' THEN 'purchase_additions'
  WHEN 'requisition' THEN 'requisitions'
  WHEN 'daily_cash_received' THEN 'daily_cash_received'
  WHEN 'daily_other_expenses' THEN 'daily_other_expenses' END;
 EXECUTE format('SELECT business_date,revision FROM public.%I WHERE id=$1 AND workspace_id=$2 FOR UPDATE',
     v_table)
 INTO v_old_day,v_old_rev USING p_entity_id,p_workspace_id;
 IF p_expected_revision=0 THEN
  IF v_old_rev IS NOT NULL THEN
   RAISE EXCEPTION 'Existing row: expected revision must match'
    USING ERRCODE='40001';END IF;
 ELSE
  IF v_old_rev IS NULL OR v_old_rev<>p_expected_revision
    OR v_old_day<>v_day OR (v_role='editor' AND v_old_day<>v_today)
  THEN RAISE EXCEPTION 'Cloud row changed or earlier day is Owner-only'
   USING ERRCODE='40001';END IF;
 END IF;

 IF p_entity_type='purchase' THEN
  IF p_expected_revision=0 THEN
   INSERT INTO public.purchases(
    id,workspace_id,business_date,item_id,quantity_grams,price_cents_per_kg)
   VALUES(p_entity_id,p_workspace_id,v_day,v_item,
    (p_payload->>'quantity_grams')::bigint,(p_payload->>'price_cents_per_kg')::bigint)
   RETURNING revision INTO v_new_rev;
  ELSE
   UPDATE public.purchases SET
    quantity_grams=(p_payload->>'quantity_grams')::bigint,
    price_cents_per_kg=(p_payload->>'price_cents_per_kg')::bigint,
    revision=revision+1,updated_at=clock_timestamp(),deleted_at=NULL
   WHERE id=p_entity_id AND workspace_id=p_workspace_id
     AND revision=p_expected_revision
   RETURNING revision INTO v_new_rev;
  END IF;
 ELSIF p_entity_type='purchase_addition' THEN
  IF p_expected_revision=0 THEN
   INSERT INTO public.purchase_additions(
    id,workspace_id,business_date,item_id,quantity_grams,price_cents_per_kg)
   VALUES(p_entity_id,p_workspace_id,v_day,v_item,
    (p_payload->>'quantity_grams')::bigint,(p_payload->>'price_cents_per_kg')::bigint)
   RETURNING revision INTO v_new_rev;
  ELSE
   UPDATE public.purchase_additions SET
    quantity_grams=(p_payload->>'quantity_grams')::bigint,
    price_cents_per_kg=(p_payload->>'price_cents_per_kg')::bigint,
    revision=revision+1,updated_at=clock_timestamp(),deleted_at=NULL
   WHERE id=p_entity_id AND workspace_id=p_workspace_id
     AND revision=p_expected_revision
   RETURNING revision INTO v_new_rev;
  END IF;
 ELSIF p_entity_type='requisition' THEN
  IF p_expected_revision=0 THEN
   INSERT INTO public.requisitions(
    id,workspace_id,business_date,item_id,kitchen,quantity_grams)
   VALUES(p_entity_id,p_workspace_id,v_day,v_item,
    btrim(p_payload->>'kitchen'),(p_payload->>'quantity_grams')::bigint)
   RETURNING revision INTO v_new_rev;
  ELSE
   UPDATE public.requisitions SET
    kitchen=btrim(p_payload->>'kitchen'),
    quantity_grams=(p_payload->>'quantity_grams')::bigint,
    revision=revision+1,updated_at=clock_timestamp(),deleted_at=NULL
   WHERE id=p_entity_id AND workspace_id=p_workspace_id
     AND revision=p_expected_revision
   RETURNING revision INTO v_new_rev;
  END IF;
 ELSIF p_entity_type='daily_cash_received' THEN
  IF p_expected_revision=0 THEN
   INSERT INTO public.daily_cash_received(
    id,workspace_id,business_date,amount_cents)
   VALUES(p_entity_id,p_workspace_id,v_day,(p_payload->>'amount_cents')::bigint)
   RETURNING revision INTO v_new_rev;
  ELSE
   UPDATE public.daily_cash_received SET
    amount_cents=(p_payload->>'amount_cents')::bigint,
    revision=revision+1,updated_at=clock_timestamp(),deleted_at=NULL
   WHERE id=p_entity_id AND workspace_id=p_workspace_id
     AND revision=p_expected_revision
   RETURNING revision INTO v_new_rev;
  END IF;
 ELSIF p_entity_type='daily_other_expenses' THEN
  IF p_expected_revision=0 THEN
   INSERT INTO public.daily_other_expenses(
    id,workspace_id,business_date,transport_cents,labor_charge_cents,
    meals_drinks_cents,others_cents,remarks)
   VALUES(p_entity_id,p_workspace_id,v_day,
    (p_payload->>'transport_cents')::bigint,
    (p_payload->>'labor_charge_cents')::bigint,
    (p_payload->>'meals_drinks_cents')::bigint,
    (p_payload->>'others_cents')::bigint,
    p_payload->>'remarks')
   RETURNING revision INTO v_new_rev;
  ELSE
   UPDATE public.daily_other_expenses SET
    transport_cents=(p_payload->>'transport_cents')::bigint,
    labor_charge_cents=(p_payload->>'labor_charge_cents')::bigint,
    meals_drinks_cents=(p_payload->>'meals_drinks_cents')::bigint,
    others_cents=(p_payload->>'others_cents')::bigint,
    remarks=p_payload->>'remarks',
    revision=revision+1,updated_at=clock_timestamp()
   WHERE id=p_entity_id AND workspace_id=p_workspace_id
     AND revision=p_expected_revision
   RETURNING revision INTO v_new_rev;
  END IF;
 END IF;
 IF v_new_rev IS NULL OR v_new_rev<>p_expected_revision+1 THEN
  RAISE EXCEPTION 'Concurrent Cloud change; reload before correction'
   USING ERRCODE='40001';END IF;

 INSERT INTO public.sync_operations(
  operation_id,workspace_id,device_id,actor_user_id,
  entity_type,entity_id,operation_type,payload_hash)
 VALUES(p_operation_id,p_workspace_id,p_device_id,v_uid,
  p_entity_type,p_entity_id,'upsert',v_hash);

 INSERT INTO marketpro_private.qa_finance_sync_receipts_v1(
  operation_id,workspace_id,actor_user_id,device_id,
  entity_type,entity_id,payload_sha256,resulting_revision)
 VALUES(p_operation_id,p_workspace_id,v_uid,p_device_id,
  p_entity_type,p_entity_id,v_hash,v_new_rev);

 RETURN jsonb_build_object('accepted',true,'replayed',false,
   'operation_id',p_operation_id,'revision',v_new_rev,
   'entity_id',p_entity_id,'entity_type',p_entity_type);
END;$apply$;
REVOKE ALL ON FUNCTION public.marketpro_qa_finance_apply_v1(
 uuid,uuid,uuid,text,uuid,bigint,jsonb) FROM PUBLIC,anon,authenticated;
GRANT EXECUTE ON FUNCTION public.marketpro_qa_finance_apply_v1(
 uuid,uuid,uuid,text,uuid,bigint,jsonb) TO authenticated;

DO $post$
BEGIN
 IF (SELECT count(*) FROM marketpro_private.qa_finance_sync_v1_gate WHERE enabled)<>0
 OR (SELECT count(*) FROM marketpro_private.qa_finance_sync_receipts_v1)<>0
 OR has_function_privilege('anon',
   'public.marketpro_qa_finance_apply_v1(uuid,uuid,uuid,text,uuid,bigint,jsonb)','EXECUTE')
 OR (SELECT count(*) FROM marketpro_private.qa_finance_sync_v1_gate)<>1
 OR NOT (SELECT relrowsecurity FROM pg_class
     WHERE oid='marketpro_private.qa_finance_sync_receipts_v1'::regclass)
 THEN RAISE EXCEPTION 'Cloud Finance Sync staging gate failed';END IF;
END;$post$;
COMMIT;
