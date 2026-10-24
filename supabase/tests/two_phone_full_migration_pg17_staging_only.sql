-- TRUE SCHEMA COMPATIBILITY TEST — DISPOSABLE POSTGRESQL 17 ONLY.
-- Build the THREE real Cloud V1 migrations (not mock finance tables), then
-- replay EVERY two-phone SQL proposal IN ORDER in an isolated database.
-- This file must NEVER be run against live/staging user data. No secrets.
\set ON_ERROR_STOP on
CREATE ROLE anon;
CREATE ROLE authenticated;
CREATE ROLE service_role;
CREATE SCHEMA auth;
CREATE SCHEMA extensions;
CREATE EXTENSION pgcrypto WITH SCHEMA extensions;
CREATE TABLE auth.users(
 id uuid PRIMARY KEY,email text,email_confirmed_at timestamptz
);
CREATE TABLE auth.sessions(
 id uuid PRIMARY KEY,user_id uuid NOT NULL REFERENCES auth.users(id),
 not_after timestamptz
);
CREATE FUNCTION auth.uid() RETURNS uuid LANGUAGE sql STABLE
AS $uid$ SELECT nullif(pg_catalog.current_setting('request.jwt.claim.sub',true),'')::uuid $uid$;
CREATE FUNCTION auth.jwt() RETURNS jsonb LANGUAGE sql STABLE
AS $jwt$
 SELECT coalesce(nullif(pg_catalog.current_setting('request.jwt.claims',true),''),
   '{}')::jsonb
$jwt$;
GRANT USAGE ON SCHEMA auth TO authenticated,service_role;
GRANT EXECUTE ON FUNCTION auth.uid() TO authenticated,service_role;
GRANT EXECUTE ON FUNCTION auth.jwt() TO authenticated,service_role;

-- Actual deployed V1 schema baseline from repository.
\i supabase/migrations/20261021174526_cloud_v1_purchase_only_foundation.sql
\i supabase/migrations/20261021180636_expand_requisitions_cash_financial_summary.sql
\i supabase/migrations/20261021183148_secure_single_writer_rpc.sql

-- Reviewed staged sequence; these remain proposal files, not deployments.
\i supabase/proposals/v352_team_write_gate_REVIEW_ONLY.sql
\i supabase/proposals/v353_team_purchase_rpc_REVIEW_ONLY.sql
\i supabase/proposals/v354_team_financial_rpc_REVIEW_ONLY.sql
\i supabase/proposals/v354_team_paged_pull_REVIEW_ONLY.sql
\i supabase/proposals/v358_owner_invite_staff_PENDING_ONLY.sql
\i supabase/proposals/v359_staff_readonly_activation_rls_REVIEW_ONLY.sql
\i supabase/proposals/v362_owner_approved_catalog_links_REVIEW_ONLY.sql
\i supabase/proposals/v364_staff_candidate_owner_review_REVIEW_ONLY.sql
\i supabase/proposals/v367_staff_approved_catalog_fetch_REVIEW_ONLY.sql
\i supabase/proposals/v368_owner_replace_team_phone_REVIEW_ONLY.sql
\i supabase/proposals/v370_dual_channel_staff_writer_REVIEW_ONLY.sql
\i supabase/proposals/v371_owner_staff_write_approval_REVIEW_ONLY.sql
\i supabase/proposals/v372_staff_auth_session_binding_REVIEW_ONLY.sql
\i supabase/proposals/v375_staff_device_challenge_ledger_REVIEW_ONLY.sql
\i supabase/proposals/two_phone_other_expenses_REVIEW_ONLY.sql
\i supabase/proposals/two_phone_other_expenses_pull_REVIEW_ONLY.sql
\i supabase/proposals/two_phone_atomic_proof_financial_write_REVIEW_ONLY.sql
\i supabase/proposals/two_phone_owner_staff_key_enrollment_REVIEW_ONLY.sql
\i supabase/proposals/two_phone_daily_summary_expense_reconciliation_REVIEW_ONLY.sql
\i supabase/proposals/two_phone_signed_staff_pull_REVIEW_ONLY.sql

-- Realistically shaped workspace data in a NEW throwaway database only.
INSERT INTO auth.users(id,email,email_confirmed_at) VALUES
 ('22222222-2222-4222-8222-222222222222','owner@local.test',now()),
 ('33333333-3333-4333-8333-333333333333','staff@local.test',now()),
 ('44444444-4444-4444-8444-444444444444','stranger@local.test',now());
