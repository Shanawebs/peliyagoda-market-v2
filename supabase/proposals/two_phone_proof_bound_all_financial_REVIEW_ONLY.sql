-- Market Pro FINANCIAL V3: four additional EXACT-signature financial entities.
-- REVIEW ONLY. Requires v375 challenge, v2 signed-purchase SQL, Other Expenses
-- schema and audited server Staff/RLS cutover. NEVER apply to LIVE Supabase.
-- Existing Owner v1 RPC is unaffected; Staff financial endpoints remain blocked.
BEGIN;
ALTER TABLE marketpro_private.staff_device_challenges
 ADD COLUMN IF NOT EXISTS financial_entity_type text;
ALTER TABLE marketpro_private.staff_device_challenges
 DROP CONSTRAINT IF EXISTS financial_v3_type_check;
ALTER TABLE marketpro_private.staff_device_challenges
 ADD CONSTRAINT financial_v3_type_check CHECK(
    financial_entity_type IS NULL OR
    (purpose='write' AND
     financial_entity_type IN
      ('requisition','purchase_addition','daily_cash_received','daily_other_expenses')
     AND operation_id IS NOT NULL AND entity_id IS NOT NULL
     AND financial_action IN('create','correct')
     AND operation_payload IS NOT NULL
     AND operation_sha256 ~ '^[a-f0-9]{64}$')
 );

