-- Local throwaway PostgreSQL 17 smoke tests only. Never point at Supabase.
\set ON_ERROR_STOP on

DO $roles$
BEGIN
  BEGIN CREATE ROLE anon; EXCEPTION WHEN duplicate_object THEN NULL; END;
  BEGIN CREATE ROLE authenticated; EXCEPTION WHEN duplicate_object THEN NULL; END;
END;
$roles$;
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


CREATE TABLE public.categories (
 id uuid PRIMARY KEY,workspace_id uuid NOT NULL,name text NOT NULL,
 revision bigint NOT NULL DEFAULT 1,deleted_at timestamptz,
 updated_at timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE public.items (
 id uuid PRIMARY KEY,workspace_id uuid NOT NULL,category_id uuid,
 name text NOT NULL,is_active boolean NOT NULL DEFAULT true,
 revision bigint NOT NULL DEFAULT 1,deleted_at timestamptz,
 updated_at timestamptz NOT NULL DEFAULT now()
);
\i supabase/proposals/v354_team_paged_pull_REVIEW_ONLY.sql

DO $test$
DECLARE
 w uuid := '11111111-1111-4111-8111-111111111111';
 fake_w uuid := '99999999-9999-4999-8999-999999999999';
 owner_id text := '22222222-2222-4222-8222-222222222222';
 staff_id text := '33333333-3333-4333-8333-333333333333';
 viewer_id text := '44444444-4444-4444-8444-444444444444';
 owner_phone uuid := 'a1111111-1111-4111-8111-111111111111';
 staff_phone uuid := 'b1111111-1111-4111-8111-111111111111';
 viewer_phone uuid := 'c1111111-1111-4111-8111-111111111111';
 disabled_phone uuid := 'd1111111-1111-4111-8111-111111111111';
 i integer;
 day date := (clock_timestamp() AT TIME ZONE 'Asia/Colombo')::date;
 after_time timestamptz;
 after_id uuid;
 r jsonb;
 items jsonb;
 denied boolean;
BEGIN
 FOR i IN 1..5 LOOP
  INSERT INTO public.purchases(id,workspace_id,business_date,item_id,
    quantity_grams,price_cents_per_kg,updated_at)
  VALUES(
    ('e0000000-0000-4000-8000-' || lpad(i::text,12,'0'))::uuid,
    w,day,('f0000000-0000-4000-8000-' || lpad(i::text,12,'0'))::uuid,
    1000,10000,'2026-10-24 00:00:00+00'::timestamptz+i*interval '1 second'
  );
 END LOOP;
 -- Another workspace row must NEVER be returned, even if the caller knows its UUID.
 INSERT INTO public.purchases(id,workspace_id,business_date,item_id,
   quantity_grams,price_cents_per_kg,updated_at)
 VALUES(
   'e9999999-9999-4999-8999-999999999999'::uuid,fake_w,day,
   'f2222222-2222-4222-8222-222222222222'::uuid,
   500,1000,'2026-10-24 00:00:00+00'::timestamptz);

 UPDATE public.purchases SET deleted_at=now()
 WHERE id='e0000000-0000-4000-8000-000000000004'::uuid;

 PERFORM set_config('request.jwt.claims','{"is_anonymous":false}',true);
 PERFORM set_config('request.jwt.claim.sub',staff_id,true);
 r := public.marketpro_team_pull_page(w,staff_phone,'purchase',null,null,2);
 IF jsonb_array_length(r->'rows')<>2 OR NOT (r->>'has_more')::boolean THEN
   RAISE EXCEPTION 'Page one not 2 rows with has_more %',r;
 END IF;
 IF (r->'rows'->0->>'id') IS NULL OR
    (r->>'server_today_colombo')::date<>day THEN
   RAISE EXCEPTION 'Page cursor lacks canonical records/date';
 END IF;
 after_time := (r->>'next_updated_at')::timestamptz;
 after_id := (r->>'next_id')::uuid;
 r := public.marketpro_team_pull_page(w,staff_phone,'purchase',after_time,after_id,2);
 IF jsonb_array_length(r->'rows')<>2 OR NOT (r->>'has_more')::boolean OR
    (r->'rows'->1->>'deleted_at') IS NULL THEN
   RAISE EXCEPTION 'Tombstone missing in second page %',r;
 END IF;
 after_time := (r->>'next_updated_at')::timestamptz;
 after_id := (r->>'next_id')::uuid;
 r := public.marketpro_team_pull_page(w,staff_phone,'purchase',after_time,after_id,2);
 IF jsonb_array_length(r->'rows')<>1 OR (r->>'has_more')::boolean THEN
   RAISE EXCEPTION 'Last page unexpected %',r;
 END IF;
 after_time := (r->>'next_updated_at')::timestamptz;
 after_id := (r->>'next_id')::uuid;
 r := public.marketpro_team_pull_page(w,staff_phone,'purchase',after_time,after_id,2);
 IF jsonb_array_length(r->'rows')<>0 OR (r->>'next_id')::uuid<>after_id THEN
   RAISE EXCEPTION 'Empty final page cursor moved unexpectedly %',r;
 END IF;

 -- Owner and VIEWER can read but cannot use a disabled/mismatched phone.
 PERFORM set_config('request.jwt.claim.sub',owner_id,true);
 r := public.marketpro_team_pull_page(w,owner_phone,'requisition',null,null,10);
 IF jsonb_array_length(r->'rows')<>0 THEN RAISE EXCEPTION 'Unexpected requisitions'; END IF;
 PERFORM set_config('request.jwt.claim.sub',viewer_id,true);
 r := public.marketpro_team_pull_page(w,viewer_phone,'purchase',null,null,10);
 IF jsonb_array_length(r->'rows')<>5 THEN RAISE EXCEPTION 'Viewer cannot read workspace'; END IF;

 PERFORM set_config('request.jwt.claim.sub',staff_id,true);
 denied:=false;
 BEGIN
   PERFORM public.marketpro_team_pull_page(w,disabled_phone,'purchase',null,null,5);
 EXCEPTION WHEN insufficient_privilege THEN denied:=true;
 END;
 IF NOT denied THEN RAISE EXCEPTION 'Disabled staff read accepted'; END IF;

 denied:=false;
 BEGIN
   PERFORM public.marketpro_team_pull_page(fake_w,staff_phone,'purchase',null,null,5);
 EXCEPTION WHEN insufficient_privilege THEN denied:=true;
 END;
 IF NOT denied THEN RAISE EXCEPTION 'Cross-workspace read accepted'; END IF;

 denied:=false;
 BEGIN
   PERFORM public.marketpro_team_pull_page(w,staff_phone,'sync_operations',null,null,5);
 EXCEPTION WHEN invalid_parameter_value THEN denied:=true;
 END;
 IF NOT denied THEN RAISE EXCEPTION 'Hidden operations table exposed'; END IF;

 denied:=false;
 BEGIN
   PERFORM public.marketpro_team_pull_page(w,staff_phone,'purchase',now(),null,5);
 EXCEPTION WHEN invalid_parameter_value THEN denied:=true;
 END;
 IF NOT denied THEN RAISE EXCEPTION 'Cursor with missing UUID accepted'; END IF;

 denied:=false;
 BEGIN
   PERFORM public.marketpro_team_pull_page(w,staff_phone,'purchase',null,null,101);
 EXCEPTION WHEN invalid_parameter_value THEN denied:=true;
 END;
 IF NOT denied THEN RAISE EXCEPTION 'Oversized page accepted'; END IF;

 PERFORM set_config('request.jwt.claims','{"is_anonymous":true}',true);
 denied:=false;
 BEGIN
   PERFORM public.marketpro_team_pull_page(w,staff_phone,'purchase',null,null,5);
 EXCEPTION WHEN insufficient_privilege THEN denied:=true;
 END;
 IF NOT denied THEN RAISE EXCEPTION 'Anonymous reader accepted'; END IF;
END;
$test$;

DO $privileges$
BEGIN
 IF has_function_privilege('anon',
   'public.marketpro_team_pull_page(uuid,uuid,text,timestamptz,uuid,integer)',
   'EXECUTE') THEN RAISE EXCEPTION 'Anon can call download RPC'; END IF;
 IF NOT has_function_privilege('authenticated',
   'public.marketpro_team_pull_page(uuid,uuid,text,timestamptz,uuid,integer)',
   'EXECUTE') THEN RAISE EXCEPTION 'Authenticated role cannot call downloader'; END IF;
END;
$privileges$;
\echo V354 owner/staff paginated download and tombstones passed
