\set ON_ERROR_STOP on
-- Disposable PostgreSQL 17 only: all requested "phones" and data are synthetic.
CREATE ROLE anon;
CREATE ROLE authenticated;
CREATE SCHEMA auth;
CREATE SCHEMA marketpro_private;
CREATE FUNCTION auth.uid() RETURNS uuid LANGUAGE sql STABLE AS
$$ SELECT nullif(current_setting('request.jwt.claim.sub',true),'')::uuid $$;
CREATE FUNCTION auth.jwt() RETURNS jsonb LANGUAGE sql STABLE AS
$$ SELECT coalesce(nullif(current_setting('request.jwt.claims',true),'')::jsonb,'{}'::jsonb) $$;
CREATE TABLE auth.users(id uuid PRIMARY KEY,email_confirmed_at timestamptz);
CREATE TABLE auth.sessions(id uuid PRIMARY KEY,user_id uuid,not_after timestamptz);
CREATE TABLE auth.mfa_factors(user_id uuid,factor_type text,status text);
CREATE TABLE public.workspaces(id uuid PRIMARY KEY,name text);
CREATE TABLE public.workspace_members(workspace_id uuid,user_id uuid,role text);
CREATE TABLE public.registered_devices(
 id uuid PRIMARY KEY,workspace_id uuid,user_id uuid,device_label text,
 enabled bool NOT NULL DEFAULT false,can_write bool NOT NULL DEFAULT false,
 enrolled_at timestamptz DEFAULT now(),last_seen_at timestamptz);
CREATE UNIQUE INDEX registered_devices_one_enabled_writer_idx
 ON public.registered_devices(workspace_id) WHERE enabled AND can_write;
CREATE TABLE marketpro_private.qa_owner_pending_devices(
 workspace_id uuid,owner_user_id uuid,status text,enabled boolean,can_write boolean);
CREATE TABLE public.categories(id uuid,workspace_id uuid,name text,name_key text,
 revision bigint,deleted_at timestamptz,created_at timestamptz,updated_at timestamptz);
CREATE TABLE public.items(id uuid,workspace_id uuid,category_id uuid,name text,name_key text,
 is_active bool,revision bigint,deleted_at timestamptz,created_at timestamptz,updated_at timestamptz);
CREATE TABLE public.purchases(id uuid,workspace_id uuid,business_date date,item_id uuid,
 quantity_grams bigint,price_cents_per_kg bigint,revision bigint,deleted_at timestamptz,
 created_at timestamptz,updated_at timestamptz);
CREATE TABLE public.purchase_additions(LIKE public.purchases INCLUDING ALL);
CREATE TABLE public.requisitions(id uuid,workspace_id uuid,business_date date,item_id uuid,kitchen text,
 quantity_grams bigint,revision bigint,deleted_at timestamptz,created_at timestamptz,updated_at timestamptz);
CREATE TABLE public.daily_cash_received(id uuid,workspace_id uuid,business_date date,
 amount_cents bigint,revision bigint,deleted_at timestamptz,created_at timestamptz,updated_at timestamptz);
CREATE TABLE public.daily_other_expenses(id uuid,workspace_id uuid,business_date date,
 transport_cents bigint,labor_charge_cents bigint,meals_drinks_cents bigint,others_cents bigint,
 remarks text,revision bigint,created_at timestamptz,updated_at timestamptz);
