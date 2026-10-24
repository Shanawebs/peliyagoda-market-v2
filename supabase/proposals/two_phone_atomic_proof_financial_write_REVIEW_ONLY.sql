-- MARKET PRO — TWO-PHONE V3.7.7 ATOMIC STAFF DEVICE PROOF + FINANCIAL WRITE
-- REVIEW ONLY. NOT LIVE. Apply only AFTER audited V352..V375 and
-- two_phone_other_expenses_REVIEW_ONLY. This REPLACES direct public Staff
-- business RPC privileges; legacy Owner marketpro_apply_change is UNTOUCHED.
-- Deployment MUST be a single reviewed cutover transaction.
BEGIN;

-- Staff MUST NOT bypass a fresh signed device challenge by calling older
-- SECURITY DEFINER v2 financial RPCs with a copied Staff bearer JWT.
REVOKE ALL ON FUNCTION public.marketpro_apply_team_purchase_change(
 uuid,uuid,uuid,uuid,text,bigint,jsonb) FROM PUBLIC,anon,authenticated;
REVOKE ALL ON FUNCTION public.marketpro_apply_team_financial_change(
 uuid,uuid,uuid,text,uuid,text,bigint,jsonb) FROM PUBLIC,anon,authenticated;
REVOKE ALL ON FUNCTION public.marketpro_apply_team_other_expense(
 uuid,uuid,uuid,uuid,text,bigint,jsonb) FROM PUBLIC,anon,authenticated;

