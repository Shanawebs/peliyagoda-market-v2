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


\i supabase/proposals/v368_owner_replace_team_phone_REVIEW_ONLY.sql

\i supabase/proposals/v368_owner_replace_team_phone_REVIEW_ONLY.sql

DO $test$
DECLARE
 w uuid := '11111111-1111-4111-8111-111111111111';
 owner text := '22222222-2222-4222-8222-222222222222';
 staff text := '33333333-3333-4333-8333-333333333333';
 viewer text := '44444444-4444-4444-8444-444444444444';
 owner_dev uuid := 'a1111111-1111-4111-8111-111111111111';
 first_dev uuid := 'b1111111-1111-4111-8111-111111111111';
 second_dev uuid := 'd1111111-1111-4111-8111-111111111111';
 third_dev uuid := 'e1111111-1111-4111-8111-111111111111';
 response jsonb;
 invite_code text;
 denied boolean;
BEGIN
 PERFORM set_config('request.jwt.claims','{"is_anonymous":false}',true);
 PERFORM set_config('request.jwt.claim.sub',owner,true);
 response:=public.marketpro_owner_invite_staff(
    w,owner_dev,staff::uuid,first_dev,'Old Team Phone');
 invite_code:=response->>'one_time_code';
 PERFORM set_config('request.jwt.claim.sub',staff,true);
 PERFORM public.marketpro_staff_accept_invitation(w,first_dev,invite_code);
 PERFORM set_config('request.jwt.claim.sub',owner,true);
 INSERT INTO marketpro_private.team_rollout_gate(
   workspace_id,staff_direct_rest_restricted,team_pull_rpc_reviewed,
   team_writes_reviewed
 ) VALUES(w,true,true,false);
 response:=public.marketpro_owner_activate_staff_readonly(w,owner_dev,staff::uuid);
 IF NOT (response->>'activated')::boolean THEN
   RAISE EXCEPTION 'Initial old phone enrollment failed';
 END IF;
 IF (SELECT count(*) FROM public.registered_devices WHERE enabled AND can_write)<>1 THEN
   RAISE EXCEPTION 'Initial Owner writer disturbed';
 END IF;

 -- STAFF / VIEWER MAY NEVER replace or revoke another phone.
 PERFORM set_config('request.jwt.claim.sub',staff,true);
 denied:=false;
 BEGIN
  PERFORM public.marketpro_owner_replace_team_phone(
     w,owner_dev,staff::uuid,first_dev,second_dev,'Fake New');
 EXCEPTION WHEN SQLSTATE '42501' THEN denied:=true; END;
 IF NOT denied THEN RAISE EXCEPTION 'Staff replaced their own phone'; END IF;
 denied:=false;
 BEGIN
  PERFORM public.marketpro_owner_disable_team_phone(
     w,owner_dev,staff::uuid,first_dev);
 EXCEPTION WHEN SQLSTATE '42501' THEN denied:=true; END;
 IF NOT denied THEN RAISE EXCEPTION 'Staff revoked the old phone'; END IF;
 PERFORM set_config('request.jwt.claim.sub',viewer,true);
 denied:=false;
 BEGIN
  PERFORM public.marketpro_owner_replace_team_phone(
     w,owner_dev,staff::uuid,first_dev,second_dev,'Fake New');
 EXCEPTION WHEN SQLSTATE '42501' THEN denied:=true; END;
 IF NOT denied THEN RAISE EXCEPTION 'Viewer replaced team device'; END IF;

 -- VALID OWNER ATOMIC TRANSFER: OLD OFF, NEW NOT YET AUTHORIZED.
 PERFORM set_config('request.jwt.claim.sub',owner,true);
 response:=public.marketpro_owner_replace_team_phone(
   w,owner_dev,staff::uuid,first_dev,second_dev,'Replacement Team Phone');
 IF NOT (response->>'old_device_disabled')::boolean OR
    NOT (response->>'replacement_pending')::boolean OR
    NOT (response->>'new_device_not_active')::boolean OR
    (response->>'new_device_can_write')::boolean OR
    NOT (response->>'cloud_history_retained')::boolean THEN
    RAISE EXCEPTION 'Replacement status flags unsafe';
 END IF;
 invite_code:=response->>'one_time_code';
 IF length(invite_code)<>64 THEN RAISE EXCEPTION 'New invite token too short'; END IF;
 IF (SELECT count(*) FROM public.registered_devices
     WHERE id=first_dev AND (enabled OR can_write))<>0 OR
    (SELECT count(*) FROM public.registered_devices
     WHERE id=second_dev)<>0 OR
    (SELECT count(*) FROM public.registered_devices WHERE enabled AND can_write)<>1 THEN
    RAISE EXCEPTION 'Old phone still active or new phone prematurely enrolled';
 END IF;
 IF (SELECT count(*) FROM public.purchases)<>0 THEN
   -- RLS? The function OWNER creator sees rows; this fixture has purchase rows.
   NULL;
 END IF;
 IF (SELECT count(*) FROM public.purchases)<>2 THEN
   RAISE EXCEPTION 'Existing Cloud purchase history altered';
 END IF;
 IF (SELECT count(*) FROM marketpro_private.staff_device_transfer_audit
   WHERE action='replace' AND new_device_id=second_dev)<>1 THEN
   RAISE EXCEPTION 'Replacement audit not created';
 END IF;
 IF (SELECT count(*) FROM public.workspace_members
     WHERE workspace_id=w AND user_id=staff::uuid
       AND role='purchasing_staff')<>1 THEN
   RAISE EXCEPTION 'Staff membership unexpectedly deleted/recreated';
 END IF;

 -- Cannot use old device to claim new invitation.
 PERFORM set_config('request.jwt.claim.sub',staff,true);
 denied:=false;
 BEGIN
   PERFORM public.marketpro_staff_accept_invitation(w,first_dev,invite_code);
 EXCEPTION WHEN SQLSTATE '42501' THEN denied:=true; END;
 IF NOT denied THEN RAISE EXCEPTION 'Old device claimed new invite'; END IF;
 response:=public.marketpro_staff_accept_invitation(w,second_dev,invite_code);
 IF NOT (response->>'invitation_accepted')::boolean THEN
   RAISE EXCEPTION 'Staff new phone cannot claim invited device';
 END IF;
 PERFORM set_config('request.jwt.claim.sub',owner,true);

 -- Read-only activation denied if proper RLS+pull gate disabled.
 UPDATE marketpro_private.team_rollout_gate SET team_pull_rpc_reviewed=false
 WHERE workspace_id=w;
 denied:=false;
 BEGIN
  PERFORM public.marketpro_owner_activate_replacement_readonly(
     w,owner_dev,staff::uuid);
 EXCEPTION WHEN SQLSTATE '42501' THEN denied:=true; END;
 IF NOT denied THEN RAISE EXCEPTION 'Replacement activated without team pull review'; END IF;
 UPDATE marketpro_private.team_rollout_gate SET team_pull_rpc_reviewed=true
 WHERE workspace_id=w;
 response:=public.marketpro_owner_activate_replacement_readonly(w,owner_dev,staff::uuid);
 IF NOT (response->>'replacement_activated')::boolean OR
    NOT (response->>'old_phone_stays_disabled')::boolean OR
    (response->>'new_device_can_write')::boolean THEN
   RAISE EXCEPTION 'New phone was not enrolled read-only';
 END IF;
 IF (SELECT count(*) FROM public.registered_devices
     WHERE id=second_dev AND enabled AND NOT can_write)<>1 OR
    (SELECT count(*) FROM public.registered_devices
     WHERE id=first_dev AND (enabled OR can_write))<>0 THEN
   RAISE EXCEPTION 'Replacement activation changed old device permissions';
 END IF;
 denied:=false;
 BEGIN
  PERFORM public.marketpro_owner_activate_replacement_readonly(
     w,owner_dev,staff::uuid);
 EXCEPTION WHEN SQLSTATE '42501' THEN denied:=true; END;
 IF NOT denied THEN RAISE EXCEPTION 'Replacement activation replay'; END IF;

 -- Old/new roles never changed, and new device can be replaced AGAIN.
 response:=public.marketpro_owner_replace_team_phone(
   w,owner_dev,staff::uuid,second_dev,third_dev,'Third Team Phone');
 IF (SELECT count(*) FROM public.registered_devices
     WHERE id=second_dev AND (enabled OR can_write))<>0 OR
    (SELECT count(*) FROM public.registered_devices WHERE enabled AND can_write)<>1 THEN
    RAISE EXCEPTION 'Second phone was not revoked on repeat replacement';
 END IF;
 -- Old old phone cannot be used to replace a CURRENT live Staff phone.
 denied:=false;
 BEGIN
  PERFORM public.marketpro_owner_replace_team_phone(
    w,owner_dev,staff::uuid,first_dev,'f1111111-1111-4111-8111-111111111111',
    'Wrong old phone');
 EXCEPTION WHEN SQLSTATE '42501' THEN denied:=true; END;
 IF NOT denied THEN RAISE EXCEPTION 'Stale replaced phone used as current'; END IF;

 -- Owner can INDEPENDENTLY DISABLE staff device in emergencies.
 response:=public.marketpro_owner_disable_team_phone(
  w,owner_dev,staff::uuid,second_dev);
 IF NOT (response->>'old_device_disabled')::boolean THEN
   RAISE EXCEPTION 'Owner emergency revoke response missing';
 END IF;
 denied:=false;
 BEGIN
  PERFORM public.marketpro_owner_disable_team_phone(
   w,owner_dev,owner::uuid,owner_dev);
 EXCEPTION WHEN SQLSTATE '42501' THEN denied:=true; END;
 IF NOT denied THEN RAISE EXCEPTION 'Staff revoke disabled Owner phone'; END IF;

 -- Third phone was only invited, never enrolled; wait for Staff claim.
 IF (SELECT count(*) FROM public.registered_devices WHERE id=third_dev)<>0 OR
    (SELECT count(*) FROM public.registered_devices WHERE enabled AND can_write)<>1 OR
    (SELECT count(*) FROM public.purchases)<>2 THEN
    RAISE EXCEPTION 'Old writer/history or new Staff permissions changed';
 END IF;

 -- Anonymously authenticated role must still not access owner RPC.
 PERFORM set_config('request.jwt.claims','{"is_anonymous":true}',true);
 denied:=false;
 BEGIN
  PERFORM public.marketpro_owner_replace_team_phone(
     w,owner_dev,staff::uuid,second_dev,
     'f2222222-2222-4222-8222-222222222222','Anonymous Attempt');
 EXCEPTION WHEN SQLSTATE '42501' THEN denied:=true; END;
 IF NOT denied THEN RAISE EXCEPTION 'Anonymous owner replacement accepted'; END IF;