INSERT INTO public.workspaces VALUES ('af77cb66-76b2-492a-a460-06132f468713','Market Pro Two-Phone QA');
INSERT INTO auth.users VALUES
('98c3b101-d452-4b6b-902c-0b1a31fc340c',clock_timestamp()),
('b805598b-421f-447b-aa4c-ffb66f721338',clock_timestamp());
INSERT INTO auth.sessions VALUES
('11111111-1111-4111-8111-111111111111','98c3b101-d452-4b6b-902c-0b1a31fc340c',clock_timestamp()+interval '1 day'),
('22222222-2222-4222-8222-222222222222','b805598b-421f-447b-aa4c-ffb66f721338',clock_timestamp()+interval '1 day');
INSERT INTO auth.mfa_factors VALUES
('98c3b101-d452-4b6b-902c-0b1a31fc340c','totp','verified');
INSERT INTO public.workspace_members VALUES
('af77cb66-76b2-492a-a460-06132f468713','98c3b101-d452-4b6b-902c-0b1a31fc340c','owner'),
('af77cb66-76b2-492a-a460-06132f468713','b805598b-421f-447b-aa4c-ffb66f721338','editor');
INSERT INTO marketpro_private.qa_owner_pending_devices VALUES
('af77cb66-76b2-492a-a460-06132f468713',
'98c3b101-d452-4b6b-902c-0b1a31fc340c','pending',false,false);
\i supabase/staging/marketpro_cloud_sync_device_pull_v1.sql
-- Only in disposable PG17: temporary test principals + enrollment/download gate enabled.
UPDATE marketpro_private.qa_cloud_sync_v1_gate SET intake_enabled=true,pull_enabled=true;
SELECT set_config('request.jwt.claim.sub','98c3b101-d452-4b6b-902c-0b1a31fc340c',false);
SELECT set_config('request.jwt.claims','{"sub":"98c3b101-d452-4b6b-902c-0b1a31fc340c","session_id":"11111111-1111-4111-8111-111111111111","aal":"aal1"}',false);
DO $test$BEGIN
 BEGIN
 PERFORM public.marketpro_qa_cloud_request_phone(
 'af77cb66-76b2-492a-a460-06132f468713',
 '33333333-3333-4333-8333-333333333333','Owner Test');
 RAISE EXCEPTION 'Owner AAL1 unexpectedly enrolled';
 EXCEPTION WHEN SQLSTATE '42501' THEN NULL; END;
END;$test$;
SELECT set_config('request.jwt.claims','{"sub":"98c3b101-d452-4b6b-902c-0b1a31fc340c","session_id":"11111111-1111-4111-8111-111111111111","aal":"aal2"}',false);
DO $test$DECLARE x jsonb;BEGIN
 x:=public.marketpro_qa_cloud_request_phone(
 'af77cb66-76b2-492a-a460-06132f468713',
 '33333333-3333-4333-8333-333333333333','Owner Test');
 IF x->>'status'<>'approved' OR x->>'can_write'<>'true'
 THEN RAISE EXCEPTION 'Owner not active'; END IF;
 BEGIN
 PERFORM public.marketpro_qa_cloud_request_phone(
 'af77cb66-76b2-492a-a460-06132f468713',
 '44444444-4444-4444-8444-444444444444','Second Owner Phone');
 RAISE EXCEPTION 'Two Owner phones wrongly approved';
 EXCEPTION WHEN SQLSTATE '42501' THEN NULL; END;
END;$test$;
SELECT set_config('request.jwt.claim.sub','b805598b-421f-447b-aa4c-ffb66f721338',false);
SELECT set_config('request.jwt.claims','{"sub":"b805598b-421f-447b-aa4c-ffb66f721338","session_id":"22222222-2222-4222-8222-222222222222","aal":"aal1"}',false);
DO $test$DECLARE x jsonb;BEGIN
 x:=public.marketpro_qa_cloud_request_phone(
 'af77cb66-76b2-492a-a460-06132f468713',
 '55555555-5555-4555-8555-555555555555','Market Team Phone A');
 IF x->>'status'<>'pending' OR x->>'enabled'<>'false'
 THEN RAISE EXCEPTION 'Team auto-approved'; END IF;
 BEGIN
 PERFORM public.marketpro_team_pull_page(
 'af77cb66-76b2-492a-a460-06132f468713',
 '55555555-5555-4555-8555-555555555555','purchase');
 RAISE EXCEPTION 'Pending Team was able to pull';
 EXCEPTION WHEN SQLSTATE '42501' THEN NULL; END;
 BEGIN
 PERFORM public.marketpro_qa_cloud_decide_team_phone(
 'af77cb66-76b2-492a-a460-06132f468713',
 '33333333-3333-4333-8333-333333333333',
 '55555555-5555-4555-8555-555555555555',true);
 RAISE EXCEPTION 'Team self-approved';
 EXCEPTION WHEN SQLSTATE '42501' THEN NULL; END;
