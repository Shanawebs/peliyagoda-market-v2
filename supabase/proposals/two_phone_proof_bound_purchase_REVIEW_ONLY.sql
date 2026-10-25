-- MARKET PRO: ONE-TIME DEVICE SIGNATURE BOUND TO EXACT PURCHASE MUTATION.
-- REVIEW ONLY. Never run on live Supabase; requires v353, v372, v375 and
-- completed/independently audited Staff RLS before activation.
-- Security boundary: ONLY Edge service_role may read proof material and call
-- COMMIT. Existing direct V2 Staff finance RPC grants must be removed together.
BEGIN;

ALTER TABLE marketpro_private.staff_device_challenges
 ADD COLUMN IF NOT EXISTS operation_id uuid,
 ADD COLUMN IF NOT EXISTS entity_id uuid,
 ADD COLUMN IF NOT EXISTS financial_action text,
 ADD COLUMN IF NOT EXISTS expected_revision bigint,
 ADD COLUMN IF NOT EXISTS operation_payload jsonb,
 ADD COLUMN IF NOT EXISTS operation_sha256 text;

ALTER TABLE marketpro_private.staff_device_challenges
 DROP CONSTRAINT IF EXISTS staff_bound_purchase_shape;
ALTER TABLE marketpro_private.staff_device_challenges
 ADD CONSTRAINT staff_bound_purchase_shape CHECK(
  operation_id IS NULL OR
  (purpose='write' AND entity_id IS NOT NULL
   AND financial_action IN('create','correct')
   AND operation_payload IS NOT NULL
   AND jsonb_typeof(operation_payload)='object'
   AND operation_sha256 ~ '^[0-9a-f]{64}$'
   AND ((financial_action='create' AND expected_revision IS NULL) OR
        (financial_action='correct' AND expected_revision > 0)))
 );

