-- REVIEW-ONLY Disposable PG17 integration. Never point at real Supabase.
\set ON_ERROR_STOP on
\i supabase/tests/v372_staff_auth_session_binding_pg17_local_only.sql

CREATE TABLE public.workspaces(id uuid PRIMARY KEY);
INSERT INTO public.workspaces(id) VALUES(
 '11111111-1111-4111-8111-111111111111');
-- Recreate the signed Staff Auth session removed by the V372 logout test.
INSERT INTO auth.sessions(id,user_id,not_after) VALUES(
 '91111111-1111-4111-8111-111111111111',
 '33333333-3333-4333-8333-333333333333',NULL);
\i supabase/proposals/two_phone_other_expenses_REVIEW_ONLY.sql

DO $t$
DECLARE
 w uuid:='11111111-1111-4111-8111-111111111111';
 staff uuid:='33333333-3333-4333-8333-333333333333';
 owner uuid:='22222222-2222-4222-8222-222222222222';
 staff_phone uuid:='f1111111-1111-4111-8111-111111111111';
 owner_phone uuid:='a1111111-1111-4111-8111-111111111111';
 staff_row uuid:='a1000000-0000-4000-8000-000000000001';
 owner_row uuid:='a1000000-0000-4000-8000-000000000002';
 op1 uuid:='b1000000-0000-4000-8000-000000000001';
 op2 uuid:='b1000000-0000-4000-8000-000000000002';
 today_date date:=(clock_timestamp() AT TIME ZONE 'Asia/Colombo')::date;
 yesterday_date date:=(clock_timestamp() AT TIME ZONE 'Asia/Colombo')::date-1;
 payload jsonb;
 response jsonb;
 denied boolean;
