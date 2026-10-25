-- Local PG17 ONLY, no production Supabase modifications.
\set ON_ERROR_STOP on
\i supabase/tests/two_phone_owner_approved_staff_key_pg17_local_only.sql
\i supabase/proposals/two_phone_owner_key_mfa_stepup_REVIEW_ONLY.sql

-- Prior fixture tests finished after disabled Staff phone. Re-enable only the
-- disposable fixture and stage a fresh candidate key for Owner MFA approval.
UPDATE public.registered_devices SET enabled=true
 WHERE id='f1111111-1111-4111-8111-111111111111';
INSERT INTO auth.sessions(id,user_id,not_after) VALUES(
 '94444444-4444-4444-8444-444444444444',
 '22222222-2222-4222-8222-222222222222',NULL)
ON CONFLICT(id) DO UPDATE SET not_after=NULL;

DO $mfa$
DECLARE
 w uuid:='11111111-1111-4111-8111-111111111111';
 own uuid:='22222222-2222-4222-8222-222222222222';
 staff uuid:='33333333-3333-4333-8333-333333333333';
 phone uuid:='f1111111-1111-4111-8111-111111111111';
 owner_phone uuid:='a1111111-1111-4111-8111-111111111111';
 sid uuid:='91111111-1111-4111-8111-111111111111';
 osid uuid:='94444444-4444-4444-8444-444444444444';
 nonce jsonb;
 candidate jsonb;
 fp text;
 receipt jsonb;
 denied boolean;
 v_now bigint:=pg_catalog.floor(extract(epoch FROM clock_timestamp()))::bigint;
 v_jwt jsonb;
