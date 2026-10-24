-- MARKET PRO: LOCAL PostgreSQL 17 ONLY. This mocks successful Edge
-- ECDSA verification; Edge real signature checks have separate WebCrypto tests.
-- Never run against production Supabase.
\set ON_ERROR_STOP on
\i supabase/tests/v375_staff_device_challenge_pg17_local_only.sql

CREATE TABLE public.workspaces(id uuid PRIMARY KEY);
INSERT INTO public.workspaces(id) VALUES(
 '11111111-1111-4111-8111-111111111111');
\i supabase/proposals/two_phone_other_expenses_REVIEW_ONLY.sql

-- V375 tests deliberately sign out Staff and revoke its writer lease.
-- Recreate a freshly authenticated active Staff before this isolated test.
INSERT INTO auth.sessions(id,user_id,not_after) VALUES(
 '91111111-1111-4111-8111-111111111111',
 '33333333-3333-4333-8333-333333333333',NULL);
UPDATE marketpro_private.staff_write_leases
 SET enabled=true,revoked_at=NULL
 WHERE workspace_id='11111111-1111-4111-8111-111111111111';
\i supabase/proposals/two_phone_atomic_proof_financial_write_REVIEW_ONLY.sql

DO $test$
DECLARE
 w uuid:='11111111-1111-4111-8111-111111111111';
 staff uuid:='33333333-3333-4333-8333-333333333333';
 ph uuid:='f1111111-1111-4111-8111-111111111111';
 sess uuid:='91111111-1111-4111-8111-111111111111';
 finance_id uuid:='d1000000-0000-4000-8000-000000000001';
 op uuid:='b1000000-0000-4000-8000-000000000001';
 payload jsonb;
 today date:=(clock_timestamp() AT TIME ZONE 'Asia/Colombo')::date;
 chall jsonb;
 result jsonb;
 denied boolean;
