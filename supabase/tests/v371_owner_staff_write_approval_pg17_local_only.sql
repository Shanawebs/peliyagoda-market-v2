-- V3.7.1 DISPOSABLE POSTGRES 17 ONLY.
-- Reuse the full V3.7.0 purchase, requisition, addition, cash and
-- original single-Owner-index tests before adding the new Owner RPCs.
\set ON_ERROR_STOP on
\i supabase/tests/v370_dual_channel_staff_writer_pg17_local_only.sql

-- Simulate same Auth tables/roles as production. Never use real credentials.
CREATE TABLE auth.users(
    id uuid PRIMARY KEY,email text,email_confirmed_at timestamptz
);
INSERT INTO auth.users(id,email,email_confirmed_at) VALUES
('22222222-2222-4222-8222-222222222222','owner@example.invalid',now()),
('33333333-3333-4333-8333-333333333333','staff@example.invalid',now()),
('44444444-4444-4444-8444-444444444444','viewer@example.invalid',now());
\i supabase/proposals/v358_owner_invite_staff_PENDING_ONLY.sql
ALTER TABLE marketpro_private.pending_team_enrollments
    ADD COLUMN activated_at timestamptz;
CREATE TABLE marketpro_private.team_rollout_gate(
    workspace_id uuid PRIMARY KEY,
    staff_direct_rest_restricted boolean NOT NULL DEFAULT false,
    team_pull_rpc_reviewed boolean NOT NULL DEFAULT false,
    team_writes_reviewed boolean NOT NULL DEFAULT false,
    updated_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
REVOKE ALL ON marketpro_private.team_rollout_gate
  FROM PUBLIC,anon,authenticated;
INSERT INTO marketpro_private.team_rollout_gate(workspace_id)
VALUES('11111111-1111-4111-8111-111111111111');
-- The V370 fixture ends with a Staff lease. Make it OFF by default;
-- a Staff user must never get write rights from enrollment alone.
UPDATE marketpro_private.staff_write_leases SET enabled=false,
  revoked_at=clock_timestamp();
\i supabase/proposals/v371_owner_staff_write_approval_REVIEW_ONLY.sql

DO $negative$
DECLARE
  w uuid:='11111111-1111-4111-8111-111111111111';
  own uuid:='a1111111-1111-4111-8111-111111111111';
  staff uuid:='33333333-3333-4333-8333-333333333333';
  staff_phone uuid:='b1111111-1111-4111-8111-111111111111';
  denied boolean;
BEGIN
  PERFORM set_config('request.jwt.claims','{"is_anonymous":false}',true);
  PERFORM set_config('request.jwt.claim.sub','22222222-2222-4222-8222-222222222222',true);
  denied:=false;
  BEGIN PERFORM public.marketpro_owner_enable_staff_writer(w,own,staff,staff_phone);
  EXCEPTION WHEN SQLSTATE '42501' THEN denied:=true; END;
  IF NOT denied THEN RAISE EXCEPTION 'Gate OFF allowed Staff writer activation'; END IF;

  -- Even an Owner cannot grant to a phone before invitation is fully
  -- accepted and independently read-only activated on the server.
  UPDATE marketpro_private.team_rollout_gate SET
    staff_direct_rest_restricted=true,team_pull_rpc_reviewed=true,
    team_writes_reviewed=true WHERE workspace_id=w;
  denied:=false;
  BEGIN PERFORM public.marketpro_owner_enable_staff_writer(w,own,staff,staff_phone);
  EXCEPTION WHEN SQLSTATE '42501' THEN denied:=true; END;
  IF NOT denied THEN RAISE EXCEPTION 'No invite allowed Staff writer activation'; END IF;
END;
$negative$;

INSERT INTO marketpro_private.pending_team_enrollments(
  workspace_id,staff_user_id,inviter_user_id,intended_device_id,
  device_label,token_sha256,expires_at,status,created_at,accepted_at,
  revoked_at,activated_at)
VALUES (
 '11111111-1111-4111-8111-111111111111',
 '33333333-3333-4333-8333-333333333333',
 '22222222-2222-4222-8222-222222222222',
 'b1111111-1111-4111-8111-111111111111','Test Staff Phone',
 extensions.digest(pg_catalog.convert_to('example-code','UTF8'),'sha256'),
 clock_timestamp()+interval '30 minutes','accepted',clock_timestamp(),
 clock_timestamp(),NULL,NULL
);

DO $activation$
DECLARE
  w uuid:='11111111-1111-4111-8111-111111111111';
  own uuid:='a1111111-1111-4111-8111-111111111111';
  staff uuid:='33333333-3333-4333-8333-333333333333';
  staff_phone uuid:='b1111111-1111-4111-8111-111111111111';
  r jsonb;
  denied boolean;
BEGIN
  PERFORM set_config('request.jwt.claims','{"is_anonymous":false}',true);
  PERFORM set_config('request.jwt.claim.sub','22222222-2222-4222-8222-222222222222',true);
  denied:=false;
  BEGIN PERFORM public.marketpro_owner_enable_staff_writer(w,own,staff,staff_phone);
  EXCEPTION WHEN SQLSTATE '42501' THEN denied:=true; END;
  IF NOT denied THEN RAISE EXCEPTION 'Accepted but NOT activated phone allowed'; END IF;

  UPDATE marketpro_private.pending_team_enrollments
    SET activated_at=clock_timestamp()
    WHERE workspace_id=w AND staff_user_id=staff;
  PERFORM set_config('request.jwt.claim.sub',staff::text,true);
  denied:=false;
  BEGIN PERFORM public.marketpro_owner_enable_staff_writer(w,own,staff,staff_phone);
  EXCEPTION WHEN SQLSTATE '42501' THEN denied:=true; END;
  IF NOT denied THEN RAISE EXCEPTION 'Staff activated own write permission'; END IF;
  PERFORM set_config('request.jwt.claim.sub','44444444-4444-4444-8444-444444444444',true);
  denied:=false;
  BEGIN PERFORM public.marketpro_owner_enable_staff_writer(w,own,staff,staff_phone);
  EXCEPTION WHEN SQLSTATE '42501' THEN denied:=true; END;
  IF NOT denied THEN RAISE EXCEPTION 'Viewer activated Staff write permission'; END IF;

  PERFORM set_config('request.jwt.claim.sub','22222222-2222-4222-8222-222222222222',true);
  denied:=false;
  BEGIN PERFORM public.marketpro_owner_enable_staff_writer(
    w,'c1111111-1111-4111-8111-111111111111',staff,staff_phone);
  EXCEPTION WHEN SQLSTATE '42501' THEN denied:=true; END;
  IF NOT denied THEN RAISE EXCEPTION 'Owner with unregistered phone activated staff'; END IF;
  r:=public.marketpro_owner_enable_staff_writer(w,own,staff,staff_phone);
  IF NOT (r->>'writer_enabled')::boolean OR (r->>'already_enabled')::boolean
  THEN RAISE EXCEPTION 'Verified Owner grant failed'; END IF;
  r:=public.marketpro_owner_enable_staff_writer(w,own,staff,staff_phone);
  IF NOT (r->>'already_enabled')::boolean
  THEN RAISE EXCEPTION 'Owner repeat grant was not idempotent'; END IF;
  IF (SELECT count(*) FROM marketpro_private.staff_writer_activation_audit)<>1
  THEN RAISE EXCEPTION 'Duplicate Owner grant audit'; END IF;

  PERFORM set_config('request.jwt.claim.sub',staff::text,true);
  IF marketpro_private.require_dual_channel_writer(w,staff_phone)<>'purchasing_staff'
  THEN RAISE EXCEPTION 'Verified Owner grant did not enable v2 Staff writer'; END IF;
  IF (SELECT can_write FROM public.registered_devices WHERE id=staff_phone)
  THEN RAISE EXCEPTION 'Staff became a legacy V1 writer'; END IF;
  IF (SELECT count(*) FROM public.registered_devices
      WHERE enabled AND can_write)<>1
  THEN RAISE EXCEPTION 'Legacy single Owner writer index changed'; END IF;

  -- Admin emergency gate OFF must deny active Staff lease each time.
  UPDATE marketpro_private.team_rollout_gate
  SET team_writes_reviewed=false WHERE workspace_id=w;
  denied:=false;
  BEGIN PERFORM marketpro_private.require_dual_channel_writer(w,staff_phone);
  EXCEPTION WHEN SQLSTATE '42501' THEN denied:=true; END;
  IF NOT denied THEN RAISE EXCEPTION 'Gate OFF failed to halt active Staff writer'; END IF;
  PERFORM set_config('request.jwt.claim.sub','22222222-2222-4222-8222-222222222222',true);
  IF marketpro_private.require_dual_channel_writer(w,own)<>'owner'
  THEN RAISE EXCEPTION 'Emergency gate disabled Owner writer'; END IF;
  UPDATE marketpro_private.team_rollout_gate
  SET team_writes_reviewed=true WHERE workspace_id=w;

  -- Owner can stop writing independently of Staff read-only membership.
  r:=public.marketpro_owner_disable_staff_writer(w,own,staff,staff_phone);
  IF NOT (r->>'changed')::boolean THEN RAISE EXCEPTION 'Owner revoke failed'; END IF;
  r:=public.marketpro_owner_disable_staff_writer(w,own,staff,staff_phone);
  IF (r->>'changed')::boolean THEN RAISE EXCEPTION 'Owner revoke not idempotent'; END IF;
  PERFORM set_config('request.jwt.claim.sub',staff::text,true);
  denied:=false;
  BEGIN PERFORM marketpro_private.require_dual_channel_writer(w,staff_phone);
  EXCEPTION WHEN SQLSTATE '42501' THEN denied:=true; END;
  IF NOT denied THEN RAISE EXCEPTION 'Revoked Staff still writes'; END IF;
  PERFORM set_config('request.jwt.claim.sub','22222222-2222-4222-8222-222222222222',true);
  r:=public.marketpro_owner_enable_staff_writer(w,own,staff,staff_phone);
  IF NOT (r->>'writer_enabled')::boolean
  THEN RAISE EXCEPTION 'Owner could not resume verified existing staff'; END IF;
END;
$activation$;

-- Simulate approved phone replacement (v368 separately verifies invitation
-- and records transfer). Reuse the same Staff login with a NEW device ID.
UPDATE public.registered_devices SET enabled=false
WHERE id='b1111111-1111-4111-8111-111111111111';
INSERT INTO public.registered_devices(
  id,workspace_id,user_id,enabled,can_write)
VALUES (
 'f1111111-1111-4111-8111-111111111111',
 '11111111-1111-4111-8111-111111111111',
 '33333333-3333-4333-8333-333333333333',true,false
);
UPDATE marketpro_private.pending_team_enrollments
SET intended_device_id='f1111111-1111-4111-8111-111111111111',
    device_label='Replacement Team Phone',status='accepted',
    accepted_at=clock_timestamp(),activated_at=clock_timestamp(),
    expires_at=clock_timestamp()+interval '30 minutes',
    token_sha256=extensions.digest(pg_catalog.convert_to(
      'replacement-code','UTF8'),'sha256')
WHERE staff_user_id='33333333-3333-4333-8333-333333333333';

DO $replace$
DECLARE
  w uuid:='11111111-1111-4111-8111-111111111111';
  own uuid:='a1111111-1111-4111-8111-111111111111';
  staff uuid:='33333333-3333-4333-8333-333333333333';
  old_phone uuid:='b1111111-1111-4111-8111-111111111111';
  new_phone uuid:='f1111111-1111-4111-8111-111111111111';
  denied boolean;
  r jsonb;
BEGIN
 PERFORM set_config('request.jwt.claims','{"is_anonymous":false}',true);
 PERFORM set_config('request.jwt.claim.sub','22222222-2222-4222-8222-222222222222',true);
 r:=public.marketpro_owner_enable_staff_writer(w,own,staff,new_phone);
 IF NOT (r->>'writer_enabled')::boolean THEN
   RAISE EXCEPTION 'Owner could not authorize replacement phone'; END IF;
 PERFORM set_config('request.jwt.claim.sub',staff::text,true);
 denied:=false;
 BEGIN PERFORM marketpro_private.require_dual_channel_writer(w,old_phone);
 EXCEPTION WHEN SQLSTATE '42501' THEN denied:=true; END;
 IF NOT denied THEN RAISE EXCEPTION 'Retired Staff phone still writes'; END IF;
 IF marketpro_private.require_dual_channel_writer(w,new_phone)<>'purchasing_staff'
 THEN RAISE EXCEPTION 'Owner approved replacement cannot write'; END IF;
 PERFORM set_config('request.jwt.claim.sub','22222222-2222-4222-8222-222222222222',true);
 denied:=false;
 BEGIN PERFORM public.marketpro_owner_enable_staff_writer(w,own,staff,old_phone);
 EXCEPTION WHEN SQLSTATE '42501' THEN denied:=true; END;
 IF NOT denied THEN RAISE EXCEPTION 'Retired Staff phone was reauthorized'; END IF;
 IF (SELECT count(*) FROM public.registered_devices
     WHERE enabled AND can_write)<>1 THEN
   RAISE EXCEPTION 'Lost legacy single Owner writer'; END IF;
 IF (SELECT count(*) FROM marketpro_private.staff_writer_activation_audit
     WHERE action='grant')<>3 THEN
   RAISE EXCEPTION 'Owner grant/regrant/transfer audit incomplete'; END IF;
END;
$replace$;

DO $grants$
BEGIN
 IF has_function_privilege('anon',
    'public.marketpro_owner_enable_staff_writer(uuid,uuid,uuid,uuid)','EXECUTE')
 OR has_function_privilege('anon',
    'public.marketpro_owner_disable_staff_writer(uuid,uuid,uuid,uuid)','EXECUTE')
 OR has_function_privilege('authenticated',
    'marketpro_private.require_dual_channel_writer(uuid,uuid)','EXECUTE')
 OR has_table_privilege('authenticated',
    'marketpro_private.staff_writer_activation_audit','SELECT')
 OR has_table_privilege('authenticated',
    'marketpro_private.staff_write_leases','UPDATE')
 THEN RAISE EXCEPTION 'Staff ACLs exposed privileged capability'; END IF;
END;
$grants$;
\echo V371 Owner-controlled Staff writer grants, emergency gate and replacement passed
