-- Disposable PG17 only; no hosted user or phone writes.
\set ON_ERROR_STOP on
CREATE ROLE authenticated;
CREATE ROLE anon;
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
CREATE TABLE public.registered_devices(id uuid,workspace_id uuid);
CREATE TABLE marketpro_private.pending_team_enrollments(workspace_id uuid);
CREATE TABLE marketpro_private.qa_owner_bootstrap_gate(workspace_id uuid,enabled boolean);
CREATE TABLE marketpro_private.qa_invite_gate(workspace_id uuid,enabled boolean);
INSERT INTO public.workspaces VALUES('af77cb66-76b2-492a-a460-06132f468713','Market Pro Two-Phone QA');
INSERT INTO auth.users VALUES('98c3b101-d452-4b6b-902c-0b1a31fc340c',clock_timestamp());
INSERT INTO auth.sessions VALUES('11111111-1111-4111-8111-111111111111','98c3b101-d452-4b6b-902c-0b1a31fc340c',clock_timestamp()+interval '1 day');
INSERT INTO auth.mfa_factors VALUES('98c3b101-d452-4b6b-902c-0b1a31fc340c','totp','verified');
INSERT INTO public.workspace_members VALUES('af77cb66-76b2-492a-a460-06132f468713','98c3b101-d452-4b6b-902c-0b1a31fc340c','owner');
INSERT INTO marketpro_private.qa_owner_bootstrap_gate VALUES('af77cb66-76b2-492a-a460-06132f468713',false);
INSERT INTO marketpro_private.qa_invite_gate VALUES('af77cb66-76b2-492a-a460-06132f468713',false);
\i supabase/staging/staging_qa_owner_remote_proof_only.sql
DO $t$
DECLARE
 owner uuid:='98c3b101-d452-4b6b-902c-0b1a31fc340c';
 session uuid:='11111111-1111-4111-8111-111111111111';
 phone uuid:='33333333-3333-4333-8333-333333333333';
 spki text:=replace(encode(decode(repeat('ab',91),'hex'),'base64'),E'\n','');
 first jsonb;
 second jsonb;
 material jsonb;
 success jsonb;
 denied boolean;
BEGIN
 denied:=false;
 BEGIN
 PERFORM public.marketpro_qa_service_remote_proof_issue(owner,'ffffffff-ffff-4fff-8fff-ffffffffffff',phone,spki);
 EXCEPTION WHEN SQLSTATE '42501' THEN denied:=true; END;
 IF NOT denied THEN RAISE EXCEPTION 'Wrong Auth session got nonce';END IF;

 denied:=false;
 BEGIN
 PERFORM public.marketpro_qa_service_remote_proof_issue('aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa',session,phone,spki);
 EXCEPTION WHEN SQLSTATE '42501' THEN denied:=true; END;
 IF NOT denied THEN RAISE EXCEPTION 'Other account got nonce';END IF;

 first:=public.marketpro_qa_service_remote_proof_issue(owner,session,phone,spki);
 IF first->>'proof_version'<>'marketpro-owner-bootstrap-key-v1' OR length(first->>'nonce_hex')<>64
 OR (first->>'phone_activated')::bool<>false
 THEN RAISE EXCEPTION 'Invalid issued challenge';END IF;
 material:=public.marketpro_qa_service_remote_proof_material((first->>'challenge_id')::uuid,owner,session);
 IF material->>'spki_base64'<>spki OR material->>'nonce_hex'<>first->>'nonce_hex'
 THEN RAISE EXCEPTION 'Invalid authoritative challenge';END IF;

 second:=public.marketpro_qa_service_remote_proof_issue(owner,session,phone,spki);
 IF second->>'nonce_hex'=first->>'nonce_hex' THEN RAISE EXCEPTION 'Nonce rotation failed'; END IF;
 denied:=false;
 BEGIN PERFORM public.marketpro_qa_service_remote_proof_consume((first->>'challenge_id')::uuid,owner,session);
 EXCEPTION WHEN SQLSTATE '42501' THEN denied:=true; END;
 IF NOT denied THEN RAISE EXCEPTION 'Superseded nonce accepted'; END IF;
 success:=public.marketpro_qa_service_remote_proof_consume((second->>'challenge_id')::uuid,owner,session);
 IF success->>'verified_and_consumed'<>'true' OR success->>'device_registered'<>'false'
 THEN RAISE EXCEPTION 'Incorrect no-enrollment receipt';END IF;
 denied:=false;
 BEGIN PERFORM public.marketpro_qa_service_remote_proof_consume((second->>'challenge_id')::uuid,owner,session);
 EXCEPTION WHEN SQLSTATE '42501' THEN denied:=true; END;
 IF NOT denied THEN RAISE EXCEPTION 'Replay accepted'; END IF;
 IF (SELECT count(*) FROM public.registered_devices)<>0
 OR (SELECT count(*) FROM public.workspace_members)<>1
 OR (SELECT count(*) FROM marketpro_private.pending_team_enrollments)<>0
 THEN RAISE EXCEPTION 'Remote proof activated phone or Staff'; END IF;
END;$t$;
SELECT 'PASS: staging Owner service-only nonce and atomic single-use without enrollment' result;