-- Request commitment is issued under a CURRENT Staff JWT before Android
-- signs the device challenge. Prevents an intercepted valid signed nonce from
-- being reused with a different financial operation / amount / revision.
CREATE TABLE IF NOT EXISTS marketpro_private.staff_finance_challenge_requests (
 challenge_id uuid PRIMARY KEY REFERENCES
   marketpro_private.staff_device_challenges(challenge_id) ON DELETE CASCADE,
 request_sha256 text NOT NULL CHECK (request_sha256 ~ '^[0-9a-f]{64}$'),
 issued_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
REVOKE ALL ON marketpro_private.staff_finance_challenge_requests
 FROM PUBLIC,anon,authenticated;

CREATE OR REPLACE FUNCTION marketpro_private.finance_request_digest(
 p_workspace_id uuid,p_device_id uuid,p_staff_user_id uuid,
 p_operation_id uuid,p_entity_type text,p_entity_id uuid,
 p_action text,p_expected_revision bigint,p_payload jsonb
) RETURNS text
LANGUAGE sql IMMUTABLE SET search_path=''
AS $digest$
 SELECT pg_catalog.encode(extensions.digest(
    pg_catalog.convert_to(
      p_workspace_id::text || ':' || p_device_id::text || ':' ||
      p_staff_user_id::text || ':' || p_operation_id::text || ':' ||
      p_entity_type || ':' || p_entity_id::text || ':' || p_action || ':' ||
      COALESCE(p_expected_revision::text,'NULL') || ':' || p_payload::text,
      'UTF8'
    ),'sha256'
 ),'hex');
$digest$;
REVOKE ALL ON FUNCTION marketpro_private.finance_request_digest(
 uuid,uuid,uuid,uuid,text,uuid,text,bigint,jsonb)
 FROM PUBLIC,anon,authenticated;

-- Call only with the actual authenticated Staff JWT and enrolled device.
-- The underlying V375 issuer separately checks current Auth session, public
-- key approval, Staff role, Owner writer lease, and one-live-challenge lock.
CREATE OR REPLACE FUNCTION public.marketpro_staff_issue_finance_challenge(
 p_workspace_id uuid,p_device_id uuid,p_operation_id uuid,
 p_entity_type text,p_entity_id uuid,p_action text,
 p_expected_revision bigint,p_payload jsonb
) RETURNS jsonb
LANGUAGE plpgsql VOLATILE SECURITY DEFINER SET search_path=''
AS $issue_finance$
DECLARE
 v_staff uuid:=auth.uid();
 v_challenge jsonb;
 v_hash text;
 v_approved_fingerprint text;
BEGIN
 IF v_staff IS NULL OR p_workspace_id IS NULL OR p_device_id IS NULL
 OR p_operation_id IS NULL OR p_entity_id IS NULL
 OR p_entity_type NOT IN('purchase','purchase_addition','requisition',
    'daily_cash_received','daily_other_expenses')
 OR p_entity_type IS NULL OR p_action NOT IN('create','correct')
 OR p_action IS NULL OR p_payload IS NULL OR
 pg_catalog.jsonb_typeof(p_payload)<>'object' OR
 pg_catalog.octet_length(p_payload::text)>4096 OR
 (p_action='create' AND p_expected_revision IS NOT NULL) OR
 (p_action='correct' AND
     (p_expected_revision IS NULL OR p_expected_revision<1)) THEN
  RAISE EXCEPTION 'Invalid Staff finance intent' USING ERRCODE='22023';
 END IF;
 v_challenge:=public.marketpro_staff_issue_device_challenge(
   p_workspace_id,p_device_id,'write'
 );
 IF v_challenge->>'staff_user_id'<>v_staff::text OR
    v_challenge->>'device_id'<>p_device_id::text THEN
   RAISE EXCEPTION 'Signed session mismatched finance intent'
     USING ERRCODE='42501';
 END IF;
 v_hash:=marketpro_private.finance_request_digest(
   p_workspace_id,p_device_id,v_staff,p_operation_id,
   p_entity_type,p_entity_id,p_action,p_expected_revision,p_payload
 );
 INSERT INTO marketpro_private.staff_finance_challenge_requests(
   challenge_id,request_sha256
 ) VALUES((v_challenge->>'challenge_id')::uuid,v_hash);
 -- The phone may sign ONLY if its local AndroidKeyStore SPKI fingerprint
 -- is identical to the still Owner-approved public key in the private DB.
 -- This value is advisory for local matching; SQL/Edge independently
 -- recheck the actual approved SPKI on submit.
 SELECT k.fingerprint_sha256 INTO v_approved_fingerprint
 FROM marketpro_private.staff_device_public_keys k
 WHERE k.workspace_id=p_workspace_id AND k.staff_user_id=v_staff
   AND k.device_id=p_device_id AND k.approved AND k.revoked_at IS NULL;
 IF v_approved_fingerprint IS NULL THEN
    RAISE EXCEPTION 'Staff phone signing key is not approved'
       USING ERRCODE='42501';
 END IF;
 RETURN v_challenge || pg_catalog.jsonb_build_object(
   'key_fingerprint_sha256',v_approved_fingerprint
 );
END;
$issue_finance$;
REVOKE ALL ON FUNCTION public.marketpro_staff_issue_finance_challenge(
 uuid,uuid,uuid,text,uuid,text,bigint,jsonb) FROM PUBLIC,anon;
GRANT EXECUTE ON FUNCTION public.marketpro_staff_issue_finance_challenge(
 uuid,uuid,uuid,text,uuid,text,bigint,jsonb) TO authenticated;

-- This function is the *only* service-role entry point for Staff writes.
-- The authenticated Edge verifier must validate the actual P-256 signature
-- using the previously Owner-approved SPKI before invoking this RPC.
-- Signature validation alone never grants access: this function atomically
-- consumes ONE fresh challenge and applies ONE checked financial operation
-- in the same DB transaction. An exception rolls back BOTH effects.
CREATE OR REPLACE FUNCTION public.marketpro_service_apply_proof_bound_finance(
 p_challenge_id uuid,
 p_staff_user_id uuid,
 p_session_id uuid,
 p_operation_id uuid,
 p_entity_type text,
 p_entity_id uuid,
 p_action text,
 p_expected_revision bigint,
 p_payload jsonb
) RETURNS jsonb
LANGUAGE plpgsql VOLATILE SECURITY DEFINER SET search_path=''
AS $atomic$
DECLARE
 v_material jsonb;
 v_workspace uuid;
 v_device uuid;
 v_proof jsonb;
 v_written jsonb;
 v_old_sub text;
 v_old_claims text;
BEGIN
 IF p_challenge_id IS NULL OR p_staff_user_id IS NULL OR
    p_session_id IS NULL OR p_operation_id IS NULL OR
    p_entity_id IS NULL OR p_action NOT IN ('create','correct') OR
    p_action IS NULL OR
    p_entity_type NOT IN (
      'purchase','purchase_addition','requisition',
      'daily_cash_received','daily_other_expenses'
    ) OR p_entity_type IS NULL OR p_payload IS NULL OR
    pg_catalog.jsonb_typeof(p_payload)<>'object' OR
    pg_catalog.octet_length(p_payload::text)>4096 THEN
  RAISE EXCEPTION 'Invalid signed finance request'
    USING ERRCODE='22023';
 END IF;

 -- The same workspace lock is held by challenge issuance, Owner phone
 -- revoke/replacement, Staff leases and all legacy v2 write RPCs.
 -- The first lookup is only used to discover which workspace to lock.
 SELECT c.workspace_id INTO v_workspace
 FROM marketpro_private.staff_device_challenges c
 WHERE c.challenge_id=p_challenge_id
   AND c.staff_user_id=p_staff_user_id AND c.session_id=p_session_id;
 IF v_workspace IS NULL THEN
   RAISE EXCEPTION 'Missing or unrelated Staff proof' USING ERRCODE='42501';
 END IF;
 PERFORM pg_catalog.pg_advisory_xact_lock(
   pg_catalog.hashtextextended(v_workspace::text,0)
 );

 -- Obtain the *current* approved key/registered active device/session/lease
 -- under the workspace transaction lock (including current auth.sessions).
 v_material:=public.marketpro_service_device_challenge_material(
   p_challenge_id,p_staff_user_id,p_session_id
 );
 IF v_material->>'purpose'<>'write' OR
    v_material->>'workspace_id'<>v_workspace::text OR
    v_material->>'staff_user_id'<>p_staff_user_id::text OR
    v_material->>'session_id'<>p_session_id::text THEN
   RAISE EXCEPTION 'Device challenge does not authorize this operation'
      USING ERRCODE='42501';
 END IF;
 v_device:=(v_material->>'device_id')::uuid;

 -- The Staff JWT (not a caller-chosen account) committed to these EXACT
 -- server-side financial fields BEFORE the device signed this challenge.
 -- Reject changed quantities, amounts, business date, UUID, or revision.
 IF NOT EXISTS (
   SELECT 1 FROM marketpro_private.staff_finance_challenge_requests f
   WHERE f.challenge_id=p_challenge_id AND
     f.request_sha256=marketpro_private.finance_request_digest(
       v_workspace,v_device,p_staff_user_id,p_operation_id,p_entity_type,
       p_entity_id,p_action,p_expected_revision,p_payload
     )
 ) THEN
   RAISE EXCEPTION 'Signed proof is not bound to this financial request'
     USING ERRCODE='42501';
 END IF;

 -- Consume and store proof INSIDE the financial transaction. If the
 -- financial write fails/revisions conflict, the nonce remains unconsumed
 -- because PostgreSQL rolls back the entire transaction.
 v_proof:=public.marketpro_service_consume_device_challenge(
   p_challenge_id,p_staff_user_id,p_session_id
 );
 IF v_proof->>'proof_verified_once'<>'true' OR
    v_proof->>'device_id'<>v_device::text OR
    v_proof->>'business_write_permission_granted'<>'false' THEN
   RAISE EXCEPTION 'Invalid atomic proof consume'
     USING ERRCODE='42501';
 END IF;

 -- Nested existing finance RPCs use auth.uid()/auth.jwt(). This restricted
 -- service-only function sets a verified identity into LOCAL PostgreSQL GUCs
 -- solely for this call, instead of accepting arbitrary Android claims.
 -- The DB has already checked current Auth session, enrolled Staff account,
 -- enabled physical device, private Owner lease and server proof.
 v_old_sub:=pg_catalog.current_setting('request.jwt.claim.sub',true);
 v_old_claims:=pg_catalog.current_setting('request.jwt.claims',true);
 PERFORM pg_catalog.set_config('request.jwt.claim.sub',
   p_staff_user_id::text,true);
 PERFORM pg_catalog.set_config('request.jwt.claims',
   pg_catalog.jsonb_build_object(
     'sub',p_staff_user_id,'session_id',p_session_id,
     'role','authenticated','is_anonymous',false
   )::text,true);

 IF p_entity_type='purchase' THEN
   v_written:=public.marketpro_apply_team_purchase_change(
     v_workspace,v_device,p_operation_id,p_entity_id,p_action,
     p_expected_revision,p_payload
   );
 ELSIF p_entity_type='daily_other_expenses' THEN
   v_written:=public.marketpro_apply_team_other_expense(
     v_workspace,v_device,p_operation_id,p_entity_id,p_action,
     p_expected_revision,p_payload
   );
 ELSE
   v_written:=public.marketpro_apply_team_financial_change(
     v_workspace,v_device,p_operation_id,p_entity_type,p_entity_id,
     p_action,p_expected_revision,p_payload
   );
 END IF;

 -- Roll back if an RPC responds without the expected immutable ACK.
 IF v_written->>'accepted'<>'true' OR
    v_written->>'operation_id'<>p_operation_id::text OR
    coalesce((v_written->>'revision')::bigint,0)<1 THEN
   RAISE EXCEPTION 'Financial change was not durably acknowledged'
      USING ERRCODE='40001';
 END IF;

 PERFORM pg_catalog.set_config('request.jwt.claim.sub',
   coalesce(v_old_sub,''),true);
 PERFORM pg_catalog.set_config('request.jwt.claims',
   coalesce(v_old_claims,''),true);

 RETURN pg_catalog.jsonb_build_object(
   'accepted',true,'operation_id',p_operation_id,
   'entity_id',p_entity_id,'entity_type',p_entity_type,
   'revision',(v_written->>'revision')::bigint,
   'replayed',coalesce((v_written->>'replayed')::boolean,false),
   'challenge_consumed',true
 );
END;
$atomic$;

REVOKE ALL ON FUNCTION public.marketpro_service_apply_proof_bound_finance(
 uuid,uuid,uuid,uuid,text,uuid,text,bigint,jsonb)
 FROM PUBLIC,anon,authenticated;
GRANT EXECUTE ON FUNCTION public.marketpro_service_apply_proof_bound_finance(
 uuid,uuid,uuid,uuid,text,uuid,text,bigint,jsonb) TO service_role;

-- Critical invariant: leave Owner's installed V1 writer intact.
-- marketpro_apply_change(UUID,UUID,UUID,text,UUID,text,jsonb) unchanged.
COMMIT;
