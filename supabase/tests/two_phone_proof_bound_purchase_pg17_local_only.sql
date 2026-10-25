-- ONE USE DEVICE PROOF MUST COMMIT THE EXACT FINANCIAL PURCHASE.
-- Disposable PostgreSQL 17 only, NEVER RUN ON LIVE SUPABASE.
\set ON_ERROR_STOP on
\i supabase/tests/v375_staff_device_challenge_pg17_local_only.sql

-- V375 regression ends with the Staff Auth session deleted/lease disabled.
-- Re-enable only test accounts, never live sessions or keys.
CREATE TABLE public.workspaces(id uuid PRIMARY KEY);
INSERT INTO public.workspaces VALUES(
 '11111111-1111-4111-8111-111111111111');
\i supabase/proposals/two_phone_other_expenses_REVIEW_ONLY.sql
\i supabase/proposals/two_phone_proof_bound_purchase_REVIEW_ONLY.sql
INSERT INTO public.items(id,workspace_id) VALUES(
 '77777777-7777-4777-8777-777777777777',
 '11111111-1111-4111-8111-111111111111');
INSERT INTO auth.sessions(id,user_id,not_after) VALUES(
 '91111111-1111-4111-8111-111111111111',
 '33333333-3333-4333-8333-333333333333',NULL);
UPDATE marketpro_private.staff_write_leases SET enabled=true,revoked_at=NULL
 WHERE workspace_id='11111111-1111-4111-8111-111111111111';

DO $run$
DECLARE
 w uuid:='11111111-1111-4111-8111-111111111111';
 staff uuid:='33333333-3333-4333-8333-333333333333';
 sid uuid:='91111111-1111-4111-8111-111111111111';
 phone uuid:='f1111111-1111-4111-8111-111111111111';
 pid uuid:='c1111111-1111-4111-8111-111111111111';
 op uuid:='d1111111-1111-4111-8111-111111111111';
 op2 uuid:='d2222222-2222-4222-8222-222222222222';
 today date:=(clock_timestamp() AT TIME ZONE 'Asia/Colombo')::date;
 payload jsonb;
 changed jsonb;
 old_date jsonb;
 issued jsonb;
 material jsonb;
 r jsonb;
 denied boolean;
