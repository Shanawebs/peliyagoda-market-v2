\set ON_ERROR_STOP on
-- Disposable PG17 database only: full Owner/Team phone baseline, no hosted data.
\i supabase/tests/marketpro_cloud_sync_pg17_v1.sql
CREATE SCHEMA extensions;
CREATE EXTENSION pgcrypto WITH SCHEMA extensions;
-- No new Owner or Staff auth records: reuse exact preflight fixture.
\i supabase/staging/marketpro_qa_finance_sync_v1_REVIEW_ONLY.sql

DO $baseline$ BEGIN
 IF (SELECT count(*) FROM marketpro_private.qa_finance_sync_v1_gate WHERE enabled)<>0
 OR (SELECT count(*) FROM marketpro_private.qa_finance_sync_receipts_v1)<>0
 OR has_function_privilege('anon',
  'public.marketpro_qa_finance_apply_v1(uuid,uuid,uuid,text,uuid,bigint,jsonb)','EXECUTE')
 THEN RAISE EXCEPTION 'Finance is not OFF by default';END IF;
END;$baseline$;

-- Unique day catalog in disposable fixture only.
INSERT INTO public.categories(
 id,workspace_id,name,name_key,revision,updated_at,created_at)
VALUES('aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa',
 'af77cb66-76b2-492a-a460-06132f468713','Vegetables','vegetables',1,now(),now());
INSERT INTO public.items(
 id,workspace_id,category_id,name,name_key,is_active,revision,updated_at,created_at)
