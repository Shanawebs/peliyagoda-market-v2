-- MARKET PRO: DISPOSABLE PostgreSQL 17 ONLY. NEVER RUN ON LIVE!
\set ON_ERROR_STOP on
\i supabase/tests/v375_staff_device_challenge_pg17_local_only.sql
-- Previous V375 regression intentionally signs out Staff and leaves one
-- approved test signing-key row. Simulate a legitimate Owner-controlled
-- replacement/rotation prerequisite without modifying a real workspace.
INSERT INTO auth.sessions(id,user_id,not_after) VALUES(
 '91111111-1111-4111-8111-111111111111',
 '33333333-3333-4333-8333-333333333333',NULL);
UPDATE marketpro_private.staff_device_public_keys
 SET revoked_at=clock_timestamp(),approved=false
 WHERE workspace_id='11111111-1111-4111-8111-111111111111';
\i supabase/proposals/two_phone_owner_staff_key_enrollment_REVIEW_ONLY.sql

DO $onboarding$
DECLARE
 w uuid:='11111111-1111-4111-8111-111111111111';
 staff uuid:='33333333-3333-4333-8333-333333333333';
 owner uuid:='22222222-2222-4222-8222-222222222222';
 staff_phone uuid:='f1111111-1111-4111-8111-111111111111';
 owner_phone uuid:='a1111111-1111-4111-8111-111111111111';
 sess uuid:='91111111-1111-4111-8111-111111111111';
 der text:=replace(encode(decode(repeat('ab',100),'hex'),'base64'),chr(10),'');
 key_fp text:=encode(extensions.digest(decode(repeat('ab',100),'hex'),'sha256'),'hex');
 challenge jsonb;
 review jsonb;
 result jsonb;
 denied boolean;
