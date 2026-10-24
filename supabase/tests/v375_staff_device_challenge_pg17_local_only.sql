-- Local PostgreSQL 17 only. NEVER run against real Supabase.
\set ON_ERROR_STOP on
\i supabase/tests/v372_staff_auth_session_binding_pg17_local_only.sql
CREATE ROLE service_role;

-- V372 fixture finishes with signed-out Staff session 911... deleted.
-- Recreate the mock session solely to exercise the new challenge ledger.
INSERT INTO auth.sessions(id,user_id,not_after)
VALUES ('91111111-1111-4111-8111-111111111111',
        '33333333-3333-4333-8333-333333333333',NULL);
\i supabase/proposals/v375_staff_device_challenge_ledger_REVIEW_ONLY.sql

DO $begin$
DECLARE
 w uuid:='11111111-1111-4111-8111-111111111111';
 d uuid:='f1111111-1111-4111-8111-111111111111';
 usr uuid:='33333333-3333-4333-8333-333333333333';
 denied boolean;
BEGIN
 PERFORM set_config('request.jwt.claim.sub',usr::text,true);
 PERFORM set_config('request.jwt.claims',
  '{"is_anonymous":false,"session_id":"91111111-1111-4111-8111-111111111111"}',
  true);
 denied:=false;
 BEGIN
   PERFORM public.marketpro_staff_issue_device_challenge(w,d,'read');
 EXCEPTION WHEN SQLSTATE '42501' THEN denied:=true; END;
 IF NOT denied THEN RAISE EXCEPTION 'No approved public key still issued nonce'; END IF;

 -- This is an ADMIN-PROVISIONED TEST key only. No staff self-registration.
 INSERT INTO marketpro_private.staff_device_public_keys(
   workspace_id,staff_user_id,device_id,spki_der,fingerprint_sha256,
   approved,approved_at
 ) VALUES(
   w,usr,d,pg_catalog.decode(repeat('ab',100),'hex'),repeat('a',64),
   false,NULL
 );
 denied:=false;
 BEGIN
   PERFORM public.marketpro_staff_issue_device_challenge(w,d,'read');
 EXCEPTION WHEN SQLSTATE '42501' THEN denied:=true; END;
 IF NOT denied THEN RAISE EXCEPTION 'Unapproved key could issue proof'; END IF;
 UPDATE marketpro_private.staff_device_public_keys
 SET approved=true,approved_at=clock_timestamp() WHERE workspace_id=w;
 denied:=false;
 BEGIN
   PERFORM public.marketpro_staff_issue_device_challenge(w,d,'pair');
 EXCEPTION WHEN SQLSTATE '42501' THEN denied:=true; END;
 IF NOT denied THEN RAISE EXCEPTION 'Unsupported purpose allowed'; END IF;
END;
$begin$;

DO $issue_replay$
DECLARE
 w uuid:='11111111-1111-4111-8111-111111111111';
 d uuid:='f1111111-1111-4111-8111-111111111111';
 usr uuid:='33333333-3333-4333-8333-333333333333';
 sid uuid:='91111111-1111-4111-8111-111111111111';
 first jsonb;
 second jsonb;
 material jsonb;
 proof jsonb;
 denied boolean;
BEGIN
 PERFORM set_config('request.jwt.claim.sub',usr::text,true);
 PERFORM set_config('request.jwt.claims',
  '{"is_anonymous":false,"session_id":"91111111-1111-4111-8111-111111111111"}',
  true);
 first:=public.marketpro_staff_issue_device_challenge(w,d,'read');
 IF first->>'nonce_hex' !~ '^[0-9a-f]{64}$' OR
    first->>'session_id'<>sid::text OR
    (first->>'expires_at_millis')::bigint -
    (first->>'issued_at_millis')::bigint <> 60000
 THEN RAISE EXCEPTION 'Invalid nonce or timestamp transcript'; END IF;

 denied:=false;
 BEGIN
  PERFORM public.marketpro_service_device_challenge_material(
    (first->>'challenge_id')::uuid,usr,
    '92222222-2222-4222-8222-222222222222'::uuid);
 EXCEPTION WHEN SQLSTATE '42501' THEN denied:=true; END;
 IF NOT denied THEN RAISE EXCEPTION 'Other login session retrieved key'; END IF;

 second:=public.marketpro_staff_issue_device_challenge(w,d,'read');
 IF second->>'nonce_hex'=first->>'nonce_hex' OR
    second->>'challenge_id'=first->>'challenge_id'
 THEN RAISE EXCEPTION 'Challenge nonce/id repeated'; END IF;
 denied:=false;
 BEGIN
  PERFORM public.marketpro_service_device_challenge_material(
    (first->>'challenge_id')::uuid,usr,sid);
 EXCEPTION WHEN SQLSTATE '42501' THEN denied:=true; END;
 IF NOT denied THEN RAISE EXCEPTION 'Superseded proof challenge still active'; END IF;

 material:=public.marketpro_service_device_challenge_material(
    (second->>'challenge_id')::uuid,usr,sid);
 IF material->>'nonce_hex'<>second->>'nonce_hex' OR
    material->>'spki_base64' IS NULL
 THEN RAISE EXCEPTION 'Service proof material not bound'; END IF;
 proof:=public.marketpro_service_consume_device_challenge(
    (second->>'challenge_id')::uuid,usr,sid);
 IF proof->>'proof_verified_once'<>'true' OR
    proof->>'business_write_permission_granted'<>'false'
 THEN RAISE EXCEPTION 'Proof wrongly granted durable write authorization'; END IF;
 denied:=false;
 BEGIN
  PERFORM public.marketpro_service_consume_device_challenge(
    (second->>'challenge_id')::uuid,usr,sid);
 EXCEPTION WHEN SQLSTATE '42501' THEN denied:=true; END;
 IF NOT denied THEN RAISE EXCEPTION 'Challenge replay consumed twice'; END IF;
