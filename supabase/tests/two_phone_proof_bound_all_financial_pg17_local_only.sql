-- REQUISITION + PURCHASE ADDITION + CASH + OTHER EXPENSES
-- One-use, exactly-bound Staff financial signature, local PG17 ONLY.
\set ON_ERROR_STOP on
\i supabase/tests/two_phone_proof_bound_purchase_pg17_local_only.sql
\i supabase/proposals/two_phone_proof_bound_all_financial_REVIEW_ONLY.sql
-- Each prior fixture already verified its own financial rows. This is a
-- THROWAWAY PG17 database, not Supabase: isolate v3 create/unique-key tests
-- from older fixture's cash/expense/requisition business dates.
TRUNCATE public.requisitions,public.purchase_additions,
         public.daily_cash_received,public.daily_other_expenses;
-- Prior suite ended by disabling Staff lease. Test-only reset.
UPDATE marketpro_private.staff_write_leases
 SET enabled=true,revoked_at=NULL
 WHERE workspace_id='11111111-1111-4111-8111-111111111111';

DO $finance$
DECLARE
 w uuid:='11111111-1111-4111-8111-111111111111';
 u uuid:='33333333-3333-4333-8333-333333333333';
 sid uuid:='91111111-1111-4111-8111-111111111111';
 phone uuid:='f1111111-1111-4111-8111-111111111111';
 today date:=(clock_timestamp() AT TIME ZONE 'Asia/Colombo')::date;
 typ text;
 obj uuid;
 op uuid;
 op2 uuid;
 p jsonb;
 edited jsonb;
 wrong jsonb;
 issued jsonb;
 reply jsonb;
 info jsonb;
 denied boolean;
 actual_rev bigint;
 before_count bigint;
