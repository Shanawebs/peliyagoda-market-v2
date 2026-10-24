-- V3.6.2 isolated PostgreSQL 17 integration contract; no live Supabase.
\set ON_ERROR_STOP on
CREATE ROLE anon;
CREATE ROLE authenticated;
CREATE SCHEMA auth;
CREATE SCHEMA marketpro_private;
CREATE SCHEMA extensions;
CREATE EXTENSION pgcrypto WITH SCHEMA extensions;
CREATE FUNCTION auth.uid() RETURNS uuid LANGUAGE sql STABLE
AS $f$ SELECT NULLIF(current_setting('request.jwt.claim.sub',true),'')::uuid $f$;
CREATE FUNCTION auth.jwt() RETURNS jsonb LANGUAGE sql STABLE
AS $f$ SELECT coalesce(nullif(current_setting('request.jwt.claims',true),''),'{}')::jsonb $f$;

CREATE TABLE public.workspace_members(
 workspace_id uuid NOT NULL,user_id uuid NOT NULL,role text NOT NULL,
 PRIMARY KEY(workspace_id,user_id)
);
CREATE TABLE public.registered_devices(
 id uuid PRIMARY KEY,workspace_id uuid NOT NULL,user_id uuid NOT NULL,
 enabled boolean NOT NULL DEFAULT false,can_write boolean NOT NULL DEFAULT false
);
CREATE TABLE public.categories(
 id uuid PRIMARY KEY,workspace_id uuid NOT NULL,revision bigint NOT NULL,
 deleted_at timestamptz
);
CREATE TABLE public.items(
 id uuid PRIMARY KEY,workspace_id uuid NOT NULL,category_id uuid NOT NULL,
 revision bigint NOT NULL,deleted_at timestamptz
);
INSERT INTO public.workspace_members VALUES
 ('11111111-1111-4111-8111-111111111111','22222222-2222-4222-8222-222222222222','owner'),
 ('11111111-1111-4111-8111-111111111111','33333333-3333-4333-8333-333333333333','purchasing_staff'),
 ('11111111-1111-4111-8111-111111111111','44444444-4444-4444-8444-444444444444','viewer');
INSERT INTO public.registered_devices VALUES
 ('a1111111-1111-4111-8111-111111111111','11111111-1111-4111-8111-111111111111','22222222-2222-4222-8222-222222222222',true,true),
 ('b1111111-1111-4111-8111-111111111111','11111111-1111-4111-8111-111111111111','33333333-3333-4333-8333-333333333333',true,false),
 ('c1111111-1111-4111-8111-111111111111','11111111-1111-4111-8111-111111111111','44444444-4444-4444-8444-444444444444',true,false),
 ('d1111111-1111-4111-8111-111111111111','11111111-1111-4111-8111-111111111111','33333333-3333-4333-8333-333333333333',false,false);
INSERT INTO public.categories VALUES
 ('c1111111-1111-4111-8111-111111111111','11111111-1111-4111-8111-111111111111',3,NULL),
 ('c2222222-2222-4222-8222-222222222222','99999999-9999-4999-8999-999999999999',1,NULL);
INSERT INTO public.items VALUES
 ('e1111111-1111-4111-8111-111111111111','11111111-1111-4111-8111-111111111111','c1111111-1111-4111-8111-111111111111',8,NULL),
 ('e2222222-2222-4222-8222-222222222222','11111111-1111-4111-8111-111111111111','c1111111-1111-4111-8111-111111111111',5,NULL),
 ('e3333333-3333-4333-8333-333333333333','99999999-9999-4999-8999-999999999999','c2222222-2222-4222-8222-222222222222',1,NULL);

\i supabase/proposals/v362_owner_approved_catalog_links_REVIEW_ONLY.sql