INSERT INTO auth.sessions(id,user_id,not_after) VALUES
 ('91111111-1111-4111-8111-111111111111',
  '33333333-3333-4333-8333-333333333333',NULL);
INSERT INTO public.workspaces(id,name) VALUES(
 '11111111-1111-4111-8111-111111111111','Disposable Staging Kitchen');
INSERT INTO public.workspace_members(workspace_id,user_id,role) VALUES
 ('11111111-1111-4111-8111-111111111111',
  '22222222-2222-4222-8222-222222222222','owner'),
 ('11111111-1111-4111-8111-111111111111',
  '33333333-3333-4333-8333-333333333333','purchasing_staff');
INSERT INTO public.registered_devices(
 id,workspace_id,user_id,device_label,enabled,can_write
) VALUES
 ('a1111111-1111-4111-8111-111111111111',
  '11111111-1111-4111-8111-111111111111',
  '22222222-2222-4222-8222-222222222222','Owner Phone',true,true),
 ('f1111111-1111-4111-8111-111111111111',
  '11111111-1111-4111-8111-111111111111',
  '33333333-3333-4333-8333-333333333333','Staff Phone',true,false);
INSERT INTO public.categories(id,workspace_id,name) VALUES
 ('c1111111-1111-4111-8111-111111111111',
  '11111111-1111-4111-8111-111111111111','Vegetables');
INSERT INTO public.items(id,workspace_id,category_id,name) VALUES
 ('d1111111-1111-4111-8111-111111111111',
  '11111111-1111-4111-8111-111111111111',
  'c1111111-1111-4111-8111-111111111111','Carrot');
INSERT INTO public.purchases(
 id,workspace_id,business_date,item_id,quantity_grams,price_cents_per_kg
) VALUES (
 'e1111111-1111-4111-8111-111111111111',
 '11111111-1111-4111-8111-111111111111',CURRENT_DATE,
 'd1111111-1111-4111-8111-111111111111',2000,15000
);
INSERT INTO public.purchase_additions(
 id,workspace_id,business_date,item_id,quantity_grams,price_cents_per_kg
) VALUES(
 'e2222222-2222-4222-8222-222222222222',
 '11111111-1111-4111-8111-111111111111',CURRENT_DATE,
 'd1111111-1111-4111-8111-111111111111',1000,14000
);
INSERT INTO public.daily_cash_received(
 id,workspace_id,business_date,amount_cents
) VALUES (
 'e3333333-3333-4333-8333-333333333333',
 '11111111-1111-4111-8111-111111111111',CURRENT_DATE,75000
);
INSERT INTO public.daily_other_expenses(
 id,workspace_id,business_date,
 transport_cents,labor_charge_cents,meals_drinks_cents,others_cents,remarks
) VALUES (
 'e4444444-4444-4444-8444-444444444444',
 '11111111-1111-4111-8111-111111111111',CURRENT_DATE,
 1200,500,300,100,'Disposable cost reconciliation test'
);

-- Verify true Owner V1 writer is STILL accessible while new direct Staff
-- financial write RPCs can only be entered through a protected service path.
DO $contracts$
DECLARE
 w uuid:='11111111-1111-4111-8111-111111111111';
 rev text;
 cnt integer;
