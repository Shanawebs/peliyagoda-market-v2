-- Isolated disposable PostgreSQL 17 test only. NOT for live Supabase.
\set ON_ERROR_STOP on
CREATE ROLE anon;
CREATE ROLE authenticated;
CREATE SCHEMA auth;
CREATE SCHEMA marketpro_private;
CREATE SCHEMA extensions;
CREATE EXTENSION pgcrypto WITH SCHEMA extensions;

CREATE TABLE auth.users(
  id uuid PRIMARY KEY,
  email text,
  email_confirmed_at timestamptz
);
CREATE TABLE public.workspace_members (
  workspace_id uuid NOT NULL,
  user_id uuid NOT NULL,
  role text NOT NULL CHECK(role IN ('owner','editor','viewer')),
  PRIMARY KEY(workspace_id,user_id)
);
CREATE TABLE public.registered_devices(
  id uuid PRIMARY KEY,
  workspace_id uuid NOT NULL,
  user_id uuid NOT NULL,
  enabled boolean NOT NULL DEFAULT false,
  can_write boolean NOT NULL DEFAULT false
);
CREATE FUNCTION auth.uid() RETURNS uuid LANGUAGE sql STABLE
AS $f$ SELECT NULLIF(current_setting('request.jwt.claim.sub',true),'')::uuid $f$;
CREATE FUNCTION auth.jwt() RETURNS jsonb LANGUAGE sql STABLE
AS $f$ SELECT COALESCE(NULLIF(current_setting('request.jwt.claims',true),''),'{}')::jsonb $f$;

INSERT INTO auth.users(id,email,email_confirmed_at) VALUES
('22222222-2222-4222-8222-222222222222','owner@test.local',now()),
('33333333-3333-4333-8333-333333333333','staff@test.local',now()),
('44444444-4444-4444-8444-444444444444','viewer@test.local',now()),
('55555555-5555-4555-8555-555555555555','unverified@test.local',null),
('66666666-6666-4666-8666-666666666666','secondstaff@test.local',now());
INSERT INTO public.workspace_members VALUES
('11111111-1111-4111-8111-111111111111','22222222-2222-4222-8222-222222222222','owner'),
('11111111-1111-4111-8111-111111111111','44444444-4444-4444-8444-444444444444','viewer');
INSERT INTO public.registered_devices VALUES
('a1111111-1111-4111-8111-111111111111','11111111-1111-4111-8111-111111111111','22222222-2222-4222-8222-222222222222',true,true),
('c1111111-1111-4111-8111-111111111111','11111111-1111-4111-8111-111111111111','44444444-4444-4444-8444-444444444444',true,false);

\i supabase/proposals/v358_owner_invite_staff_PENDING_ONLY.sql

DO $test$
DECLARE
  w uuid := '11111111-1111-4111-8111-111111111111';
  o text := '22222222-2222-4222-8222-222222222222';
  s text := '33333333-3333-4333-8333-333333333333';
  other text := '66666666-6666-4666-8666-666666666666';
  viewer text := '44444444-4444-4444-8444-444444444444';
  unverified uuid := '55555555-5555-4555-8555-555555555555';
  owner_phone uuid := 'a1111111-1111-4111-8111-111111111111';
  staff_phone uuid := 'b1111111-1111-4111-8111-111111111111';
  alt_staff_phone uuid := 'd1111111-1111-4111-8111-111111111111';
  another_phone uuid := 'd2222222-2222-4222-8222-222222222222';
  response jsonb;
  token text;
  second_token text;
  denied boolean;
