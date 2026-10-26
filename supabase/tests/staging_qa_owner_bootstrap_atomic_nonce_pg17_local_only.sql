-- Disposable Postgres 17 ONLY: mocked Supabase Auth/JWT.
-- This proves SQL access checks and one-use atomic nonce semantics; actual
-- ECDSA verification is separately tested using Node WebCrypto.
\set ON_ERROR_STOP on
CREATE ROLE anon;
CREATE ROLE authenticated;
CREATE ROLE service_role;
CREATE SCHEMA auth;
CREATE SCHEMA extensions;
CREATE SCHEMA marketpro_private;
CREATE EXTENSION pgcrypto WITH SCHEMA extensions;

CREATE TABLE auth.users(id uuid PRIMARY KEY,email text NOT NULL,email_confirmed_at timestamptz);
CREATE TABLE auth.mfa_factors(id uuid PRIMARY KEY,user_id uuid NOT NULL,
 factor_type text NOT NULL,status text NOT NULL);
CREATE TABLE auth.sessions(id uuid PRIMARY KEY,user_id uuid NOT NULL,not_after timestamptz);
CREATE FUNCTION auth.uid() RETURNS uuid LANGUAGE sql STABLE AS $f$
 SELECT NULLIF(current_setting('request.jwt.claim.sub',true),'')::uuid $f$;
CREATE FUNCTION auth.jwt() RETURNS jsonb LANGUAGE sql STABLE AS $f$
 SELECT COALESCE(NULLIF(current_setting('request.jwt.claims',true),''),'{}')::jsonb $f$;

CREATE TABLE public.workspaces(id uuid PRIMARY KEY,name text NOT NULL);
CREATE TABLE public.workspace_members(
 workspace_id uuid NOT NULL,user_id uuid NOT NULL,role text NOT NULL,
 PRIMARY KEY(workspace_id,user_id));
CREATE TABLE public.registered_devices(
 id uuid PRIMARY KEY,workspace_id uuid NOT NULL,user_id uuid NOT NULL,
 enabled boolean NOT NULL,can_write boolean NOT NULL);
CREATE TABLE marketpro_private.qa_owner_bootstrap_gate(
 workspace_id uuid PRIMARY KEY,enabled boolean NOT NULL DEFAULT false,audited_at timestamptz);