BEGIN
 PERFORM set_config('request.jwt.claim.sub',staff::text,true);
 PERFORM set_config('request.jwt.claims',
  jsonb_build_object('is_anonymous',false,'session_id',sess)::text,true);
 challenge:=public.marketpro_staff_begin_key_enrollment(w,staff_phone,der);
 IF challenge->>'purpose'<>'pair' OR
    challenge->>'key_fingerprint_sha256'<>key_fp OR
    challenge->>'session_id'<>sess::text THEN
   RAISE EXCEPTION 'Staff key enrollment nonce/fingerprint mismatch';
 END IF;
 -- Other session cannot retrieve or prove this pending key.
 denied:=false;
 BEGIN
  PERFORM public.marketpro_service_key_enrollment_material(
   (challenge->>'challenge_id')::uuid,staff,
   '92222222-2222-4222-8222-222222222222'::uuid);
 EXCEPTION WHEN SQLSTATE '42501' THEN denied:=true; END;
 IF NOT denied THEN RAISE EXCEPTION 'Other session read private pending key'; END IF;
 PERFORM set_config('request.jwt.claim.sub',owner::text,true);
 PERFORM set_config('request.jwt.claims','{"is_anonymous":false}',true);
 denied:=false;
 BEGIN
  PERFORM public.marketpro_owner_pending_key_review(
    w,owner_phone,staff,staff_phone);
 EXCEPTION WHEN SQLSTATE '42501' THEN denied:=true; END;
 IF NOT denied THEN
   RAISE EXCEPTION 'Unproved Staff key visible as Owner-approvable'; END IF;
 denied:=false;
 BEGIN
  PERFORM public.marketpro_owner_approve_staff_signing_key(
    w,owner_phone,staff,staff_phone,key_fp,'APPROVE KEY');
 EXCEPTION WHEN SQLSTATE '42501' THEN denied:=true; END;
 IF NOT denied THEN
   RAISE EXCEPTION 'Staff key was approved without private-key proof'; END IF;

 -- A trusted Edge process verifies P-256 signature BEFORE calling the
 -- service-role-only completion function. Node WebCrypto test exercises
 -- the actual DER P-256 verification independently.
 result:=public.marketpro_service_prove_key_enrollment(
   (challenge->>'challenge_id')::uuid,staff,sess);
 IF result->>'key_pending_owner_approval'<>'true' OR
    result->>'proof_of_possession_verified'<>'true' THEN
    RAISE EXCEPTION 'Proof completion not persisted as pending'; END IF;
 IF EXISTS(
   SELECT 1 FROM marketpro_private.staff_device_public_keys k
   WHERE k.workspace_id=w AND k.approved AND k.revoked_at IS NULL
 ) THEN
    RAISE EXCEPTION 'Staff proof self-approved private key'; END IF;
 denied:=false;
 BEGIN
  PERFORM public.marketpro_service_prove_key_enrollment(
    (challenge->>'challenge_id')::uuid,staff,sess);
 EXCEPTION WHEN SQLSTATE '42501' THEN denied:=true; END;
 IF NOT denied THEN RAISE EXCEPTION 'Pairing challenge reused twice'; END IF;

 PERFORM set_config('request.jwt.claim.sub',staff::text,true);
 PERFORM set_config('request.jwt.claims',
  jsonb_build_object('is_anonymous',false,'session_id',sess)::text,true);
 denied:=false;
 BEGIN
  PERFORM public.marketpro_owner_approve_staff_signing_key(
    w,owner_phone,staff,staff_phone,key_fp,'APPROVE KEY');
 EXCEPTION WHEN SQLSTATE '42501' THEN denied:=true; END;
 IF NOT denied THEN RAISE EXCEPTION 'Staff approved own key'; END IF;

 PERFORM set_config('request.jwt.claim.sub',owner::text,true);
 PERFORM set_config('request.jwt.claims','{"is_anonymous":false}',true);
 review:=public.marketpro_owner_pending_key_review(
    w,owner_phone,staff,staff_phone);
 IF review->>'key_fingerprint_sha256'<>key_fp OR
    review->>'owner_approved'<>'false' THEN
    RAISE EXCEPTION 'Owner could not inspect pending fingerprint'; END IF;
 denied:=false;
 BEGIN
  PERFORM public.marketpro_owner_approve_staff_signing_key(
    w,owner_phone,staff,staff_phone,repeat('0',64),'APPROVE KEY');
 EXCEPTION WHEN SQLSTATE '42501' THEN denied:=true; END;
 IF NOT denied THEN RAISE EXCEPTION 'Wrong fingerprint Owner approval accepted'; END IF;
 denied:=false;
 BEGIN
  PERFORM public.marketpro_owner_approve_staff_signing_key(
    w,owner_phone,staff,staff_phone,key_fp,'APPROVE');
 EXCEPTION WHEN SQLSTATE '42501' THEN denied:=true; END;
 IF NOT denied THEN RAISE EXCEPTION 'Unconfirmed Owner approval accepted'; END IF;

 result:=public.marketpro_owner_approve_staff_signing_key(
   w,owner_phone,staff,staff_phone,key_fp,'APPROVE KEY');
 IF result->>'key_approved'<>'true' OR
    result->>'staff_writer_enabled'<>'false' OR
    result->>'legacy_owner_writer_unchanged'<>'true' OR
    (SELECT COUNT(*) FROM marketpro_private.staff_device_public_keys
     WHERE workspace_id=w AND approved AND revoked_at IS NULL)<>1 THEN
   RAISE EXCEPTION 'Owner key approval granted unexpected rights'; END IF;
 denied:=false;
 BEGIN
  PERFORM public.marketpro_owner_approve_staff_signing_key(
    w,owner_phone,staff,staff_phone,key_fp,'APPROVE KEY');
 EXCEPTION WHEN SQLSTATE '42501' THEN denied:=true; END;
 IF NOT denied THEN RAISE EXCEPTION 'Owner replay key approval accepted'; END IF;

 -- Approved key enables v375 read challenge but does NOT grant writer.
 PERFORM set_config('request.jwt.claim.sub',staff::text,true);
 PERFORM set_config('request.jwt.claims',
   jsonb_build_object('is_anonymous',false,'session_id',sess)::text,true);
 result:=public.marketpro_staff_issue_device_challenge(
   w,staff_phone,'read');
 IF result->>'purpose'<>'read' THEN
   RAISE EXCEPTION 'Approved read-only phone cannot issue read challenge'; END IF;

 PERFORM set_config('request.jwt.claim.sub',owner::text,true);
 PERFORM set_config('request.jwt.claims','{"is_anonymous":false}',true);
 result:=public.marketpro_owner_revoke_staff_key_and_phone(
   w,owner_phone,staff,staff_phone);
 IF result->>'staff_phone_disabled'<>'true' OR
    result->>'legacy_owner_writer_unchanged'<>'true' THEN
   RAISE EXCEPTION 'Owner revoke did not confirm staff-only target'; END IF;
 IF (SELECT count(*) FROM public.registered_devices WHERE enabled AND can_write)<>1 THEN
   RAISE EXCEPTION 'Revoke unexpectedly touched Owner writer'; END IF;

 PERFORM set_config('request.jwt.claim.sub',staff::text,true);
 PERFORM set_config('request.jwt.claims',
   jsonb_build_object('is_anonymous',false,'session_id',sess)::text,true);
 denied:=false;
 BEGIN
  PERFORM public.marketpro_staff_issue_device_challenge(w,staff_phone,'read');
 EXCEPTION WHEN SQLSTATE '42501' THEN denied:=true; END;
 IF NOT denied THEN RAISE EXCEPTION 'Revoked Staff phone retained proof access'; END IF;
 denied:=false;
 BEGIN
  PERFORM public.marketpro_staff_begin_key_enrollment(w,staff_phone,der);
 EXCEPTION WHEN SQLSTATE '42501' THEN denied:=true; END;
 IF NOT denied THEN RAISE EXCEPTION 'Revoked phone enrolled another key'; END IF;
END;
$onboarding$;

DO $permissions$
BEGIN
 IF has_table_privilege('authenticated',
   'marketpro_private.staff_key_enrollment_requests','SELECT') OR
 has_table_privilege('authenticated',
   'marketpro_private.staff_key_enrollment_requests','INSERT') OR
 has_function_privilege('authenticated',
   'public.marketpro_service_key_enrollment_material(uuid,uuid,uuid)','EXECUTE') OR
 has_function_privilege('authenticated',
   'public.marketpro_service_prove_key_enrollment(uuid,uuid,uuid)','EXECUTE') OR
 has_function_privilege('anon',
   'public.marketpro_owner_approve_staff_signing_key(uuid,uuid,uuid,uuid,text,text)','EXECUTE')
 THEN RAISE EXCEPTION 'Staff key private table or service role exposed'; END IF;
 IF NOT has_function_privilege('service_role',
   'public.marketpro_service_prove_key_enrollment(uuid,uuid,uuid)','EXECUTE')
 THEN RAISE EXCEPTION 'Edge server cannot prove key'; END IF;
END;
$permissions$;
\echo Owner-approved Staff proof key pairing/revocation ACL and replay tests passed
