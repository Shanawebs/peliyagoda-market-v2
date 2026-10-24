-- MARKET PRO V3.5.3 — purchase-only team RPC proof of concept
-- REVIEW ONLY. NOT APPLIED TO LIVE SUPABASE. Works only after V3.5.2 draft
-- helper/role check is installed in a disposable PostgreSQL test database.
-- Preserves public.marketpro_apply_change (single-owner V3.3.7 live API).
BEGIN;

CREATE SCHEMA IF NOT EXISTS marketpro_private;

CREATE TABLE IF NOT EXISTS marketpro_private.team_purchase_audit (
    operation_id uuid PRIMARY KEY,
    workspace_id uuid NOT NULL,
    device_id uuid NOT NULL,
    actor_user_id uuid NOT NULL,
    action text NOT NULL CHECK (action IN ('create','correct')),
    purchase_id uuid NOT NULL,
    before_row jsonb,
    after_row jsonb NOT NULL,
    resulting_revision bigint NOT NULL CHECK (resulting_revision > 0),
    changed_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
REVOKE ALL ON marketpro_private.team_purchase_audit FROM PUBLIC, anon, authenticated;

CREATE OR REPLACE FUNCTION public.marketpro_apply_team_purchase_change(
    p_workspace_id uuid,
    p_device_id uuid,
    p_operation_id uuid,
    p_purchase_id uuid,
    p_action text,
    p_expected_revision bigint,
    p_payload jsonb
) RETURNS jsonb
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = ''
AS $team_purchase$
DECLARE
    v_user uuid := auth.uid();
    v_existing public.purchases%ROWTYPE;
    v_exists boolean := false;
    v_previous public.sync_operations%ROWTYPE;
    v_hash text;
    v_role text;
    v_date date;
    v_item uuid;
    v_grams bigint;
    v_cents bigint;
    v_before jsonb;
    v_after jsonb;
    v_new_revision bigint;
    v_key text;
BEGIN
    -- Validate identity and ENVELOPE before taking a workspace lock.
    IF v_user IS NULL OR
       coalesce((auth.jwt()->>'is_anonymous')::boolean,false) THEN
        RAISE EXCEPTION 'Verified authenticated account required'
          USING ERRCODE = '42501';
    END IF;
    IF p_workspace_id IS NULL OR p_device_id IS NULL OR
       p_operation_id IS NULL OR p_purchase_id IS NULL OR
       p_action NOT IN ('create','correct') OR p_action IS NULL OR
       p_payload IS NULL OR jsonb_typeof(p_payload) <> 'object' OR
       octet_length(p_payload::text) > 2048 THEN
        RAISE EXCEPTION 'Invalid purchase operation' USING ERRCODE = '22023';
    END IF;
    IF NOT (
       p_payload ? 'business_date' AND p_payload ? 'item_id' AND
       p_payload ? 'quantity_grams' AND p_payload ? 'price_cents_per_kg'
    ) OR jsonb_object_length(p_payload) <> 4 THEN
        RAISE EXCEPTION 'Purchase fields must be an exact allowlist'
          USING ERRCODE = '22023';
    END IF;
    FOR v_key IN SELECT jsonb_object_keys(p_payload) LOOP
        IF v_key NOT IN ('business_date','item_id','quantity_grams','price_cents_per_kg') THEN
            RAISE EXCEPTION 'Unknown purchase field' USING ERRCODE = '22023';
        END IF;
    END LOOP;
    v_date := (p_payload->>'business_date')::date;
    v_item := (p_payload->>'item_id')::uuid;
    v_grams := (p_payload->>'quantity_grams')::bigint;
    v_cents := (p_payload->>'price_cents_per_kg')::bigint;
    IF v_date IS NULL OR v_item IS NULL OR v_grams IS NULL OR v_cents IS NULL OR
       v_grams < 0 OR v_grams > 1000000000 OR
       v_cents < 0 OR v_cents > 1000000000 OR
       (p_action = 'create' AND p_expected_revision IS NOT NULL) OR
       (p_action = 'correct' AND
          (p_expected_revision IS NULL OR p_expected_revision < 1)) THEN
        RAISE EXCEPTION 'Invalid date, values or expected revision'
          USING ERRCODE = '22023';
    END IF;

    -- Avoid an operation-id race, and serialize competing owner/staff corrections.
    PERFORM pg_catalog.pg_advisory_xact_lock(
        pg_catalog.hashtextextended(p_workspace_id::text,0)
    );

    -- Check live membership/device BEFORE acknowledging any old operation.
    IF NOT EXISTS (
        SELECT 1 FROM public.workspace_members AS m
        JOIN public.registered_devices AS d
          ON d.workspace_id = m.workspace_id AND d.user_id = m.user_id
        WHERE m.workspace_id = p_workspace_id AND
          m.user_id = v_user AND d.id = p_device_id AND
          d.enabled AND d.can_write AND m.role IN ('owner','purchasing_staff')
    ) THEN
        RAISE EXCEPTION 'Team device not authorised'
          USING ERRCODE = '42501';
    END IF;

    -- Operation-id idempotency. A committed same-day write ACK is still
    -- repeatable after midnight, without re-executing the old write.
    v_hash := pg_catalog.encode(
        extensions.digest(pg_catalog.convert_to(
            'purchase:' || p_purchase_id::text || ':' || p_action ||
            ':' || coalesce(p_expected_revision::text,'null') ||
            ':' || p_payload::text,'UTF8'
        ),'sha256'),'hex'
    );
    SELECT * INTO v_previous FROM public.sync_operations
    WHERE operation_id = p_operation_id;
    IF FOUND THEN
        IF v_previous.workspace_id IS DISTINCT FROM p_workspace_id OR
           v_previous.device_id IS DISTINCT FROM p_device_id OR
           v_previous.actor_user_id IS DISTINCT FROM v_user OR
           v_previous.entity_type IS DISTINCT FROM 'purchase' OR
           v_previous.entity_id IS DISTINCT FROM p_purchase_id OR
           v_previous.operation_type IS DISTINCT FROM 'upsert' OR
           v_previous.payload_hash IS DISTINCT FROM v_hash THEN
            RAISE EXCEPTION 'Operation UUID reused with different data'
               USING ERRCODE = '23505';
        END IF;
        SELECT resulting_revision INTO v_new_revision
        FROM marketpro_private.team_purchase_audit
        WHERE operation_id = p_operation_id;
        IF v_new_revision IS NULL THEN
            RAISE EXCEPTION 'Old-format operation UUID conflict'
              USING ERRCODE = '23505';
        END IF;
        RETURN pg_catalog.jsonb_build_object(
            'accepted',true,'replayed',true,'operation_id',p_operation_id,
            'revision',v_new_revision
        );
    END IF;

    SELECT * INTO v_existing FROM public.purchases
    WHERE workspace_id = p_workspace_id AND id = p_purchase_id
    FOR UPDATE;
    v_exists := FOUND;
    v_role := marketpro_private.authorize_team_financial_write(
        p_workspace_id,p_device_id,v_date,p_action,
        CASE WHEN v_exists THEN v_existing.business_date ELSE NULL END
    );

    IF p_action='create' THEN
        IF v_exists THEN
            RAISE EXCEPTION 'Purchase UUID already exists' USING ERRCODE='23505';
        END IF;
        -- The existing DB semantic key UNIQUE(workspace_id,business_date,item_id)
        -- prevents a new UUID silently duplicating a real purchase.
        INSERT INTO public.purchases(
            id,workspace_id,business_date,item_id,quantity_grams,price_cents_per_kg
        ) VALUES (
            p_purchase_id,p_workspace_id,v_date,v_item,v_grams,v_cents
        ) RETURNING revision INTO v_new_revision;
    ELSE
        IF NOT v_exists OR v_existing.deleted_at IS NOT NULL THEN
            RAISE EXCEPTION 'Purchase not found or deleted' USING ERRCODE='23503';
        END IF;
        IF v_existing.revision <> p_expected_revision THEN
            RAISE EXCEPTION 'Stale purchase revision; refresh phone before correcting'
              USING ERRCODE='40001';
        END IF;
        IF v_existing.item_id IS DISTINCT FROM v_item OR
           v_existing.business_date IS DISTINCT FROM v_date THEN
            RAISE EXCEPTION 'Cannot move existing purchase to another item/date'
              USING ERRCODE='42501';
        END IF;
        v_before := pg_catalog.jsonb_build_object(
            'business_date',v_existing.business_date,
            'item_id',v_existing.item_id,
            'quantity_grams',v_existing.quantity_grams,
            'price_cents_per_kg',v_existing.price_cents_per_kg,
            'revision',v_existing.revision
        );
        UPDATE public.purchases
        SET quantity_grams=v_grams,
            price_cents_per_kg=v_cents,
            revision=revision+1,
            updated_at=clock_timestamp()
        WHERE workspace_id=p_workspace_id AND id=p_purchase_id AND
              revision=p_expected_revision
        RETURNING revision INTO v_new_revision;
        IF v_new_revision IS NULL THEN
            RAISE EXCEPTION 'Purchase revision changed concurrently'
              USING ERRCODE='40001';
        END IF;
    END IF;

    v_after := pg_catalog.jsonb_build_object(
        'business_date',v_date,'item_id',v_item,
        'quantity_grams',v_grams,'price_cents_per_kg',v_cents,
        'revision',v_new_revision
    );

    -- Business mutation, idempotency and audit all commit/rollback together.
    INSERT INTO public.sync_operations(
        operation_id,workspace_id,device_id,actor_user_id,
        entity_type,entity_id,operation_type,payload_hash
    ) VALUES(
        p_operation_id,p_workspace_id,p_device_id,v_user,
        'purchase',p_purchase_id,'upsert',v_hash
    );
    INSERT INTO marketpro_private.team_purchase_audit(
        operation_id,workspace_id,device_id,actor_user_id,
        action,purchase_id,before_row,after_row,resulting_revision
    ) VALUES(
        p_operation_id,p_workspace_id,p_device_id,v_user,
        p_action,p_purchase_id,v_before,v_after,v_new_revision
    );
    RETURN pg_catalog.jsonb_build_object(
        'accepted',true,'replayed',false,'operation_id',p_operation_id,
        'revision',v_new_revision,'actor_role',v_role
    );
END;
$team_purchase$;

REVOKE ALL ON FUNCTION public.marketpro_apply_team_purchase_change(
    uuid,uuid,uuid,uuid,text,bigint,jsonb
) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.marketpro_apply_team_purchase_change(
    uuid,uuid,uuid,uuid,text,bigint,jsonb
) TO authenticated;

COMMIT;
