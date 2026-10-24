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

DO $smoke$
DECLARE
    w uuid := '11111111-1111-4111-8111-111111111111';
    owner_id text := '22222222-2222-4222-8222-222222222222';
    staff_id text := '33333333-3333-4333-8333-333333333333';
    viewer_id text := '44444444-4444-4444-8444-444444444444';
    own_phone uuid := 'a1111111-1111-4111-8111-111111111111';
    staff_phone uuid := 'b1111111-1111-4111-8111-111111111111';
    viewer_phone uuid := 'c1111111-1111-4111-8111-111111111111';
    disabled_phone uuid := 'd1111111-1111-4111-8111-111111111111';
    today_sl date := (clock_timestamp() AT TIME ZONE 'Asia/Colombo')::date;
    was_denied boolean;
    n integer;
BEGIN
    PERFORM set_config('request.jwt.claims','{"is_anonymous":false}',true);
    PERFORM set_config('request.jwt.claim.sub',staff_id,true);
    -- Same-day purchasing phone may correct UNLIMITED times without review.
    FOR n IN 1..20 LOOP
        IF marketpro_private.authorize_team_financial_write(
            w,staff_phone,today_sl,'correct',today_sl
        ) <> 'purchasing_staff' THEN
           RAISE EXCEPTION 'Staff today correction denied';
        END IF;
    END LOOP;
    IF marketpro_private.authorize_team_financial_write(
        w,staff_phone,today_sl,'create',null
    ) <> 'purchasing_staff' THEN
        RAISE EXCEPTION 'Staff today create denied';
    END IF;

    was_denied := false;
    BEGIN
        PERFORM marketpro_private.authorize_team_financial_write(
            w,staff_phone,today_sl-1,'correct',today_sl-1
        );
    EXCEPTION WHEN SQLSTATE '42501' THEN was_denied:=true;
    END;
    IF NOT was_denied THEN RAISE EXCEPTION 'Historic staff edit improperly allowed'; END IF;

    was_denied := false;
    BEGIN
        PERFORM marketpro_private.authorize_team_financial_write(
            w,staff_phone,today_sl-1,'create',null
        );
    EXCEPTION WHEN SQLSTATE '42501' THEN was_denied:=true;
    END;
    IF NOT was_denied THEN RAISE EXCEPTION 'Historic staff create improperly allowed'; END IF;

    was_denied := false;
    BEGIN
        PERFORM marketpro_private.authorize_team_financial_write(
            w,staff_phone,today_sl,'correct',today_sl-1
        );
    EXCEPTION WHEN SQLSTATE '42501' THEN was_denied:=true;
    END;
    IF NOT was_denied THEN RAISE EXCEPTION 'Backdated record moved into today'; END IF;

    was_denied := false;
    BEGIN
        PERFORM marketpro_private.authorize_team_financial_write(
            w,staff_phone,today_sl+1,'create',null
        );
    EXCEPTION WHEN SQLSTATE '42501' THEN was_denied:=true;
    END;
    IF NOT was_denied THEN RAISE EXCEPTION 'Future staff edit improperly allowed'; END IF;

    was_denied := false;
    BEGIN
        PERFORM marketpro_private.authorize_team_financial_write(
            w,disabled_phone,today_sl,'create',null
        );
    EXCEPTION WHEN SQLSTATE '42501' THEN was_denied:=true;
    END;
    IF NOT was_denied THEN RAISE EXCEPTION 'Disabled staff phone improperly allowed'; END IF;

    was_denied := false;
    BEGIN
        PERFORM marketpro_private.authorize_team_financial_write(
            w,own_phone,today_sl,'create',null
        );
    EXCEPTION WHEN SQLSTATE '42501' THEN was_denied:=true;
    END;
    IF NOT was_denied THEN RAISE EXCEPTION 'Staff impersonated owner device'; END IF;

    -- Owner can amend prior day without a daily approval queue.
    PERFORM set_config('request.jwt.claim.sub',owner_id,true);
    IF marketpro_private.authorize_team_financial_write(
        w,own_phone,today_sl-35,'correct',today_sl-35
    ) <> 'owner' THEN RAISE EXCEPTION 'Owner old-day correction denied'; END IF;

    was_denied := false;
    BEGIN
        PERFORM marketpro_private.authorize_team_financial_write(
            w,own_phone,today_sl+1,'create',null
        );
    EXCEPTION WHEN SQLSTATE '42501' THEN was_denied:=true;
    END;
    IF NOT was_denied THEN RAISE EXCEPTION 'Future Owner edit improperly allowed'; END IF;

    -- Owner can NOT use 'create' to overwrite a record.
    was_denied := false;
    BEGIN
        PERFORM marketpro_private.authorize_team_financial_write(
            w,own_phone,today_sl,'create',today_sl
        );
    EXCEPTION WHEN SQLSTATE '23505' THEN was_denied:=true;
    END;
    IF NOT was_denied THEN RAISE EXCEPTION 'Existing row overwrite via create'; END IF;

    -- Viewer cannot ever write, even if on registered device.
    PERFORM set_config('request.jwt.claim.sub',viewer_id,true);
    was_denied := false;
    BEGIN
        PERFORM marketpro_private.authorize_team_financial_write(
            w,viewer_phone,today_sl,'create',null
        );
    EXCEPTION WHEN SQLSTATE '42501' THEN was_denied:=true;
    END;
    IF NOT was_denied THEN RAISE EXCEPTION 'Viewer improperly allowed to write'; END IF;

    -- Anonymous JWT cannot bypass via registered Device ID.
    PERFORM set_config('request.jwt.claim.sub',owner_id,true);
    PERFORM set_config('request.jwt.claims','{"is_anonymous":true}',true);
    was_denied := false;
    BEGIN
        PERFORM marketpro_private.authorize_team_financial_write(
            w,own_phone,today_sl,'create',null
        );
    EXCEPTION WHEN SQLSTATE '42501' THEN was_denied:=true;
    END;
    IF NOT was_denied THEN RAISE EXCEPTION 'Anonymous user passed'; END IF;
END;
$smoke$;

DO $privileges$
BEGIN
    IF has_function_privilege('anon',
      'marketpro_private.authorize_team_financial_write(uuid,uuid,date,text,date)',
      'EXECUTE') THEN RAISE EXCEPTION 'Anon can execute private helper'; END IF;
    IF has_function_privilege('authenticated',
      'marketpro_private.authorize_team_financial_write(uuid,uuid,date,text,date)',
      'EXECUTE') THEN RAISE EXCEPTION 'Authenticated can execute private helper'; END IF;
END;
$privileges$;
\echo V352 PRIVATE TEAM WRITE GATE: all local PostgreSQL 17 assertions passed
