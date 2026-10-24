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

DO $test$
DECLARE
  w uuid := '11111111-1111-4111-8111-111111111111';
  owner_id text := '22222222-2222-4222-8222-222222222222';
  staff_id text := '33333333-3333-4333-8333-333333333333';
  staff_phone uuid := 'b1111111-1111-4111-8111-111111111111';
  owner_phone uuid := 'a1111111-1111-4111-8111-111111111111';
  today date := (clock_timestamp() AT TIME ZONE 'Asia/Colombo')::date;
  item1 uuid := 'f1111111-1111-4111-8111-111111111111';
  item2 uuid := 'f2222222-2222-4222-8222-222222222222';
  purchase1 uuid := 'e1111111-1111-4111-8111-111111111111';
  purchase2 uuid := 'e2222222-2222-4222-8222-222222222222';
  payload jsonb;
  op uuid;
  result jsonb;
  n bigint;
  denied boolean;
BEGIN
  PERFORM set_config('request.jwt.claims','{"is_anonymous":false}',true);
  PERFORM set_config('request.jwt.claim.sub',staff_id,true);
  payload := jsonb_build_object(
    'business_date',today,'item_id',item1,
    'quantity_grams',1000,'price_cents_per_kg',10000
  );
  op := 'e0000000-0000-4000-8000-000000000001';
  result := public.marketpro_apply_team_purchase_change(
    w,staff_phone,op,purchase1,'create',null,payload
  );
  IF (result->>'revision')::bigint<>1 OR
     (result->>'replayed')::boolean THEN
       RAISE EXCEPTION 'Staff create unexpected result %',result;
  END IF;

  -- Same idempotency operation ID returns an ACK without another edit.
  result := public.marketpro_apply_team_purchase_change(
    w,staff_phone,op,purchase1,'create',null,payload
  );
  IF NOT (result->>'replayed')::boolean OR
     (SELECT revision FROM public.purchases WHERE id=purchase1)<>1 THEN
       RAISE EXCEPTION 'Duplicate idempotency was not acknowledged';
  END IF;
  denied := false;
  BEGIN
    PERFORM public.marketpro_apply_team_purchase_change(
      w,staff_phone,op,purchase1,'create',null,
      jsonb_set(payload,'{quantity_grams}','2000'::jsonb)
    );
  EXCEPTION WHEN unique_violation THEN denied := true;
  END;
  IF NOT denied THEN RAISE EXCEPTION 'Operation ID accepted changed payload'; END IF;

  -- 10 same-day staff edits. Every one must acknowledge a CURRENT revision.
  FOR n IN 1..10 LOOP
    op := ('e0000000-0000-4000-8000-' || lpad((n+1)::text,12,'0'))::uuid;
    result := public.marketpro_apply_team_purchase_change(
      w,staff_phone,op,purchase1,'correct',n,
      jsonb_set(payload,'{quantity_grams}',to_jsonb(1000+n))
    );
    IF (result->>'revision')::bigint<>(n+1) THEN
        RAISE EXCEPTION 'Revision mismatch: %',result;
    END IF;
  END LOOP;
  IF (SELECT revision FROM public.purchases WHERE id=purchase1)<>11 THEN
     RAISE EXCEPTION 'Ten edits failed';
  END IF;
  -- Correct revision but changed semantic business key is forbidden.
  denied := false;
  BEGIN
    PERFORM public.marketpro_apply_team_purchase_change(
      w,staff_phone,'e0000000-0000-4000-8000-000000000013',purchase1,
      'correct',11,jsonb_set(payload,'{item_id}',to_jsonb(item2::text))
    );
  EXCEPTION WHEN insufficient_privilege THEN denied:=true;
  END;
  IF NOT denied THEN RAISE EXCEPTION 'Item/date remap allowed'; END IF;

  -- Owner corrects today. Old staff revision can't override owner fix.
  PERFORM set_config('request.jwt.claim.sub',owner_id,true);
  result := public.marketpro_apply_team_purchase_change(
    w,owner_phone,'e0000000-0000-4000-8000-000000000014',purchase1,
    'correct',11,jsonb_set(payload,'{price_cents_per_kg}','12000'::jsonb)
  );
  IF (result->>'revision')::bigint<>12 THEN
     RAISE EXCEPTION 'Owner correction failed';
  END IF;
  PERFORM set_config('request.jwt.claim.sub',staff_id,true);
  denied:=false;
  BEGIN
    PERFORM public.marketpro_apply_team_purchase_change(
      w,staff_phone,'e0000000-0000-4000-8000-000000000015',purchase1,
      'correct',11,payload
    );
  EXCEPTION WHEN serialization_failure THEN denied:=true;
  END;
  IF NOT denied THEN RAISE EXCEPTION 'Stale staff edit overwrote owner fix'; END IF;
  -- Staff can STILL correct same-day record, once it uses the NEW revision.
  result := public.marketpro_apply_team_purchase_change(
    w,staff_phone,'e0000000-0000-4000-8000-000000000016',purchase1,
    'correct',12,jsonb_set(payload,'{quantity_grams}','2000'::jsonb)
  );
  IF (result->>'revision')::bigint<>13 THEN
     RAISE EXCEPTION 'Latest staff correction failed';
  END IF;

  -- Staff cannot create yesterday or tomorrow.
  denied:=false;
  BEGIN
    PERFORM public.marketpro_apply_team_purchase_change(
      w,staff_phone,'e0000000-0000-4000-8000-000000000017',purchase2,
      'create',null,jsonb_set(payload,'{business_date}',to_jsonb((today-1)::text))
    );
  EXCEPTION WHEN insufficient_privilege THEN denied:=true;
  END;
  IF NOT denied THEN RAISE EXCEPTION 'Staff backdated create allowed'; END IF;
  denied:=false;
  BEGIN
    PERFORM public.marketpro_apply_team_purchase_change(
      w,staff_phone,'e0000000-0000-4000-8000-000000000018',purchase2,
      'create',null,jsonb_set(payload,'{business_date}',to_jsonb((today+1)::text))
    );
  EXCEPTION WHEN insufficient_privilege THEN denied:=true;
  END;
  IF NOT denied THEN RAISE EXCEPTION 'Staff future create allowed'; END IF;

  -- Owner may create/correct an old business date.
  PERFORM set_config('request.jwt.claim.sub',owner_id,true);
  result := public.marketpro_apply_team_purchase_change(
    w,owner_phone,'e0000000-0000-4000-8000-000000000019',purchase2,
    'create',null,jsonb_set(jsonb_set(payload,'{business_date}',to_jsonb((today-1)::text)),
           '{item_id}',to_jsonb(item2::text))
  );
  IF (result->>'revision')::bigint<>1 THEN
    RAISE EXCEPTION 'Owner old-day entry failed';
  END IF;
  result := public.marketpro_apply_team_purchase_change(
    w,owner_phone,'e0000000-0000-4000-8000-000000000020',purchase2,
    'correct',1,jsonb_set(jsonb_set(payload,'{business_date}',to_jsonb((today-1)::text)),
             '{item_id}',to_jsonb(item2::text))
  );
  IF (result->>'revision')::bigint<>2 THEN
    RAISE EXCEPTION 'Owner old-day correction failed';
  END IF;

  -- Staff cannot reuse a recorded past entry, even if supplying TODAY date.
  PERFORM set_config('request.jwt.claim.sub',staff_id,true);
  denied:=false;
  BEGIN
    PERFORM public.marketpro_apply_team_purchase_change(
      w,staff_phone,'e0000000-0000-4000-8000-000000000021',purchase2,
      'correct',2,jsonb_set(jsonb_set(payload,'{business_date}',to_jsonb(today::text)),
              '{item_id}',to_jsonb(item2::text))
    );
  EXCEPTION WHEN insufficient_privilege THEN denied:=true;
  END;
  IF NOT denied THEN RAISE EXCEPTION 'Staff previous record moved into today'; END IF;

  -- NEW UUID but existing day+item business key: reject.
  denied:=false;
  BEGIN
    PERFORM public.marketpro_apply_team_purchase_change(
      w,staff_phone,'e0000000-0000-4000-8000-000000000022',
      'e3333333-3333-4333-8333-333333333333'::uuid,
      'create',null,payload
    );
  EXCEPTION WHEN unique_violation THEN denied:=true;
  END;
  IF NOT denied THEN RAISE EXCEPTION 'Duplicate purchase business key allowed'; END IF;

  -- No invoice paths / photos / arbitrary columns via RPC.
  denied:=false;
  BEGIN
    PERFORM public.marketpro_apply_team_purchase_change(
      w,staff_phone,'e0000000-0000-4000-8000-000000000023',
      'e4444444-4444-4444-8444-444444444444'::uuid,
      'create',null,payload || '{"invoice_photo":"/storage/secret"}'::jsonb
    );
  EXCEPTION WHEN invalid_parameter_value THEN denied:=true;
  END;
  IF NOT denied THEN RAISE EXCEPTION 'Photo field accepted'; END IF;

  IF (SELECT count(*) FROM marketpro_private.team_purchase_audit) <> 15 OR
     (SELECT count(*) FROM public.sync_operations) <> 15 THEN
    RAISE EXCEPTION 'Transaction or audit count mismatch';
  END IF;
END;
$test$;

DO $privileges$
BEGIN
    IF has_function_privilege('anon',
      'public.marketpro_apply_team_purchase_change(uuid,uuid,uuid,uuid,text,bigint,jsonb)',
      'EXECUTE') THEN RAISE EXCEPTION 'Anonymous role can call Team RPC'; END IF;
    IF NOT has_function_privilege('authenticated',
      'public.marketpro_apply_team_purchase_change(uuid,uuid,uuid,uuid,text,bigint,jsonb)',
      'EXECUTE') THEN RAISE EXCEPTION 'Authenticated role cannot call team RPC'; END IF;
    IF has_table_privilege('authenticated',
      'marketpro_private.team_purchase_audit','SELECT') THEN
       RAISE EXCEPTION 'Audit table exposed';
    END IF;
END;
$privileges$;
\echo V353 purchase-only team write RPC safe tests passed