DO $test$
DECLARE
 w uuid := '11111111-1111-4111-8111-111111111111';
 owner_id text := '22222222-2222-4222-8222-222222222222';
 staff_id text := '33333333-3333-4333-8333-333333333333';
 viewer_id text := '44444444-4444-4444-8444-444444444444';
 owner_dev uuid := 'a1111111-1111-4111-8111-111111111111';
 staff_dev uuid := 'b1111111-1111-4111-8111-111111111111';
 viewer_dev uuid := 'c1111111-1111-4111-8111-111111111111';
 disabled_dev uuid := 'd1111111-1111-4111-8111-111111111111';
 item_id uuid := 'e1111111-1111-4111-8111-111111111111';
 other_item_id uuid := 'e2222222-2222-4222-8222-222222222222';
 category_id uuid := 'c1111111-1111-4111-8111-111111111111';
 response jsonb;
 approved_id text;
 denied boolean;
BEGIN
 PERFORM set_config('request.jwt.claims','{"is_anonymous":false}',true);
 PERFORM set_config('request.jwt.claim.sub',owner_id,true);
 response:=public.marketpro_owner_approve_catalog_link(
   w,owner_dev,staff_dev,'item','29',item_id,8,repeat('a',64));
 IF response->>'status'<>'owner_approved_pending_device_validation' THEN
   RAISE EXCEPTION 'Owner approval not saved';
 END IF;
 approved_id:=response->>'approval_id';
 IF approved_id IS NULL THEN RAISE EXCEPTION 'Approval ID absent'; END IF;
 -- Staff can read exactly THEIR registered device's owner-approved link.
 PERFORM set_config('request.jwt.claim.sub',staff_id,true);
 response:=public.marketpro_device_catalog_link_approval(w,staff_dev,'item',item_id);
 IF response->>'local_key'<>'29' OR response->>'cloud_revision'<>'8' OR
    response->>'approval_id'<>approved_id OR
    response->>'local_fingerprint_sha256'<>repeat('a',64) THEN
   RAISE EXCEPTION 'Staff device approval unexpected %',response;
 END IF;
 denied:=false;
 BEGIN
   PERFORM public.marketpro_owner_approve_catalog_link(
     w,owner_dev,staff_dev,'item','30',other_item_id,5,repeat('b',64));
 EXCEPTION WHEN SQLSTATE '42501' THEN denied:=true; END;
 IF NOT denied THEN RAISE EXCEPTION 'Staff self-approved a catalog mapping'; END IF;
 denied:=false;
 BEGIN
   PERFORM public.marketpro_device_catalog_link_approval(w,disabled_dev,'item',item_id);
 EXCEPTION WHEN SQLSTATE '42501' THEN denied:=true; END;
 IF NOT denied THEN RAISE EXCEPTION 'Disabled phone fetched approval'; END IF;
 denied:=false;
 BEGIN
   PERFORM public.marketpro_device_catalog_link_approval(w,owner_dev,'item',item_id);
 EXCEPTION WHEN SQLSTATE '42501' THEN denied:=true; END;
 IF NOT denied THEN RAISE EXCEPTION 'Staff impersonated owner device'; END IF;
 denied:=false;
 BEGIN
   PERFORM public.marketpro_device_catalog_link_approval(
     '99999999-9999-4999-8999-999999999999'::uuid,staff_dev,'item',item_id);
 EXCEPTION WHEN SQLSTATE '42501' THEN denied:=true; END;
 IF NOT denied THEN RAISE EXCEPTION 'Cross-workspace fetch'; END IF;
 PERFORM set_config('request.jwt.claim.sub',viewer_id,true);
 denied:=false;
 BEGIN
   PERFORM public.marketpro_device_catalog_link_approval(w,viewer_dev,'item',item_id);
 EXCEPTION WHEN SQLSTATE '42501' THEN denied:=true; END;
 IF NOT denied THEN RAISE EXCEPTION 'Viewer fetched Staff approval'; END IF;
 -- Owner cannot forge a stale revision, wrong workspace, duplicate local ID
 -- or wrong cloud UUID after first approval. Item must not be deleted.
 PERFORM set_config('request.jwt.claim.sub',owner_id,true);
 denied:=false;
 BEGIN
   PERFORM public.marketpro_owner_approve_catalog_link(
     w,owner_dev,staff_dev,'item','30',other_item_id,4,repeat('b',64));
 EXCEPTION WHEN SQLSTATE '42501' THEN denied:=true; END;
 IF NOT denied THEN RAISE EXCEPTION 'Stale catalog revision approved'; END IF;
 denied:=false;
 BEGIN
   PERFORM public.marketpro_owner_approve_catalog_link(
     w,owner_dev,staff_dev,'item','29',other_item_id,5,repeat('b',64));
 EXCEPTION WHEN unique_violation THEN denied:=true; END;
 IF NOT denied THEN RAISE EXCEPTION 'One staff local item mapped to 2 cloud UUIDs'; END IF;
 denied:=false;
 BEGIN
   PERFORM public.marketpro_owner_approve_catalog_link(
     w,owner_dev,staff_dev,'item','30',item_id,8,repeat('b',64));
 EXCEPTION WHEN unique_violation THEN denied:=true; END;
 IF NOT denied THEN RAISE EXCEPTION 'One cloud UUID mapped to 2 local IDs'; END IF;
 denied:=false;
 BEGIN
   PERFORM public.marketpro_owner_approve_catalog_link(
     w,owner_dev,staff_dev,'item','30',
     'e3333333-3333-4333-8333-333333333333'::uuid,1,repeat('b',64));
 EXCEPTION WHEN SQLSTATE '42501' THEN denied:=true; END;
 IF NOT denied THEN RAISE EXCEPTION 'Different workspace catalog approved'; END IF;
 denied:=false;
 BEGIN
   PERFORM public.marketpro_owner_approve_catalog_link(
     w,owner_dev,disabled_dev,'category','vegetables',category_id,3,repeat('c',64));
 EXCEPTION WHEN SQLSTATE '42501' THEN denied:=true; END;
 IF NOT denied THEN RAISE EXCEPTION 'Disabled Staff target accepted'; END IF;
 -- Category approval uses string logical key, never Room auto ID.
 response:=public.marketpro_owner_approve_catalog_link(
    w,owner_dev,staff_dev,'category','vegetables',category_id,3,repeat('c',64));
 IF response->>'cloud_revision'<>'3' THEN
    RAISE EXCEPTION 'Category owner approval failed';
 END IF;
 -- Expiry must fail even with an otherwise valid Staff JWT/device.
 UPDATE marketpro_private.owner_catalog_link_approvals
 SET expires_at=clock_timestamp()-interval '1 second'
 WHERE cloud_id=item_id;
 PERFORM set_config('request.jwt.claim.sub',staff_id,true);
 denied:=false;
 BEGIN
   PERFORM public.marketpro_device_catalog_link_approval(w,staff_dev,'item',item_id);
 EXCEPTION WHEN SQLSTATE '42501' THEN denied:=true; END;
 IF NOT denied THEN RAISE EXCEPTION 'Expired approval reused'; END IF;
 PERFORM set_config('request.jwt.claims','{"is_anonymous":true}',true);
 denied:=false;
 BEGIN
   PERFORM public.marketpro_device_catalog_link_approval(w,staff_dev,'category',category_id);
 EXCEPTION WHEN SQLSTATE '42501' THEN denied:=true; END;
 IF NOT denied THEN RAISE EXCEPTION 'Anonymous Staff fetched approval'; END IF;
 -- No existing app live-writer permission or membership is changed.
 IF (SELECT count(*) FROM public.registered_devices WHERE can_write AND enabled)<>1 OR
    (SELECT count(*) FROM public.workspace_members)<>3 THEN
   RAISE EXCEPTION 'Existing Cloud device rights changed';
 END IF;
END;
$test$;
DO $priv$
BEGIN
 IF has_table_privilege('authenticated',
     'marketpro_private.owner_catalog_link_approvals','SELECT') THEN
   RAISE EXCEPTION 'Private approvals exposed via Data API';
 END IF;
 IF has_function_privilege('anon',
     'public.marketpro_owner_approve_catalog_link(uuid,uuid,uuid,text,text,uuid,bigint,text)','EXECUTE') OR
    has_function_privilege('anon',
     'public.marketpro_device_catalog_link_approval(uuid,uuid,text,uuid)','EXECUTE') THEN
    RAISE EXCEPTION 'Anonymous approval RPC access';
 END IF;
END;
$priv$;
\echo V362 OWNER-VERIFIED CATALOG LINK PG17 SECURITY TESTS PASS