BEGIN
 PERFORM set_config('request.jwt.claim.sub',staff::text,true);
 PERFORM set_config('request.jwt.claims',jsonb_build_object(
   'is_anonymous',false,'session_id',sid)::text,true);
 nonce:=public.marketpro_staff_issue_key_enrollment(
   w,phone,encode(decode(repeat('ef',100),'hex'),'base64'));
 PERFORM public.marketpro_service_stage_verified_staff_key(
   (nonce->>'challenge_id')::uuid,staff,sid);
 fp:=nonce->>'fingerprint_sha256';
 PERFORM set_config('request.jwt.claim.sub',own::text,true);
 PERFORM set_config('request.jwt.claims','{"is_anonymous":false}',true);
 candidate:=public.marketpro_owner_review_staff_key(w,owner_phone,staff);
 IF candidate->>'fingerprint_sha256'<>fp
 THEN RAISE EXCEPTION 'MFA candidate mismatch'; END IF;

 denied:=false;
 BEGIN
  PERFORM public.marketpro_owner_approve_staff_key_mfa(
    w,owner_phone,staff,phone,(nonce->>'challenge_id')::uuid,fp,'APPROVE KEY');
 EXCEPTION WHEN SQLSTATE '42501' THEN denied:=true; END;
 IF NOT denied THEN RAISE EXCEPTION 'No-MFA Owner approved Staff key'; END IF;

 -- AAL1 cannot pass despite recent TOTP-looking method.
 v_jwt:=jsonb_build_object('is_anonymous',false,'session_id',osid,
   'aal','aal1','amr',jsonb_build_array(jsonb_build_object(
     'method','totp','timestamp',v_now)));
 PERFORM set_config('request.jwt.claims',v_jwt::text,true);
 denied:=false;
 BEGIN PERFORM public.marketpro_owner_approve_staff_key_mfa(
    w,owner_phone,staff,phone,(nonce->>'challenge_id')::uuid,fp,'APPROVE KEY');
 EXCEPTION WHEN SQLSTATE '42501' THEN denied:=true; END;
 IF NOT denied THEN RAISE EXCEPTION 'AAL1 permitted approval'; END IF;

 -- AAL2 with a stale or future second factor cannot pass.
 FOR v_jwt IN SELECT jsonb_build_object('is_anonymous',false,
    'session_id',osid,'aal','aal2','amr',jsonb_build_array(v)) FROM (
    VALUES (jsonb_build_object('method','totp','timestamp',v_now-301)),
           (jsonb_build_object('method','totp','timestamp',v_now+16)),
           (jsonb_build_object('method','password','timestamp',v_now)),
           (jsonb_build_object('method','totp','timestamp','invalid'))
 ) AS x(v) LOOP
   PERFORM set_config('request.jwt.claims',v_jwt::text,true);
   denied:=false;
   BEGIN PERFORM public.marketpro_owner_approve_staff_key_mfa(
      w,owner_phone,staff,phone,(nonce->>'challenge_id')::uuid,fp,'APPROVE KEY');
   EXCEPTION WHEN SQLSTATE '42501' THEN denied:=true; END;
   IF NOT denied THEN RAISE EXCEPTION 'Invalid/expired MFA accepted: %',v_jwt; END IF;
 END LOOP;

 -- Owner MFA cannot substitute a revoked or unrelated Auth session.
 PERFORM set_config('request.jwt.claims',jsonb_build_object(
   'is_anonymous',false,'aal','aal2','session_id',
     '95555555-5555-4555-8555-555555555555',
   'amr',jsonb_build_array(jsonb_build_object(
      'method','totp','timestamp',v_now)))::text,true);
 denied:=false;
 BEGIN PERFORM public.marketpro_owner_approve_staff_key_mfa(
   w,owner_phone,staff,phone,(nonce->>'challenge_id')::uuid,fp,'APPROVE KEY');
 EXCEPTION WHEN SQLSTATE '42501' THEN denied:=true; END;
 IF NOT denied THEN RAISE EXCEPTION 'Forged MFA session accepted'; END IF;

 -- Only real Owner TOTP factor verified WITHIN FIVE MINUTES works.
 v_jwt:=jsonb_build_object('is_anonymous',false,'session_id',osid,
   'aal','aal2','amr',jsonb_build_array(
      jsonb_build_object('method','totp','timestamp',v_now),
      jsonb_build_object('method','password','timestamp',v_now-120)));
 PERFORM set_config('request.jwt.claims',v_jwt::text,true);
 receipt:=public.marketpro_owner_approve_staff_key_mfa(
   w,owner_phone,staff,phone,(nonce->>'challenge_id')::uuid,fp,'APPROVE KEY');
 IF receipt->>'key_approved'<>'true' OR
    receipt->>'owner_mfa_verified'<>'true' OR
    receipt->>'key_approval_only'<>'true' OR
    receipt->>'writer_approved'<>'false' OR
    (SELECT enabled FROM marketpro_private.staff_write_leases
     WHERE workspace_id=w) IS NOT FALSE THEN
  RAISE EXCEPTION 'Fresh Owner MFA granted writing or failed approval'; END IF;

 IF has_function_privilege('authenticated',
      'public.marketpro_owner_approve_staff_key(uuid,uuid,uuid,uuid,uuid,text,text)','EXECUTE')
 OR NOT has_function_privilege('authenticated',
      'public.marketpro_owner_approve_staff_key_mfa(uuid,uuid,uuid,uuid,uuid,text,text)','EXECUTE')
 OR has_function_privilege('anon',
      'public.marketpro_owner_approve_staff_key_mfa(uuid,uuid,uuid,uuid,uuid,text,text)','EXECUTE')
 THEN RAISE EXCEPTION 'Pre-MFA approval endpoint remains callable'; END IF;

 -- AAL2 is not a bypass after Owner logout/revocation.
 DELETE FROM auth.sessions WHERE id=osid;
 denied:=false;
 BEGIN
  PERFORM marketpro_private.require_fresh_owner_key_mfa(w,owner_phone);
 EXCEPTION WHEN SQLSTATE '42501' THEN denied:=true; END;
 IF NOT denied THEN RAISE EXCEPTION 'Signed-out Owner had valid MFA privileges'; END IF;
END;
$mfa$;
\echo PASS: Owner key approval mandates recent AAL2 TOTP, live Auth session and no old bypass
