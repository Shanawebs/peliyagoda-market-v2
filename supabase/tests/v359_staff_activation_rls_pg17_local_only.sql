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


-- Mock the direct PostgREST-accessible financial tables exactly for RLS policy tests.
ALTER TABLE public.registered_devices ADD COLUMN device_label text;
CREATE TABLE public.categories (id uuid PRIMARY KEY,workspace_id uuid NOT NULL);
CREATE TABLE public.items (id uuid PRIMARY KEY,workspace_id uuid NOT NULL);
CREATE TABLE public.requisitions (id uuid PRIMARY KEY,workspace_id uuid NOT NULL);
CREATE TABLE public.purchases (id uuid PRIMARY KEY,workspace_id uuid NOT NULL);
CREATE TABLE public.purchase_additions (id uuid PRIMARY KEY,workspace_id uuid NOT NULL);
CREATE TABLE public.daily_cash_received (id uuid PRIMARY KEY,workspace_id uuid NOT NULL);

ALTER TABLE public.categories ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.items ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.requisitions ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.purchases ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.purchase_additions ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.daily_cash_received ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.workspace_members ENABLE ROW LEVEL SECURITY;

GRANT USAGE ON SCHEMA auth TO authenticated;
GRANT SELECT ON public.workspace_members TO authenticated;
GRANT SELECT ON public.categories,public.items,public.requisitions,
  public.purchases,public.purchase_additions,public.daily_cash_received
  TO authenticated;

CREATE POLICY members_own_membership ON public.workspace_members FOR SELECT
TO authenticated USING (user_id=(SELECT auth.uid()));

INSERT INTO public.categories VALUES('c1111111-1111-4111-8111-111111111111','11111111-1111-4111-8111-111111111111');
INSERT INTO public.items VALUES('c2222222-2222-4222-8222-222222222222','11111111-1111-4111-8111-111111111111');
INSERT INTO public.requisitions VALUES('c3333333-3333-4333-8333-333333333333','11111111-1111-4111-8111-111111111111');
INSERT INTO public.purchases VALUES('c4444444-4444-4444-8444-444444444444','11111111-1111-4111-8111-111111111111');
INSERT INTO public.purchase_additions VALUES('c5555555-5555-4555-8555-555555555555','11111111-1111-4111-8111-111111111111');
INSERT INTO public.daily_cash_received VALUES('c6666666-6666-4666-8666-666666666666','11111111-1111-4111-8111-111111111111');
INSERT INTO public.purchases VALUES('c7777777-7777-4777-8777-777777777777','99999999-9999-4999-8999-999999999999');

\i supabase/proposals/v359_staff_readonly_activation_rls_REVIEW_ONLY.sql

DO $test$
DECLARE
 w uuid := '11111111-1111-4111-8111-111111111111';
 owner_id text := '22222222-2222-4222-8222-222222222222';
 staff_id text := '33333333-3333-4333-8333-333333333333';
 viewer_id text := '44444444-4444-4444-8444-444444444444';
 owner_device uuid := 'a1111111-1111-4111-8111-111111111111';
 staff_device uuid := 'b1111111-1111-4111-8111-111111111111';
 r jsonb;
 code text;
 refused boolean;
BEGIN
 PERFORM set_config('request.jwt.claim.sub',owner_id,true);
 PERFORM set_config('request.jwt.claims','{"is_anonymous":false}',true);
 r:=public.marketpro_owner_invite_staff(w,owner_device,staff_id::uuid,
    staff_device,'Team Entry Only');
 code:=r->>'one_time_code';
 IF length(code)<>64 THEN RAISE EXCEPTION 'Invite did not produce secure token'; END IF;
 PERFORM set_config('request.jwt.claim.sub',staff_id,true);
 r:=public.marketpro_staff_accept_invitation(w,staff_device,code);
 IF r->>'status'<>'awaiting_owner_activation_and_server_cutover' THEN
    RAISE EXCEPTION 'Unexpected claim state';
 END IF;
 PERFORM set_config('request.jwt.claim.sub',owner_id,true);
 refused:=false;
 BEGIN
   PERFORM public.marketpro_owner_activate_staff_readonly(
     w,owner_device,staff_id::uuid);
 EXCEPTION WHEN insufficient_privilege THEN refused:=true;
 END;
 IF NOT refused THEN RAISE EXCEPTION 'Read-only enrollment activated without rollout gates'; END IF;

 INSERT INTO marketpro_private.team_rollout_gate (
     workspace_id,staff_direct_rest_restricted,team_pull_rpc_reviewed,team_writes_reviewed
 ) VALUES(w,true,false,false);
 refused:=false;
 BEGIN
   PERFORM public.marketpro_owner_activate_staff_readonly(
     w,owner_device,staff_id::uuid);
 EXCEPTION WHEN insufficient_privilege THEN refused:=true;
 END;
 IF NOT refused THEN RAISE EXCEPTION 'Read-only enrollment activated without reviewed pull RPC'; END IF;

 UPDATE marketpro_private.team_rollout_gate SET
    staff_direct_rest_restricted=true,team_pull_rpc_reviewed=true,
    team_writes_reviewed=false
 WHERE workspace_id=w;
 r:=public.marketpro_owner_activate_staff_readonly(w,owner_device,staff_id::uuid);
 IF NOT (r->>'activated')::boolean OR
    NOT (r->>'read_only')::boolean OR
    (r->>'can_write')::boolean THEN
   RAISE EXCEPTION 'Activation incorrectly granted writes';
 END IF;
 IF (SELECT count(*) FROM public.workspace_members
     WHERE workspace_id=w AND user_id=staff_id::uuid AND role='purchasing_staff')<>1
 OR (SELECT count(*) FROM public.registered_devices
     WHERE workspace_id=w AND id=staff_device AND enabled AND NOT can_write)<>1 THEN
   RAISE EXCEPTION 'Staff must have only one active read-only device';
 END IF;
 IF (SELECT count(*) FROM public.registered_devices WHERE enabled AND can_write)<>1
 OR (SELECT count(*) FROM public.registered_devices WHERE id=owner_device AND enabled AND can_write)<>1 THEN
   RAISE EXCEPTION 'Legacy live writer not preserved';
 END IF;

 refused:=false;
 BEGIN
   PERFORM public.marketpro_owner_activate_staff_readonly(w,owner_device,staff_id::uuid);
 EXCEPTION WHEN insufficient_privilege THEN refused:=true;
 END;
 IF NOT refused THEN RAISE EXCEPTION 'Replay activated same enrollment'; END IF;

 PERFORM set_config('request.jwt.claim.sub',viewer_id,true);
 refused:=false;
 BEGIN
   PERFORM public.marketpro_owner_revoke_staff_device(w,owner_device,staff_id::uuid);
 EXCEPTION WHEN insufficient_privilege THEN refused:=true;
 END;
 IF NOT refused THEN RAISE EXCEPTION 'Viewer revoked staff phone'; END IF;

 PERFORM set_config('request.jwt.claim.sub',staff_id,true);
 refused:=false;
 BEGIN
   PERFORM public.marketpro_owner_revoke_staff_device(w,owner_device,staff_id::uuid);
 EXCEPTION WHEN insufficient_privilege THEN refused:=true;
 END;
 IF NOT refused THEN RAISE EXCEPTION 'Staff revoked own phone using owner API'; END IF;
