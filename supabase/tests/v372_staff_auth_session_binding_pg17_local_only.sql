-- V372 disposable PostgreSQL 17 integration. No Supabase production traffic.
\set ON_ERROR_STOP on
\i supabase/tests/v371_owner_staff_write_approval_pg17_local_only.sql

-- Mock only the minimum columns of real live auth.sessions (validated via
-- read-only schema introspection). Signed JWT claims are simulated with GUC.
CREATE TABLE auth.sessions(
 id uuid PRIMARY KEY,
 user_id uuid NOT NULL REFERENCES auth.users(id),
 not_after timestamptz
);
INSERT INTO auth.sessions(id,user_id,not_after) VALUES
 ('91111111-1111-4111-8111-111111111111',
  '33333333-3333-4333-8333-333333333333',NULL),
 ('92222222-2222-4222-8222-222222222222',
  '33333333-3333-4333-8333-333333333333',NULL),
 ('93333333-3333-4333-8333-333333333333',
  '22222222-2222-4222-8222-222222222222',NULL);
-- The V371 fixture replaces Staff phone b... with f... and grants f... .
-- Session binding is not present. Hardened V372 must FAIL CLOSED.
CREATE TABLE public.categories(
 id uuid PRIMARY KEY,workspace_id uuid NOT NULL,
 updated_at timestamptz NOT NULL DEFAULT now(), deleted_at timestamptz
);
CREATE TABLE public.items(
 id uuid PRIMARY KEY,workspace_id uuid NOT NULL,
 updated_at timestamptz NOT NULL DEFAULT now(), deleted_at timestamptz
);
INSERT INTO public.categories(id,workspace_id) VALUES(
 'a3333333-3333-4333-8333-333333333333',
 '11111111-1111-4111-8111-111111111111'
);
\i supabase/proposals/v354_team_paged_pull_REVIEW_ONLY.sql
\i supabase/proposals/v372_staff_auth_session_binding_REVIEW_ONLY.sql

-- Previous V371 approved Staff lease is intentionally left enabled.
-- Without the new paired session it MUST be denied by both write and pull.
DO $no_session$
DECLARE
 w uuid:='11111111-1111-4111-8111-111111111111';
 ph uuid:='f1111111-1111-4111-8111-111111111111';
 denied boolean;
BEGIN
 PERFORM set_config('request.jwt.claim.sub','33333333-3333-4333-8333-333333333333',true);
 PERFORM set_config('request.jwt.claims','{"is_anonymous":false}',true);
 denied:=false;
 BEGIN PERFORM marketpro_private.require_dual_channel_writer(w,ph);
 EXCEPTION WHEN SQLSTATE '42501' THEN denied:=true; END;
 IF NOT denied THEN RAISE EXCEPTION 'Unpaired Staff JWT wrote'; END IF;
 denied:=false;
 BEGIN PERFORM public.marketpro_team_pull_page(w,ph,'category',NULL,NULL,50);
 EXCEPTION WHEN SQLSTATE '42501' THEN denied:=true; END;
 IF NOT denied THEN RAISE EXCEPTION 'Unpaired Staff JWT downloaded'; END IF;
 IF marketpro_private.is_verified_staff_session(w,ph)
 THEN RAISE EXCEPTION 'Unpaired session marked as paired'; END IF;
END;
$no_session$;

-- Mock Owner has issued a fresh 64 hex one-time code for the REPLACEMENT
-- phone. Reset the invitation to invited, with no membership changes.
UPDATE marketpro_private.pending_team_enrollments SET
 status='invited',accepted_at=NULL,activated_at=NULL,revoked_at=NULL,
 expires_at=clock_timestamp()+interval '30 minutes',
 token_sha256=extensions.digest(
   pg_catalog.convert_to(repeat('a',64),'UTF8'),'sha256')
WHERE staff_user_id='33333333-3333-4333-8333-333333333333';
UPDATE marketpro_private.staff_write_leases
 SET enabled=false,revoked_at=clock_timestamp();

DO $claim$
DECLARE
 w uuid:='11111111-1111-4111-8111-111111111111';
 old_ph uuid:='b1111111-1111-4111-8111-111111111111';
 ph uuid:='f1111111-1111-4111-8111-111111111111';
 owner_ph uuid:='a1111111-1111-4111-8111-111111111111';
 staff uuid:='33333333-3333-4333-8333-333333333333';
 session_good uuid:='91111111-1111-4111-8111-111111111111';
 denied boolean;
 r jsonb;
