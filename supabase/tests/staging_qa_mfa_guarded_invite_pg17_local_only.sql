-- Market Pro QA STAGING Owner/Staff MFA invitation regression
-- Disposable PostgreSQL 17 ONLY. Fake Auth users + signed-JWT claim simulation.
\set ON_ERROR_STOP on
CREATE ROLE anon;
CREATE ROLE authenticated;
CREATE SCHEMA auth;
CREATE SCHEMA extensions;
CREATE SCHEMA marketpro_private;
CREATE EXTENSION pgcrypto WITH SCHEMA extensions;

CREATE TABLE auth.users(
 id uuid PRIMARY KEY, email text NOT NULL, email_confirmed_at timestamptz
);
CREATE TABLE auth.mfa_factors(
 id uuid PRIMARY KEY, user_id uuid NOT NULL REFERENCES auth.users(id),
 factor_type text NOT NULL, status text NOT NULL
);
CREATE TABLE auth.sessions(
 id uuid PRIMARY KEY, user_id uuid NOT NULL REFERENCES auth.users(id),
 not_after timestamptz
);
CREATE FUNCTION auth.uid() RETURNS uuid LANGUAGE sql STABLE
AS $f$ SELECT NULLIF(current_setting('request.jwt.claim.sub',true),'')::uuid $f$;
CREATE FUNCTION auth.jwt() RETURNS jsonb LANGUAGE sql STABLE
AS $f$ SELECT COALESCE(NULLIF(current_setting('request.jwt.claims',true),''),'{}')::jsonb $f$;

CREATE TABLE public.workspaces(id uuid PRIMARY KEY,name text);
CREATE TABLE public.workspace_members(
 workspace_id uuid NOT NULL REFERENCES public.workspaces(id),
 user_id uuid NOT NULL REFERENCES auth.users(id),
 role text NOT NULL,
 PRIMARY KEY(workspace_id,user_id)
);
CREATE TABLE public.registered_devices(
 id uuid PRIMARY KEY,workspace_id uuid NOT NULL,
 user_id uuid NOT NULL,enabled boolean NOT NULL DEFAULT false,
 can_write boolean NOT NULL DEFAULT false
);
CREATE TABLE marketpro_private.pending_team_enrollments(
 workspace_id uuid NOT NULL REFERENCES public.workspaces(id),
 staff_user_id uuid NOT NULL REFERENCES auth.users(id),
 inviter_user_id uuid NOT NULL REFERENCES auth.users(id),
 intended_device_id uuid NOT NULL UNIQUE,
 device_label text NOT NULL,
 token_sha256 bytea NOT NULL CHECK(octet_length(token_sha256)=32),
 expires_at timestamptz NOT NULL,status text NOT NULL,
 created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 accepted_at timestamptz,revoked_at timestamptz,activated_at timestamptz,
 PRIMARY KEY(workspace_id,staff_user_id)
);
REVOKE ALL ON marketpro_private.pending_team_enrollments FROM PUBLIC,anon,authenticated;
ALTER TABLE marketpro_private.pending_team_enrollments ENABLE ROW LEVEL SECURITY;

INSERT INTO public.workspaces VALUES
('af77cb66-76b2-492a-a460-06132f468713','Market Pro Two-Phone QA');
INSERT INTO auth.users VALUES
('98c3b101-d452-4b6b-902c-0b1a31fc340c','owner@qa.invalid',clock_timestamp()),
('b805598b-421f-447b-aa4c-ffb66f721338','staff@qa.invalid',clock_timestamp()),
('aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa','outsider@qa.invalid',clock_timestamp());
INSERT INTO public.workspace_members VALUES
('af77cb66-76b2-492a-a460-06132f468713','98c3b101-d452-4b6b-902c-0b1a31fc340c','owner');
INSERT INTO auth.mfa_factors VALUES
('bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb','98c3b101-d452-4b6b-902c-0b1a31fc340c','totp','verified');
INSERT INTO auth.sessions VALUES
('11111111-1111-4111-8111-111111111111','98c3b101-d452-4b6b-902c-0b1a31fc340c',clock_timestamp()+interval '1 day'),
('22222222-2222-4222-8222-222222222222','b805598b-421f-447b-aa4c-ffb66f721338',clock_timestamp()+interval '1 day');

\i supabase/staging/staging_qa_mfa_guarded_invite_REVIEW_ONLY.sql