BEGIN
 PERFORM set_config('request.jwt.claim.sub',staff::text,true);
 PERFORM set_config('request.jwt.claims',
  pg_catalog.jsonb_build_object('is_anonymous',false,'session_id',sid)::text,true);
 payload:=pg_catalog.jsonb_build_object(
  'business_date',today,'item_id','77777777-7777-4777-8777-777777777777',
  'quantity_grams',2500,'price_cents_per_kg',52000);
 issued:=public.marketpro_staff_issue_purchase_challenge(
   w,phone,op,pid,'create',NULL,payload);
 IF issued->>'purpose'<>'write' OR
    issued->>'financial_action'<>'create' OR
    issued->>'operation_id'<>op::text OR
    issued->>'proof_version'<>'marketpro-device-proof-purchase-v2'
 THEN RAISE EXCEPTION 'Signed purchase context did not bind to one mutation'; END IF;
 material:=public.marketpro_service_purchase_proof_material(
    (issued->>'challenge_id')::uuid,staff,sid);
 IF material->>'operation_sha256' !~ '^[0-9a-f]{64}$'
 OR material->'operation_payload' IS DISTINCT FROM payload
 OR material->>'operation_id'<>op::text
 THEN RAISE EXCEPTION 'Untrusted purchase operation context'; END IF;

 -- A valid proof CANNOT be redeemed for a larger price or new target UUID.
 denied:=false;
 BEGIN
  PERFORM public.marketpro_service_commit_signed_purchase(
    (issued->>'challenge_id')::uuid,staff,sid,w,phone,op,pid,
    'create',NULL,
    pg_catalog.jsonb_set(payload,'{price_cents_per_kg}','999999'::jsonb));
 EXCEPTION WHEN SQLSTATE '42501' THEN denied:=true; END;
 IF NOT denied THEN RAISE EXCEPTION 'Changed price accepted after challenge'; END IF;
 denied:=false;
 BEGIN
  PERFORM public.marketpro_service_commit_signed_purchase(
    (issued->>'challenge_id')::uuid,staff,sid,w,phone,op,
    'c9999999-9999-4999-8999-999999999999'::uuid,'create',NULL,payload);
 EXCEPTION WHEN SQLSTATE '42501' THEN denied:=true; END;
 IF NOT denied THEN RAISE EXCEPTION 'Changed financial record UUID accepted'; END IF;
 IF (SELECT COUNT(*) FROM public.purchases WHERE id=pid)<>0
 THEN RAISE EXCEPTION 'Invalid proof mutated purchases'; END IF;

 r:=public.marketpro_service_commit_signed_purchase(
    (issued->>'challenge_id')::uuid,staff,sid,w,phone,op,pid,
    'create',NULL,payload);
 IF r->>'accepted'<>'true' OR r->>'revision'<>'1'
 OR r->>'proof_consumed_in_financial_transaction'<>'true'
 THEN RAISE EXCEPTION 'Valid purchase+nonce atomic commit failed'; END IF;
 IF (SELECT count(*) FROM marketpro_private.proof_bound_purchase_operations)<>1
 OR (SELECT count(*) FROM public.sync_operations WHERE operation_id=op)<>1
 THEN RAISE EXCEPTION 'Idempotency ledgers not atomic'; END IF;
 denied:=false;
 BEGIN
  PERFORM public.marketpro_service_commit_signed_purchase(
    (issued->>'challenge_id')::uuid,staff,sid,w,phone,op,pid,
    'create',NULL,payload);
 EXCEPTION WHEN SQLSTATE '42501' THEN denied:=true; END;
 IF NOT denied THEN RAISE EXCEPTION 'Same nonce spent twice'; END IF;

 -- Lost-ACK network retry must sign a NEW nonce for SAME operation ID,
 -- but never repeat the financial write or increment purchase revision.
 issued:=public.marketpro_staff_issue_purchase_challenge(
   w,phone,op,pid,'create',NULL,payload);
 r:=public.marketpro_service_commit_signed_purchase(
    (issued->>'challenge_id')::uuid,staff,sid,w,phone,op,pid,
    'create',NULL,payload);
 IF r->>'replayed'<>'true' OR r->>'revision'<>'1'
 OR (SELECT revision FROM public.purchases WHERE id=pid)<>1
 THEN RAISE EXCEPTION 'Signed idempotent retry repeated purchase mutation'; END IF;

 -- Unlimited corrections today, but every correction requires exact revision.
 changed:=pg_catalog.jsonb_set(payload,'{quantity_grams}','3000'::jsonb);
 issued:=public.marketpro_staff_issue_purchase_challenge(
   w,phone,op2,pid,'correct',1,changed);
 r:=public.marketpro_service_commit_signed_purchase(
   (issued->>'challenge_id')::uuid,staff,sid,w,phone,op2,pid,
   'correct',1,changed);
 IF r->>'revision'<>'2' OR
    (SELECT quantity_grams FROM public.purchases WHERE id=pid)<>3000
 THEN RAISE EXCEPTION 'Valid Staff same-day correction failed'; END IF;

 -- Yesterday can be read by Staff but never created/changed by them.
 old_date:=pg_catalog.jsonb_set(payload,'{business_date}',
    to_jsonb(today-1));
 issued:=public.marketpro_staff_issue_purchase_challenge(
   w,phone,extensions.gen_random_uuid(),extensions.gen_random_uuid(),
   'create',NULL,old_date);
 denied:=false;
 BEGIN
  PERFORM public.marketpro_service_commit_signed_purchase(
   (issued->>'challenge_id')::uuid,staff,sid,w,phone,
   (issued->>'operation_id')::uuid,(issued->>'entity_id')::uuid,
   'create',NULL,old_date);
 EXCEPTION WHEN SQLSTATE '42501' THEN denied:=true; END;
 IF NOT denied THEN RAISE EXCEPTION 'Staff old-day purchase accepted'; END IF;

 -- Emergency Owner staff lease revoke must immediately prevent commit.
 issued:=public.marketpro_staff_issue_purchase_challenge(
   w,phone,extensions.gen_random_uuid(),extensions.gen_random_uuid(),
   'create',NULL,payload);
 UPDATE marketpro_private.staff_write_leases SET enabled=false
 WHERE workspace_id=w;
 denied:=false;
 BEGIN
  PERFORM public.marketpro_service_purchase_proof_material(
   (issued->>'challenge_id')::uuid,staff,sid);
 EXCEPTION WHEN SQLSTATE '42501' THEN denied:=true; END;
 IF NOT denied THEN RAISE EXCEPTION 'Disabled Staff writer retained nonce'; END IF;
END;
$run$;

DO $acl$
BEGIN
 IF has_function_privilege('authenticated',
    'public.marketpro_service_commit_signed_purchase(uuid,uuid,uuid,uuid,uuid,uuid,uuid,text,bigint,jsonb)','EXECUTE')
 OR has_function_privilege('authenticated',
    'public.marketpro_service_purchase_proof_material(uuid,uuid,uuid)','EXECUTE')
 OR has_function_privilege('authenticated',
    'public.marketpro_apply_team_purchase_change(uuid,uuid,uuid,uuid,text,bigint,jsonb)','EXECUTE')
 OR has_function_privilege('authenticated',
    'public.marketpro_apply_team_financial_change(uuid,uuid,uuid,text,uuid,text,bigint,jsonb)','EXECUTE')
 OR has_function_privilege('authenticated',
    'public.marketpro_apply_team_other_expense(uuid,uuid,uuid,uuid,text,bigint,jsonb)','EXECUTE')
 OR NOT has_function_privilege('service_role',
    'public.marketpro_service_commit_signed_purchase(uuid,uuid,uuid,uuid,uuid,uuid,uuid,text,bigint,jsonb)','EXECUTE')
 THEN RAISE EXCEPTION 'Unverified bearer token still accesses direct finance RPC'; END IF;
END;
$acl$;
\echo PURCHASE SIGNATURE: exact request, staff revoked, atomic nonce/write, replay and ACL verified