BEGIN
 -- Staff independent Supabase login, paired session, Owner-approved writing.
 PERFORM set_config('request.jwt.claim.sub',staff::text,true);
 PERFORM set_config('request.jwt.claims',
  '{"is_anonymous":false,"session_id":"91111111-1111-4111-8111-111111111111"}',true);
 payload:=jsonb_build_object(
  'business_date',today_date,'transport_cents',12000,
  'labor_charge_cents',3400,'meals_drinks_cents',1300,
  'others_cents',200,'remarks','Public test expense — no photos');
 response:=public.marketpro_apply_team_other_expense(
  w,staff_phone,op1,staff_row,'create',NULL,payload);
 IF response->>'accepted'<>'true' OR (response->>'revision')::int<>1
 THEN RAISE EXCEPTION 'Staff today expense create failed'; END IF;
 IF (SELECT transport_cents+labor_charge_cents+meals_drinks_cents+others_cents
  FROM public.daily_other_expenses WHERE id=staff_row)<>16900
 THEN RAISE EXCEPTION 'Four other expense categories were not saved accurately'; END IF;
 response:=public.marketpro_apply_team_other_expense(
  w,staff_phone,op1,staff_row,'create',NULL,payload);
 IF response->>'replayed'<>'true' THEN RAISE EXCEPTION 'Idempotent retry did not replay'; END IF;
 denied:=false;
 BEGIN
  PERFORM public.marketpro_apply_team_other_expense(
   w,staff_phone,op1,staff_row,'create',NULL,
   jsonb_set(payload,'{transport_cents}','1000'::jsonb));
 EXCEPTION WHEN SQLSTATE '23505' THEN denied:=true; END;
 IF NOT denied THEN RAISE EXCEPTION 'Reused operation modified expenses'; END IF;

 payload:=jsonb_set(payload,'{transport_cents}','20000'::jsonb);
 response:=public.marketpro_apply_team_other_expense(
  w,staff_phone,op2,staff_row,'correct',1,payload);
 IF (response->>'revision')::int<>2
 THEN RAISE EXCEPTION 'Unlimited same-day Staff corrections not applied'; END IF;
 denied:=false;
 BEGIN
  PERFORM public.marketpro_apply_team_other_expense(
   w,staff_phone,'b1000000-0000-4000-8000-000000000003',staff_row,
   'correct',1,payload);
 EXCEPTION WHEN SQLSTATE '40001' THEN denied:=true; END;
 IF NOT denied THEN RAISE EXCEPTION 'Stale expense correction allowed'; END IF;

 denied:=false;
 BEGIN
  PERFORM public.marketpro_apply_team_other_expense(
   w,staff_phone,'b1000000-0000-4000-8000-000000000004',
   'a1000000-0000-4000-8000-000000000004','create',NULL,
   jsonb_set(payload,'{business_date}',to_jsonb(yesterday_date)));
 EXCEPTION WHEN SQLSTATE '42501' THEN denied:=true; END;
 IF NOT denied THEN RAISE EXCEPTION 'Staff created previous-day expenses'; END IF;
 denied:=false;
 BEGIN
  PERFORM public.marketpro_apply_team_other_expense(
   w,staff_phone,'b1000000-0000-4000-8000-000000000005',
   staff_row,'correct',2,payload||'{"invoice_photo":"private.jpg"}'::jsonb);
 EXCEPTION WHEN SQLSTATE '22023' THEN denied:=true; END;
 IF NOT denied THEN RAISE EXCEPTION 'Invoice/photo field escaped JSON whitelist'; END IF;

 -- Owner can make corrections to old business dates with the legacy Owner phone.
 PERFORM set_config('request.jwt.claim.sub',owner::text,true);
 PERFORM set_config('request.jwt.claims','{"is_anonymous":false}',true);
 payload:=jsonb_set(payload,'{business_date}',to_jsonb(yesterday_date));
 response:=public.marketpro_apply_team_other_expense(
  w,owner_phone,'b1000000-0000-4000-8000-000000000006',
  owner_row,'create',NULL,payload);
 IF response->>'accepted'<>'true' THEN RAISE EXCEPTION 'Owner past-day create failed'; END IF;
 response:=public.marketpro_apply_team_other_expense(
  w,owner_phone,'b1000000-0000-4000-8000-000000000007',
  owner_row,'correct',1,jsonb_set(payload,'{remarks}','"Owner corrected"'));
 IF (response->>'revision')::int<>2
 THEN RAISE EXCEPTION 'Owner old-day correction failed'; END IF;

 -- Staff's current session is already bound; old dates are STILL owner-only.
 PERFORM set_config('request.jwt.claim.sub',staff::text,true);
 PERFORM set_config('request.jwt.claims',
  '{"is_anonymous":false,"session_id":"91111111-1111-4111-8111-111111111111"}',true);
 denied:=false;
 BEGIN
  PERFORM public.marketpro_apply_team_other_expense(
   w,staff_phone,'b1000000-0000-4000-8000-000000000008',
   owner_row,'correct',2,payload);
 EXCEPTION WHEN SQLSTATE '42501' THEN denied:=true; END;
 IF NOT denied THEN RAISE EXCEPTION 'Staff corrected Owner past-day expense'; END IF;
 UPDATE public.registered_devices SET enabled=false WHERE id=staff_phone;
 denied:=false;
 BEGIN
  PERFORM public.marketpro_apply_team_other_expense(
   w,staff_phone,'b1000000-0000-4000-8000-000000000009',
   staff_row,'correct',2,jsonb_set(payload,'{business_date}',to_jsonb(today_date)));
 EXCEPTION WHEN SQLSTATE '42501' THEN denied:=true; END;
 IF NOT denied THEN RAISE EXCEPTION 'Revoked Staff Phone wrote other expenses'; END IF;
END;
$t$;
DO $private$
BEGIN
 IF has_table_privilege('authenticated','public.daily_other_expenses','UPDATE') OR
 has_table_privilege('authenticated','public.daily_other_expenses','INSERT') OR
 has_table_privilege('authenticated',
    'marketpro_private.other_expenses_operation_audit','SELECT') OR
 has_function_privilege('anon',
    'public.marketpro_apply_team_other_expense(uuid,uuid,uuid,uuid,text,bigint,jsonb)',
    'EXECUTE')
 THEN RAISE EXCEPTION 'Other expenses SQL privilege leak'; END IF;
END;
$private$;
\echo TWO-PHONE OTHER EXPENSES: staff today, owner past, OTP session and replay tests passed
