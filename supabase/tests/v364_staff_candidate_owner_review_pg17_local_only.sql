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
 other_item uuid := 'e2222222-2222-4222-8222-222222222222';
 category_id uuid := 'c1111111-1111-4111-8111-111111111111';
 response jsonb;
 candidate uuid;
 another uuid;
 denied boolean;
BEGIN
 PERFORM set_config('request.jwt.claims','{"is_anonymous":false}',true);
 PERFORM set_config('request.jwt.claim.sub',staff_id,true);
 response := public.marketpro_staff_submit_catalog_candidate(
   w,staff_dev,'item',item_id,8,
   '29','Carrot','Vegetables',true,repeat('a',64));
 candidate := (response->>'candidate_id')::uuid;
 IF candidate IS NULL OR response->>'status'<>'awaiting_owner_review' THEN
    RAISE EXCEPTION 'Staff candidate not submitted';
 END IF;
 IF (SELECT count(*) FROM marketpro_private.staff_catalog_review_candidates)<>1 THEN
    RAISE EXCEPTION 'Candidate not stored in private table';
 END IF;
 IF (SELECT status FROM marketpro_private.staff_catalog_review_candidates
     WHERE candidate_id=candidate)<>'pending' THEN
    RAISE EXCEPTION 'Staff submission automatically approved';
 END IF;

 -- Staff cannot read Owner's private queue or approve their own record.
 denied:=false;
 BEGIN
   PERFORM public.marketpro_owner_list_catalog_candidates(w,owner_dev,null,30);
 EXCEPTION WHEN insufficient_privilege THEN denied:=true; END;
 IF NOT denied THEN RAISE EXCEPTION 'Staff accessed owner review inbox'; END IF;
 denied:=false;
 BEGIN
   PERFORM public.marketpro_owner_approve_submitted_catalog(w,owner_dev,candidate);
 EXCEPTION WHEN insufficient_privilege THEN denied:=true; END;
 IF NOT denied THEN RAISE EXCEPTION 'Staff self-approved catalog'; END IF;

 -- Another device, wrong role, stale revision, bad natural key all denied.
 denied:=false;
 BEGIN
   PERFORM public.marketpro_staff_submit_catalog_candidate(
     w,disabled_dev,'item',other_item,5,
     '30','Potato','Vegetables',true,repeat('b',64));
 EXCEPTION WHEN insufficient_privilege THEN denied:=true; END;
 IF NOT denied THEN RAISE EXCEPTION 'Disabled device submitted candidate'; END IF;
 denied:=false;
 BEGIN
   PERFORM public.marketpro_staff_submit_catalog_candidate(
     w,staff_dev,'item',other_item,4,
     '30','Potato','Vegetables',true,repeat('b',64));
 EXCEPTION WHEN insufficient_privilege THEN denied:=true; END;
 IF NOT denied THEN RAISE EXCEPTION 'Stale cloud revision accepted'; END IF;
 denied:=false;
 BEGIN
   PERFORM public.marketpro_staff_submit_catalog_candidate(
     w,staff_dev,'item',other_item,5,
     '0','Potato','Vegetables',true,repeat('b',64));
 EXCEPTION WHEN invalid_parameter_value THEN denied:=true; END;
 IF NOT denied THEN RAISE EXCEPTION 'Invalid local item ID accepted'; END IF;
 denied:=false;
 BEGIN
   PERFORM public.marketpro_staff_submit_catalog_candidate(
     w,staff_dev,'item','e3333333-3333-4333-8333-333333333333',
     1,'32','Alien','Other',true,repeat('b',64));
 EXCEPTION WHEN insufficient_privilege THEN denied:=true; END;
 IF NOT denied THEN RAISE EXCEPTION 'Cross-workspace cloud item accepted'; END IF;
 -- Candidate may be resubmitted by SAME staff/phone for same exact local ID
 -- while pending; it must rotate candidate UUID so stale owner selection dies.
 response:=public.marketpro_staff_submit_catalog_candidate(
     w,staff_dev,'item',item_id,8,
     '29','Carrot','Vegetables',true,repeat('c',64));
 another:=(response->>'candidate_id')::uuid;
 IF another=candidate THEN
     RAISE EXCEPTION 'Staff changed details without new candidate UUID';
 END IF;
 candidate:=another;

 -- Owner fetches exact bounded server-sourced candidate details.
 PERFORM set_config('request.jwt.claim.sub',owner_id,true);
 response:=public.marketpro_owner_list_catalog_candidates(w,owner_dev,null,20);
 IF pg_catalog.jsonb_array_length(response->'candidates')<>1
    OR response->'candidates'->0->>'candidate_id'<>candidate::text
    OR response->'candidates'->0->>'local_name'<>'Carrot'
    OR response->'candidates'->0->>'local_key'<>'29'
    OR response->'candidates'->0->>'device_id'<>staff_dev::text
    OR response->'candidates'->0->>'local_fingerprint_sha256'<>repeat('c',64)
    OR NOT (response->>'requires_explicit_owner_approval')::boolean THEN
   RAISE EXCEPTION 'Owner cannot see exact staff snapshot';
 END IF;
 response:=public.marketpro_owner_list_catalog_candidates(
    w,owner_dev,candidate,20);
 IF pg_catalog.jsonb_array_length(response->'candidates')<>0 THEN
    RAISE EXCEPTION 'Paged cursor repeats earlier candidate';
 END IF;
 denied:=false;
 BEGIN
   PERFORM public.marketpro_owner_list_catalog_candidates(w,owner_dev,null,51);
 EXCEPTION WHEN insufficient_privilege THEN denied:=true; END;
 IF NOT denied THEN RAISE EXCEPTION 'Owner page size not bounded'; END IF;

 -- Deliberately revoked v362 arbitrary request endpoint must be unusable
 -- by authenticated. (Mock creator postgres has special superuser privileges.)
 IF has_function_privilege('authenticated',
   'public.marketpro_owner_approve_catalog_link(uuid,uuid,uuid,text,text,uuid,bigint,text)',
   'EXECUTE') THEN
   RAISE EXCEPTION 'Unsafe owner arbitrary-key approval endpoint still exposed';
 END IF;

 -- Viewer cannot obtain candidate list.
 PERFORM set_config('request.jwt.claim.sub',viewer_id,true);
 denied:=false;
 BEGIN
   PERFORM public.marketpro_owner_list_catalog_candidates(w,viewer_dev,null,30);
 EXCEPTION WHEN insufficient_privilege THEN denied:=true; END;
 IF NOT denied THEN RAISE EXCEPTION 'Viewer accessed owner candidates'; END IF;

 -- Owner accepts exactly the submitted candidate. Staff phone stays READ-ONLY.
 PERFORM set_config('request.jwt.claim.sub',owner_id,true);
 response:=public.marketpro_owner_approve_submitted_catalog(w,owner_dev,candidate);
 IF NOT (response->>'approved')::boolean OR
    response->>'candidate_id'<>candidate::text THEN
   RAISE EXCEPTION 'Owner could not approve selected candidate';
 END IF;
 IF (SELECT count(*) FROM marketpro_private.owner_catalog_link_approvals
     WHERE target_device_id=staff_dev AND cloud_id=item_id AND
       local_key='29' AND local_fingerprint_sha256=repeat('c',64)
       AND approved_by=owner_id::uuid)<>1 THEN
   RAISE EXCEPTION 'Owner approval did not bind submitted digest';
 END IF;
 IF (SELECT status FROM marketpro_private.staff_catalog_review_candidates
     WHERE candidate_id=candidate)<>'approved' THEN
   RAISE EXCEPTION 'Review candidate not atomically consumed';
 END IF;
 denied:=false;
 BEGIN
    PERFORM public.marketpro_owner_approve_submitted_catalog(w,owner_dev,candidate);
 EXCEPTION WHEN insufficient_privilege THEN denied:=true; END;
 IF NOT denied THEN RAISE EXCEPTION 'Approved candidate replay accepted'; END IF;

 -- Staff cannot silently rewrite a consumed/approved candidate.
 PERFORM set_config('request.jwt.claim.sub',staff_id,true);
 denied:=false;
 BEGIN
    PERFORM public.marketpro_staff_submit_catalog_candidate(
      w,staff_dev,'item',item_id,8,
      '29','Carrot','Vegetables',true,repeat('d',64));
 EXCEPTION WHEN unique_violation THEN denied:=true; END;
 IF NOT denied THEN RAISE EXCEPTION 'Approved candidate overwritten'; END IF;

 -- Category display name and Room logical key have different casing.
 -- The canonical Room/Cloud category key is lowercase "vegetables".
 response:=public.marketpro_staff_submit_catalog_candidate(
     w,staff_dev,'category',
     'c1111111-1111-4111-8111-111111111111'::uuid,3,
     'vegetables','Vegetables',NULL,NULL,repeat('e',64));
 IF response->>'status'<>'awaiting_owner_review' THEN
     RAISE EXCEPTION 'Canonical category name/key pair rejected';
 END IF;
 denied:=false;
 BEGIN
     PERFORM public.marketpro_staff_submit_catalog_candidate(
         w,staff_dev,'category',
         'c1111111-1111-4111-8111-111111111111'::uuid,3,
         'Vegetables','Vegetables',NULL,NULL,repeat('f',64));
 EXCEPTION WHEN invalid_parameter_value THEN denied:=true; END;
 IF NOT denied THEN
     RAISE EXCEPTION 'Noncanonical display-case category key accepted';
 END IF;

 -- Approved record cannot give staff write rights to purchases.
 IF (SELECT count(*) FROM public.registered_devices WHERE enabled AND can_write)<>1 OR
    (SELECT count(*) FROM public.registered_devices WHERE id=staff_dev AND can_write)<>0 THEN
    RAISE EXCEPTION 'Staff review accidentally granted writer permission';
 END IF;
 PERFORM set_config('request.jwt.claims','{"is_anonymous":true}',true);
 denied:=false;
 BEGIN
    PERFORM public.marketpro_staff_submit_catalog_candidate(
      w,staff_dev,'item',other_item,5,
      '30','Potato','Vegetables',true,repeat('b',64));
 EXCEPTION WHEN insufficient_privilege THEN denied:=true; END;
 IF NOT denied THEN RAISE EXCEPTION 'Anonymous candidate accepted'; END IF;
END;
$test$;

DO $privileges$
BEGIN
 IF has_table_privilege('authenticated',
    'marketpro_private.staff_catalog_review_candidates','SELECT') THEN
     RAISE EXCEPTION 'Private candidate table readable from authenticated REST';
 END IF;
 IF has_function_privilege('anon',
    'public.marketpro_staff_submit_catalog_candidate(uuid,uuid,text,uuid,bigint,text,text,text,boolean,text)',
    'EXECUTE') OR has_function_privilege('anon',
    'public.marketpro_owner_list_catalog_candidates(uuid,uuid,uuid,integer)',
    'EXECUTE') OR has_function_privilege('anon',
    'public.marketpro_owner_approve_submitted_catalog(uuid,uuid,uuid)',
    'EXECUTE') THEN
    RAISE EXCEPTION 'Anonymous candidate endpoint accessible';
 END IF;
END;
$privileges$;
\echo V364 CANDIDATE REVIEW AND SAFE APPROVAL TESTS PASS
