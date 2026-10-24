-- Local throwaway PostgreSQL 17 smoke tests only. Never point at Supabase.
\set ON_ERROR_STOP on

CREATE ROLE anon;
CREATE ROLE authenticated;
CREATE SCHEMA auth;
CREATE SCHEMA marketpro_private;
CREATE TABLE public.workspace_members(
    workspace_id uuid NOT NULL,
    user_id uuid NOT NULL,
    role text NOT NULL,
    CONSTRAINT workspace_members_role_check
      CHECK (role IN ('owner','editor','viewer')),
    PRIMARY KEY (workspace_id,user_id)
);
CREATE TABLE public.registered_devices(
    id uuid NOT NULL PRIMARY KEY,
    workspace_id uuid NOT NULL,
    user_id uuid NOT NULL,
    enabled boolean NOT NULL DEFAULT false,
    can_write boolean NOT NULL DEFAULT false
);
CREATE FUNCTION auth.uid() RETURNS uuid LANGUAGE sql STABLE
AS $fn$ SELECT NULLIF(current_setting('request.jwt.claim.sub', true),'')::uuid $fn$;
CREATE FUNCTION auth.jwt() RETURNS jsonb LANGUAGE sql STABLE
AS $fn$ SELECT COALESCE(NULLIF(current_setting('request.jwt.claims', true),''),'{}')::jsonb $fn$;

\i supabase/proposals/v352_team_write_gate_REVIEW_ONLY.sql

INSERT INTO public.workspace_members(workspace_id,user_id,role) VALUES
('11111111-1111-4111-8111-111111111111','22222222-2222-4222-8222-222222222222','owner'),
('11111111-1111-4111-8111-111111111111','33333333-3333-4333-8333-333333333333','purchasing_staff'),
('11111111-1111-4111-8111-111111111111','44444444-4444-4444-8444-444444444444','viewer');
INSERT INTO public.registered_devices(id,workspace_id,user_id,enabled,can_write) VALUES
('a1111111-1111-4111-8111-111111111111','11111111-1111-4111-8111-111111111111','22222222-2222-4222-8222-222222222222',true,true),
('b1111111-1111-4111-8111-111111111111','11111111-1111-4111-8111-111111111111','33333333-3333-4333-8333-333333333333',true,true),
('c1111111-1111-4111-8111-111111111111','11111111-1111-4111-8111-111111111111','44444444-4444-4444-8444-444444444444',true,true),
('d1111111-1111-4111-8111-111111111111','11111111-1111-4111-8111-111111111111','33333333-3333-4333-8333-333333333333',false,true);


CREATE SCHEMA IF NOT EXISTS extensions;
CREATE EXTENSION IF NOT EXISTS pgcrypto WITH SCHEMA extensions;
CREATE TABLE public.purchases (
  id uuid NOT NULL PRIMARY KEY,
  workspace_id uuid NOT NULL,
  business_date date NOT NULL,
  item_id uuid NOT NULL,
  quantity_grams bigint NOT NULL CHECK(quantity_grams BETWEEN 0 AND 1000000000),
  price_cents_per_kg bigint NOT NULL CHECK(price_cents_per_kg BETWEEN 0 AND 1000000000),
  revision bigint NOT NULL DEFAULT 1 CHECK(revision>0),
  deleted_at timestamptz,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(workspace_id,business_date,item_id)
);
CREATE TABLE public.sync_operations (
  operation_id uuid PRIMARY KEY,
  workspace_id uuid NOT NULL,
  device_id uuid NOT NULL,
  actor_user_id uuid NOT NULL,
  entity_type text NOT NULL,
  entity_id uuid NOT NULL,
  operation_type text NOT NULL,
  payload_hash text NOT NULL,
  applied_at timestamptz NOT NULL DEFAULT now()
);
\i supabase/proposals/v353_team_purchase_rpc_REVIEW_ONLY.sql