CREATE TABLE IF NOT EXISTS marketpro_private.proof_bound_purchase_operations(
 operation_id uuid PRIMARY KEY,
 workspace_id uuid NOT NULL,
 staff_user_id uuid NOT NULL,
 device_id uuid NOT NULL,
 session_id uuid NOT NULL,
 entity_id uuid NOT NULL,
 action text NOT NULL CHECK(action IN('create','correct')),
 expected_revision bigint,
 payload_sha256 text NOT NULL CHECK(payload_sha256 ~ '^[a-f0-9]{64}$'),
 resulting_revision bigint NOT NULL CHECK(resulting_revision>0),
 committed_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
REVOKE ALL ON marketpro_private.proof_bound_purchase_operations
 FROM PUBLIC,anon,authenticated;

-- Only allow a Staff-signed JWT to request a challenge for ONE exact purchase
-- action and payload. Other types are deliberately not supported in this slice.
CREATE OR REPLACE FUNCTION public.marketpro_staff_issue_purchase_challenge(
 p_workspace_id uuid,p_device_id uuid,p_operation_id uuid,
 p_entity_id uuid,p_action text,p_expected_revision bigint,p_payload jsonb
) RETURNS jsonb
LANGUAGE plpgsql VOLATILE SECURITY DEFINER SET search_path=''
AS $issue$
DECLARE
 v jsonb;
 v_challenge uuid;
 v_hash text;
 v_key text;
BEGIN
 IF p_workspace_id IS NULL OR p_device_id IS NULL OR
    p_operation_id IS NULL OR p_entity_id IS NULL OR
    p_action NOT IN('create','correct') OR p_action IS NULL OR
    (p_action='create' AND p_expected_revision IS NOT NULL) OR
    (p_action='correct' AND
      (p_expected_revision IS NULL OR p_expected_revision<1)) OR
    p_payload IS NULL OR jsonb_typeof(p_payload)<>'object' OR
    octet_length(p_payload::text)>1024 THEN
  RAISE EXCEPTION 'Invalid purchase-proof envelope' USING ERRCODE='22023';
 END IF;
 IF (SELECT count(*) FROM jsonb_object_keys(p_payload))<>4 OR NOT
    (p_payload ? 'business_date' AND p_payload ? 'item_id' AND
     p_payload ? 'quantity_grams' AND p_payload ? 'price_cents_per_kg') THEN
  RAISE EXCEPTION 'Exact four purchase fields required' USING ERRCODE='22023';
 END IF;
 FOR v_key IN SELECT jsonb_object_keys(p_payload) LOOP
  IF v_key NOT IN('business_date','item_id',
                 'quantity_grams','price_cents_per_kg') THEN
   RAISE EXCEPTION 'Unknown purchase field' USING ERRCODE='22023';
  END IF;
 END LOOP;
 -- The V375 function validates a live JWT session + approved key +
 -- Owner-approved write lease and serializes the workspace challenge.
 v:=public.marketpro_staff_issue_device_challenge(
   p_workspace_id,p_device_id,'write');
 v_challenge:=(v->>'challenge_id')::uuid;
 v_hash:=pg_catalog.encode(extensions.digest(
   pg_catalog.convert_to(p_payload::text,'UTF8'),'sha256'),'hex');
 UPDATE marketpro_private.staff_device_challenges SET
   operation_id=p_operation_id,entity_id=p_entity_id,
   financial_action=p_action,expected_revision=p_expected_revision,
   operation_payload=p_payload,operation_sha256=v_hash
 WHERE challenge_id=v_challenge AND workspace_id=p_workspace_id
   AND consumed_at IS NULL;
 IF NOT FOUND THEN
  RAISE EXCEPTION 'Challenge ledger not bound' USING ERRCODE='42501';
 END IF;
 RETURN v||pg_catalog.jsonb_build_object(
   'proof_version','marketpro-device-proof-purchase-v2',
   'operation_id',p_operation_id,'entity_id',p_entity_id,
   'financial_action',p_action,
   'expected_revision',p_expected_revision,'operation_sha256',v_hash
 );
END;
$issue$;
REVOKE ALL ON FUNCTION public.marketpro_staff_issue_purchase_challenge(
 uuid,uuid,uuid,uuid,text,bigint,jsonb) FROM PUBLIC,anon;
GRANT EXECUTE ON FUNCTION public.marketpro_staff_issue_purchase_challenge(
 uuid,uuid,uuid,uuid,text,bigint,jsonb) TO authenticated;

-- Service-only material retrieval rechecks ALL current V375 identity/key,
-- Owner lease, Auth session, phone replacement and challenge expiry guards.
CREATE OR REPLACE FUNCTION public.marketpro_service_purchase_proof_material(
 p_challenge_id uuid,p_staff_user_id uuid,p_session_id uuid
) RETURNS jsonb
LANGUAGE plpgsql VOLATILE SECURITY DEFINER SET search_path=''
AS $material$
DECLARE
 v jsonb;
 v_extra jsonb;
BEGIN
 v:=public.marketpro_service_device_challenge_material(
   p_challenge_id,p_staff_user_id,p_session_id);
 SELECT pg_catalog.jsonb_build_object(
   'proof_version','marketpro-device-proof-purchase-v2',
   'operation_id',c.operation_id,'entity_id',c.entity_id,
   'financial_action',c.financial_action,
   'expected_revision',c.expected_revision,
   'operation_sha256',c.operation_sha256,
   'operation_payload',c.operation_payload
 ) INTO v_extra
 FROM marketpro_private.staff_device_challenges c
 WHERE c.challenge_id=p_challenge_id AND c.staff_user_id=p_staff_user_id
   AND c.session_id=p_session_id AND c.purpose='write'
   AND c.operation_id IS NOT NULL AND c.entity_id IS NOT NULL
   AND c.operation_payload IS NOT NULL;
 IF v_extra IS NULL THEN
  RAISE EXCEPTION 'Unbound purchase challenge' USING ERRCODE='42501';
 END IF;
 RETURN v||v_extra;
END;
$material$;
REVOKE ALL ON FUNCTION public.marketpro_service_purchase_proof_material(
 uuid,uuid,uuid) FROM PUBLIC,anon,authenticated;
GRANT EXECUTE ON FUNCTION public.marketpro_service_purchase_proof_material(
 uuid,uuid,uuid) TO service_role;

-- ONLY the server-side Edge Function, after verifying P-256 over the v2
-- transcript, may call this service-role-only RPC. It atomically consumes
-- the one-time nonce AND performs the financial mutation in one transaction.
CREATE OR REPLACE FUNCTION public.marketpro_service_commit_signed_purchase(
 p_challenge_id uuid,p_staff_user_id uuid,p_session_id uuid,
 p_workspace_id uuid,p_device_id uuid,
 p_operation_id uuid,p_entity_id uuid,p_action text,
 p_expected_revision bigint,p_payload jsonb
) RETURNS jsonb
LANGUAGE plpgsql VOLATILE SECURITY DEFINER SET search_path=''
AS $commit$
DECLARE
 v_material jsonb;
 v_purchase public.purchases%ROWTYPE;
 v_audit marketpro_private.proof_bound_purchase_operations%ROWTYPE;
 v_date date;
 v_item uuid;
 v_qty bigint;
 v_price bigint;
 v_hash text;
 v_revision bigint;
 v_updated int;
 v_today date:=(clock_timestamp() AT TIME ZONE 'Asia/Colombo')::date;
BEGIN
 IF p_challenge_id IS NULL OR p_staff_user_id IS NULL OR
    p_session_id IS NULL OR p_workspace_id IS NULL OR
    p_device_id IS NULL OR p_operation_id IS NULL OR p_entity_id IS NULL
    OR p_payload IS NULL OR jsonb_typeof(p_payload)<>'object'
    OR octet_length(p_payload::text)>1024 OR
    p_action NOT IN('create','correct') OR p_action IS NULL THEN
  RAISE EXCEPTION 'Invalid signed purchase' USING ERRCODE='22023';
 END IF;
 -- SAME advisory lock as device revocation, phone replacement,
 -- fresh challenge issuance and financial operations.
 PERFORM pg_catalog.pg_advisory_xact_lock(
   pg_catalog.hashtextextended(p_workspace_id::text,0));
 v_material:=public.marketpro_service_purchase_proof_material(
    p_challenge_id,p_staff_user_id,p_session_id);
 v_hash:=pg_catalog.encode(extensions.digest(
   pg_catalog.convert_to(p_payload::text,'UTF8'),'sha256'),'hex');
 IF v_material->>'purpose'<>'write' OR
    v_material->>'proof_version'<>'marketpro-device-proof-purchase-v2' OR
    (v_material->>'workspace_id')::uuid<>p_workspace_id OR
    (v_material->>'device_id')::uuid<>p_device_id OR
    (v_material->>'staff_user_id')::uuid<>p_staff_user_id OR
    (v_material->>'session_id')::uuid<>p_session_id OR
    (v_material->>'operation_id')::uuid<>p_operation_id OR
    (v_material->>'entity_id')::uuid<>p_entity_id OR
    v_material->>'financial_action' IS DISTINCT FROM p_action OR
    (v_material->>'expected_revision')::bigint IS DISTINCT FROM p_expected_revision OR
    v_material->>'operation_sha256' IS DISTINCT FROM v_hash OR
    (v_material->'operation_payload') IS DISTINCT FROM p_payload THEN
  RAISE EXCEPTION 'Signed challenge is not for this exact transaction'
    USING ERRCODE='42501';
 END IF;
 IF (SELECT count(*) FROM jsonb_object_keys(p_payload))<>4 OR NOT (
    p_payload ? 'business_date' AND p_payload ? 'item_id' AND
    p_payload ? 'quantity_grams' AND p_payload ? 'price_cents_per_kg'
 ) THEN
  RAISE EXCEPTION 'Invalid financial payload' USING ERRCODE='22023';
 END IF;
 -- All fields have exact typed casts and DB constraints, not merely client UI.
 v_date:=(p_payload->>'business_date')::date;
 v_item:=(p_payload->>'item_id')::uuid;
 v_qty:=(p_payload->>'quantity_grams')::bigint;
 v_price:=(p_payload->>'price_cents_per_kg')::bigint;
 IF v_date IS NULL OR v_date<>v_today OR v_item IS NULL OR
    v_qty IS NULL OR v_price IS NULL OR
    v_qty NOT BETWEEN 0 AND 1000000000 OR
    v_price NOT BETWEEN 0 AND 1000000000 OR
    (p_action='create' AND p_expected_revision IS NOT NULL) OR
    (p_action='correct' AND
      (p_expected_revision IS NULL OR p_expected_revision<1)) THEN
  RAISE EXCEPTION 'Only server-today valid Staff purchases may be written'
    USING ERRCODE='42501';
 END IF;
 IF NOT EXISTS(
   SELECT 1 FROM public.items i
   WHERE i.workspace_id=p_workspace_id AND i.id=v_item
     AND i.deleted_at IS NULL
 ) THEN
  RAISE EXCEPTION 'Cloud item UUID does not belong to this workspace'
    USING ERRCODE='42501';
 END IF;

 SELECT * INTO v_audit FROM marketpro_private.proof_bound_purchase_operations
 WHERE operation_id=p_operation_id;
 IF FOUND THEN
   IF v_audit.workspace_id IS DISTINCT FROM p_workspace_id OR
      v_audit.staff_user_id IS DISTINCT FROM p_staff_user_id OR
      v_audit.device_id IS DISTINCT FROM p_device_id OR
      v_audit.session_id IS DISTINCT FROM p_session_id OR
      v_audit.entity_id IS DISTINCT FROM p_entity_id OR
      v_audit.action IS DISTINCT FROM p_action OR
      v_audit.expected_revision IS DISTINCT FROM p_expected_revision OR
      v_audit.payload_sha256 IS DISTINCT FROM v_hash THEN
     RAISE EXCEPTION 'Operation UUID reused with different purchase'
       USING ERRCODE='23505';
   END IF;
   v_revision:=v_audit.resulting_revision;
 ELSE
   SELECT * INTO v_purchase FROM public.purchases p
   WHERE p.workspace_id=p_workspace_id AND p.id=p_entity_id FOR UPDATE;
   IF p_action='create' THEN
     IF FOUND THEN RAISE EXCEPTION 'Purchase UUID already exists'
       USING ERRCODE='23505'; END IF;
     INSERT INTO public.purchases(
       id,workspace_id,business_date,item_id,quantity_grams,price_cents_per_kg
     ) VALUES(
       p_entity_id,p_workspace_id,v_date,v_item,v_qty,v_price
     ) RETURNING revision INTO v_revision;
   ELSE
     IF NOT FOUND OR v_purchase.deleted_at IS NOT NULL OR
       v_purchase.business_date<>v_date OR
       v_purchase.item_id<>v_item THEN
       RAISE EXCEPTION 'Immutable purchase business identity mismatch'
         USING ERRCODE='42501';
     END IF;
     IF v_purchase.revision<>p_expected_revision THEN
       RAISE EXCEPTION 'Stale purchase revision' USING ERRCODE='40001';
     END IF;
     UPDATE public.purchases SET quantity_grams=v_qty,
        price_cents_per_kg=v_price,
        revision=revision+1,updated_at=clock_timestamp()
     WHERE workspace_id=p_workspace_id AND id=p_entity_id
       AND revision=p_expected_revision
     RETURNING revision INTO v_revision;
     IF v_revision IS NULL THEN
        RAISE EXCEPTION 'Purchase changed while correcting' USING ERRCODE='40001';
     END IF;
   END IF;
   -- Share the global operation ledger with legacy finance endpoints.
   INSERT INTO public.sync_operations(
      operation_id,workspace_id,device_id,actor_user_id,
      entity_type,entity_id,operation_type,payload_hash
   ) VALUES(
      p_operation_id,p_workspace_id,p_device_id,p_staff_user_id,
      'purchase',p_entity_id,'upsert',v_hash
   );
   INSERT INTO marketpro_private.proof_bound_purchase_operations(
     operation_id,workspace_id,staff_user_id,device_id,session_id,
     entity_id,action,expected_revision,payload_sha256,resulting_revision
   ) VALUES(
     p_operation_id,p_workspace_id,p_staff_user_id,p_device_id,p_session_id,
     p_entity_id,p_action,p_expected_revision,v_hash,v_revision
   );
 END IF;

 UPDATE marketpro_private.staff_device_challenges
 SET consumed_at=clock_timestamp(),verified_at=clock_timestamp()
 WHERE challenge_id=p_challenge_id AND workspace_id=p_workspace_id
   AND staff_user_id=p_staff_user_id AND device_id=p_device_id
   AND session_id=p_session_id
   AND consumed_at IS NULL AND expires_at>clock_timestamp()
   AND operation_id=p_operation_id AND entity_id=p_entity_id;
 GET DIAGNOSTICS v_updated=ROW_COUNT;
 IF v_updated<>1 THEN
  RAISE EXCEPTION 'Already-consumed or expired financial proof'
    USING ERRCODE='42501';
 END IF;
 RETURN pg_catalog.jsonb_build_object(
   'accepted',true,'operation_id',p_operation_id,'revision',v_revision,
   'replayed',v_audit.operation_id IS NOT NULL,
   'proof_consumed_in_financial_transaction',true
 );
END;
$commit$;

REVOKE ALL ON FUNCTION public.marketpro_service_commit_signed_purchase(
 uuid,uuid,uuid,uuid,uuid,uuid,uuid,text,bigint,jsonb)
 FROM PUBLIC,anon,authenticated;
GRANT EXECUTE ON FUNCTION public.marketpro_service_commit_signed_purchase(
 uuid,uuid,uuid,uuid,uuid,uuid,uuid,text,bigint,jsonb) TO service_role;

-- CRITICAL HARD STOP: no Android bearer can bypass the device-signature
-- Edge gate by calling reviewed old V2 financial RPCs directly.
REVOKE EXECUTE ON FUNCTION public.marketpro_apply_team_purchase_change(
 uuid,uuid,uuid,uuid,text,bigint,jsonb) FROM PUBLIC,anon,authenticated;
REVOKE EXECUTE ON FUNCTION public.marketpro_apply_team_financial_change(
 uuid,uuid,uuid,text,uuid,text,bigint,jsonb) FROM PUBLIC,anon,authenticated;
REVOKE EXECUTE ON FUNCTION public.marketpro_apply_team_other_expense(
 uuid,uuid,uuid,uuid,text,bigint,jsonb) FROM PUBLIC,anon,authenticated;
COMMIT;
