-- TWO-PHONE RELEASE — PG17 ONLY, never point at LIVE Supabase.
\set ON_ERROR_STOP on
\i supabase/tests/two_phone_other_expenses_pg17_local_only.sql
\i supabase/proposals/two_phone_other_expenses_pull_REVIEW_ONLY.sql

DO $test$
DECLARE
 workspace uuid:='11111111-1111-4111-8111-111111111111';
 owner uuid:='22222222-2222-4222-8222-222222222222';
 staff uuid:='33333333-3333-4333-8333-333333333333';
 owner_phone uuid:='a1111111-1111-4111-8111-111111111111';
 staff_phone uuid:='f1111111-1111-4111-8111-111111111111';
 actual jsonb;
 values_ jsonb;
 refused boolean;
BEGIN
 -- Existing Owner Phone has always retained read access, including old dates.
 PERFORM set_config('request.jwt.claim.sub',owner::text,true);
 PERFORM set_config('request.jwt.claims','{"is_anonymous":false}',true);
 actual:=public.marketpro_team_pull_page(
   workspace,owner_phone,'daily_other_expenses',NULL,NULL,20);
 values_:=actual->'rows';
 IF jsonb_array_length(values_)<>2 OR
   actual->>'entity_type'<>'daily_other_expenses' OR
   actual->>'server_today_colombo' IS NULL
 THEN RAISE EXCEPTION 'Owner did not receive exactly two Cloud expenses'; END IF;
 IF NOT EXISTS (
  SELECT 1 FROM jsonb_array_elements(values_) item
  WHERE item->>'remarks'='Owner corrected'
 ) THEN RAISE EXCEPTION 'Owner prior-day expense missing from pull'; END IF;

 -- Staff lost device initially denied, even with signed-in JWT and valid key.
 PERFORM set_config('request.jwt.claim.sub',staff::text,true);
 PERFORM set_config('request.jwt.claims',
  '{"is_anonymous":false,"session_id":"91111111-1111-4111-8111-111111111111"}',
  true);
 refused:=false;
 BEGIN
   PERFORM public.marketpro_team_pull_page(
     workspace,staff_phone,'daily_other_expenses',NULL,NULL,20);
 EXCEPTION WHEN SQLSTATE '42501' THEN refused:=true; END;
 IF NOT refused THEN RAISE EXCEPTION 'Revoked Staff phone read other expenses'; END IF;

 -- Re-enabling the physical device alone is NOT enough with wrong JWT session.
 UPDATE public.registered_devices SET enabled=true WHERE id=staff_phone;
 PERFORM set_config('request.jwt.claims',
  '{"is_anonymous":false,"session_id":"92222222-2222-4222-8222-222222222222"}',
  true);
 refused:=false;
 BEGIN
  PERFORM public.marketpro_team_pull_page(
    workspace,staff_phone,'daily_other_expenses',NULL,NULL,20);
 EXCEPTION WHEN SQLSTATE '42501' THEN refused:=true; END;
 IF NOT refused THEN RAISE EXCEPTION 'Unpaired second JWT read Staff expenses'; END IF;

 -- The exact paired Staff session can see both dates in the audited RPC;
 -- older dates are read-only and cannot be edited by Staff.
 PERFORM set_config('request.jwt.claims',
  '{"is_anonymous":false,"session_id":"91111111-1111-4111-8111-111111111111"}',
  true);
 actual:=public.marketpro_team_pull_page(
   workspace,staff_phone,'daily_other_expenses',NULL,NULL,1);
 IF jsonb_array_length(actual->'rows')<>1 OR
   actual->>'has_more'<>'true' OR
   actual->>'next_id' IS NULL THEN
    RAISE EXCEPTION 'Staff first expense page invalid'; END IF;
 values_:=public.marketpro_team_pull_page(
   workspace,staff_phone,'daily_other_expenses',
   (actual->>'next_updated_at')::timestamptz,
   (actual->>'next_id')::uuid,1);
 IF jsonb_array_length(values_->'rows')<>1 OR
   values_->>'has_more'<>'false'
 THEN RAISE EXCEPTION 'Staff cursor missed/duplicated other expenses'; END IF;
END;
$test$;
\echo TWO PHONE: authenticated paginated other-expenses pulls and revoked-device denial passed