END;
$test$;

-- Crucial: staff workspace membership must NOT bypass device-bound DATA API.
SET ROLE authenticated;
SELECT set_config('request.jwt.claim.sub','33333333-3333-4333-8333-333333333333',false);
DO $rls$
BEGIN
 IF (SELECT count(*) FROM public.purchases)<>0 OR
    (SELECT count(*) FROM public.items)<>0 OR
    (SELECT count(*) FROM public.categories)<>0 THEN
     RAISE EXCEPTION 'Old Staff JWT can read directly despite disabled phone';
 END IF;
END;
$rls$;
RESET ROLE;

DO $privileges$
BEGIN
 IF has_table_privilege('authenticated',
   'marketpro_private.staff_device_transfer_audit','SELECT') THEN
   RAISE EXCEPTION 'Private transfer audit exposed to Data API';
 END IF;
 IF has_function_privilege('anon',
    'public.marketpro_owner_replace_team_phone(uuid,uuid,uuid,uuid,uuid,text)',
    'EXECUTE') OR
    has_function_privilege('anon',
    'public.marketpro_owner_disable_team_phone(uuid,uuid,uuid,uuid)',
    'EXECUTE') OR
    has_function_privilege('anon',
    'public.marketpro_owner_activate_replacement_readonly(uuid,uuid,uuid)',
    'EXECUTE') THEN
   RAISE EXCEPTION 'Anonymous can call team device transfer';
 END IF;
END;
$privileges$;
\echo V368 OWNER REVOKE/REPLACE/CLAIM/ACTIVATE/REVOKE-AGAIN TESTS PASS