DO $test$
DECLARE
 v_workspace uuid := 'af77cb66-76b2-492a-a460-06132f468713';
 v_owner uuid := '98c3b101-d452-4b6b-902c-0b1a31fc340c';
 v_staff uuid := 'b805598b-421f-447b-aa4c-ffb66f721338';
 v_owner_device uuid := '33333333-3333-4333-8333-333333333333';
 v_staff_device uuid := '44444444-4444-4444-8444-444444444444';
 v_alt_device uuid := '55555555-5555-4555-8555-555555555555';
 v_owner_session uuid := '11111111-1111-4111-8111-111111111111';
 v_staff_session uuid := '22222222-2222-4222-8222-222222222222';
 v_owner_claim jsonb;
 v_staff_claim jsonb;
 v_result jsonb;
 v_token text;
 v_second_token text;
 v_denied boolean;
BEGIN
  -- Valid MFA JWT used only in local disposable fixture.
  v_owner_claim := jsonb_build_object(
    'sub',v_owner,'aal','aal2','session_id',v_owner_session,
    'is_anonymous',false,'amr',jsonb_build_array(jsonb_build_object(
      'method','totp','timestamp',extract(epoch from clock_timestamp())::bigint
    ))
  );
  v_staff_claim := jsonb_build_object(
    'sub',v_staff,'aal','aal1','session_id',v_staff_session,
    'is_anonymous',false
  );
  PERFORM set_config('request.jwt.claim.sub',v_owner::text,true);
  PERFORM set_config('request.jwt.claims',v_owner_claim::text,true);
  IF marketpro_private.qa_current_owner_is_fresh(v_workspace,v_owner_device) THEN
    RAISE EXCEPTION 'Owner has no enrolled phone yet; required fail-closed';
  END IF;

  v_denied:=false;
  BEGIN
    PERFORM public.marketpro_qa_owner_issue_invite(
      v_workspace,v_owner_device,v_staff,v_staff_device,'Test Staff Phone');
  EXCEPTION WHEN SQLSTATE '42501' THEN v_denied:=true; END;
  IF NOT v_denied THEN RAISE EXCEPTION 'Disabled gate allowed an invite'; END IF;

  -- Manual gate switch is a TEST FIXTURE ONLY, never shipped to Staging.
  UPDATE marketpro_private.qa_invite_gate SET enabled=true
  WHERE workspace_id=v_workspace;
  v_denied:=false;
  BEGIN
    PERFORM public.marketpro_qa_owner_issue_invite(
      v_workspace,v_owner_device,v_staff,v_staff_device,'Test Staff Phone');
  EXCEPTION WHEN SQLSTATE '42501' THEN v_denied:=true; END;
  IF NOT v_denied THEN RAISE EXCEPTION 'Owner without phone could invite'; END IF;

  INSERT INTO public.registered_devices VALUES (
    v_owner_device,v_workspace,v_owner,true,true
  );
  v_denied:=false;
  PERFORM set_config('request.jwt.claims',
    jsonb_set(v_owner_claim,'{aal}','"aal1"'::jsonb)::text,true);
  BEGIN
    PERFORM public.marketpro_qa_owner_issue_invite(
      v_workspace,v_owner_device,v_staff,v_staff_device,'Test Staff Phone');
  EXCEPTION WHEN SQLSTATE '42501' THEN v_denied:=true; END;
  IF NOT v_denied THEN RAISE EXCEPTION 'AAL1 Owner could invite'; END IF;

  v_denied:=false;
  PERFORM set_config('request.jwt.claims',
     jsonb_set(v_owner_claim,'{amr}',jsonb_build_array(
       jsonb_build_object('method','totp',
         'timestamp',extract(epoch from clock_timestamp())::bigint-1200)))::text,true);
  BEGIN
    PERFORM public.marketpro_qa_owner_issue_invite(
      v_workspace,v_owner_device,v_staff,v_staff_device,'Test Staff Phone');
  EXCEPTION WHEN SQLSTATE '42501' THEN v_denied:=true; END;
  IF NOT v_denied THEN RAISE EXCEPTION 'Stale Owner TOTP accepted'; END IF;

  v_denied:=false;
  PERFORM set_config('request.jwt.claims',
    jsonb_set(v_owner_claim,'{session_id}','"66666666-6666-4666-8666-666666666666"'::jsonb)::text,true);
  BEGIN
    PERFORM public.marketpro_qa_owner_issue_invite(
      v_workspace,v_owner_device,v_staff,v_staff_device,'Test Staff Phone');
  EXCEPTION WHEN SQLSTATE '42501' THEN v_denied:=true; END;
  IF NOT v_denied THEN RAISE EXCEPTION 'Nonexistent Auth session accepted'; END IF;

  PERFORM set_config('request.jwt.claims',v_owner_claim::text,true);
  v_denied:=false;
  BEGIN
    PERFORM public.marketpro_qa_owner_issue_invite(
      v_workspace,v_alt_device,v_staff,v_staff_device,'Test Staff Phone');
  EXCEPTION WHEN SQLSTATE '42501' THEN v_denied:=true; END;
  IF NOT v_denied THEN RAISE EXCEPTION 'Wrong Owner phone accepted'; END IF;

  v_result:=public.marketpro_qa_owner_issue_invite(
    v_workspace,v_owner_device,v_staff,v_staff_device,'Test Staff Phone');
  v_token:=v_result->>'one_time_code';
  IF length(v_token)<>64 OR v_token !~ '^[a-f0-9]{64}$'
     OR (v_result->>'staff_can_write')::boolean IS DISTINCT FROM false
     OR (SELECT token_sha256 FROM marketpro_private.pending_team_enrollments
         WHERE staff_user_id=v_staff)
        IS DISTINCT FROM extensions.digest(convert_to(v_token,'UTF8'),'sha256')
  THEN RAISE EXCEPTION 'Owner invite token is not securely stored'; END IF;
  IF (SELECT count(*) FROM public.workspace_members) <> 1 OR
     (SELECT count(*) FROM public.registered_devices) <> 1 THEN
    RAISE EXCEPTION 'Invitation granted Staff business access prematurely';
  END IF;

  -- Rotate invitation to invalidate the old code.
  v_result:=public.marketpro_qa_owner_issue_invite(
    v_workspace,v_owner_device,v_staff,v_staff_device,'Test Staff Phone');
  v_second_token:=v_result->>'one_time_code';
  IF v_second_token=v_token THEN RAISE EXCEPTION 'Token rotation failed'; END IF;

  PERFORM set_config('request.jwt.claim.sub',v_staff::text,true);
  PERFORM set_config('request.jwt.claims',v_staff_claim::text,true);

  v_denied:=false;
  BEGIN
    PERFORM public.marketpro_qa_staff_accept_invite(
      v_workspace,v_staff_device,v_token);
  EXCEPTION WHEN SQLSTATE '42501' THEN v_denied:=true; END;
  IF NOT v_denied THEN RAISE EXCEPTION 'Rotated token still usable'; END IF;

  v_denied:=false;
  BEGIN
    PERFORM public.marketpro_qa_staff_accept_invite(
      v_workspace,v_alt_device,v_second_token);
  EXCEPTION WHEN SQLSTATE '42501' THEN v_denied:=true; END;
  IF NOT v_denied THEN RAISE EXCEPTION 'Wrong Staff phone accepted'; END IF;

  v_denied:=false;
  PERFORM set_config('request.jwt.claims',
    jsonb_set(v_staff_claim,'{session_id}','"88888888-8888-4888-8888-888888888888"'::jsonb)::text,true);
  BEGIN
    PERFORM public.marketpro_qa_staff_accept_invite(
      v_workspace,v_staff_device,v_second_token);
  EXCEPTION WHEN SQLSTATE '42501' THEN v_denied:=true; END;
  IF NOT v_denied THEN RAISE EXCEPTION 'Signed-out Staff session accepted'; END IF;

  PERFORM set_config('request.jwt.claims',v_staff_claim::text,true);
  v_result:=public.marketpro_qa_staff_accept_invite(
    v_workspace,v_staff_device,v_second_token);
  IF (v_result->>'accepted')::boolean IS DISTINCT FROM true
    OR (v_result->>'staff_can_read')::boolean IS DISTINCT FROM false
    OR (SELECT status FROM marketpro_private.pending_team_enrollments
        WHERE staff_user_id=v_staff)<>'accepted'
  THEN RAISE EXCEPTION 'Valid Staff code did not register pending acceptance'; END IF;

  v_denied:=false;
  BEGIN
    PERFORM public.marketpro_qa_staff_accept_invite(
      v_workspace,v_staff_device,v_second_token);
  EXCEPTION WHEN SQLSTATE '42501' THEN v_denied:=true; END;
  IF NOT v_denied THEN RAISE EXCEPTION 'Used invite code accepted again'; END IF;

  IF (SELECT count(*) FROM public.workspace_members)<>1
   OR (SELECT count(*) FROM public.registered_devices)<>1 THEN
    RAISE EXCEPTION 'Staff was granted device or workspace access'; END IF;
END;
$test$;

DO $acl$
BEGIN
 IF has_function_privilege('anon',
 'public.marketpro_qa_owner_issue_invite(uuid,uuid,uuid,uuid,text)','EXECUTE')
 OR has_function_privilege('authenticated',
 'public.marketpro_qa_owner_issue_invite(uuid,uuid,uuid,uuid,text)','EXECUTE')
 OR has_function_privilege('authenticated',
 'public.marketpro_qa_staff_accept_invite(uuid,uuid,text)','EXECUTE')
 OR has_table_privilege('authenticated',
 'marketpro_private.qa_invite_gate','UPDATE')
 THEN RAISE EXCEPTION 'Staging RPCs/gate unexpectedly exposed'; END IF;
END;
$acl$;
SELECT 'PASS: QA invitation is MFA/session/device guarded and no phone activated' AS result;
