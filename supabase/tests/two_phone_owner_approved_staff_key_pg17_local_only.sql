-- DISPOSABLE POSTGRES 17 ONLY: never run against live Supabase.
-- Simulates Edge having verified ECDSA before service stage. Real ECDSA
-- cryptographic validation is independently exercised in Node tests.
\set ON_ERROR_STOP on
\i supabase/tests/two_phone_proof_bound_all_financial_pg17_local_only.sql
\i supabase/proposals/two_phone_owner_approved_staff_key_REVIEW_ONLY.sql

UPDATE public.registered_devices SET enabled=true
 WHERE id='f1111111-1111-4111-8111-111111111111';
UPDATE marketpro_private.staff_write_leases
 SET enabled=false,revoked_at=clock_timestamp()
 WHERE workspace_id='11111111-1111-4111-8111-111111111111';
UPDATE marketpro_private.staff_device_public_keys SET
 approved=false,approved_at=NULL
 WHERE workspace_id='11111111-1111-4111-8111-111111111111';

DO $enroll$
DECLARE
 w uuid:='11111111-1111-4111-8111-111111111111';
 staff uuid:='33333333-3333-4333-8333-333333333333';
 owner uuid:='22222222-2222-4222-8222-222222222222';
 phone uuid:='f1111111-1111-4111-8111-111111111111';
 owner_phone uuid:='a1111111-1111-4111-8111-111111111111';
 sid uuid:='91111111-1111-4111-8111-111111111111';
 spki text:=pg_catalog.encode(pg_catalog.decode(repeat('ab',100),'hex'),'base64');
 first jsonb;
 second jsonb;
 candidate jsonb;
 result jsonb;
 fingerprint text;
 denied boolean;