BEGIN
 PERFORM set_config('request.jwt.claim.sub',staff::text,true);
 PERFORM set_config('request.jwt.claims',pg_catalog.jsonb_build_object(
   'is_anonymous',false,'session_id',session_good)::text,true);
 denied:=false;
 BEGIN PERFORM public.marketpro_staff_accept_invitation(w,ph,repeat('b',64));
 EXCEPTION WHEN SQLSTATE '42501' THEN denied:=true; END;
 IF NOT denied THEN RAISE EXCEPTION 'Bad Owner pairing code accepted'; END IF;
 denied:=false;
 BEGIN PERFORM public.marketpro_staff_accept_invitation(w,old_ph,repeat('a',64));
 EXCEPTION WHEN SQLSTATE '42501' THEN denied:=true; END;
 IF NOT denied THEN RAISE EXCEPTION 'Old Staff device accepted new code'; END IF;
 r:=public.marketpro_staff_accept_invitation(w,ph,repeat('a',64));
 IF NOT (r->>'invitation_accepted')::boolean THEN
   RAISE EXCEPTION 'Owner one-time pairing failed'; END IF;
 IF (SELECT session_id FROM marketpro_private.staff_session_bindings
    WHERE workspace_id=w)<>session_good
 THEN RAISE EXCEPTION 'Signed session was not bound by code'; END IF;
 denied:=false;
 BEGIN PERFORM public.marketpro_staff_accept_invitation(w,ph,repeat('a',64));
 EXCEPTION WHEN SQLSTATE '42501' THEN denied:=true; END;
 IF NOT denied THEN RAISE EXCEPTION 'Owner pairing code was reused'; END IF;

 -- Staff has accepted but Owner still has not activated READ-ONLY device.
 IF marketpro_private.is_verified_staff_session(w,ph)
 THEN RAISE EXCEPTION 'Accepted but not activated Staff could read'; END IF;

 PERFORM set_config('request.jwt.claim.sub',
 '22222222-2222-4222-8222-222222222222',true);
 PERFORM set_config('request.jwt.claims','{"is_anonymous":false}',true);
 denied:=false;
 BEGIN PERFORM public.marketpro_owner_enable_staff_writer(w,owner_ph,staff,ph);
 EXCEPTION WHEN SQLSTATE '42501' THEN denied:=true; END;
 IF NOT denied THEN RAISE EXCEPTION 'Owner granted before activation'; END IF;

 UPDATE marketpro_private.pending_team_enrollments
 SET activated_at=clock_timestamp()
 WHERE workspace_id=w AND staff_user_id=staff;
 r:=public.marketpro_owner_enable_staff_writer(w,owner_ph,staff,ph);
 IF NOT (r->>'writer_enabled')::boolean THEN
 RAISE EXCEPTION 'Owner approved paired active Staff phone failed'; END IF;

 PERFORM set_config('request.jwt.claim.sub',staff::text,true);
 PERFORM set_config('request.jwt.claims',pg_catalog.jsonb_build_object(
   'is_anonymous',false,'session_id',session_good)::text,true);
 IF marketpro_private.require_dual_channel_writer(w,ph)<>'purchasing_staff'
 THEN RAISE EXCEPTION 'Correct paired Staff session cannot write'; END IF;
 r:=public.marketpro_team_pull_page(w,ph,'category',NULL,NULL,50);
 IF pg_catalog.jsonb_array_length(r->'rows')<>1
 THEN RAISE EXCEPTION 'Correct Staff session cannot pull'; END IF;
 IF (SELECT count(*) FROM public.registered_devices
   WHERE enabled AND can_write)<>1
 THEN RAISE EXCEPTION 'Legacy Owner writer index changed'; END IF;
END;
$claim$;

DO $other_login$
DECLARE
 w uuid:='11111111-1111-4111-8111-111111111111';
 ph uuid:='f1111111-1111-4111-8111-111111111111';
 denied boolean;