BEGIN
 PERFORM set_config('request.jwt.claim.sub',u::text,true);
 PERFORM set_config('request.jwt.claims',
  pg_catalog.jsonb_build_object('is_anonymous',false,'session_id',sid)::text,true);
 FOR typ IN SELECT unnest(ARRAY[
  'requisition','purchase_addition','daily_cash_received','daily_other_expenses'])
 LOOP
  obj:=extensions.gen_random_uuid();
  op:=extensions.gen_random_uuid();
  op2:=extensions.gen_random_uuid();
  p:=CASE typ
   WHEN 'requisition' THEN pg_catalog.jsonb_build_object(
     'business_date',today,'item_id','77777777-7777-4777-8777-777777777777',
     'kitchen','Main Kitchen','quantity_grams',1200)
   WHEN 'purchase_addition' THEN pg_catalog.jsonb_build_object(
     'business_date',today,'item_id','77777777-7777-4777-8777-777777777777',
     'quantity_grams',500,'price_cents_per_kg',68000)
   WHEN 'daily_cash_received' THEN pg_catalog.jsonb_build_object(
     'business_date',today,'amount_cents',1223400)
   WHEN 'daily_other_expenses' THEN pg_catalog.jsonb_build_object(
     'business_date',today,'transport_cents',50000,
     'labor_charge_cents',22000,'meals_drinks_cents',12345,
     'others_cents',3000,'remarks','Evening expenses')
  END;
  issued:=public.marketpro_staff_issue_financial_challenge(
     w,phone,op,typ,obj,'create',NULL,p);
  IF issued->>'proof_version'<>'marketpro-device-proof-financial-v3' OR
     issued->>'entity_type'<>typ OR
     issued->>'operation_id'<>op::text THEN
    RAISE EXCEPTION 'Financial challenge omitted signed transaction identity for %',typ;
  END IF;
  info:=public.marketpro_service_financial_proof_material(
    (issued->>'challenge_id')::uuid,u,sid);
  IF info->>'entity_type'<>typ OR info->'operation_payload'<>p THEN
   RAISE EXCEPTION 'Financial proof did not bind payload for %',typ; END IF;

  -- Tampering with amount or financial TYPE before commit is always denied.
  wrong:=pg_catalog.jsonb_set(p,
     CASE typ WHEN 'requisition' THEN '{quantity_grams}'::text[]
              WHEN 'purchase_addition' THEN '{quantity_grams}'::text[]
              WHEN 'daily_cash_received' THEN '{amount_cents}'::text[]
              ELSE '{transport_cents}'::text[] END,
     '9999'::jsonb);
  denied:=false;
  BEGIN
   PERFORM public.marketpro_service_commit_signed_financial(
      (issued->>'challenge_id')::uuid,u,sid,w,phone,op,typ,obj,
      'create',NULL,wrong);
  EXCEPTION WHEN SQLSTATE '42501' THEN denied:=true; END;
  IF NOT denied THEN RAISE EXCEPTION 'Changed signed amount written for %',typ; END IF;
  denied:=false;
  BEGIN
   PERFORM public.marketpro_service_commit_signed_financial(
      (issued->>'challenge_id')::uuid,u,sid,w,phone,op,
      CASE WHEN typ='daily_cash_received' THEN 'requisition'
           ELSE 'daily_cash_received' END,obj,
      'create',NULL,p);
  EXCEPTION WHEN SQLSTATE '42501' THEN denied:=true; END;
  IF NOT denied THEN
   RAISE EXCEPTION 'Signature type confusion for %',typ; END IF;

  reply:=public.marketpro_service_commit_signed_financial(
    (issued->>'challenge_id')::uuid,u,sid,w,phone,op,typ,obj,'create',NULL,p);
  IF reply->>'accepted'<>'true' OR reply->>'revision'<>'1'
     OR reply->>'proof_consumed_in_financial_transaction'<>'true' THEN
    RAISE EXCEPTION 'Signed Staff finance create failed for %',typ; END IF;
  -- In-request idempotent replay must be impossible with the same nonce.
  denied:=false;
  BEGIN
   PERFORM public.marketpro_service_commit_signed_financial(
    (issued->>'challenge_id')::uuid,u,sid,w,phone,op,typ,obj,'create',NULL,p);
  EXCEPTION WHEN SQLSTATE '42501' THEN denied:=true; END;
  IF NOT denied THEN RAISE EXCEPTION 'Single-use nonce replay for %',typ; END IF;

  -- Another signed challenge for same op is idempotent, no double totals.
  issued:=public.marketpro_staff_issue_financial_challenge(
    w,phone,op,typ,obj,'create',NULL,p);
  reply:=public.marketpro_service_commit_signed_financial(
    (issued->>'challenge_id')::uuid,u,sid,w,phone,op,typ,obj,'create',NULL,p);
  IF reply->>'replayed'<>'true' OR reply->>'revision'<>'1' THEN
   RAISE EXCEPTION 'Lost ACK repeated finance write for %',typ; END IF;

  edited:=CASE typ
   WHEN 'requisition' THEN pg_catalog.jsonb_set(p,'{quantity_grams}','1300'::jsonb)
   WHEN 'purchase_addition' THEN pg_catalog.jsonb_set(p,'{price_cents_per_kg}','70000'::jsonb)
   WHEN 'daily_cash_received' THEN pg_catalog.jsonb_set(p,'{amount_cents}','1333400'::jsonb)
   ELSE pg_catalog.jsonb_set(p,'{labor_charge_cents}','24000'::jsonb)
  END;
  issued:=public.marketpro_staff_issue_financial_challenge(
    w,phone,op2,typ,obj,'correct',1,edited);
  reply:=public.marketpro_service_commit_signed_financial(
    (issued->>'challenge_id')::uuid,u,sid,w,phone,op2,typ,obj,'correct',1,edited);
  IF reply->>'revision'<>'2' THEN
    RAISE EXCEPTION 'Unlimited same-day correction failed for %',typ; END IF;

  -- Staff may never correct past days (before and after midnight).
  issued:=public.marketpro_staff_issue_financial_challenge(
    w,phone,extensions.gen_random_uuid(),typ,extensions.gen_random_uuid(),
    'create',NULL,pg_catalog.jsonb_set(
      p,'{business_date}',to_jsonb(today-1)));
  denied:=false;
  BEGIN
   PERFORM public.marketpro_service_commit_signed_financial(
      (issued->>'challenge_id')::uuid,u,sid,w,phone,
      (issued->>'operation_id')::uuid,typ,(issued->>'entity_id')::uuid,
      'create',NULL,pg_catalog.jsonb_set(
      p,'{business_date}',to_jsonb(today-1)));
  EXCEPTION WHEN SQLSTATE '42501' THEN denied:=true; END;
  IF NOT denied THEN
   RAISE EXCEPTION 'Old-day Staff financial write accepted for %',typ; END IF;
 END LOOP;

 IF (SELECT count(*) FROM marketpro_private.proof_bound_financial_operations)<>8
    OR (SELECT count(*) FROM marketpro_private.staff_device_challenges
        WHERE financial_entity_type IS NOT NULL AND verified_at IS NOT NULL)<>12 THEN
   -- Four create, four identical operation retries, four corrections.
   RAISE EXCEPTION 'Signed finance audits mismatched'; END IF;

 -- A revoked physical phone must lose proof even with an earlier valid nonce.
 issued:=public.marketpro_staff_issue_financial_challenge(
   w,phone,extensions.gen_random_uuid(),'daily_cash_received',
   extensions.gen_random_uuid(),'create',NULL,
   pg_catalog.jsonb_build_object('business_date',today,'amount_cents',100));
 UPDATE public.registered_devices SET enabled=false WHERE id=phone;
 denied:=false;
 BEGIN
   PERFORM public.marketpro_service_financial_proof_material(
     (issued->>'challenge_id')::uuid,u,sid);
 EXCEPTION WHEN SQLSTATE '42501' THEN denied:=true; END;
 IF NOT denied THEN RAISE EXCEPTION 'Revoked phone retained finance proof'; END IF;
END;
$finance$;

DO $acl$
BEGIN
 IF has_function_privilege('authenticated',
   'public.marketpro_service_financial_proof_material(uuid,uuid,uuid)','EXECUTE')
 OR has_function_privilege('authenticated',
   'public.marketpro_service_commit_signed_financial(uuid,uuid,uuid,uuid,uuid,uuid,text,uuid,text,bigint,jsonb)','EXECUTE')
 OR has_function_privilege('authenticated',
   'public.marketpro_apply_team_financial_change(uuid,uuid,uuid,text,uuid,text,bigint,jsonb)','EXECUTE')
 OR has_function_privilege('authenticated',
   'public.marketpro_apply_team_other_expense(uuid,uuid,uuid,uuid,text,bigint,jsonb)','EXECUTE')
 OR NOT has_function_privilege('service_role',
   'public.marketpro_service_commit_signed_financial(uuid,uuid,uuid,uuid,uuid,uuid,text,uuid,text,bigint,jsonb)','EXECUTE') THEN
  RAISE EXCEPTION 'Unverified staff direct finance RPC access present'; END IF;
END;
$acl$;
\echo 4 FINANCIAL TYPES: signed operations, revisions, idempotency, revoked phone PASS
