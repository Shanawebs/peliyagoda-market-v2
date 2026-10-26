\set ON_ERROR_STOP on
-- Disposable PG17 ONLY. MUST NEVER run on hosted Supabase.
CREATE ROLE anon;
CREATE ROLE authenticated;
CREATE ROLE service_role;
CREATE SCHEMA auth;
CREATE SCHEMA extensions;
CREATE SCHEMA marketpro_private;
CREATE EXTENSION pgcrypto WITH SCHEMA extensions;
CREATE TABLE auth.users(id uuid PRIMARY KEY,email_confirmed_at timestamptz);
CREATE TABLE auth.sessions(id uuid PRIMARY KEY,user_id uuid,not_after timestamptz);
CREATE TABLE auth.mfa_factors(user_id uuid,factor_type text,status text);
CREATE TABLE public.workspaces(id uuid PRIMARY KEY,name text);
CREATE TABLE public.workspace_members(workspace_id uuid,user_id uuid,role text);
CREATE TABLE public.registered_devices(id uuid,workspace_id uuid,enabled boolean,can_write boolean);
CREATE TABLE marketpro_private.qa_owner_device_public_keys(workspace_id uuid);
CREATE TABLE marketpro_private.qa_owner_bootstrap_gate(workspace_id uuid,enabled boolean);
CREATE TABLE marketpro_private.qa_invite_gate(workspace_id uuid,enabled boolean);
CREATE TABLE marketpro_private.pending_team_enrollments(workspace_id uuid);
INSERT INTO public.workspaces VALUES('af77cb66-76b2-492a-a460-06132f468713','Market Pro Two-Phone QA');
INSERT INTO auth.users VALUES('98c3b101-d452-4b6b-902c-0b1a31fc340c',clock_timestamp());
INSERT INTO auth.sessions VALUES('11111111-1111-4111-8111-111111111111','98c3b101-d452-4b6b-902c-0b1a31fc340c',clock_timestamp()+interval '1 day');
INSERT INTO auth.mfa_factors VALUES('98c3b101-d452-4b6b-902c-0b1a31fc340c','totp','verified');
INSERT INTO public.workspace_members VALUES('af77cb66-76b2-492a-a460-06132f468713','98c3b101-d452-4b6b-902c-0b1a31fc340c','owner');
INSERT INTO marketpro_private.qa_owner_bootstrap_gate VALUES('af77cb66-76b2-492a-a460-06132f468713',false);
INSERT INTO marketpro_private.qa_invite_gate VALUES('af77cb66-76b2-492a-a460-06132f468713',false);
\i supabase/staging/staging_qa_owner_pending_service_only_v050.sql
DO $first$
DECLARE denied boolean:=false;
BEGIN
 BEGIN
 PERFORM public.marketpro_qa_service_pending_owner_issue(
 '98c3b101-d452-4b6b-902c-0b1a31fc340c',
 '11111111-1111-4111-8111-111111111111',
 '33333333-3333-4333-8333-333333333333',
 replace(encode(decode(repeat('bc',91),'hex'),'base64'),E'\n',''));
 EXCEPTION WHEN SQLSTATE '42501' THEN denied:=true; END;
 IF NOT denied THEN RAISE EXCEPTION 'Gate OFF accepted pending issue'; END IF;
 IF has_function_privilege('authenticated',
 'public.marketpro_qa_service_pending_owner_issue(uuid,uuid,uuid,text)','EXECUTE')
 OR has_function_privilege('anon',
 'public.marketpro_qa_service_pending_owner_commit(uuid,uuid,uuid)','EXECUTE')
 THEN RAISE EXCEPTION 'Unauthorized SQL client RPC executable'; END IF;
 IF (SELECT count(*) FROM marketpro_private.qa_owner_pending_devices)<>0
 THEN RAISE EXCEPTION 'Gate OFF wrote pending records'; END IF;