CREATE TABLE marketpro_private.qa_owner_bootstrap_challenges(
 challenge_id uuid PRIMARY KEY DEFAULT extensions.gen_random_uuid(),
 workspace_id uuid NOT NULL,owner_user_id uuid NOT NULL,
 device_id uuid NOT NULL,session_id uuid NOT NULL,
 spki_der bytea NOT NULL CHECK(octet_length(spki_der) BETWEEN 64 AND 512),
 fingerprint_sha256 text NOT NULL CHECK(fingerprint_sha256 ~ '^[a-f0-9]{64}$'),
 nonce_hex text NOT NULL CHECK(nonce_hex ~ '^[a-f0-9]{64}$'),
 issued_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 expires_at timestamptz NOT NULL,consumed_at timestamptz,
 signature_verified_at timestamptz,
 CHECK(expires_at>issued_at AND expires_at<=issued_at+interval '60 seconds'),
 CHECK(signature_verified_at IS NULL OR consumed_at IS NOT NULL),
 CHECK(signature_verified_at IS NULL OR signature_verified_at>=issued_at)
);
CREATE TABLE marketpro_private.qa_owner_device_public_keys(
 workspace_id uuid PRIMARY KEY,owner_user_id uuid NOT NULL,
 device_id uuid NOT NULL UNIQUE,
 spki_der bytea NOT NULL CHECK(octet_length(spki_der) BETWEEN 64 AND 512),
 fingerprint_sha256 text NOT NULL CHECK(fingerprint_sha256 ~ '^[a-f0-9]{64}$'),
 challenge_id uuid NOT NULL UNIQUE,
 activated boolean NOT NULL DEFAULT false CHECK(activated=false),
 attestation_verified boolean NOT NULL DEFAULT false CHECK(attestation_verified=false),
 recorded_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
INSERT INTO public.workspaces VALUES
('af77cb66-76b2-492a-a460-06132f468713','Market Pro Two-Phone QA');
INSERT INTO auth.users VALUES
('98c3b101-d452-4b6b-902c-0b1a31fc340c','owner@example.invalid',clock_timestamp()),
('b805598b-421f-447b-aa4c-ffb66f721338','staff@example.invalid',clock_timestamp());
INSERT INTO auth.sessions VALUES
('11111111-1111-4111-8111-111111111111',
 '98c3b101-d452-4b6b-902c-0b1a31fc340c',clock_timestamp()+interval '1 day'),
('22222222-2222-4222-8222-222222222222',
 'b805598b-421f-447b-aa4c-ffb66f721338',clock_timestamp()+interval '1 day');
INSERT INTO auth.mfa_factors VALUES
('aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa',
 '98c3b101-d452-4b6b-902c-0b1a31fc340c','totp','verified');
INSERT INTO public.workspace_members VALUES
('af77cb66-76b2-492a-a460-06132f468713',
 '98c3b101-d452-4b6b-902c-0b1a31fc340c','owner');
INSERT INTO marketpro_private.qa_owner_bootstrap_gate VALUES
('af77cb66-76b2-492a-a460-06132f468713',false,NULL);

\i supabase/staging/staging_qa_owner_bootstrap_atomic_challenge_REVIEW_ONLY.sql

DO $test$
DECLARE
 workspace uuid:='af77cb66-76b2-492a-a460-06132f468713';
 owner_id uuid:='98c3b101-d452-4b6b-902c-0b1a31fc340c';
 staff_id uuid:='b805598b-421f-447b-aa4c-ffb66f721338';
 owner_session uuid:='11111111-1111-4111-8111-111111111111';
 owner_device uuid:='33333333-3333-4333-8333-333333333333';
 spki text:=replace(encode(decode(repeat('ab',91),'hex'),'base64'), E'\n', '');
 claims jsonb;
 c1 jsonb;
 c2 jsonb;
 m jsonb;
 result jsonb;
 denied boolean;
BEGIN
 claims:=jsonb_build_object('sub',owner_id,'aal','aal2',
   'session_id',owner_session,'is_anonymous',false,
   'amr',jsonb_build_array(jsonb_build_object('method','totp',
     'timestamp',floor(extract(epoch from clock_timestamp()))::bigint)));
 PERFORM set_config('request.jwt.claim.sub',owner_id::text,true);
 PERFORM set_config('request.jwt.claims',claims::text,true);

 denied:=false;
 BEGIN
  PERFORM public.marketpro_qa_owner_issue_bootstrap(workspace,owner_device,spki);
 EXCEPTION WHEN SQLSTATE '42501' THEN denied:=true; END;
 IF NOT denied THEN RAISE EXCEPTION 'Disabled gate unexpectedly issued challenge'; END IF;

 -- TEST FIXTURE ONLY: toggle gate in throwaway PG17; never in hosted Supabase.
 UPDATE marketpro_private.qa_owner_bootstrap_gate SET enabled=true;
 denied:=false;
 PERFORM set_config('request.jwt.claims',
   jsonb_set(claims,'{aal}','"aal1"'::jsonb)::text,true);
 BEGIN
  PERFORM public.marketpro_qa_owner_issue_bootstrap(workspace,owner_device,spki);
 EXCEPTION WHEN SQLSTATE '42501' THEN denied:=true; END;
 IF NOT denied THEN RAISE EXCEPTION 'AAL1 Owner issued challenge'; END IF;

 PERFORM set_config('request.jwt.claims',jsonb_set(claims,'{amr}',
   jsonb_build_array(jsonb_build_object('method','totp','timestamp',
   floor(extract(epoch from clock_timestamp()))::bigint-1000)))::text,true);
 denied:=false;
 BEGIN
  PERFORM public.marketpro_qa_owner_issue_bootstrap(workspace,owner_device,spki);
 EXCEPTION WHEN SQLSTATE '42501' THEN denied:=true; END;
 IF NOT denied THEN RAISE EXCEPTION 'Stale TOTP issued challenge'; END IF;

 PERFORM set_config('request.jwt.claims',
  jsonb_set(claims,'{session_id}',
   '"bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"'::jsonb)::text,true);
 denied:=false;
 BEGIN
  PERFORM public.marketpro_qa_owner_issue_bootstrap(workspace,owner_device,spki);
 EXCEPTION WHEN SQLSTATE '42501' THEN denied:=true; END;
 IF NOT denied THEN RAISE EXCEPTION 'Invalid Auth session issued challenge'; END IF;

 PERFORM set_config('request.jwt.claims',claims::text,true);
 c1:=public.marketpro_qa_owner_issue_bootstrap(workspace,owner_device,spki);
 IF c1->>'proof_version'<>'marketpro-owner-bootstrap-key-v1'
    OR length(c1->>'nonce_hex')<>64
    OR c1->>'fingerprint_sha256' IS DISTINCT FROM
       encode(extensions.digest(decode(spki,'base64'),'sha256'),'hex')
 THEN RAISE EXCEPTION 'Challenge issue fields incorrect'; END IF;
 m:=public.marketpro_qa_service_owner_bootstrap_material(
   (c1->>'challenge_id')::uuid,owner_id,owner_session);
 IF m->>'nonce_hex'<>c1->>'nonce_hex'
   OR m->>'spki_base64'<>spki
 THEN RAISE EXCEPTION 'Service challenge material mismatched'; END IF;

 denied:=false;
 BEGIN
  PERFORM public.marketpro_qa_service_owner_bootstrap_commit(
     (c1->>'challenge_id')::uuid,owner_id,
     'cccccccc-cccc-4ccc-8ccc-cccccccccccc'::uuid);
 EXCEPTION WHEN SQLSTATE '42501' THEN denied:=true; END;
 IF NOT denied THEN RAISE EXCEPTION 'Wrong signed Auth session committed'; END IF;

 -- Issuing a newer challenge invalidates the older one.
 c2:=public.marketpro_qa_owner_issue_bootstrap(workspace,owner_device,spki);
 IF c2->>'nonce_hex'=c1->>'nonce_hex' THEN
  RAISE EXCEPTION 'Challenge nonce not rotated'; END IF;

 denied:=false;
 BEGIN
  PERFORM public.marketpro_qa_service_owner_bootstrap_commit(
     (c1->>'challenge_id')::uuid,owner_id,owner_session);
 EXCEPTION WHEN SQLSTATE '42501' THEN denied:=true; END;
 IF NOT denied THEN RAISE EXCEPTION 'Superseded challenge was accepted'; END IF;

 denied:=false;
 BEGIN
  PERFORM public.marketpro_qa_service_owner_bootstrap_material(
    (c1->>'challenge_id')::uuid,owner_id,owner_session);
 EXCEPTION WHEN SQLSTATE '42501' THEN denied:=true; END;
 IF NOT denied THEN RAISE EXCEPTION 'Superseded material fetched'; END IF;

 -- Simulates completion AFTER trusted Edge verifies a genuine signature.
 -- No arbitrary SQL caller can do this on hosted Supabase: service role ONLY.
 result:=public.marketpro_qa_service_owner_bootstrap_commit(
   (c2->>'challenge_id')::uuid,owner_id,owner_session);
 IF (result->>'proof_verified_and_consumed')::boolean IS DISTINCT FROM true
    OR (result->>'phone_activated')::boolean IS DISTINCT FROM false
    OR (SELECT count(*) FROM marketpro_private.qa_owner_device_public_keys)<>1
    OR (SELECT count(*) FROM public.registered_devices)<>0
 THEN RAISE EXCEPTION 'Proof stage accidentally activated phone'; END IF;
 denied:=false;
 BEGIN
  PERFORM public.marketpro_qa_service_owner_bootstrap_commit(
    (c2->>'challenge_id')::uuid,owner_id,owner_session);
 EXCEPTION WHEN SQLSTATE '42501' THEN denied:=true; END;
 IF NOT denied THEN RAISE EXCEPTION 'Already consumed challenge replayed'; END IF;

 denied:=false;
 BEGIN
  PERFORM public.marketpro_qa_owner_issue_bootstrap(workspace,owner_device,spki);
 EXCEPTION WHEN SQLSTATE '42501' THEN denied:=true; END;
 IF NOT denied THEN RAISE EXCEPTION 'Second bootstrap after pending Owner key'; END IF;
 IF EXISTS (SELECT 1 FROM marketpro_private.qa_owner_device_public_keys
      WHERE activated OR attestation_verified)
 THEN RAISE EXCEPTION 'Bootstrap asserted unsupported key attestation'; END IF;
END;
$test$;

DO $acl$
BEGIN
 IF has_function_privilege('authenticated',
     'public.marketpro_qa_owner_issue_bootstrap(uuid,uuid,text)','EXECUTE')
  OR has_function_privilege('authenticated',
     'public.marketpro_qa_service_owner_bootstrap_commit(uuid,uuid,uuid)','EXECUTE')
  OR has_function_privilege('anon',
     'public.marketpro_qa_service_owner_bootstrap_material(uuid,uuid,uuid)','EXECUTE')
  OR NOT has_function_privilege('service_role',
     'public.marketpro_qa_service_owner_bootstrap_commit(uuid,uuid,uuid)','EXECUTE')
 THEN RAISE EXCEPTION 'Owner bootstrap ACL too broad/narrow'; END IF;
END;
$acl$;
SELECT 'PASS: Owner bootstrap pending-only + one-use challenge SQL (mocked Auth)' AS result;