BEGIN
 IF NOT has_function_privilege('authenticated',
   'public.marketpro_apply_change(uuid,uuid,uuid,text,uuid,text,jsonb)',
   'EXECUTE') THEN
  RAISE EXCEPTION 'Original Owner V1 RPC unexpectedly revoked';
 END IF;
 IF has_function_privilege('authenticated',
   'public.marketpro_apply_team_purchase_change(uuid,uuid,uuid,uuid,text,bigint,jsonb)',
   'EXECUTE') OR
   has_function_privilege('authenticated',
   'public.marketpro_apply_team_financial_change(uuid,uuid,uuid,text,uuid,text,bigint,jsonb)',
   'EXECUTE') OR
   has_function_privilege('authenticated',
   'public.marketpro_apply_team_other_expense(uuid,uuid,uuid,uuid,text,bigint,jsonb)',
   'EXECUTE') OR
   has_function_privilege('authenticated',
   'public.marketpro_service_apply_proof_bound_finance(uuid,uuid,uuid,uuid,text,uuid,text,bigint,jsonb)',
   'EXECUTE') THEN
    RAISE EXCEPTION 'Direct Staff JWT bypasses one-time signed proof';
 END IF;
 IF NOT has_function_privilege('service_role',
   'public.marketpro_service_apply_proof_bound_finance(uuid,uuid,uuid,uuid,text,uuid,text,bigint,jsonb)',
   'EXECUTE') THEN
    RAISE EXCEPTION 'Trusted signed-proof server cannot apply finance change';
 END IF;
 IF (SELECT COUNT(*) FROM public.registered_devices
   WHERE workspace_id=w AND enabled AND can_write)<>1 THEN
   RAISE EXCEPTION 'Original single Owner enabled writer changed';
 END IF;
 IF (SELECT COUNT(*) FROM public.daily_financial_summary
  WHERE workspace_id=w AND cash_received_cents=75000
   AND purchase_expenses_cents=44000
   AND transport_expenses_cents=1200
   AND labor_charge_expenses_cents=500
   AND meals_drinks_expenses_cents=300
   AND others_expenses_cents=100
   AND total_expenses_cents=46100
   AND cash_balance_cents=28900)<>1 THEN
     RAISE EXCEPTION 'Final Cloud dashboard cash balance omits Other Expenses';
 END IF;
 IF NOT EXISTS(
   SELECT 1 FROM pg_catalog.pg_class c
   JOIN pg_catalog.pg_namespace n ON n.oid=c.relnamespace
   WHERE c.relname='daily_financial_summary' AND n.nspname='public'
     AND 'security_invoker=true'=ANY(c.reloptions)
 ) THEN
    RAISE EXCEPTION 'Cloud daily summary loses RLS with definer-view';
 END IF;
 IF EXISTS(
   SELECT 1 FROM marketpro_private.team_rollout_gate
   WHERE team_pull_rpc_reviewed OR team_writes_reviewed
 ) THEN
    RAISE EXCEPTION 'Migration silently activated Staff permissions';
 END IF;
END;
$contracts$;

-- Actual PostgreSQL role-scoped RLS checks, not just text inspection.
SET ROLE authenticated;
SELECT pg_catalog.set_config('request.jwt.claim.sub',
  '33333333-3333-4333-8333-333333333333',false);
SELECT pg_catalog.set_config('request.jwt.claims',
  '{"is_anonymous":false,"session_id":"91111111-1111-4111-8111-111111111111"}',
  false);
DO $staff_rls$
BEGIN
 IF (SELECT COUNT(*) FROM public.categories)>0 OR
    (SELECT COUNT(*) FROM public.items)>0 OR
    (SELECT COUNT(*) FROM public.purchases)>0 OR
    (SELECT COUNT(*) FROM public.purchase_additions)>0 OR
    (SELECT COUNT(*) FROM public.daily_cash_received)>0 OR
    (SELECT COUNT(*) FROM public.daily_other_expenses)>0 OR
    (SELECT COUNT(*) FROM public.daily_financial_summary)>0 THEN
    RAISE EXCEPTION 'Staff direct REST/RLS leaked workspace finance rows';
 END IF;
 IF (SELECT COUNT(*) FROM public.workspace_members
   WHERE user_id='33333333-3333-4333-8333-333333333333')<>1 THEN
   RAISE EXCEPTION 'Staff cannot view own membership';
 END IF;
END;
$staff_rls$;
SELECT pg_catalog.set_config('request.jwt.claim.sub',
  '22222222-2222-4222-8222-222222222222',false);
SELECT pg_catalog.set_config('request.jwt.claims','{"is_anonymous":false}',false);
DO $owner_rls$
BEGIN
 IF (SELECT COUNT(*) FROM public.categories)<>1 OR
    (SELECT COUNT(*) FROM public.items)<>1 OR
    (SELECT COUNT(*) FROM public.purchases)<>1 OR
    (SELECT COUNT(*) FROM public.purchase_additions)<>1 OR
    (SELECT COUNT(*) FROM public.daily_cash_received)<>1 OR
    (SELECT COUNT(*) FROM public.daily_other_expenses)<>1 OR
    (SELECT COUNT(*) FROM public.daily_financial_summary)<>1 THEN
   RAISE EXCEPTION 'Existing Owner dashboard REST view lost authorized data';
 END IF;
END;
$owner_rls$;
RESET ROLE;
\echo OK: all Cloud V1 migrations + two-phone proposed SQL replay, RLS, Owner and other-expenses cash balance