END;$test$;
SELECT set_config('request.jwt.claim.sub','98c3b101-d452-4b6b-902c-0b1a31fc340c',false);
SELECT set_config('request.jwt.claims','{"sub":"98c3b101-d452-4b6b-902c-0b1a31fc340c","session_id":"11111111-1111-4111-8111-111111111111","aal":"aal2"}',false);
DO $test$DECLARE x jsonb;BEGIN
 x:=public.marketpro_qa_cloud_decide_team_phone(
 'af77cb66-76b2-492a-a460-06132f468713',
 '33333333-3333-4333-8333-333333333333',
 '55555555-5555-4555-8555-555555555555',true);
 IF x->>'enabled'<>'true' OR x->>'can_write'<>'false'
 THEN RAISE EXCEPTION 'Team approval unexpectedly granted legacy writer';END IF;
END;$test$;
SELECT set_config('request.jwt.claim.sub','b805598b-421f-447b-aa4c-ffb66f721338',false);
SELECT set_config('request.jwt.claims','{"sub":"b805598b-421f-447b-aa4c-ffb66f721338","session_id":"22222222-2222-4222-8222-222222222222","aal":"aal1"}',false);
DO $test$DECLARE x jsonb; t text;BEGIN
 FOREACH t IN ARRAY ARRAY['category','item','requisition','purchase',
   'purchase_addition','daily_cash_received','daily_other_expenses'] LOOP
  x:=public.marketpro_team_pull_page(
   'af77cb66-76b2-492a-a460-06132f468713',
   '55555555-5555-4555-8555-555555555555',t);
  IF x->>'entity_type'<>t OR x->>'has_more'<>'false'
   OR jsonb_array_length(x->'rows')<>0
  THEN RAISE EXCEPTION 'Read page corrupted: %',t;END IF;
 END LOOP;
END;$test$;
SELECT set_config('request.jwt.claim.sub','98c3b101-d452-4b6b-902c-0b1a31fc340c',false);
SELECT set_config('request.jwt.claims','{"sub":"98c3b101-d452-4b6b-902c-0b1a31fc340c","session_id":"11111111-1111-4111-8111-111111111111","aal":"aal2"}',false);
SELECT public.marketpro_qa_cloud_decide_team_phone(
 'af77cb66-76b2-492a-a460-06132f468713',
 '33333333-3333-4333-8333-333333333333',
 '55555555-5555-4555-8555-555555555555',false);
SELECT set_config('request.jwt.claim.sub','b805598b-421f-447b-aa4c-ffb66f721338',false);
SELECT set_config('request.jwt.claims','{"sub":"b805598b-421f-447b-aa4c-ffb66f721338","session_id":"22222222-2222-4222-8222-222222222222","aal":"aal1"}',false);
DO $test$BEGIN
 BEGIN
 PERFORM public.marketpro_team_pull_page(
 'af77cb66-76b2-492a-a460-06132f468713',
 '55555555-5555-4555-8555-555555555555','purchase');
 RAISE EXCEPTION 'Revoked Team could still pull';
 EXCEPTION WHEN SQLSTATE '42501' THEN NULL; END;
END;$test$;
DO $post$BEGIN
 IF (SELECT count(*) FROM public.registered_devices WHERE enabled AND can_write)<>1
 OR (SELECT count(*) FROM public.registered_devices WHERE enabled)<>1
 OR (SELECT count(*) FROM public.registered_devices)<>2
 OR (SELECT count(*) FROM public.purchases)<>0
 THEN RAISE EXCEPTION 'Device/revocation/finance regression';END IF;
END;$post$;
SELECT 'PASS: one Owner, multiple Team requests, Owner approve/revoke, all seven pull types, no financial writes' AS verified;
