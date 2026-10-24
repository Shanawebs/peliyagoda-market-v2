-- Local PostgreSQL 17 ONLY; trusted Edge crypto signature verification
-- is separately exercised in Node P-256 tests. This suite simulates Edge
-- only AFTER a valid Android DER signature and JWT have been verified.
\set ON_ERROR_STOP on
\i supabase/tests/v375_staff_device_challenge_pg17_local_only.sql

-- The V375 disposable fixture intentionally revokes/signed-out Staff at end.
-- Restore ONLY for these subsequent isolated signed read regression tests.
INSERT INTO auth.sessions(id,user_id,not_after)
VALUES('91111111-1111-4111-8111-111111111111',
 '33333333-3333-4333-8333-333333333333',NULL);
\i supabase/proposals/two_phone_signed_staff_pull_REVIEW_ONLY.sql

INSERT INTO public.categories(id,workspace_id) VALUES (
 'a4444444-4444-4444-8444-444444444444',
 '11111111-1111-4111-8111-111111111111'
);
DO $signed_read$
DECLARE
 w uuid:='11111111-1111-4111-8111-111111111111';
 staff uuid:='33333333-3333-4333-8333-333333333333';
 owner uuid:='22222222-2222-4222-8222-222222222222';
 dev uuid:='f1111111-1111-4111-8111-111111111111';
 owner_phone uuid:='a1111111-1111-4111-8111-111111111111';
 sid uuid:='91111111-1111-4111-8111-111111111111';
 other_sid uuid:='92222222-2222-4222-8222-222222222222';
 first jsonb; second jsonb; page jsonb; v_owner_page jsonb;
 denied boolean;
 cursor_at timestamptz; cursor_id uuid;