BEGIN
 PERFORM set_config('request.jwt.claim.sub',staff::text,true);
 PERFORM set_config('request.jwt.claims',pg_catalog.jsonb_build_object(
   'is_anonymous',false,'session_id',sid)::text,true);

 IF marketpro_private.key_enrollment_allowed(w,staff,phone,sid)<>true
 THEN RAISE EXCEPTION 'Real paired active read-only device not eligible'; END IF;
 denied:=false;
 BEGIN
  PERFORM public.marketpro_staff_issue_key_enrollment(
   w,phone,'invalid_spki_not_base64!');
 EXCEPTION WHEN OTHERS THEN denied:=true; END;
 IF NOT denied THEN RAISE EXCEPTION 'Bad public key accepted'; END IF;
 denied:=false;
 BEGIN
  PERFORM public.marketpro_staff_issue_key_enrollment(
   w,'f2222222-2222-4222-8222-222222222222'::uuid,spki);
 EXCEPTION WHEN SQLSTATE '42501' THEN denied:=true; END;
 IF NOT denied THEN RAISE EXCEPTION 'Unenrolled new physical phone issued candidate'; END IF;

 first:=public.marketpro_staff_issue_key_enrollment(w,phone,spki);
 fingerprint:=first->>'fingerprint_sha256';
 IF fingerprint<>pg_catalog.encode(extensions.digest(
     pg_catalog.decode(spki,'base64'),'sha256'),'hex')
 OR first->>'proof_version'<>'marketpro-staff-key-enrollment-v1'
 OR first->>'session_id'<>sid::text OR
 (first->>'expires_at_millis')::bigint-
 (first->>'issued_at_millis')::bigint<>60000 THEN
  RAISE EXCEPTION 'Key challenge fingerprint/session/expiry invalid'; END IF;
 second:=public.marketpro_staff_issue_key_enrollment(w,phone,spki);
 denied:=false;
 BEGIN
  PERFORM public.marketpro_service_key_enrollment_material(
   (first->>'challenge_id')::uuid,staff,sid);
 EXCEPTION WHEN SQLSTATE '42501' THEN denied:=true; END;
 IF NOT denied THEN RAISE EXCEPTION 'Replaced key nonce still accepted'; END IF;
 candidate:=public.marketpro_service_key_enrollment_material(
   (second->>'challenge_id')::uuid,staff,sid);
 IF candidate->>'fingerprint_sha256'<>fingerprint
 OR candidate->>'spki_base64' IS NULL THEN
  RAISE EXCEPTION 'Key bytes not bound to fingerprint'; END IF;

 -- Successful Service stage models a VALID WebCrypto P256 proof by Edge.
 result:=public.marketpro_service_stage_verified_staff_key(
   (second->>'challenge_id')::uuid,staff,sid);
 IF result->>'proof_verified'<>'true' OR
 result->>'owner_approval_granted'<>'false' OR
 (SELECT approved FROM marketpro_private.staff_device_public_keys
  WHERE workspace_id=w)<>false THEN
  RAISE EXCEPTION 'Unapproved key treated as writer'; END IF;
 denied:=false;
 BEGIN
  PERFORM public.marketpro_service_stage_verified_staff_key(
   (second->>'challenge_id')::uuid,staff,sid);
 EXCEPTION WHEN SQLSTATE '42501' THEN denied:=true; END;
 IF NOT denied THEN RAISE EXCEPTION 'Key proof replay accepted twice'; END IF;

 PERFORM set_config('request.jwt.claim.sub',owner::text,true);
 PERFORM set_config('request.jwt.claims','{"is_anonymous":false}',true);
 result:=public.marketpro_owner_review_staff_key(w,owner_phone,staff);
 IF result->>'fingerprint_sha256'<>fingerprint OR
 result->>'owner_review_required'<>'true' OR
 result->>'writer_approved'<>'false' THEN
  RAISE EXCEPTION 'Owner review exposed wrong candidate'; END IF;

 denied:=false;
 BEGIN
  PERFORM public.marketpro_owner_approve_staff_key(
    w,owner_phone,staff,phone,(second->>'challenge_id')::uuid,
    repeat('f',64),'APPROVE KEY');
 EXCEPTION WHEN SQLSTATE '42501' THEN denied:=true; END;
 IF NOT denied THEN RAISE EXCEPTION 'Wrong fingerprint permitted Owner approval'; END IF;
 denied:=false;
 BEGIN
  PERFORM public.marketpro_owner_approve_staff_key(
    w,owner_phone,staff,phone,(second->>'challenge_id')::uuid,
    fingerprint,'YES');
 EXCEPTION WHEN SQLSTATE '42501' THEN denied:=true; END;
 IF NOT denied THEN RAISE EXCEPTION 'Owner confirmation bypassed'; END IF;
 result:=public.marketpro_owner_approve_staff_key(
    w,owner_phone,staff,phone,(second->>'challenge_id')::uuid,
    fingerprint,'APPROVE KEY');
 IF result->>'key_approved'<>'true' OR
 result->>'writer_approved'<>'false' OR
 (SELECT enabled FROM marketpro_private.staff_write_leases WHERE workspace_id=w)<>false
 THEN RAISE EXCEPTION 'Owner key approval automatically granted Staff writer'; END IF;
 IF (SELECT fingerprint_sha256 FROM marketpro_private.staff_device_public_keys
     WHERE workspace_id=w)<>fingerprint
 OR (SELECT count(*) FROM marketpro_private.staff_key_owner_approval_audit
     WHERE workspace_id=w)<>1 THEN
  RAISE EXCEPTION 'Owner public key approval not auditable'; END IF;
 denied:=false;
 BEGIN
  PERFORM public.marketpro_owner_approve_staff_key(
    w,owner_phone,staff,phone,(second->>'challenge_id')::uuid,
    fingerprint,'APPROVE KEY');
 EXCEPTION WHEN SQLSTATE '42501' THEN denied:=true; END;
 IF NOT denied THEN RAISE EXCEPTION 'Owner reused a stale candidate'; END IF;

 -- Fully approved key can prove READ. WRITE remains disabled until
 -- separate Owner Staff writer RPC grants a live write lease.
 PERFORM set_config('request.jwt.claim.sub',staff::text,true);
 PERFORM set_config('request.jwt.claims',pg_catalog.jsonb_build_object(
   'is_anonymous',false,'session_id',sid)::text,true);
 result:=public.marketpro_staff_issue_device_challenge(w,phone,'read');
 IF result->>'purpose'<>'read' THEN
  RAISE EXCEPTION 'Owner-approved key failed read challenge'; END IF;
 denied:=false;
 BEGIN
  PERFORM public.marketpro_staff_issue_device_challenge(w,phone,'write');
 EXCEPTION WHEN SQLSTATE '42501' THEN denied:=true; END;
 IF NOT denied THEN RAISE EXCEPTION 'Key approval without Owner lease grants writes'; END IF;

 -- New key rotation is staged first, old approved key remains in place.
 first:=public.marketpro_staff_issue_key_enrollment(w,phone,
      pg_catalog.encode(pg_catalog.decode(repeat('cd',100),'hex'),'base64'));
 PERFORM public.marketpro_service_stage_verified_staff_key(
      (first->>'challenge_id')::uuid,staff,sid);
 IF (SELECT fingerprint_sha256 FROM marketpro_private.staff_device_public_keys
     WHERE workspace_id=w)<>fingerprint THEN
  RAISE EXCEPTION 'Staff silently replaced Owner-approved public key'; END IF;

 PERFORM set_config('request.jwt.claim.sub',owner::text,true);
 PERFORM set_config('request.jwt.claims','{"is_anonymous":false}',true);
 denied:=false;
 BEGIN
  PERFORM public.marketpro_owner_approve_staff_key(
    w,owner_phone,staff,phone,(first->>'challenge_id')::uuid,
    fingerprint,'APPROVE KEY');
 EXCEPTION WHEN SQLSTATE '42501' THEN denied:=true; END;
 IF NOT denied THEN RAISE EXCEPTION 'Old fingerprint approved unreviewed new key'; END IF;
 result:=public.marketpro_owner_approve_staff_key(
    w,owner_phone,staff,phone,(first->>'challenge_id')::uuid,
    first->>'fingerprint_sha256','APPROVE KEY');
 IF result->>'key_approved'<>'true' OR
 (SELECT count(*) FROM marketpro_private.staff_key_owner_approval_audit
     WHERE workspace_id=w)<>2 THEN
  RAISE EXCEPTION 'Owner key rotation not audited'; END IF;

 -- Existing device can be switched OFF without erasing cloud history.
 UPDATE public.registered_devices SET enabled=false WHERE id=phone;
 PERFORM set_config('request.jwt.claim.sub',staff::text,true);
 PERFORM set_config('request.jwt.claims',pg_catalog.jsonb_build_object(
   'is_anonymous',false,'session_id',sid)::text,true);
 denied:=false;
 BEGIN
  PERFORM public.marketpro_staff_issue_key_enrollment(w,phone,spki);
 EXCEPTION WHEN SQLSTATE '42501' THEN denied:=true; END;
 IF NOT denied THEN RAISE EXCEPTION 'Disabled phone enrolled another key'; END IF;
END;
$enroll$;

DO $permissions$
BEGIN
 IF has_function_privilege('anon',
   'public.marketpro_staff_issue_key_enrollment(uuid,uuid,text)','EXECUTE')
 OR has_function_privilege('authenticated',
   'public.marketpro_service_key_enrollment_material(uuid,uuid,uuid)','EXECUTE')
 OR has_function_privilege('authenticated',
   'public.marketpro_service_stage_verified_staff_key(uuid,uuid,uuid)','EXECUTE')
 OR has_table_privilege('authenticated',
   'marketpro_private.staff_key_pending_approval','SELECT')
 OR has_table_privilege('authenticated',
   'marketpro_private.staff_key_enrollment_challenges','INSERT')
 OR NOT has_function_privilege('service_role',
   'public.marketpro_service_stage_verified_staff_key(uuid,uuid,uuid)','EXECUTE') THEN
  RAISE EXCEPTION 'Staff proof or key candidate privileges exposed'; END IF;
END;
$permissions$;
\echo OWNER DEVICE KEY ENROLLMENT: proof nonce, Owner fingerprint approval, key rotation, revoked-phone denial PASS