BEGIN
 -- Same verified account, different Auth session on ANOTHER PHONE, even
 -- supplied with a copied device_id, cannot write or read.
 PERFORM set_config('request.jwt.claim.sub',
 '33333333-3333-4333-8333-333333333333',true);
 PERFORM set_config('request.jwt.claims','{"is_anonymous":false, "session_id":"92222222-2222-4222-8222-222222222222"}',true);
 denied:=false;
 BEGIN PERFORM marketpro_private.require_dual_channel_writer(w,ph);
 EXCEPTION WHEN SQLSTATE '42501' THEN denied:=true; END;
 IF NOT denied THEN RAISE EXCEPTION 'Secondary Staff session could write'; END IF;
 denied:=false;
 BEGIN PERFORM public.marketpro_team_pull_page(w,ph,'category',NULL,NULL,50);
 EXCEPTION WHEN SQLSTATE '42501' THEN denied:=true; END;
 IF NOT denied THEN RAISE EXCEPTION 'Secondary Staff session could read'; END IF;
 -- Missing or forged JWT session id fail closed.
 PERFORM set_config('request.jwt.claims','{"is_anonymous":false, "session_id":"untrusted"}',true);
 IF marketpro_private.is_verified_staff_session(w,ph)
 THEN RAISE EXCEPTION 'Malformed session ID allowed'; END IF;
 PERFORM set_config('request.jwt.claims','{"is_anonymous":false}',true);
 IF marketpro_private.is_verified_staff_session(w,ph)
 THEN RAISE EXCEPTION 'Missing session claim allowed'; END IF;
END;
$other_login$;

DO $logout$
DECLARE
 w uuid:='11111111-1111-4111-8111-111111111111';
 ph uuid:='f1111111-1111-4111-8111-111111111111';
 own uuid:='a1111111-1111-4111-8111-111111111111';
 denied boolean;
BEGIN
 PERFORM set_config('request.jwt.claim.sub',
 '33333333-3333-4333-8333-333333333333',true);
 PERFORM set_config('request.jwt.claims','{"is_anonymous":false,"session_id":"91111111-1111-4111-8111-111111111111"}',true);
 UPDATE auth.sessions SET not_after=clock_timestamp()-interval '1 second'
 WHERE id='91111111-1111-4111-8111-111111111111';
 denied:=false;
 BEGIN PERFORM marketpro_private.require_dual_channel_writer(w,ph);
 EXCEPTION WHEN SQLSTATE '42501' THEN denied:=true; END;
 IF NOT denied THEN RAISE EXCEPTION 'Expired paired session still writes'; END IF;
 UPDATE auth.sessions SET not_after=NULL
 WHERE id='91111111-1111-4111-8111-111111111111';
 DELETE FROM auth.sessions
 WHERE id='91111111-1111-4111-8111-111111111111';
 denied:=false;
 BEGIN PERFORM public.marketpro_team_pull_page(w,ph,'category',NULL,NULL,50);
 EXCEPTION WHEN SQLSTATE '42501' THEN denied:=true; END;
 IF NOT denied THEN RAISE EXCEPTION 'Signed-out but valid JWT still downloads'; END IF;
 denied:=false;
 BEGIN PERFORM marketpro_private.require_dual_channel_writer(w,ph);
 EXCEPTION WHEN SQLSTATE '42501' THEN denied:=true; END;
 IF NOT denied THEN RAISE EXCEPTION 'Signed-out but valid JWT still writes'; END IF;
 PERFORM set_config('request.jwt.claim.sub',
 '22222222-2222-4222-8222-222222222222',true);
 PERFORM set_config('request.jwt.claims','{"is_anonymous":false}',true);
 IF marketpro_private.require_dual_channel_writer(w,own)<>'owner'
 THEN RAISE EXCEPTION 'Hardened Staff sessions broke legacy Owner'; END IF;
 -- Reviewer/Viewer existing read-only path unchanged by Staff-only gate.
 PERFORM set_config('request.jwt.claim.sub',
 '44444444-4444-4444-8444-444444444444',true);
 IF pg_catalog.jsonb_array_length(
   public.marketpro_team_pull_page(w,
     'c1111111-1111-4111-8111-111111111111','category',NULL,NULL,50)->'rows')<>1
 THEN RAISE EXCEPTION 'Legacy Viewer read was wrongly disabled'; END IF;
END;
$logout$;

DO $acl$
BEGIN
 IF has_function_privilege('anon',
   'public.marketpro_staff_accept_invitation(uuid,uuid,text)','EXECUTE')
 OR has_table_privilege('authenticated',
   'marketpro_private.staff_session_bindings','SELECT')
 OR has_table_privilege('authenticated',
   'marketpro_private.staff_session_bindings','UPDATE')
 OR has_function_privilege('authenticated',
   'marketpro_private.is_verified_staff_session(uuid,uuid)','EXECUTE')
 THEN RAISE EXCEPTION 'Paired session data or helper exposed'; END IF;
END;
$acl$;
\echo V372 session-bound pairing, one-time code and revoked JWT tests passed