BEGIN
 PERFORM pg_catalog.set_config('request.jwt.claim.sub',staff::text,true);
 PERFORM pg_catalog.set_config('request.jwt.claims',
  pg_catalog.jsonb_build_object('session_id',sid,'is_anonymous',false)::text,true);

 -- Staff bearer JWT alone must NOT read ANY Cloud rows using old RPC.
 denied:=false;
 BEGIN
   PERFORM public.marketpro_team_pull_page(w,dev,'category',NULL,NULL,1);
 EXCEPTION WHEN SQLSTATE '42501' THEN denied:=true; END;
 IF NOT denied THEN
    RAISE EXCEPTION 'Copied Staff JWT bypassed signature via direct pull';
 END IF;

 first:=public.marketpro_staff_issue_read_page_challenge(
  w,dev,'category',NULL,NULL,1);
 IF first->>'purpose'<>'read' OR
    first->>'session_id'<>sid::text OR
    first->>'key_fingerprint_sha256'<>repeat('a',64) THEN
    RAISE EXCEPTION 'Staff signed read challenge wrong context or fingerprint';
 END IF;

 -- Request substitution without owning P-256 key must never disclose data,
 -- nor should it consume the valid original nonce.
 denied:=false;
 BEGIN
  PERFORM public.marketpro_service_pull_signed_staff_page(
   (first->>'challenge_id')::uuid,staff,sid,w,dev,
   'item',NULL,NULL,1);
 EXCEPTION WHEN SQLSTATE '42501' THEN denied:=true; END;
 IF NOT denied THEN RAISE EXCEPTION 'Challenge reused for different type'; END IF;
 denied:=false;
 BEGIN
  PERFORM public.marketpro_service_pull_signed_staff_page(
   (first->>'challenge_id')::uuid,staff,sid,w,dev,
   'category',NULL,NULL,2);
 EXCEPTION WHEN SQLSTATE '42501' THEN denied:=true; END;
 IF NOT denied THEN RAISE EXCEPTION 'Challenge reused for different page size'; END IF;
 denied:=false;
 BEGIN
  PERFORM public.marketpro_service_pull_signed_staff_page(
   (first->>'challenge_id')::uuid,staff,other_sid,w,dev,
   'category',NULL,NULL,1);
 EXCEPTION WHEN SQLSTATE '42501' THEN denied:=true; END;
 IF NOT denied THEN RAISE EXCEPTION 'Different signed JWT session read page'; END IF;
 IF (SELECT consumed_at IS NOT NULL
     FROM marketpro_private.staff_device_challenges
     WHERE challenge_id=(first->>'challenge_id')::uuid) THEN
  RAISE EXCEPTION 'Tampered read request consumed legitimate nonce'; END IF;

 page:=public.marketpro_service_pull_signed_staff_page(
   (first->>'challenge_id')::uuid,staff,sid,w,dev,
   'category',NULL,NULL,1);
 IF page->>'entity_type'<>'category' OR
    page->>'has_more'<>'true' OR
    jsonb_array_length(page->'rows')<>1 OR
    page->>'server_today_colombo' IS NULL THEN
    RAISE EXCEPTION 'Staff signed read page incorrect result format';
 END IF;
 denied:=false;
 BEGIN
  PERFORM public.marketpro_service_pull_signed_staff_page(
   (first->>'challenge_id')::uuid,staff,sid,w,dev,
   'category',NULL,NULL,1);
 EXCEPTION WHEN SQLSTATE '42501' THEN denied:=true; END;
 IF NOT denied THEN RAISE EXCEPTION 'Signed Staff read nonce replay'; END IF;

 cursor_at:=(page->>'next_updated_at')::timestamptz;
 cursor_id:=(page->>'next_id')::uuid;
 second:=public.marketpro_staff_issue_read_page_challenge(
   w,dev,'category',cursor_at,cursor_id,1);
 denied:=false;
 BEGIN
  PERFORM public.marketpro_service_pull_signed_staff_page(
   (second->>'challenge_id')::uuid,staff,sid,w,dev,
   'category',NULL,NULL,1);
 EXCEPTION WHEN SQLSTATE '42501' THEN denied:=true; END;
 IF NOT denied THEN RAISE EXCEPTION 'Signed cursor substituted for old page'; END IF;
 page:=public.marketpro_service_pull_signed_staff_page(
    (second->>'challenge_id')::uuid,staff,sid,w,dev,
    'category',cursor_at,cursor_id,1);
 IF jsonb_array_length(page->'rows')<>1 OR page->>'has_more'<>'false'
 THEN RAISE EXCEPTION 'Signed page cursor pagination incorrect'; END IF;

 -- Owner's existing direct authenticated pull must keep working.
 PERFORM pg_catalog.set_config('request.jwt.claim.sub',owner::text,true);
 PERFORM pg_catalog.set_config('request.jwt.claims','{"is_anonymous":false}',true);
 v_owner_page:=public.marketpro_team_pull_page(
   w,owner_phone,'category',NULL,NULL,2);
 IF jsonb_array_length(v_owner_page->'rows')<>2 THEN
    RAISE EXCEPTION 'Existing Owner direct Cloud read regressed'; END IF;

 -- Revocation occurs before signature redemption: DB denies read even if
 -- an attacker had captured a JWT+signature+challenge earlier.
 PERFORM pg_catalog.set_config('request.jwt.claim.sub',staff::text,true);
 PERFORM pg_catalog.set_config('request.jwt.claims',
  pg_catalog.jsonb_build_object('session_id',sid,'is_anonymous',false)::text,true);
 first:=public.marketpro_staff_issue_read_page_challenge(
   w,dev,'category',NULL,NULL,1);
 UPDATE public.registered_devices SET enabled=false WHERE id=dev;
 denied:=false;
 BEGIN
  PERFORM public.marketpro_service_pull_signed_staff_page(
    (first->>'challenge_id')::uuid,staff,sid,w,dev,
    'category',NULL,NULL,1);
 EXCEPTION WHEN SQLSTATE '42501' THEN denied:=true; END;
 IF NOT denied THEN RAISE EXCEPTION 'Revoked Staff phone retained signed read'; END IF;
 IF (SELECT COUNT(*) FROM public.registered_devices
      WHERE enabled AND can_write)<>1 THEN
    RAISE EXCEPTION 'Original Owner active writer was disabled'; END IF;
END;
$signed_read$;

DO $access$
BEGIN
 IF has_function_privilege('authenticated',
    'public.marketpro_service_pull_signed_staff_page(uuid,uuid,uuid,uuid,uuid,text,timestamptz,uuid,integer)','EXECUTE')
 OR has_function_privilege('anon',
    'public.marketpro_service_pull_signed_staff_page(uuid,uuid,uuid,uuid,uuid,text,timestamptz,uuid,integer)','EXECUTE')
 OR has_table_privilege('authenticated',
    'marketpro_private.staff_read_page_intents','SELECT')
 OR NOT has_function_privilege('service_role',
    'public.marketpro_service_pull_signed_staff_page(uuid,uuid,uuid,uuid,uuid,text,timestamptz,uuid,integer)','EXECUTE')
 OR NOT has_function_privilege('authenticated',
    'public.marketpro_team_pull_page(uuid,uuid,text,timestamptz,uuid,integer)','EXECUTE')
 THEN RAISE EXCEPTION 'Staff signed read role boundary invalid'; END IF;
END;
$access$;
\echo Signed Staff read: one-use P-256 nonce, page cursor commitment, direct JWT bypass blocked; Owner retained