BEGIN
 PERFORM set_config('request.jwt.claim.sub',staff::text,true);
 PERFORM set_config('request.jwt.claims',
   pg_catalog.jsonb_build_object('is_anonymous',false,'session_id',sess)::text,true);
 payload:=pg_catalog.jsonb_build_object(
  'business_date',today,'transport_cents',1200,
  'labor_charge_cents',340,'meals_drinks_cents',220,
  'others_cents',70,'remarks','Staff today'
 );
 chall:=public.marketpro_staff_issue_finance_challenge(
   w,ph,op,'daily_other_expenses',finance_id,'create',NULL,payload
 );
 IF chall->>'purpose'<>'write' OR
   chall->>'staff_user_id'<>staff::text OR
   chall->>'session_id'<>sess::text OR
   chall->>'key_fingerprint_sha256'<>repeat('a',64) THEN
  RAISE EXCEPTION 'Finance challenge not bound to signed Staff user/session';
 END IF;

 -- A signed challenge must NOT be usable for different financial amounts,
 -- operation ID, revision or entity; the commitment must still be usable
 -- for the ORIGINAL exact parameters after mismatch rejection.
 denied:=false;
 BEGIN
  PERFORM public.marketpro_service_apply_proof_bound_finance(
    (chall->>'challenge_id')::uuid,staff,sess,op,
    'daily_other_expenses',finance_id,'create',NULL,
    pg_catalog.jsonb_set(payload,'{transport_cents}','999'::jsonb)
  );
 EXCEPTION WHEN SQLSTATE '42501' THEN denied:=true; END;
 IF NOT denied THEN RAISE EXCEPTION 'Signed Staff proof accepted modified price'; END IF;
 IF (SELECT consumed_at IS NOT NULL FROM marketpro_private.staff_device_challenges
   WHERE challenge_id=(chall->>'challenge_id')::uuid) THEN
   RAISE EXCEPTION 'Invalid changed request consumed original nonce'; END IF;
 denied:=false;
 BEGIN
  PERFORM public.marketpro_service_apply_proof_bound_finance(
    (chall->>'challenge_id')::uuid,staff,sess,
    'a1000000-0000-4000-8000-000000000001',
    'daily_other_expenses',finance_id,'create',NULL,payload
  );
 EXCEPTION WHEN SQLSTATE '42501' THEN denied:=true; END;
 IF NOT denied THEN RAISE EXCEPTION 'Nonce reused for different operation'; END IF;
 result:=public.marketpro_service_apply_proof_bound_finance(
    (chall->>'challenge_id')::uuid,staff,sess,op,
    'daily_other_expenses',finance_id,'create',NULL,payload
 );
 IF result->>'accepted'<>'true' OR result->>'challenge_consumed'<>'true'
 OR (result->>'revision')::int<>1 OR
   (SELECT transport_cents FROM public.daily_other_expenses WHERE id=finance_id)<>1200
 THEN RAISE EXCEPTION 'Atomic verified financial create failed'; END IF;
 denied:=false;
 BEGIN
  PERFORM public.marketpro_service_apply_proof_bound_finance(
    (chall->>'challenge_id')::uuid,staff,sess,op,
    'daily_other_expenses',finance_id,'create',NULL,payload
  );
 EXCEPTION WHEN SQLSTATE '42501' THEN denied:=true; END;
 IF NOT denied THEN RAISE EXCEPTION 'Signed nonce replay wrote twice'; END IF;
 IF (SELECT count(*) FROM public.daily_other_expenses WHERE id=finance_id)<>1
 THEN RAISE EXCEPTION 'Duplicated financial record'; END IF;

 -- Insufficient/stale revision must roll back BOTH the finance edit and
 -- the challenge consumption.
 op:='b1000000-0000-4000-8000-000000000002';
 payload:=pg_catalog.jsonb_set(payload,'{transport_cents}','2300'::jsonb);
 chall:=public.marketpro_staff_issue_finance_challenge(
   w,ph,op,'daily_other_expenses',finance_id,'correct',2,payload);
 denied:=false;
 BEGIN
  PERFORM public.marketpro_service_apply_proof_bound_finance(
    (chall->>'challenge_id')::uuid,staff,sess,op,
    'daily_other_expenses',finance_id,'correct',2,payload
  );
 EXCEPTION WHEN SQLSTATE '40001' THEN denied:=true; END;
 IF NOT denied THEN RAISE EXCEPTION 'Stale revision corrected Staff finance'; END IF;
 IF (SELECT consumed_at IS NOT NULL FROM marketpro_private.staff_device_challenges
     WHERE challenge_id=(chall->>'challenge_id')::uuid) OR
    (SELECT revision FROM public.daily_other_expenses WHERE id=finance_id)<>1 THEN
    RAISE EXCEPTION 'Financial revision error did not roll back signature consume';
 END IF;

 -- Today's second correction (new challenge) can proceed without Owner review.
 op:='b1000000-0000-4000-8000-000000000003';
 chall:=public.marketpro_staff_issue_finance_challenge(
   w,ph,op,'daily_other_expenses',finance_id,'correct',1,payload);
 result:=public.marketpro_service_apply_proof_bound_finance(
   (chall->>'challenge_id')::uuid,staff,sess,op,
   'daily_other_expenses',finance_id,'correct',1,payload);
 IF (result->>'revision')::int<>2 OR
   (SELECT transport_cents FROM public.daily_other_expenses
      WHERE id=finance_id)<>2300 THEN
   RAISE EXCEPTION 'Repeated today Staff correction failed'; END IF;

 -- Past business dates are Owner-only EVEN if Staff possesses valid key.
 op:='b1000000-0000-4000-8000-000000000004';
 payload:=pg_catalog.jsonb_set(payload,'{business_date}',
    pg_catalog.to_jsonb(today-1));
 chall:=public.marketpro_staff_issue_finance_challenge(
   w,ph,op,'daily_other_expenses',
   'd1000000-0000-4000-8000-000000000002','create',NULL,payload);
 denied:=false;
 BEGIN
   PERFORM public.marketpro_service_apply_proof_bound_finance(
     (chall->>'challenge_id')::uuid,staff,sess,op,
     'daily_other_expenses',
     'd1000000-0000-4000-8000-000000000002','create',NULL,payload);
 EXCEPTION WHEN SQLSTATE '42501' THEN denied:=true; END;
 IF NOT denied THEN RAISE EXCEPTION 'Staff used signed device proof for old day'; END IF;
 IF (SELECT consumed_at IS NOT NULL FROM marketpro_private.staff_device_challenges
    WHERE challenge_id=(chall->>'challenge_id')::uuid) THEN
    RAISE EXCEPTION 'Past-day write consumed challenge'; END IF;

 -- If Owner deactivates the Writer lease, even an outstanding signed
 -- challenge is useless. The old Owner device V1 remains registered writer.
 UPDATE marketpro_private.staff_write_leases SET enabled=false,
   revoked_at=clock_timestamp() WHERE workspace_id=w;
 denied:=false;
 BEGIN
   PERFORM public.marketpro_service_apply_proof_bound_finance(
     (chall->>'challenge_id')::uuid,staff,sess,op,
     'daily_other_expenses',
     'd1000000-0000-4000-8000-000000000002','create',NULL,payload);
 EXCEPTION WHEN SQLSTATE '42501' THEN denied:=true; END;
 IF NOT denied THEN RAISE EXCEPTION 'Revoked Staff writer accepted proof'; END IF;
 IF (SELECT count(*) FROM public.registered_devices
    WHERE enabled AND can_write)<>1 THEN
    RAISE EXCEPTION 'Owner-only V1 enabled writer changed'; END IF;
END;
$test$;

DO $acl$
BEGIN
 IF has_function_privilege('authenticated',
   'public.marketpro_apply_team_purchase_change(uuid,uuid,uuid,uuid,text,bigint,jsonb)',
   'EXECUTE') OR
    has_function_privilege('authenticated',
   'public.marketpro_apply_team_financial_change(uuid,uuid,uuid,text,uuid,text,bigint,jsonb)',
   'EXECUTE') OR
    has_function_privilege('authenticated',
   'public.marketpro_apply_team_other_expense(uuid,uuid,uuid,uuid,text,bigint,jsonb)',
   'EXECUTE') OR
    has_function_privilege('authenticated',
   'public.marketpro_service_apply_proof_bound_finance(uuid,uuid,uuid,uuid,text,uuid,text,bigint,jsonb)',
   'EXECUTE') OR
    has_table_privilege('authenticated',
   'marketpro_private.staff_finance_challenge_requests','SELECT')
 THEN RAISE EXCEPTION 'Direct Staff JWT finance bypass remains'; END IF;
 IF NOT has_function_privilege('service_role',
   'public.marketpro_service_apply_proof_bound_finance(uuid,uuid,uuid,uuid,text,uuid,text,bigint,jsonb)',
   'EXECUTE') THEN
   RAISE EXCEPTION 'Trusted server denied atomic finance function';
 END IF;
END;
$acl$;
\echo TWO PHONE: Signed proof and finance transaction atomicity, nonce replay, Staff old dates and bypass ACL passed