END;$first$;
-- The next gate is enabled ONLY in disposable PostgreSQL, never hosted!
UPDATE marketpro_private.qa_owner_pending_gate SET enabled=true;
DO $tests$
DECLARE
 owner uuid:='98c3b101-d452-4b6b-902c-0b1a31fc340c';
 sess uuid:='11111111-1111-4111-8111-111111111111';
 device uuid:='33333333-3333-4333-8333-333333333333';
 b64 text:=replace(encode(decode(repeat('bc',91),'hex'),'base64'),E'\n','');
 wrong uuid:='aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa';
 a jsonb;b jsonb; material jsonb;receipt jsonb;denied boolean;
BEGIN
 denied:=false;
 BEGIN PERFORM public.marketpro_qa_service_pending_owner_issue(owner,wrong,device,b64);
 EXCEPTION WHEN SQLSTATE '42501' THEN denied:=true; END;
 IF NOT denied THEN RAISE EXCEPTION 'Wrong session accepted';END IF;
 denied:=false;
 BEGIN PERFORM public.marketpro_qa_service_pending_owner_issue(wrong,sess,device,b64);
 EXCEPTION WHEN SQLSTATE '42501' THEN denied:=true; END;
 IF NOT denied THEN RAISE EXCEPTION 'Non-owner accepted';END IF;

 a:=public.marketpro_qa_service_pending_owner_issue(owner,sess,device,b64);
 IF a->>'proof_version'<>'marketpro-owner-pending-registration-v1'
 OR a->>'registration_status'<>'not_registered'
 OR a->>'device_activated'<>'false'
 OR length(a->>'nonce_hex')<>64
 THEN RAISE EXCEPTION 'Issue returned unexpected registration/nonce data';END IF;
 material:=public.marketpro_qa_service_pending_owner_material(
  (a->>'challenge_id')::uuid,owner,sess);
 IF material->>'nonce_hex'<>a->>'nonce_hex'
 OR material->>'spki_base64'<>b64
 THEN RAISE EXCEPTION 'Material identity mismatch';END IF;
 b:=public.marketpro_qa_service_pending_owner_issue(owner,sess,device,b64);
 denied:=false;
 BEGIN PERFORM public.marketpro_qa_service_pending_owner_commit((a->>'challenge_id')::uuid,owner,sess);
 EXCEPTION WHEN SQLSTATE '42501' THEN denied:=true; END;
 IF NOT denied THEN RAISE EXCEPTION 'Superseded challenge was committed';END IF;

 receipt:=public.marketpro_qa_service_pending_owner_commit(
 (b->>'challenge_id')::uuid,owner,sess);
 IF receipt->>'registration_status'<>'pending'
 OR receipt->>'can_write'<>'false'
 OR receipt->>'staff_access'<>'false'
 OR receipt->>'device_activated'<>'false'
 THEN RAISE EXCEPTION 'Pending receipt granted access';END IF;
 IF (SELECT count(*) FROM marketpro_private.qa_owner_pending_devices)<>1
 OR (SELECT bool_and(status='pending' AND NOT enabled AND NOT can_write
 AND NOT hardware_attestation_verified) FROM marketpro_private.qa_owner_pending_devices) IS DISTINCT FROM true
 OR (SELECT count(*) FROM public.registered_devices)<>0
 THEN RAISE EXCEPTION 'Pending commit activated device';END IF;
 denied:=false;
 BEGIN PERFORM public.marketpro_qa_service_pending_owner_commit((b->>'challenge_id')::uuid,owner,sess);
 EXCEPTION WHEN SQLSTATE '42501' THEN denied:=true; END;
 IF NOT denied THEN RAISE EXCEPTION 'Replay committed';END IF;

 denied:=false;
 BEGIN PERFORM public.marketpro_qa_service_pending_owner_issue(owner,sess,wrong,b64);
 EXCEPTION WHEN SQLSTATE '42501' THEN denied:=true; END;
 IF NOT denied THEN RAISE EXCEPTION 'Duplicate phone registration accepted';END IF;
 IF (SELECT count(*) FROM public.registered_devices)<>0
 OR (SELECT count(*) FROM public.workspace_members WHERE role<>'owner')<>0
 THEN RAISE EXCEPTION 'Private registration granted business rights';END IF;
END;$tests$;
SELECT 'PASS isolated PG17 pending registration: Gate OFF, identity checks, nonce rotation/replay, no activation, no staff' AS result;