END;
$issue_replay$;

DO $revocation$
DECLARE
 w uuid:='11111111-1111-4111-8111-111111111111';
 d uuid:='f1111111-1111-4111-8111-111111111111';
 usr uuid:='33333333-3333-4333-8333-333333333333';
 sid uuid:='91111111-1111-4111-8111-111111111111';
 c jsonb;
 denied boolean;
BEGIN
 PERFORM set_config('request.jwt.claim.sub',usr::text,true);
 PERFORM set_config('request.jwt.claims',
  '{"is_anonymous":false,"session_id":"91111111-1111-4111-8111-111111111111"}',
  true);
 c:=public.marketpro_staff_issue_device_challenge(w,d,'write');
 UPDATE marketpro_private.team_rollout_gate
 SET team_writes_reviewed=false WHERE workspace_id=w;
 denied:=false;
 BEGIN
  PERFORM public.marketpro_service_device_challenge_material(
    (c->>'challenge_id')::uuid,usr,sid);
 EXCEPTION WHEN SQLSTATE '42501' THEN denied:=true; END;
 IF NOT denied THEN RAISE EXCEPTION 'Emergency write gate did not halt proof'; END IF;
 UPDATE marketpro_private.team_rollout_gate
 SET team_writes_reviewed=true WHERE workspace_id=w;
 UPDATE marketpro_private.staff_write_leases
 SET enabled=false,revoked_at=clock_timestamp() WHERE workspace_id=w;
 denied:=false;
 BEGIN
  PERFORM public.marketpro_service_device_challenge_material(
    (c->>'challenge_id')::uuid,usr,sid);
 EXCEPTION WHEN SQLSTATE '42501' THEN denied:=true; END;
 IF NOT denied THEN RAISE EXCEPTION 'Revoked write lease retained proof'; END IF;
 -- A read challenge may still be issued with an active read-only device.
 c:=public.marketpro_staff_issue_device_challenge(w,d,'read');
 UPDATE public.registered_devices SET enabled=false WHERE id=d;
 denied:=false;
 BEGIN
  PERFORM public.marketpro_service_device_challenge_material(
    (c->>'challenge_id')::uuid,usr,sid);
 EXCEPTION WHEN SQLSTATE '42501' THEN denied:=true; END;
 IF NOT denied THEN RAISE EXCEPTION 'Disabled device retained read proof'; END IF;
 UPDATE public.registered_devices SET enabled=true WHERE id=d;
 UPDATE marketpro_private.staff_device_public_keys
 SET revoked_at=clock_timestamp() WHERE workspace_id=w;
 denied:=false;
 BEGIN
  PERFORM public.marketpro_staff_issue_device_challenge(w,d,'read');
 EXCEPTION WHEN SQLSTATE '42501' THEN denied:=true; END;
 IF NOT denied THEN RAISE EXCEPTION 'Revoked private key still issued proof'; END IF;
 UPDATE marketpro_private.staff_device_public_keys
 SET revoked_at=NULL WHERE workspace_id=w;
 c:=public.marketpro_staff_issue_device_challenge(w,d,'read');
 DELETE FROM auth.sessions WHERE id=sid;
 denied:=false;
 BEGIN
  PERFORM public.marketpro_service_device_challenge_material(
    (c->>'challenge_id')::uuid,usr,sid);
 EXCEPTION WHEN SQLSTATE '42501' THEN denied:=true; END;
 IF NOT denied THEN RAISE EXCEPTION 'Signed-out JWT retained device proof'; END IF;
END;
$revocation$;

DO $acl$
BEGIN
 IF has_table_privilege('authenticated',
   'marketpro_private.staff_device_public_keys','SELECT')
 OR has_table_privilege('authenticated',
   'marketpro_private.staff_device_challenges','SELECT')
 OR has_table_privilege('authenticated',
   'marketpro_private.staff_device_challenges','INSERT')
 OR has_function_privilege('anon',
   'public.marketpro_staff_issue_device_challenge(uuid,uuid,text)','EXECUTE')
 OR has_function_privilege('authenticated',
   'public.marketpro_service_device_challenge_material(uuid,uuid,uuid)','EXECUTE')
 OR has_function_privilege('authenticated',
   'public.marketpro_service_consume_device_challenge(uuid,uuid,uuid)','EXECUTE')
 OR NOT has_function_privilege('service_role',
   'public.marketpro_service_consume_device_challenge(uuid,uuid,uuid)','EXECUTE')
 THEN RAISE EXCEPTION 'Device proof data or service authority exposed'; END IF;
END;
$acl$;
\echo V375 SQL proof key approval, one-use nonce, JWT binding and revocation passed
