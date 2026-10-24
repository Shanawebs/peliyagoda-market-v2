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


\i supabase/proposals/v364_staff_candidate_owner_review_REVIEW_ONLY.sql

\i supabase/proposals/v367_staff_approved_catalog_fetch_REVIEW_ONLY.sql

DO $test$
DECLARE
 w uuid:='11111111-1111-4111-8111-111111111111';
 owner_id text:='22222222-2222-4222-8222-222222222222';
 staff_id text:='33333333-3333-4333-8333-333333333333';
 viewer_id text:='44444444-4444-4444-8444-444444444444';
 owner_dev uuid:='a1111111-1111-4111-8111-111111111111';
 staff_dev uuid:='b1111111-1111-4111-8111-111111111111';
 disabled_dev uuid:='d1111111-1111-4111-8111-111111111111';
 item_id uuid:='e1111111-1111-4111-8111-111111111111';
 other_item uuid:='e2222222-2222-4222-8222-222222222222';
 category_id uuid:='c1111111-1111-4111-8111-111111111111';
 candidate uuid;
 response jsonb;
 denied boolean;
BEGIN
 PERFORM set_config('request.jwt.claims','{"is_anonymous":false}',true);
 PERFORM set_config('request.jwt.claim.sub',staff_id,true);

 -- No Owner-reviewed submission => no staff approval may be downloaded.
 denied:=false;
 BEGIN
  PERFORM public.marketpro_device_catalog_link_approval(w,staff_dev,'item',item_id);
 EXCEPTION WHEN SQLSTATE '42501' THEN denied:=true; END;
 IF NOT denied THEN RAISE EXCEPTION 'No-approval Staff fetch was allowed'; END IF;

 response:=public.marketpro_staff_submit_catalog_candidate(w,staff_dev,'item',
  item_id,8,'29','Carrot','Vegetables',true,repeat('a',64));
 candidate:=(response->>'candidate_id')::uuid;

 -- A pending submission still grants NO catalog identity.
 denied:=false;
 BEGIN
  PERFORM public.marketpro_device_catalog_link_approval(w,staff_dev,'item',item_id);
 EXCEPTION WHEN SQLSTATE '42501' THEN denied:=true; END;
 IF NOT denied THEN RAISE EXCEPTION 'Pending candidate treated as approved'; END IF;

 -- Explicit Owner approval after a matching Staff submission.
 PERFORM set_config('request.jwt.claim.sub',owner_id,true);
 response:=public.marketpro_owner_approve_submitted_catalog(w,owner_dev,candidate);
 IF NOT (response->>'approved')::boolean THEN RAISE EXCEPTION 'Owner approval failed'; END IF;

 -- Staff can now fetch ONLY their own, current, approved exact item proof.
 PERFORM set_config('request.jwt.claim.sub',staff_id,true);
 response:=public.marketpro_device_catalog_link_approval(w,staff_dev,'item',item_id);
 IF response->>'status'<>'owner_approved_for_device_recheck'
    OR response->>'workspace_id'<>w::text
    OR response->>'target_device_id'<>staff_dev::text
    OR response->>'cloud_revision'<>'8'
    OR response->>'local_key'<>'29'
    OR response->>'local_fingerprint_sha256'<>repeat('a',64)
    OR NOT (response->>'requires_current_room_recheck')::boolean
    OR response->>'server_now' IS NULL
    OR response->>'approved_by'<>owner_id THEN
  RAISE EXCEPTION 'Server omitted required device-bound fields: %',response;
 END IF;

 denied:=false;
 BEGIN
  PERFORM public.marketpro_device_catalog_link_approval(w,owner_dev,'item',item_id);
 EXCEPTION WHEN SQLSTATE '42501' THEN denied:=true; END;
 IF NOT denied THEN RAISE EXCEPTION 'Staff could act as owner phone'; END IF;
 denied:=false;
 BEGIN
  PERFORM public.marketpro_device_catalog_link_approval(w,disabled_dev,'item',item_id);
 EXCEPTION WHEN SQLSTATE '42501' THEN denied:=true; END;
 IF NOT denied THEN RAISE EXCEPTION 'Disabled alternate Staff phone could fetch'; END IF;

 PERFORM set_config('request.jwt.claim.sub',viewer_id,true);
 denied:=false;
 BEGIN
  PERFORM public.marketpro_device_catalog_link_approval(w,staff_dev,'item',item_id);
 EXCEPTION WHEN SQLSTATE '42501' THEN denied:=true; END;
 IF NOT denied THEN RAISE EXCEPTION 'Viewer hijacked Staff-approved UUID'; END IF;

 -- Simulate Cloud catalogue changed after Owner approved.
 PERFORM set_config('request.jwt.claim.sub',staff_id,true);
 UPDATE public.items SET revision=9 WHERE id=item_id;
 denied:=false;
 BEGIN
  PERFORM public.marketpro_device_catalog_link_approval(w,staff_dev,'item',item_id);
 EXCEPTION WHEN SQLSTATE '42501' THEN denied:=true; END;
 IF NOT denied THEN RAISE EXCEPTION 'Stale Cloud revision approval usable'; END IF;
 UPDATE public.items SET revision=8 WHERE id=item_id;

 -- Cloud item changes category but revision erroneously same: still deny.
 UPDATE public.items SET category_id='c2222222-2222-4222-8222-222222222222'
 WHERE id=item_id;
 denied:=false;
 BEGIN
  PERFORM public.marketpro_device_catalog_link_approval(w,staff_dev,'item',item_id);
 EXCEPTION WHEN SQLSTATE '42501' THEN denied:=true; END;
 IF NOT denied THEN RAISE EXCEPTION 'Changed Cloud category approval usable'; END IF;
 UPDATE public.items SET category_id='c1111111-1111-4111-8111-111111111111'::uuid WHERE id=item_id;

 -- A direct, arbitrary v362 Approval (even inserted privately as postgres) is
 -- NEVER enough without an approved Staff candidate review.
 PERFORM set_config('request.jwt.claim.sub',owner_id,true);
 INSERT INTO marketpro_private.owner_catalog_link_approvals(
   workspace_id,target_device_id,entity_type,local_key,cloud_id,cloud_revision,
   local_fingerprint_sha256,approved_by)
 VALUES(w,staff_dev,'item','30',other_item,5,repeat('b',64),owner_id::uuid);
 PERFORM set_config('request.jwt.claim.sub',staff_id,true);
 denied:=false;
 BEGIN
  PERFORM public.marketpro_device_catalog_link_approval(w,staff_dev,'item',other_item);
 EXCEPTION WHEN SQLSTATE '42501' THEN denied:=true; END;
 IF NOT denied THEN RAISE EXCEPTION 'Arbitrary non-reviewed Owner approval fetched'; END IF;

 -- Staff still read-only. Revocation blocks previously valid approval.
 UPDATE public.registered_devices SET enabled=false WHERE id=staff_dev;
 denied:=false;
 BEGIN
  PERFORM public.marketpro_device_catalog_link_approval(w,staff_dev,'item',item_id);
 EXCEPTION WHEN SQLSTATE '42501' THEN denied:=true; END;
 IF NOT denied THEN RAISE EXCEPTION 'Revoked Staff phone still fetched proof'; END IF;
 UPDATE public.registered_devices SET enabled=true WHERE id=staff_dev;

 -- Owner must remain active at the time Staff fetches approval.
 UPDATE public.registered_devices SET enabled=false WHERE id=owner_dev;
 denied:=false;
 BEGIN
  PERFORM public.marketpro_device_catalog_link_approval(w,staff_dev,'item',item_id);
 EXCEPTION WHEN SQLSTATE '42501' THEN denied:=true; END;
 IF NOT denied THEN RAISE EXCEPTION 'Revoked Owner approval still active'; END IF;
 UPDATE public.registered_devices SET enabled=true WHERE id=owner_dev;

 UPDATE marketpro_private.owner_catalog_link_approvals
 SET expires_at=clock_timestamp()-interval '1 second'
 WHERE cloud_id=item_id;
 denied:=false;
 BEGIN
  PERFORM public.marketpro_device_catalog_link_approval(w,staff_dev,'item',item_id);
 EXCEPTION WHEN SQLSTATE '42501' THEN denied:=true; END;
 IF NOT denied THEN RAISE EXCEPTION 'Expired approval accepted'; END IF;

 IF (SELECT count(*) FROM public.registered_devices WHERE enabled AND can_write)<>1
    OR (SELECT count(*) FROM public.registered_devices WHERE id=staff_dev AND can_write)<>0 THEN
  RAISE EXCEPTION 'Staff fetch activated write access or altered Original Owner';
 END IF;
END;
$test$;

DO $privileges$
BEGIN
 IF has_table_privilege('authenticated','marketpro_private.owner_catalog_link_approvals','SELECT') THEN
  RAISE EXCEPTION 'Private approval exposed to Data API';
 END IF;
 IF has_function_privilege('anon','public.marketpro_device_catalog_link_approval(uuid,uuid,text,uuid)','EXECUTE') THEN
  RAISE EXCEPTION 'Anonymous may fetch Owner approved catalog link';
 END IF;
 IF has_function_privilege('authenticated',
    'public.marketpro_owner_approve_catalog_link(uuid,uuid,uuid,text,text,uuid,bigint,text)',
    'EXECUTE') THEN
  RAISE EXCEPTION 'Arbitrary direct approval still callable';
 END IF;
END;
$privileges$;
\echo V367 APPROVED STAFF CATALOG FETCH PG17 SECURITY PASS