END;
$test$;

-- These are REAL RLS SELECT checks as PostgREST's authenticated role.
SET ROLE authenticated;
SELECT set_config('request.jwt.claim.sub','33333333-3333-4333-8333-333333333333',false);
DO $rls$
BEGIN
  IF (SELECT count(*) FROM public.workspace_members)<>1 THEN
    RAISE EXCEPTION 'Staff membership should be visible only to self';
  END IF;
  IF (SELECT count(*) FROM public.categories)<>0 OR
     (SELECT count(*) FROM public.items)<>0 OR
     (SELECT count(*) FROM public.requisitions)<>0 OR
     (SELECT count(*) FROM public.purchases)<>0 OR
     (SELECT count(*) FROM public.purchase_additions)<>0 OR
     (SELECT count(*) FROM public.daily_cash_received)<>0 THEN
    RAISE EXCEPTION 'CRITICAL: staff can bypass enrolled device via direct REST RLS!';
  END IF;
END;
$rls$;

SELECT set_config('request.jwt.claim.sub','22222222-2222-4222-8222-222222222222',false);
DO $rls$
BEGIN
  IF (SELECT count(*) FROM public.categories)<>1 OR
     (SELECT count(*) FROM public.items)<>1 OR
     (SELECT count(*) FROM public.requisitions)<>1 OR
     (SELECT count(*) FROM public.purchases)<>1 OR
     (SELECT count(*) FROM public.purchase_additions)<>1 OR
     (SELECT count(*) FROM public.daily_cash_received)<>1 THEN
    RAISE EXCEPTION 'Owner legacy dashboard/Data API SELECT was broken';
  END IF;
END;
$rls$;

SELECT set_config('request.jwt.claim.sub','44444444-4444-4444-8444-444444444444',false);
DO $rls$
BEGIN
 IF (SELECT count(*) FROM public.purchases)<>1 THEN
   RAISE EXCEPTION 'Existing viewer data read regression';
 END IF;
END;
$rls$;
RESET ROLE;

-- In PostgreSQL mock, owner can revoke staff without touching owner writer.
SELECT set_config('request.jwt.claims','{"is_anonymous":false}',false);
SELECT set_config('request.jwt.claim.sub','22222222-2222-4222-8222-222222222222',false);
DO $revoke$
DECLARE
 r jsonb;
BEGIN
 r:=public.marketpro_owner_revoke_staff_device(
    '11111111-1111-4111-8111-111111111111'::uuid,
    'a1111111-1111-4111-8111-111111111111'::uuid,
    '33333333-3333-4333-8333-333333333333'::uuid
 );
 IF (r->>'revoked_devices')::int<>1 THEN
    RAISE EXCEPTION 'Owner could not revoke staff read phone';
 END IF;
 IF EXISTS(SELECT 1 FROM public.registered_devices
    WHERE user_id='33333333-3333-4333-8333-333333333333'::uuid
    AND (enabled OR can_write)) THEN
   RAISE EXCEPTION 'Staff device not revoked';
 END IF;
 IF (SELECT count(*) FROM public.registered_devices
   WHERE enabled AND can_write)<>1 THEN
   RAISE EXCEPTION 'Owner live device altered';
 END IF;
END;
$revoke$;

DO $priv$
BEGIN
 IF has_function_privilege('anon',
  'public.marketpro_owner_activate_staff_readonly(uuid,uuid,uuid)','EXECUTE') OR
    has_function_privilege('anon',
  'public.marketpro_owner_revoke_staff_device(uuid,uuid,uuid)','EXECUTE') THEN
  RAISE EXCEPTION 'Anonymous role can invoke sensitive owner activation';
 END IF;
 IF has_table_privilege('authenticated','marketpro_private.team_rollout_gate','SELECT') THEN
  RAISE EXCEPTION 'Protected rollout flags visible to authenticated role';
 END IF;
END;
$priv$;
\echo V359 RLS bypass, owner-only read activation and revoke tests: PASS