CREATE TABLE public.requisitions (
 id uuid PRIMARY KEY,workspace_id uuid NOT NULL,business_date date NOT NULL,
 kitchen text NOT NULL CHECK(kitchen IN('Main Kitchen','Indian Kitchen','Pastry Kitchen')),
 item_id uuid NOT NULL,quantity_grams bigint NOT NULL CHECK(quantity_grams BETWEEN 0 AND 1000000000),
 revision bigint NOT NULL DEFAULT 1 CHECK(revision>0),deleted_at timestamptz,
 created_at timestamptz NOT NULL DEFAULT now(),updated_at timestamptz NOT NULL DEFAULT now(),
 UNIQUE(workspace_id,business_date,kitchen,item_id)
);
CREATE TABLE public.purchase_additions (
 id uuid PRIMARY KEY,workspace_id uuid NOT NULL,business_date date NOT NULL,
 item_id uuid NOT NULL,quantity_grams bigint NOT NULL CHECK(quantity_grams BETWEEN 0 AND 1000000000),
 price_cents_per_kg bigint NOT NULL CHECK(price_cents_per_kg BETWEEN 0 AND 1000000000),
 revision bigint NOT NULL DEFAULT 1 CHECK(revision>0),deleted_at timestamptz,
 created_at timestamptz NOT NULL DEFAULT now(),updated_at timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE public.daily_cash_received (
 id uuid PRIMARY KEY,workspace_id uuid NOT NULL,business_date date NOT NULL,
 amount_cents bigint NOT NULL CHECK(amount_cents BETWEEN 0 AND 9999999999),
 revision bigint NOT NULL DEFAULT 1 CHECK(revision>0),deleted_at timestamptz,
 created_at timestamptz NOT NULL DEFAULT now(),updated_at timestamptz NOT NULL DEFAULT now(),
 UNIQUE(workspace_id,business_date)
);
\i supabase/proposals/v354_team_financial_rpc_REVIEW_ONLY.sql

DO $test$
DECLARE
 w uuid := '11111111-1111-4111-8111-111111111111';
 owner_id text := '22222222-2222-4222-8222-222222222222';
 staff_id text := '33333333-3333-4333-8333-333333333333';
 staff_phone uuid := 'b1111111-1111-4111-8111-111111111111';
 owner_phone uuid := 'a1111111-1111-4111-8111-111111111111';
 today date := (clock_timestamp() AT TIME ZONE 'Asia/Colombo')::date;
 item1 uuid := 'f1111111-1111-4111-8111-111111111111';
 req_id uuid := 'e1111111-1111-4111-8111-111111111111';
 addition_id uuid := 'e2222222-2222-4222-8222-222222222222';
 cash_id uuid := 'e3333333-3333-4333-8333-333333333333';
 old_req_id uuid := 'e4444444-4444-4444-8444-444444444444';
 r jsonb;
 req_payload jsonb;
 addition_payload jsonb;
 cash_payload jsonb;
 n bigint;
 denied boolean;
BEGIN
 PERFORM set_config('request.jwt.claims','{"is_anonymous":false}',true);
 PERFORM set_config('request.jwt.claim.sub',staff_id,true);

 req_payload := jsonb_build_object('business_date',today,'kitchen','Main Kitchen',
   'item_id',item1,'quantity_grams',1000);
 r := public.marketpro_apply_team_financial_change(
   w,staff_phone,'e0000000-0000-4000-8000-000000001001',
   'requisition',req_id,'create',null,req_payload);
 IF (r->>'revision')::bigint<>1 THEN RAISE EXCEPTION 'Requisition create failed'; END IF;
 r := public.marketpro_apply_team_financial_change(
   w,staff_phone,'e0000000-0000-4000-8000-000000001001',
   'requisition',req_id,'create',null,req_payload);
 IF NOT (r->>'replayed')::boolean THEN RAISE EXCEPTION 'Requisition ACK duplicate'; END IF;

 FOR n IN 1..10 LOOP
   r := public.marketpro_apply_team_financial_change(
     w,staff_phone,('e0000000-0000-4000-8000-' || lpad((1001+n)::text,12,'0'))::uuid,
     'requisition',req_id,'correct',n,
     jsonb_set(req_payload,'{quantity_grams}',to_jsonb(1000+n)));
   IF (r->>'revision')::bigint<>n+1 THEN
     RAISE EXCEPTION 'Unlimited corrections failed n=%',n;
   END IF;
 END LOOP;

 denied:=false;
 BEGIN
   PERFORM public.marketpro_apply_team_financial_change(
     w,staff_phone,'e0000000-0000-4000-8000-000000001099',
     'requisition',req_id,'correct',11,
     jsonb_set(req_payload,'{kitchen}','"Indian Kitchen"'::jsonb));
 EXCEPTION WHEN insufficient_privilege THEN denied:=true;
 END;
 IF NOT denied THEN RAISE EXCEPTION 'Requisition kitchen changed'; END IF;

 addition_payload := jsonb_build_object('business_date',today,'item_id',item1,
   'quantity_grams',2000,'price_cents_per_kg',15000);
 r := public.marketpro_apply_team_financial_change(
   w,staff_phone,'e0000000-0000-4000-8000-000000001101',
   'purchase_addition',addition_id,'create',null,addition_payload);
 IF (r->>'revision')::bigint<>1 THEN RAISE EXCEPTION 'Addition create failed'; END IF;
 r := public.marketpro_apply_team_financial_change(
   w,staff_phone,'e0000000-0000-4000-8000-000000001102',
   'purchase_addition',addition_id,'correct',1,
   jsonb_set(addition_payload,'{price_cents_per_kg}','17000'::jsonb));
 IF (r->>'revision')::bigint<>2 THEN RAISE EXCEPTION 'Addition correction failed'; END IF;

 cash_payload := jsonb_build_object('business_date',today,'amount_cents',50000);
 r := public.marketpro_apply_team_financial_change(
   w,staff_phone,'e0000000-0000-4000-8000-000000001201',
   'daily_cash_received',cash_id,'create',null,cash_payload);
 IF (r->>'revision')::bigint<>1 THEN RAISE EXCEPTION 'Cash create failed'; END IF;

 -- Owner correction must take priority over stale offline cash updates.
 PERFORM set_config('request.jwt.claim.sub',owner_id,true);
 r := public.marketpro_apply_team_financial_change(
   w,owner_phone,'e0000000-0000-4000-8000-000000001202',
   'daily_cash_received',cash_id,'correct',1,
   jsonb_set(cash_payload,'{amount_cents}','60000'::jsonb));
 IF (r->>'revision')::bigint<>2 THEN RAISE EXCEPTION 'Owner cash correction failed'; END IF;
 PERFORM set_config('request.jwt.claim.sub',staff_id,true);
 denied:=false;
 BEGIN
   PERFORM public.marketpro_apply_team_financial_change(
     w,staff_phone,'e0000000-0000-4000-8000-000000001203',
     'daily_cash_received',cash_id,'correct',1,cash_payload);
 EXCEPTION WHEN serialization_failure THEN denied:=true;
 END;
 IF NOT denied THEN RAISE EXCEPTION 'Stale staff cash overwrite allowed'; END IF;

 -- Staff may correct again after fetching the owner revision.
 r := public.marketpro_apply_team_financial_change(
   w,staff_phone,'e0000000-0000-4000-8000-000000001204',
   'daily_cash_received',cash_id,'correct',2,
   jsonb_set(cash_payload,'{amount_cents}','65000'::jsonb));
 IF (r->>'revision')::bigint<>3 THEN RAISE EXCEPTION 'Staff latest cash correction failed'; END IF;

 -- Staff yesterday/tomorrow is forbidden; altering immutable date is forbidden.
 denied:=false;
 BEGIN
   PERFORM public.marketpro_apply_team_financial_change(
     w,staff_phone,'e0000000-0000-4000-8000-000000001205',
     'purchase_addition','e5555555-5555-4555-8555-555555555555'::uuid,
     'create',null,
     jsonb_set(addition_payload,'{business_date}',to_jsonb((today-1)::text)));
 EXCEPTION WHEN insufficient_privilege THEN denied:=true;
 END;
 IF NOT denied THEN RAISE EXCEPTION 'Staff historic addition allowed'; END IF;
 denied:=false;
 BEGIN
   PERFORM public.marketpro_apply_team_financial_change(
     w,staff_phone,'e0000000-0000-4000-8000-000000001206',
     'daily_cash_received','e6666666-6666-4666-8666-666666666666'::uuid,
     'create',null,jsonb_set(cash_payload,'{business_date}',to_jsonb((today+1)::text)));
 EXCEPTION WHEN insufficient_privilege THEN denied:=true;
 END;
 IF NOT denied THEN RAISE EXCEPTION 'Future cash allowed'; END IF;

 PERFORM set_config('request.jwt.claim.sub',owner_id,true);
 r := public.marketpro_apply_team_financial_change(
   w,owner_phone,'e0000000-0000-4000-8000-000000001301',
   'requisition',old_req_id,'create',null,
   jsonb_set(req_payload,'{business_date}',to_jsonb((today-5)::text)));
 IF (r->>'revision')::bigint<>1 THEN RAISE EXCEPTION 'Owner historic create failed'; END IF;
 r := public.marketpro_apply_team_financial_change(
   w,owner_phone,'e0000000-0000-4000-8000-000000001302',
   'requisition',old_req_id,'correct',1,
   jsonb_set(jsonb_set(req_payload,'{business_date}',to_jsonb((today-5)::text)),
             '{quantity_grams}','990'::jsonb));
 IF (r->>'revision')::bigint<>2 THEN RAISE EXCEPTION 'Owner historic edit failed'; END IF;

 PERFORM set_config('request.jwt.claim.sub',staff_id,true);
 denied:=false;
 BEGIN
   PERFORM public.marketpro_apply_team_financial_change(
     w,staff_phone,'e0000000-0000-4000-8000-000000001303',
     'requisition',old_req_id,'correct',2,
     jsonb_set(req_payload,'{quantity_grams}','880'::jsonb));
 EXCEPTION WHEN insufficient_privilege THEN denied:=true;
 END;
 IF NOT denied THEN RAISE EXCEPTION 'Staff moved old date to today'; END IF;

 -- Reject payload not in financial field allowlist.
 denied:=false;
 BEGIN
   PERFORM public.marketpro_apply_team_financial_change(
     w,staff_phone,'e0000000-0000-4000-8000-000000001401',
     'purchase_addition','e7777777-7777-4777-8777-777777777777'::uuid,
     'create',null,addition_payload || '{"photo_path":"secret.jpg"}'::jsonb);
 EXCEPTION WHEN invalid_parameter_value THEN denied:=true;
 END;
 IF NOT denied THEN RAISE EXCEPTION 'Local invoice path accepted'; END IF;
 denied:=false;
 BEGIN
   PERFORM public.marketpro_apply_team_financial_change(
     w,staff_phone,'e0000000-0000-4000-8000-000000001402',
     'daily_cash_received','e8888888-8888-4888-8888-888888888888'::uuid,
     'create',null,jsonb_set(cash_payload,'{amount_cents}','-1'::jsonb));
 EXCEPTION WHEN invalid_parameter_value THEN denied:=true;
 END;
 IF NOT denied THEN RAISE EXCEPTION 'Negative cash accepted'; END IF;

 -- All accepted edits created ONE audit row and ONE outbox ACK record.
 IF (SELECT count(*) FROM public.sync_operations)<>18 OR
    (SELECT count(*) FROM marketpro_private.team_financial_audit)<>18 THEN
    RAISE EXCEPTION 'Audit/ledger count differs from accepted transactions';
 END IF;
 IF (SELECT revision FROM public.requisitions WHERE id=req_id)<>11 OR
    (SELECT revision FROM public.purchase_additions WHERE id=addition_id)<>2 OR
    (SELECT revision FROM public.daily_cash_received WHERE id=cash_id)<>3 THEN
    RAISE EXCEPTION 'Unexpected final revisions';
 END IF;
END;
$test$;

DO $privileges$
BEGIN
 IF has_function_privilege('anon',
  'public.marketpro_apply_team_financial_change(uuid,uuid,uuid,text,uuid,text,bigint,jsonb)',
  'EXECUTE') THEN RAISE EXCEPTION 'Anon can access financial RPC'; END IF;
 IF has_table_privilege('authenticated',
  'marketpro_private.team_financial_audit','SELECT') THEN
  RAISE EXCEPTION 'Private audit exposed'; END IF;
END;
$privileges$;
\echo V354 requisition, cash, additions revision checks passed