VALUES('bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb',
 'af77cb66-76b2-492a-a460-06132f468713',
 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa','Carrot','carrot',true,1,now(),now());

-- Reuse Owner phone active from previous QA tests. Previous Team phone revoked.
SELECT set_config('request.jwt.claim.sub','98c3b101-d452-4b6b-902c-0b1a31fc340c',false);
SELECT set_config('request.jwt.claims',
 '{"sub":"98c3b101-d452-4b6b-902c-0b1a31fc340c","session_id":"11111111-1111-4111-8111-111111111111","aal":"aal2"}',false);

DO $closed$DECLARE payload jsonb:=jsonb_build_object(
 'business_date',(clock_timestamp() AT TIME ZONE 'Asia/Colombo')::date,
 'amount_cents',1000);
BEGIN
 BEGIN
  PERFORM public.marketpro_qa_finance_apply_v1(
   'af77cb66-76b2-492a-a460-06132f468713',
   '33333333-3333-4333-8333-333333333333',
   '66666666-6666-4666-8666-666666666666','daily_cash_received',
   '77777777-7777-4777-8777-777777777777',0,payload);
  RAISE EXCEPTION 'Finance gate disabled but writes succeeded';
 EXCEPTION WHEN SQLSTATE '42501' THEN NULL;END;
 IF (SELECT count(*) FROM public.daily_cash_received)<>0 THEN
  RAISE EXCEPTION 'Closed gate changed finance';END IF;
END;$closed$;

-- Only in disposable PostgreSQL: enable Staging Finance gate for unit testing.
UPDATE marketpro_private.qa_finance_sync_v1_gate SET enabled=true;
DO $owner$
DECLARE
 w uuid:='af77cb66-76b2-492a-a460-06132f468713';
 d uuid:='33333333-3333-4333-8333-333333333333';
 op uuid:='66666666-6666-4666-8666-666666666666';
 c uuid:='77777777-7777-4777-8777-777777777777';
 today date:=(clock_timestamp() AT TIME ZONE 'Asia/Colombo')::date;
 old_day date:=((clock_timestamp() AT TIME ZONE 'Asia/Colombo')::date-1);
 a jsonb; b jsonb; r jsonb;
BEGIN
 a:=jsonb_build_object('business_date',today,'amount_cents',1000);
 r:=public.marketpro_qa_finance_apply_v1(w,d,op,'daily_cash_received',c,0,a);
 IF r->>'accepted'<>'true' OR r->>'revision'<>'1' THEN
  RAISE EXCEPTION 'Cash creation failed';END IF;
 r:=public.marketpro_qa_finance_apply_v1(w,d,op,'daily_cash_received',c,0,a);
 IF r->>'replayed'<>'true' OR r->>'revision'<>'1' THEN
  RAISE EXCEPTION 'Idempotent replay did not return original receipt';END IF;
 BEGIN
  PERFORM public.marketpro_qa_finance_apply_v1(w,d,op,'daily_cash_received',
   c,0,jsonb_build_object('business_date',today,'amount_cents',999));
  RAISE EXCEPTION 'Reused UUID with altered payload accepted';
 EXCEPTION WHEN unique_violation THEN NULL;END;
 BEGIN
  PERFORM public.marketpro_qa_finance_apply_v1(w,d,
   '66666666-6666-4666-8666-666666666667','daily_cash_received',
   c,0,a);
  RAISE EXCEPTION 'Stale create overwrote cash';
 EXCEPTION WHEN serialization_failure THEN NULL;END;
 b:=jsonb_build_object('business_date',today,'amount_cents',2000);
 r:=public.marketpro_qa_finance_apply_v1(w,d,
   '66666666-6666-4666-8666-666666666668','daily_cash_received',c,1,b);
 IF r->>'revision'<>'2' THEN
  RAISE EXCEPTION 'Cash correction revision invalid';END IF;
 BEGIN
  PERFORM public.marketpro_qa_finance_apply_v1(w,d,
   '66666666-6666-4666-8666-666666666669','daily_cash_received',c,1,b);
  RAISE EXCEPTION 'Same stale correction overwrote newer data';
 EXCEPTION WHEN serialization_failure THEN NULL;END;

 -- Owner can create/edit historical day.
 r:=public.marketpro_qa_finance_apply_v1(w,d,
 '66666666-6666-4666-8666-666666666670','purchase',
 '88888888-8888-4888-8888-888888888888',0,
 jsonb_build_object('business_date',old_day,
 'item_id','bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb',
 'quantity_grams',100,'price_cents_per_kg',300));
 IF r->>'revision'<>'1' THEN RAISE EXCEPTION 'Owner old-day purchase denied';END IF;
END;$owner$;

-- Team phone was revoked in the v1 fixture. It must not sync.
SELECT set_config('request.jwt.claim.sub','b805598b-421f-447b-aa4c-ffb66f721338',false);
SELECT set_config('request.jwt.claims',
 '{"sub":"b805598b-421f-447b-aa4c-ffb66f721338","session_id":"22222222-2222-4222-8222-222222222222","aal":"aal1"}',false);
DO $revoked$
DECLARE today date:=(clock_timestamp() AT TIME ZONE 'Asia/Colombo')::date;
BEGIN
 BEGIN
  PERFORM public.marketpro_qa_finance_apply_v1(
   'af77cb66-76b2-492a-a460-06132f468713',
   '55555555-5555-4555-8555-555555555555',
   '99999999-9999-4999-8999-999999999999','purchase',
   'aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee',0,
   jsonb_build_object('business_date',today,
    'item_id','bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb',
    'quantity_grams',100,'price_cents_per_kg',500));
  RAISE EXCEPTION 'Revoked Team phone could upload money';
 EXCEPTION WHEN SQLSTATE '42501' THEN NULL;END;
END;$revoked$;

-- Owner approves second Team phone (multiple phone support), no legacy writer.
SELECT set_config('request.jwt.claim.sub','98c3b101-d452-4b6b-902c-0b1a31fc340c',false);
SELECT set_config('request.jwt.claims',
 '{"sub":"98c3b101-d452-4b6b-902c-0b1a31fc340c","session_id":"11111111-1111-4111-8111-111111111111","aal":"aal2"}',false);
DO $approve$DECLARE x jsonb;BEGIN
 x:=public.marketpro_qa_cloud_decide_team_phone(
 'af77cb66-76b2-492a-a460-06132f468713',
 '33333333-3333-4333-8333-333333333333',
 '55555555-5555-4555-8555-555555555555',true);
 IF x->>'enabled'<>'true' OR x->>'can_write'<>'false' THEN
  RAISE EXCEPTION 'Team approval raised legacy writer';END IF;
END;$approve$;

SELECT set_config('request.jwt.claim.sub','b805598b-421f-447b-aa4c-ffb66f721338',false);
SELECT set_config('request.jwt.claims',
 '{"sub":"b805598b-421f-447b-aa4c-ffb66f721338","session_id":"22222222-2222-4222-8222-222222222222","aal":"aal1"}',false);

DO $staff$
DECLARE
 w uuid:='af77cb66-76b2-492a-a460-06132f468713';
 d uuid:='55555555-5555-4555-8555-555555555555';
 today date:=(clock_timestamp() AT TIME ZONE 'Asia/Colombo')::date;
 yesterday date:=((clock_timestamp() AT TIME ZONE 'Asia/Colombo')::date-1);
 r jsonb;
BEGIN
 -- Today purchase (safe Team write), then correction.
 r:=public.marketpro_qa_finance_apply_v1(w,d,
 '99999999-9999-4999-8999-999999999999','purchase',
 'aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee',0,
 jsonb_build_object('business_date',today,
  'item_id','bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb',
  'quantity_grams',100,'price_cents_per_kg',500));
 IF r->>'revision'<>'1' THEN RAISE EXCEPTION 'Team today create denied'; END IF;
 r:=public.marketpro_qa_finance_apply_v1(w,d,
 '99999999-9999-4999-8999-999999999998','purchase',
 'aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee',1,
 jsonb_build_object('business_date',today,
  'item_id','bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb',
  'quantity_grams',200,'price_cents_per_kg',500));
 IF r->>'revision'<>'2' THEN RAISE EXCEPTION 'Team repeat today correction denied'; END IF;
 BEGIN
  PERFORM public.marketpro_qa_finance_apply_v1(w,d,
   '99999999-9999-4999-8999-999999999997','purchase',
   '88888888-8888-4888-8888-888888888888',1,
   jsonb_build_object('business_date',today,
    'item_id','bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb',
    'quantity_grams',222,'price_cents_per_kg',444));
  RAISE EXCEPTION 'Team spoofed yesterday record to today';
 EXCEPTION WHEN serialization_failure THEN NULL;END;
 BEGIN
  PERFORM public.marketpro_qa_finance_apply_v1(w,d,
   '99999999-9999-4999-8999-999999999996','purchase',
   'bbbbbbbb-cccc-4ddd-8eee-ffffffffffff',0,
   jsonb_build_object('business_date',yesterday,
    'item_id','bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb',
    'quantity_grams',100,'price_cents_per_kg',500));
  RAISE EXCEPTION 'Team inserted earlier-day purchase';
 EXCEPTION WHEN SQLSTATE '42501' THEN NULL;END;
 -- Req, cash already tested, extras and additional lot today.
 r:=public.marketpro_qa_finance_apply_v1(w,d,
 '99999999-9999-4999-8999-999999999995','requisition',
 '11111111-1111-4111-8111-111111111112',0,
 jsonb_build_object('business_date',today,
  'item_id','bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb',
  'kitchen','Main Kitchen','quantity_grams',500));
 IF r->>'revision'<>'1' THEN RAISE EXCEPTION 'Requisition test failed';END IF;
 r:=public.marketpro_qa_finance_apply_v1(w,d,
 '99999999-9999-4999-8999-999999999994','purchase_addition',
 '11111111-1111-4111-8111-111111111113',0,
 jsonb_build_object('business_date',today,
  'item_id','bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb',
  'quantity_grams',500,'price_cents_per_kg',800));
 IF r->>'revision'<>'1' THEN RAISE EXCEPTION 'Addition test failed';END IF;
 r:=public.marketpro_qa_finance_apply_v1(w,d,
 '99999999-9999-4999-8999-999999999993','daily_other_expenses',
 '11111111-1111-4111-8111-111111111114',0,
 jsonb_build_object('business_date',today,'transport_cents',1000,
 'labor_charge_cents',200,'meals_drinks_cents',100,'others_cents',0,
 'remarks','Market transport'));
 IF r->>'revision'<>'1' THEN RAISE EXCEPTION 'Other Expenses test failed';END IF;
 BEGIN
  PERFORM public.marketpro_qa_finance_apply_v1(w,d,
   '99999999-9999-4999-8999-999999999992','daily_other_expenses',
   '11111111-1111-4111-8111-111111111115',0,
   jsonb_build_object('business_date',today,'transport_cents',1000,
    'labor_charge_cents',0,'meals_drinks_cents',0,'others_cents',0,
    'remarks','Test','invoice_photo','secret.jpg'));
  RAISE EXCEPTION 'Invoice photo accepted into Cloud';
 EXCEPTION WHEN SQLSTATE '22023' THEN NULL;END;
END;$staff$;

-- No Owner-only legacy writer was given to Team.
DO $verify$BEGIN
 IF (SELECT count(*) FROM public.registered_devices WHERE enabled AND can_write)<>1
 OR (SELECT count(*) FROM public.registered_devices WHERE enabled)<>2
 OR (SELECT count(*) FROM marketpro_private.qa_finance_sync_receipts_v1)<>8
 OR (SELECT count(*) FROM public.sync_operations)<>8
 OR (SELECT revision FROM public.purchases
   WHERE id='aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee')<>2
 THEN RAISE EXCEPTION 'Cloud finance consistency error';END IF;
END;$verify$;

-- Revocation is authoritative even with a still-valid Auth JWT.
SELECT set_config('request.jwt.claim.sub','98c3b101-d452-4b6b-902c-0b1a31fc340c',false);
SELECT set_config('request.jwt.claims',
 '{"sub":"98c3b101-d452-4b6b-902c-0b1a31fc340c","session_id":"11111111-1111-4111-8111-111111111111","aal":"aal2"}',false);
SELECT public.marketpro_qa_cloud_decide_team_phone(
 'af77cb66-76b2-492a-a460-06132f468713',
 '33333333-3333-4333-8333-333333333333',
 '55555555-5555-4555-8555-555555555555',false);
SELECT set_config('request.jwt.claim.sub','b805598b-421f-447b-aa4c-ffb66f721338',false);
SELECT set_config('request.jwt.claims',
 '{"sub":"b805598b-421f-447b-aa4c-ffb66f721338","session_id":"22222222-2222-4222-8222-222222222222","aal":"aal1"}',false);
DO $revoked_final$ BEGIN
 BEGIN
  PERFORM public.marketpro_qa_finance_apply_v1(
   'af77cb66-76b2-492a-a460-06132f468713',
   '55555555-5555-4555-8555-555555555555',
   '99999999-9999-4999-8999-999999999991','purchase',
   'aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee',2,
   jsonb_build_object('business_date',
    (clock_timestamp() AT TIME ZONE 'Asia/Colombo')::date,
    'item_id','bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb',
    'quantity_grams',300,'price_cents_per_kg',500));
  RAISE EXCEPTION 'Revoked Team could still upload';
 EXCEPTION WHEN SQLSTATE '42501' THEN NULL;END;
END;$revoked_final$;
SELECT 'PASS staged finance writer: Owner+Team, Colombo dates, 5 types, revisions, replay, revocation, photos denied' AS result;