CREATE TABLE IF NOT EXISTS marketpro_private.proof_bound_financial_operations(
 operation_id uuid PRIMARY KEY,
 workspace_id uuid NOT NULL,
 staff_user_id uuid NOT NULL,
 device_id uuid NOT NULL,
 session_id uuid NOT NULL,
 entity_type text NOT NULL CHECK(entity_type IN(
  'requisition','purchase_addition','daily_cash_received','daily_other_expenses')),
 entity_id uuid NOT NULL,
 action text NOT NULL CHECK(action IN('create','correct')),
 expected_revision bigint,
 payload_sha256 text NOT NULL CHECK(payload_sha256 ~ '^[a-f0-9]{64}$'),
 resulting_revision bigint NOT NULL CHECK(resulting_revision>0),
 committed_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
REVOKE ALL ON marketpro_private.proof_bound_financial_operations
 FROM PUBLIC,anon,authenticated;

CREATE OR REPLACE FUNCTION public.marketpro_staff_issue_financial_challenge(
 p_workspace_id uuid,p_device_id uuid,p_operation_id uuid,
 p_entity_type text,p_entity_id uuid,p_action text,
 p_expected_revision bigint,p_payload jsonb
) RETURNS jsonb
LANGUAGE plpgsql VOLATILE SECURITY DEFINER SET search_path=''
AS $issue$
DECLARE
 v jsonb;
 v_count int;
 v_keys text[];
 v_allowed text[];
 v_challenge uuid;
 v_hash text;
BEGIN
 IF p_workspace_id IS NULL OR p_device_id IS NULL OR
    p_operation_id IS NULL OR p_entity_id IS NULL OR
    p_entity_type NOT IN
       ('requisition','purchase_addition','daily_cash_received','daily_other_expenses')
    OR p_entity_type IS NULL OR
    p_action NOT IN('create','correct') OR p_action IS NULL OR
    (p_action='create' AND p_expected_revision IS NOT NULL) OR
    (p_action='correct' AND (p_expected_revision IS NULL OR p_expected_revision<1))
    OR p_payload IS NULL OR jsonb_typeof(p_payload)<>'object' OR
    octet_length(p_payload::text)>2048 THEN
  RAISE EXCEPTION 'Invalid signed financial challenge' USING ERRCODE='22023';
 END IF;
 v_allowed:=CASE p_entity_type
    WHEN 'requisition' THEN ARRAY['business_date','item_id','kitchen','quantity_grams']
    WHEN 'purchase_addition' THEN ARRAY['business_date','item_id','quantity_grams','price_cents_per_kg']
    WHEN 'daily_cash_received' THEN ARRAY['business_date','amount_cents']
    WHEN 'daily_other_expenses' THEN ARRAY[
      'business_date','transport_cents','labor_charge_cents',
      'meals_drinks_cents','others_cents','remarks']
 END;
 SELECT array_agg(k ORDER BY k), count(*) INTO v_keys,v_count
 FROM jsonb_object_keys(p_payload) k;
 IF v_count<>array_length(v_allowed,1) OR
    v_keys IS DISTINCT FROM (SELECT array_agg(k ORDER BY k)
       FROM unnest(v_allowed) k) OR
    jsonb_typeof(p_payload->'business_date')<>'string' THEN
  RAISE EXCEPTION 'Exact financial field allowlist required' USING ERRCODE='22023';
 END IF;
 -- Use v375 Staff challenge issuance for live owner lease, verified session,
 -- enrolled public-key and device authorization. It locks the workspace.
 v:=public.marketpro_staff_issue_device_challenge(p_workspace_id,p_device_id,'write');
 v_challenge:=(v->>'challenge_id')::uuid;
 v_hash:=pg_catalog.encode(extensions.digest(
   pg_catalog.convert_to(p_payload::text,'UTF8'),'sha256'),'hex');
 UPDATE marketpro_private.staff_device_challenges SET
   operation_id=p_operation_id,entity_id=p_entity_id,
   financial_entity_type=p_entity_type,
   financial_action=p_action,expected_revision=p_expected_revision,
   operation_payload=p_payload,operation_sha256=v_hash
 WHERE challenge_id=v_challenge AND workspace_id=p_workspace_id
   AND consumed_at IS NULL;
 IF NOT FOUND THEN RAISE EXCEPTION 'Financial challenge was not bound'
   USING ERRCODE='42501'; END IF;
 RETURN v || pg_catalog.jsonb_build_object(
   'proof_version','marketpro-device-proof-financial-v3',
   'entity_type',p_entity_type,'operation_id',p_operation_id,
   'entity_id',p_entity_id,'financial_action',p_action,
   'expected_revision',p_expected_revision,'operation_sha256',v_hash
 );
END;
$issue$;
REVOKE ALL ON FUNCTION public.marketpro_staff_issue_financial_challenge(
 uuid,uuid,uuid,text,uuid,text,bigint,jsonb) FROM PUBLIC,anon;
GRANT EXECUTE ON FUNCTION public.marketpro_staff_issue_financial_challenge(
 uuid,uuid,uuid,text,uuid,text,bigint,jsonb) TO authenticated;

CREATE OR REPLACE FUNCTION public.marketpro_service_financial_proof_material(
 p_challenge_id uuid,p_staff_user_id uuid,p_session_id uuid
) RETURNS jsonb
LANGUAGE plpgsql VOLATILE SECURITY DEFINER SET search_path=''
AS $material$
DECLARE
 v jsonb;
 extra jsonb;
BEGIN
 v:=public.marketpro_service_device_challenge_material(
    p_challenge_id,p_staff_user_id,p_session_id);
 SELECT pg_catalog.jsonb_build_object(
   'proof_version','marketpro-device-proof-financial-v3',
   'entity_type',c.financial_entity_type,
   'operation_id',c.operation_id,'entity_id',c.entity_id,
   'financial_action',c.financial_action,
   'expected_revision',c.expected_revision,
   'operation_sha256',c.operation_sha256,
   'operation_payload',c.operation_payload
 ) INTO extra
 FROM marketpro_private.staff_device_challenges c
 WHERE c.challenge_id=p_challenge_id AND c.staff_user_id=p_staff_user_id
   AND c.session_id=p_session_id AND c.purpose='write'
   AND c.financial_entity_type IN
     ('requisition','purchase_addition','daily_cash_received','daily_other_expenses')
   AND c.operation_id IS NOT NULL AND c.entity_id IS NOT NULL
   AND c.operation_payload IS NOT NULL;
 IF extra IS NULL THEN
  RAISE EXCEPTION 'Unbound financial proof' USING ERRCODE='42501'; END IF;
 RETURN v||extra;
END;
$material$;
REVOKE ALL ON FUNCTION public.marketpro_service_financial_proof_material(
 uuid,uuid,uuid) FROM PUBLIC,anon,authenticated;
GRANT EXECUTE ON FUNCTION public.marketpro_service_financial_proof_material(
 uuid,uuid,uuid) TO service_role;

-- Static, closed CASE branches: NOT a dynamic SQL/table dispatcher.
CREATE OR REPLACE FUNCTION public.marketpro_service_commit_signed_financial(
 p_challenge_id uuid,p_staff_user_id uuid,p_session_id uuid,
 p_workspace_id uuid,p_device_id uuid,p_operation_id uuid,
 p_entity_type text,p_entity_id uuid,p_action text,
 p_expected_revision bigint,p_payload jsonb
) RETURNS jsonb
LANGUAGE plpgsql VOLATILE SECURITY DEFINER SET search_path=''
AS $commit$
DECLARE
 v jsonb;
 v_hash text;
 v_keys text[];
 v_allowed text[];
 v_prior marketpro_private.proof_bound_financial_operations%ROWTYPE;
 v_date date;
 v_today date:=(clock_timestamp() AT TIME ZONE 'Asia/Colombo')::date;
 v_item uuid;
 v_qty bigint;
 v_price bigint;
 v_cash bigint;
 v_kitchen text;
 v_transport bigint;
 v_labor bigint;
 v_meals bigint;
 v_others bigint;
 v_remarks text;
 v_old_date date;
 v_old_item uuid;
 v_old_kitchen text;
 v_old_revision bigint;
 v_existing_deleted timestamptz;
 v_revision bigint;
 v_count int;
 v_updated int;
 v_replayed boolean:=false;
BEGIN
 IF p_challenge_id IS NULL OR p_staff_user_id IS NULL OR
    p_session_id IS NULL OR p_workspace_id IS NULL OR p_device_id IS NULL OR
    p_operation_id IS NULL OR p_entity_id IS NULL OR p_entity_type IS NULL OR
    p_entity_type NOT IN
     ('requisition','purchase_addition','daily_cash_received','daily_other_expenses')
    OR p_action NOT IN('create','correct') OR p_action IS NULL OR
    p_payload IS NULL OR jsonb_typeof(p_payload)<>'object' OR
    octet_length(p_payload::text)>2048 THEN
  RAISE EXCEPTION 'Invalid signed finance envelope' USING ERRCODE='22023'; END IF;
 PERFORM pg_catalog.pg_advisory_xact_lock(
   pg_catalog.hashtextextended(p_workspace_id::text,0));
 -- Inside this transaction, recheck every Owner-approved Staff JWT/session,
 -- key, phone, lease, revocation and nonce with the V375 service helper.
 v:=public.marketpro_service_financial_proof_material(
    p_challenge_id,p_staff_user_id,p_session_id);
 v_hash:=pg_catalog.encode(extensions.digest(
   pg_catalog.convert_to(p_payload::text,'UTF8'),'sha256'),'hex');
 IF v->>'purpose'<>'write' OR
    v->>'proof_version'<>'marketpro-device-proof-financial-v3' OR
    v->>'workspace_id' IS DISTINCT FROM p_workspace_id::text OR
    v->>'staff_user_id' IS DISTINCT FROM p_staff_user_id::text OR
    v->>'device_id' IS DISTINCT FROM p_device_id::text OR
    v->>'session_id' IS DISTINCT FROM p_session_id::text OR
    v->>'operation_id' IS DISTINCT FROM p_operation_id::text OR
    v->>'entity_id' IS DISTINCT FROM p_entity_id::text OR
    v->>'entity_type' IS DISTINCT FROM p_entity_type OR
    v->>'financial_action' IS DISTINCT FROM p_action OR
    (v->>'expected_revision')::bigint IS DISTINCT FROM p_expected_revision OR
    v->>'operation_sha256' IS DISTINCT FROM v_hash OR
    v->'operation_payload' IS DISTINCT FROM p_payload THEN
  RAISE EXCEPTION 'Signature is not bound to this exact finance transaction'
     USING ERRCODE='42501'; END IF;

 v_allowed:=CASE p_entity_type
    WHEN 'requisition' THEN ARRAY['business_date','item_id','kitchen','quantity_grams']
    WHEN 'purchase_addition' THEN ARRAY['business_date','item_id','quantity_grams','price_cents_per_kg']
    WHEN 'daily_cash_received' THEN ARRAY['business_date','amount_cents']
    WHEN 'daily_other_expenses' THEN ARRAY[
       'business_date','transport_cents','labor_charge_cents',
       'meals_drinks_cents','others_cents','remarks']
 END;
 SELECT array_agg(k ORDER BY k),count(*) INTO v_keys,v_count
 FROM jsonb_object_keys(p_payload) k;
 IF v_count<>array_length(v_allowed,1) OR
    v_keys IS DISTINCT FROM (SELECT array_agg(k ORDER BY k)
       FROM unnest(v_allowed) k) OR
    jsonb_typeof(p_payload->'business_date')<>'string' OR
    (p_action='create' AND p_expected_revision IS NOT NULL) OR
    (p_action='correct' AND
       (p_expected_revision IS NULL OR p_expected_revision<1)) THEN
  RAISE EXCEPTION 'Invalid finance type, revision or allowlist'
    USING ERRCODE='22023'; END IF;
 v_date:=(p_payload->>'business_date')::date;
 IF v_date IS NULL OR v_date<>v_today THEN
  RAISE EXCEPTION 'Staff may change ONLY the current Colombo day'
    USING ERRCODE='42501'; END IF;

 IF p_entity_type IN('requisition','purchase_addition') THEN
  v_item:=(p_payload->>'item_id')::uuid;
  v_qty:=(p_payload->>'quantity_grams')::bigint;
  IF v_item IS NULL OR v_qty IS NULL OR v_qty NOT BETWEEN 0 AND 1000000000 OR
     NOT EXISTS(SELECT 1 FROM public.items i
       WHERE i.workspace_id=p_workspace_id AND i.id=v_item
       AND i.deleted_at IS NULL) THEN
    RAISE EXCEPTION 'Unrecognized item UUID or quantity' USING ERRCODE='42501';
  END IF;
  IF p_entity_type='requisition' THEN
   v_kitchen:=p_payload->>'kitchen';
   IF v_kitchen NOT IN('Main Kitchen','Indian Kitchen','Pastry Kitchen') OR
      v_kitchen IS NULL THEN
    RAISE EXCEPTION 'Unknown kitchen' USING ERRCODE='22023'; END IF;
  ELSE
   v_price:=(p_payload->>'price_cents_per_kg')::bigint;
   IF v_price IS NULL OR v_price NOT BETWEEN 0 AND 1000000000 THEN
    RAISE EXCEPTION 'Invalid addition price' USING ERRCODE='22023'; END IF;
  END IF;
 ELSIF p_entity_type='daily_cash_received' THEN
  v_cash:=(p_payload->>'amount_cents')::bigint;
  IF v_cash IS NULL OR v_cash NOT BETWEEN 0 AND 9999999999 THEN
   RAISE EXCEPTION 'Invalid cash received' USING ERRCODE='22023'; END IF;
 ELSE
  v_transport:=(p_payload->>'transport_cents')::bigint;
  v_labor:=(p_payload->>'labor_charge_cents')::bigint;
  v_meals:=(p_payload->>'meals_drinks_cents')::bigint;
  v_others:=(p_payload->>'others_cents')::bigint;
  v_remarks:=p_payload->>'remarks';
  IF v_transport IS NULL OR v_labor IS NULL OR v_meals IS NULL OR
     v_others IS NULL OR v_remarks IS NULL OR
     v_transport NOT BETWEEN 0 AND 9999999999 OR
     v_labor NOT BETWEEN 0 AND 9999999999 OR
     v_meals NOT BETWEEN 0 AND 9999999999 OR
     v_others NOT BETWEEN 0 AND 9999999999 OR
     char_length(v_remarks)>500 OR
     position(chr(0) IN v_remarks)>0 THEN
   RAISE EXCEPTION 'Invalid expense amounts/remarks' USING ERRCODE='22023'; END IF;
 END IF;

 SELECT * INTO v_prior FROM marketpro_private.proof_bound_financial_operations
 WHERE operation_id=p_operation_id;
 IF FOUND THEN
  IF v_prior.workspace_id IS DISTINCT FROM p_workspace_id OR
     v_prior.staff_user_id IS DISTINCT FROM p_staff_user_id OR
     v_prior.device_id IS DISTINCT FROM p_device_id OR
     v_prior.session_id IS DISTINCT FROM p_session_id OR
     v_prior.entity_type IS DISTINCT FROM p_entity_type OR
     v_prior.entity_id IS DISTINCT FROM p_entity_id OR
     v_prior.action IS DISTINCT FROM p_action OR
     v_prior.expected_revision IS DISTINCT FROM p_expected_revision OR
     v_prior.payload_sha256 IS DISTINCT FROM v_hash THEN
    RAISE EXCEPTION 'Conflicting duplicate finance operation UUID'
       USING ERRCODE='23505'; END IF;
  v_revision:=v_prior.resulting_revision;
  v_replayed:=true;
 ELSE
  IF p_entity_type='requisition' THEN
   SELECT business_date,item_id,kitchen,revision,deleted_at INTO
    v_old_date,v_old_item,v_old_kitchen,v_old_revision,v_existing_deleted
   FROM public.requisitions
   WHERE workspace_id=p_workspace_id AND id=p_entity_id FOR UPDATE;
   IF p_action='create' THEN
    IF FOUND THEN RAISE EXCEPTION 'Requisition UUID collision'
      USING ERRCODE='23505'; END IF;
    INSERT INTO public.requisitions(id,workspace_id,business_date,item_id,
       kitchen,quantity_grams)
    VALUES(p_entity_id,p_workspace_id,v_date,v_item,v_kitchen,v_qty)
    RETURNING revision INTO v_revision;
   ELSE
    IF NOT FOUND OR v_existing_deleted IS NOT NULL OR
       v_old_date<>v_date OR v_old_item<>v_item OR
       v_old_kitchen<>v_kitchen OR v_old_revision<>p_expected_revision THEN
      RAISE EXCEPTION 'Stale/changed requisition identity'
         USING ERRCODE='40001'; END IF;
    UPDATE public.requisitions SET quantity_grams=v_qty,
      revision=revision+1,updated_at=clock_timestamp()
    WHERE workspace_id=p_workspace_id AND id=p_entity_id
      AND revision=p_expected_revision
    RETURNING revision INTO v_revision;
   END IF;
  ELSIF p_entity_type='purchase_addition' THEN
   SELECT business_date,item_id,revision,deleted_at INTO
     v_old_date,v_old_item,v_old_revision,v_existing_deleted
   FROM public.purchase_additions
   WHERE workspace_id=p_workspace_id AND id=p_entity_id FOR UPDATE;
   IF p_action='create' THEN
    IF FOUND THEN RAISE EXCEPTION 'Addition lot UUID collision'
      USING ERRCODE='23505'; END IF;
    INSERT INTO public.purchase_additions(id,workspace_id,business_date,item_id,
      quantity_grams,price_cents_per_kg)
    VALUES(p_entity_id,p_workspace_id,v_date,v_item,v_qty,v_price)
    RETURNING revision INTO v_revision;
   ELSE
    IF NOT FOUND OR v_existing_deleted IS NOT NULL OR
       v_old_date<>v_date OR v_old_item<>v_item OR
       v_old_revision<>p_expected_revision THEN
      RAISE EXCEPTION 'Stale or reidentified purchase lot'
        USING ERRCODE='40001'; END IF;
    UPDATE public.purchase_additions SET quantity_grams=v_qty,
      price_cents_per_kg=v_price,
      revision=revision+1,updated_at=clock_timestamp()
    WHERE workspace_id=p_workspace_id AND id=p_entity_id
      AND revision=p_expected_revision
    RETURNING revision INTO v_revision;
   END IF;
  ELSIF p_entity_type='daily_cash_received' THEN
   SELECT business_date,revision,deleted_at INTO
      v_old_date,v_old_revision,v_existing_deleted
   FROM public.daily_cash_received
   WHERE workspace_id=p_workspace_id AND id=p_entity_id FOR UPDATE;
   IF p_action='create' THEN
    IF FOUND THEN RAISE EXCEPTION 'Cash record UUID collision'
      USING ERRCODE='23505'; END IF;
    INSERT INTO public.daily_cash_received(id,workspace_id,business_date,amount_cents)
    VALUES(p_entity_id,p_workspace_id,v_date,v_cash)
    RETURNING revision INTO v_revision;
   ELSE
    IF NOT FOUND OR v_existing_deleted IS NOT NULL OR
       v_old_date<>v_date OR v_old_revision<>p_expected_revision THEN
      RAISE EXCEPTION 'Stale/cross-day cash correction'
       USING ERRCODE='40001'; END IF;
    UPDATE public.daily_cash_received SET amount_cents=v_cash,
       revision=revision+1,updated_at=clock_timestamp()
    WHERE workspace_id=p_workspace_id AND id=p_entity_id
      AND revision=p_expected_revision
    RETURNING revision INTO v_revision;
   END IF;
  ELSE
   SELECT business_date,revision INTO v_old_date,v_old_revision
   FROM public.daily_other_expenses
   WHERE workspace_id=p_workspace_id AND id=p_entity_id FOR UPDATE;
   IF p_action='create' THEN
    IF FOUND THEN RAISE EXCEPTION 'Expense record UUID collision'
      USING ERRCODE='23505'; END IF;
    INSERT INTO public.daily_other_expenses(
      id,workspace_id,business_date,transport_cents,labor_charge_cents,
      meals_drinks_cents,others_cents,remarks)
    VALUES(p_entity_id,p_workspace_id,v_date,v_transport,v_labor,
      v_meals,v_others,v_remarks)
    RETURNING revision INTO v_revision;
   ELSE
    IF NOT FOUND OR v_old_date<>v_date OR
       v_old_revision<>p_expected_revision THEN
      RAISE EXCEPTION 'Stale/cross-day expense correction'
       USING ERRCODE='40001'; END IF;
    UPDATE public.daily_other_expenses SET
      transport_cents=v_transport,labor_charge_cents=v_labor,
      meals_drinks_cents=v_meals,others_cents=v_others,remarks=v_remarks,
      revision=revision+1,updated_at=clock_timestamp()
    WHERE workspace_id=p_workspace_id AND id=p_entity_id
      AND revision=p_expected_revision
    RETURNING revision INTO v_revision;
   END IF;
  END IF;
  IF v_revision IS NULL THEN
    RAISE EXCEPTION 'Concurrent finance revision changed'
      USING ERRCODE='40001'; END IF;
  INSERT INTO public.sync_operations(
    operation_id,workspace_id,device_id,actor_user_id,
    entity_type,entity_id,operation_type,payload_hash)
  VALUES(p_operation_id,p_workspace_id,p_device_id,p_staff_user_id,
    p_entity_type,p_entity_id,'upsert',v_hash);
  INSERT INTO marketpro_private.proof_bound_financial_operations(
     operation_id,workspace_id,staff_user_id,device_id,session_id,
     entity_type,entity_id,action,expected_revision,payload_sha256,
     resulting_revision)
  VALUES(p_operation_id,p_workspace_id,p_staff_user_id,p_device_id,p_session_id,
     p_entity_type,p_entity_id,p_action,p_expected_revision,v_hash,v_revision);
 END IF;

 UPDATE marketpro_private.staff_device_challenges
 SET consumed_at=clock_timestamp(),verified_at=clock_timestamp()
 WHERE challenge_id=p_challenge_id AND workspace_id=p_workspace_id
   AND staff_user_id=p_staff_user_id AND device_id=p_device_id
   AND session_id=p_session_id AND consumed_at IS NULL AND
   expires_at>clock_timestamp() AND financial_entity_type=p_entity_type
   AND operation_id=p_operation_id AND entity_id=p_entity_id;
 GET DIAGNOSTICS v_updated=ROW_COUNT;
 IF v_updated<>1 THEN
   RAISE EXCEPTION 'Expired/replayed financial proof'
      USING ERRCODE='42501'; END IF;
 RETURN pg_catalog.jsonb_build_object(
   'accepted',true,'operation_id',p_operation_id,
   'revision',v_revision,'replayed',v_replayed,
   'proof_consumed_in_financial_transaction',true,
   'entity_type',p_entity_type);
END;
$commit$;

REVOKE ALL ON FUNCTION public.marketpro_service_commit_signed_financial(
 uuid,uuid,uuid,uuid,uuid,uuid,text,uuid,text,bigint,jsonb)
 FROM PUBLIC,anon,authenticated;
GRANT EXECUTE ON FUNCTION public.marketpro_service_commit_signed_financial(
 uuid,uuid,uuid,uuid,uuid,uuid,uuid,text,uuid,text,bigint,jsonb) TO service_role;

-- New direct-RPC bypasses forbidden even if earlier review-only migrations
-- were applied. The signed purchase V2 RPC remains service-only as before.
REVOKE EXECUTE ON FUNCTION public.marketpro_apply_team_financial_change(
 uuid,uuid,uuid,text,uuid,text,bigint,jsonb) FROM PUBLIC,anon,authenticated;
REVOKE EXECUTE ON FUNCTION public.marketpro_apply_team_other_expense(
 uuid,uuid,uuid,uuid,text,bigint,jsonb) FROM PUBLIC,anon,authenticated;
COMMIT;