BEGIN
  PERFORM set_config('request.jwt.claims','{"is_anonymous":false}',true);
  PERFORM set_config('request.jwt.claim.sub',o,true);
  response := public.marketpro_owner_invite_staff(w,owner_phone,s::uuid,staff_phone,'Market Team Phone');
  token := response->>'one_time_code';
  IF length(token)<>64 OR token !~ '^[0-9a-f]{64}$' THEN
    RAISE EXCEPTION 'Owner invite failed to issue 256 bit code';
  END IF;
  IF (SELECT count(*) FROM public.workspace_members WHERE user_id=s::uuid)<>0 OR
     (SELECT count(*) FROM public.registered_devices WHERE user_id=s::uuid)<>0 THEN
    RAISE EXCEPTION 'Invitation prematurely granted workspace membership or writer';
  END IF;
  IF (SELECT count(*) FROM public.registered_devices WHERE enabled AND can_write)<>1 THEN
    RAISE EXCEPTION 'Invitation changed old active writer';
  END IF;
  IF (SELECT status FROM marketpro_private.pending_team_enrollments WHERE staff_user_id=s::uuid)<>'invited' THEN
    RAISE EXCEPTION 'Invitation not stored as pending';
  END IF;
  IF (SELECT token_sha256 FROM marketpro_private.pending_team_enrollments WHERE staff_user_id=s::uuid)
      IS DISTINCT FROM extensions.digest(convert_to(token,'UTF8'),'sha256') THEN
    RAISE EXCEPTION 'Hash not persisted correctly';
  END IF;

  -- Staff cannot invite themselves or assume owner identity.
  PERFORM set_config('request.jwt.claim.sub',s,true);
  denied:=false;
  BEGIN
    PERFORM public.marketpro_owner_invite_staff(w,owner_phone,other::uuid,another_phone,'Forged Team Phone');
  EXCEPTION WHEN SQLSTATE '42501' THEN denied:=true; END;
  IF NOT denied THEN RAISE EXCEPTION 'Staff could invite themselves'; END IF;

  denied:=false;
  BEGIN
    PERFORM public.marketpro_staff_accept_invitation(w,staff_phone,repeat('0',64));
  EXCEPTION WHEN SQLSTATE '42501' THEN denied:=true; END;
  IF NOT denied THEN RAISE EXCEPTION 'Incorrect invitation code accepted'; END IF;

  denied:=false;
  BEGIN
    PERFORM public.marketpro_staff_accept_invitation(w,another_phone,token);
  EXCEPTION WHEN SQLSTATE '42501' THEN denied:=true; END;
  IF NOT denied THEN RAISE EXCEPTION 'Different phone identity accepted'; END IF;

  -- Third-party login cannot use same secret.
  PERFORM set_config('request.jwt.claim.sub',viewer,true);
  denied:=false;
  BEGIN
    PERFORM public.marketpro_staff_accept_invitation(w,staff_phone,token);
  EXCEPTION WHEN SQLSTATE '42501' THEN denied:=true; END;
  IF NOT denied THEN RAISE EXCEPTION 'Different Auth user hijacked invitation'; END IF;

  PERFORM set_config('request.jwt.claim.sub',s,true);
  response:=public.marketpro_staff_accept_invitation(w,staff_phone,token);
  IF response->>'status'<>'awaiting_owner_activation_and_server_cutover' THEN
    RAISE EXCEPTION 'Staff acceptance incorrectly claims live access';
  END IF;
  IF (SELECT count(*) FROM public.workspace_members WHERE user_id=s::uuid)<>0 OR
     (SELECT count(*) FROM public.registered_devices WHERE user_id=s::uuid)<>0 THEN
    RAISE EXCEPTION 'Claim unexpectedly gave live Cloud access';
  END IF;
  denied:=false;
  BEGIN
    PERFORM public.marketpro_staff_accept_invitation(w,staff_phone,token);
  EXCEPTION WHEN SQLSTATE '42501' THEN denied:=true; END;
  IF NOT denied THEN RAISE EXCEPTION 'Consumed one-time code reused'; END IF;

  -- Staff cannot revoke or alter invitations.
  denied:=false;
  BEGIN
    PERFORM public.marketpro_owner_revoke_staff_invitation(w,owner_phone,s::uuid);
  EXCEPTION WHEN SQLSTATE '42501' THEN denied:=true; END;
  IF NOT denied THEN RAISE EXCEPTION 'Staff could revoke owner invite'; END IF;

  -- Owner can revoke accepted invitation (still no actual staff membership).
  PERFORM set_config('request.jwt.claim.sub',o,true);
  response:=public.marketpro_owner_revoke_staff_invitation(w,owner_phone,s::uuid);
  IF (response->>'revoked')::boolean IS DISTINCT FROM true OR
     (SELECT status FROM marketpro_private.pending_team_enrollments WHERE staff_user_id=s::uuid)<>'revoked' THEN
    RAISE EXCEPTION 'Owner failed to revoke pending invitation';
  END IF;

  -- Existing viewer or owner user cannot be re-enrolled as purchasing staff.
  denied:=false;
  BEGIN
    PERFORM public.marketpro_owner_invite_staff(
      w,owner_phone,viewer::uuid,another_phone,'Viewer Device');
  EXCEPTION WHEN unique_violation THEN denied:=true; END;
  IF NOT denied THEN RAISE EXCEPTION 'Existing viewer role replaced'; END IF;

  denied:=false;
  BEGIN
    PERFORM public.marketpro_owner_invite_staff(
      w,owner_phone,o::uuid,another_phone,'Owner Device');
  EXCEPTION WHEN invalid_parameter_value THEN denied:=true; END;
  IF NOT denied THEN RAISE EXCEPTION 'Owner could invite self as staff'; END IF;

  denied:=false;
  BEGIN
    PERFORM public.marketpro_owner_invite_staff(
      w,owner_phone,unverified,alt_staff_phone,'No Email Verified');
  EXCEPTION WHEN SQLSTATE '42501' THEN denied:=true; END;
  IF NOT denied THEN RAISE EXCEPTION 'Unverified staff email invited'; END IF;

  denied:=false;
  BEGIN
    PERFORM public.marketpro_owner_invite_staff(
      w,'c1111111-1111-4111-8111-111111111111'::uuid,
      other::uuid,another_phone,'Wrong Phone');
  EXCEPTION WHEN SQLSTATE '42501' THEN denied:=true; END;
  IF NOT denied THEN RAISE EXCEPTION 'Owner could spoof viewer device'; END IF;

  -- Revoking and re-inviting resets old token; old token must fail.
  response:=public.marketpro_owner_invite_staff(
    w,owner_phone,s::uuid,staff_phone,'New Owner Invite');
  second_token:=response->>'one_time_code';
  IF second_token=token OR length(second_token)<>64 THEN
     RAISE EXCEPTION 'New invitation did not rotate token';
  END IF;
  PERFORM set_config('request.jwt.claim.sub',s,true);
  denied:=false;
  BEGIN
    PERFORM public.marketpro_staff_accept_invitation(w,staff_phone,token);
  EXCEPTION WHEN SQLSTATE '42501' THEN denied:=true; END;
  IF NOT denied THEN RAISE EXCEPTION 'Old invitation token still valid'; END IF;

  -- Expiry checked on SERVER, not phone clock.
  UPDATE marketpro_private.pending_team_enrollments
  SET expires_at=clock_timestamp()-interval '1 second'
  WHERE staff_user_id=s::uuid;
  denied:=false;
  BEGIN
    PERFORM public.marketpro_staff_accept_invitation(w,staff_phone,second_token);
  EXCEPTION WHEN SQLSTATE '42501' THEN denied:=true; END;
  IF NOT denied THEN RAISE EXCEPTION 'Expired invitation accepted'; END IF;

  -- Owner cannot invite while signed out/anonymous.
  PERFORM set_config('request.jwt.claim.sub',o,true);
  PERFORM set_config('request.jwt.claims','{"is_anonymous":true}',true);
  denied:=false;
  BEGIN
    PERFORM public.marketpro_owner_invite_staff(
      w,owner_phone,other::uuid,another_phone,'Anon Attempt');
  EXCEPTION WHEN SQLSTATE '42501' THEN denied:=true; END;
  IF NOT denied THEN RAISE EXCEPTION 'Anonymous owner invite allowed'; END IF;
END;
$test$;

DO $privileges$
BEGIN
  IF has_table_privilege('authenticated',
       'marketpro_private.pending_team_enrollments','SELECT') THEN
    RAISE EXCEPTION 'Private one-time token table exposed to authenticated';
  END IF;
  IF has_table_privilege('anon',
       'marketpro_private.pending_team_enrollments','SELECT') THEN
    RAISE EXCEPTION 'Private one-time token table exposed to anon';
  END IF;
  IF has_function_privilege('anon',
       'public.marketpro_owner_invite_staff(uuid,uuid,uuid,uuid,text)','EXECUTE') THEN
    RAISE EXCEPTION 'Anon may invite';
  END IF;
  IF has_function_privilege('anon',
       'public.marketpro_staff_accept_invitation(uuid,uuid,text)','EXECUTE') THEN
    RAISE EXCEPTION 'Anon may claim';
  END IF;
  IF has_function_privilege('authenticated',
       'marketpro_private.require_enrolled_owner(uuid,uuid)','EXECUTE') THEN
    RAISE EXCEPTION 'Private helper can be called by authenticated';
  END IF;
END;
$privileges$;
\echo V358 OWNER APPROVED STAFF INVITATION TESTS: PASS
