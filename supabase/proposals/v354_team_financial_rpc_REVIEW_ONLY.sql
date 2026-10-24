-- MARKET PRO V3.5.4 — REVIEW ONLY, NOT APPLIED TO PRODUCTION SUPABASE
-- Authenticated staff/owner create or correct: requisition, purchase_addition, daily_cash_received.
-- This is a sibling to v353 purchase-only RPC, NOT a replacement for the old live V3.3.7 RPC.
-- Requires v352 private authorize_team_financial_write() in a test DB.
BEGIN;

CREATE SCHEMA IF NOT EXISTS marketpro_private;

CREATE TABLE IF NOT EXISTS marketpro_private.team_financial_audit (
    operation_id uuid PRIMARY KEY,
    workspace_id uuid NOT NULL,
    actor_user_id uuid NOT NULL,
    device_id uuid NOT NULL,
    entity_type text NOT NULL
      CHECK (entity_type IN ('requisition','purchase_addition','daily_cash_received')),
    entity_id uuid NOT NULL,
    action text NOT NULL CHECK (action IN ('create','correct')),
    before_row jsonb,
    after_row jsonb NOT NULL,
    resulting_revision bigint NOT NULL CHECK(resulting_revision>0),
    changed_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
REVOKE ALL ON marketpro_private.team_financial_audit FROM PUBLIC, anon, authenticated;

CREATE OR REPLACE FUNCTION public.marketpro_apply_team_financial_change(
    p_workspace_id uuid,
    p_device_id uuid,
    p_operation_id uuid,
    p_entity_type text,
    p_entity_id uuid,
    p_action text,
    p_expected_revision bigint,
    p_payload jsonb
) RETURNS jsonb
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = ''
AS $rpc$
DECLARE
    v_user uuid := auth.uid();
    v_table text;
    v_date date;
    v_existing jsonb;
    v_record_exists boolean := false;
    v_existing_date date;
    v_key text;
    v_hash text;
    v_previous public.sync_operations%ROWTYPE;
    v_role text;
    v_revision bigint;
    v_kitchen text;
    v_item uuid;
    v_qty bigint;
    v_price bigint;
    v_amount bigint;
    v_after jsonb;
BEGIN
    IF v_user IS NULL OR coalesce((auth.jwt()->>'is_anonymous')::boolean,false) THEN
      RAISE EXCEPTION 'Authenticated nonanonymous user required' USING ERRCODE='42501';
    END IF;
    IF p_workspace_id IS NULL OR p_device_id IS NULL OR
       p_operation_id IS NULL OR p_entity_id IS NULL OR
       p_action NOT IN ('create','correct') OR p_action IS NULL OR
       p_entity_type NOT IN ('requisition','purchase_addition','daily_cash_received') OR
       p_entity_type IS NULL OR p_payload IS NULL OR
       jsonb_typeof(p_payload) <> 'object' OR
       octet_length(p_payload::text) > 2048 THEN
      RAISE EXCEPTION 'Invalid team financial envelope' USING ERRCODE='22023';
    END IF;
    IF (p_action='create' AND p_expected_revision IS NOT NULL) OR
       (p_action='correct' AND
          (p_expected_revision IS NULL OR p_expected_revision<1)) THEN
      RAISE EXCEPTION 'Invalid expected revision' USING ERRCODE='22023';
    END IF;

    IF NOT p_payload ? 'business_date' THEN
      RAISE EXCEPTION 'Business date missing' USING ERRCODE='22023';
    END IF;
    -- Never accept arbitrary data such as invoice image paths.
    FOR v_key IN SELECT jsonb_object_keys(p_payload) LOOP
      IF NOT (
        (p_entity_type='requisition' AND
          v_key IN ('business_date','kitchen','item_id','quantity_grams')) OR
        (p_entity_type='purchase_addition' AND
          v_key IN ('business_date','item_id','quantity_grams','price_cents_per_kg')) OR
        (p_entity_type='daily_cash_received' AND
          v_key IN ('business_date','amount_cents'))
      ) THEN
        RAISE EXCEPTION 'Unknown financial field' USING ERRCODE='22023';
      END IF;
    END LOOP;

    v_date := (p_payload->>'business_date')::date;
    IF v_date IS NULL THEN
      RAISE EXCEPTION 'Invalid business date' USING ERRCODE='22023';
    END IF;
    IF p_entity_type IN ('requisition','purchase_addition') THEN
      IF NOT (p_payload ? 'item_id' AND p_payload ? 'quantity_grams') THEN
        RAISE EXCEPTION 'Missing required item/quantity' USING ERRCODE='22023';
      END IF;
      v_item := (p_payload->>'item_id')::uuid;
      v_qty := (p_payload->>'quantity_grams')::bigint;
      IF v_item IS NULL OR v_qty IS NULL OR v_qty<0 OR v_qty>1000000000 THEN
        RAISE EXCEPTION 'Invalid item/quantity' USING ERRCODE='22023';
      END IF;
    END IF;

    IF p_entity_type='requisition' THEN
      IF NOT p_payload ? 'kitchen' THEN
        RAISE EXCEPTION 'Missing kitchen' USING ERRCODE='22023';
      END IF;
      v_kitchen := p_payload->>'kitchen';
      IF v_kitchen NOT IN ('Main Kitchen','Indian Kitchen','Pastry Kitchen') OR
         v_kitchen IS NULL THEN
        RAISE EXCEPTION 'Invalid kitchen' USING ERRCODE='22023';
      END IF;
      v_table := 'requisitions';
    ELSIF p_entity_type='purchase_addition' THEN
      IF NOT p_payload ? 'price_cents_per_kg' THEN
        RAISE EXCEPTION 'Missing price' USING ERRCODE='22023';
      END IF;
      v_price := (p_payload->>'price_cents_per_kg')::bigint;
      IF v_price IS NULL OR v_price<0 OR v_price>1000000000 THEN
        RAISE EXCEPTION 'Invalid addition price' USING ERRCODE='22023';
      END IF;
      v_table := 'purchase_additions';
    ELSE
      IF NOT p_payload ? 'amount_cents' THEN
        RAISE EXCEPTION 'Missing cash amount' USING ERRCODE='22023';
      END IF;
      v_amount := (p_payload->>'amount_cents')::bigint;
      IF v_amount IS NULL OR v_amount<0 OR v_amount>9999999999 THEN
        RAISE EXCEPTION 'Invalid cash amount' USING ERRCODE='22023';
      END IF;
      v_table := 'daily_cash_received';
    END IF;

    -- Serialize members/devices, idempotency and ALL same-workspace financial edits.
    PERFORM pg_catalog.pg_advisory_xact_lock(
      pg_catalog.hashtextextended(p_workspace_id::text,0)
    );
    IF NOT EXISTS (
      SELECT 1 FROM public.registered_devices d
      JOIN public.workspace_members m
        ON m.workspace_id=d.workspace_id AND m.user_id=d.user_id
      WHERE d.workspace_id=p_workspace_id AND d.id=p_device_id AND
        d.user_id=v_user AND d.enabled AND d.can_write AND
        m.role IN ('owner','purchasing_staff')
    ) THEN
      RAISE EXCEPTION 'Device/membership denied' USING ERRCODE='42501';
    END IF;

    v_hash := pg_catalog.encode(extensions.digest(
      pg_catalog.convert_to(
        p_entity_type || ':' || p_entity_id || ':' || p_action || ':' ||
        coalesce(p_expected_revision::text,'null') || ':' || p_payload::text,'UTF8'
      ), 'sha256'), 'hex'
    );
    SELECT * INTO v_previous FROM public.sync_operations WHERE operation_id=p_operation_id;
    IF FOUND THEN
      IF v_previous.workspace_id IS DISTINCT FROM p_workspace_id OR
         v_previous.device_id IS DISTINCT FROM p_device_id OR
         v_previous.actor_user_id IS DISTINCT FROM v_user OR
         v_previous.entity_type IS DISTINCT FROM p_entity_type OR
         v_previous.entity_id IS DISTINCT FROM p_entity_id OR
         v_previous.operation_type IS DISTINCT FROM 'upsert' OR
         v_previous.payload_hash IS DISTINCT FROM v_hash THEN
         RAISE EXCEPTION 'Operation UUID reused with different content'
           USING ERRCODE='23505';
      END IF;
      SELECT resulting_revision INTO v_revision
      FROM marketpro_private.team_financial_audit WHERE operation_id=p_operation_id;
      IF v_revision IS NULL THEN
         RAISE EXCEPTION 'Old operation UUID incompatible' USING ERRCODE='23505';
      END IF;
      RETURN pg_catalog.jsonb_build_object(
        'accepted',true,'replayed',true,'operation_id',p_operation_id,
        'revision',v_revision
      );
    END IF;

    -- v_table is selected ONLY from three compile-time allowlisted literals.
    EXECUTE pg_catalog.format(
      'SELECT to_jsonb(r) FROM public.%I AS r
       WHERE r.workspace_id=$1 AND r.id=$2 FOR UPDATE',
       v_table
    ) INTO v_existing USING p_workspace_id,p_entity_id;
    v_record_exists := (v_existing IS NOT NULL);
    IF v_record_exists THEN
      v_existing_date := (v_existing->>'business_date')::date;
    END IF;
    v_role := marketpro_private.authorize_team_financial_write(
      p_workspace_id,p_device_id,v_date,p_action,v_existing_date
    );
    IF v_record_exists AND v_existing->>'deleted_at' IS NOT NULL THEN
      RAISE EXCEPTION 'Deleted row cannot be resurrected' USING ERRCODE='23503';
    END IF;

    IF p_action='create' THEN
      IF v_record_exists THEN
        RAISE EXCEPTION 'Record UUID already exists' USING ERRCODE='23505';
      END IF;
      IF p_entity_type='requisition' THEN
        INSERT INTO public.requisitions(
          id,workspace_id,business_date,kitchen,item_id,quantity_grams
        ) VALUES(p_entity_id,p_workspace_id,v_date,v_kitchen,v_item,v_qty)
        RETURNING revision INTO v_revision;
      ELSIF p_entity_type='purchase_addition' THEN
        INSERT INTO public.purchase_additions(
          id,workspace_id,business_date,item_id,quantity_grams,price_cents_per_kg
        ) VALUES(p_entity_id,p_workspace_id,v_date,v_item,v_qty,v_price)
        RETURNING revision INTO v_revision;
      ELSE
        INSERT INTO public.daily_cash_received(
          id,workspace_id,business_date,amount_cents
        ) VALUES(p_entity_id,p_workspace_id,v_date,v_amount)
        RETURNING revision INTO v_revision;
      END IF;
    ELSE
      IF NOT v_record_exists OR v_existing->>'deleted_at' IS NOT NULL THEN
        RAISE EXCEPTION 'Record missing or deleted' USING ERRCODE='23503';
      END IF;
      IF (v_existing->>'revision')::bigint <> p_expected_revision THEN
        RAISE EXCEPTION 'Stale revision; refresh before editing' USING ERRCODE='40001';
      END IF;
      IF p_entity_type='requisition' THEN
        IF v_existing->>'kitchen' IS DISTINCT FROM v_kitchen OR
           (v_existing->>'item_id')::uuid IS DISTINCT FROM v_item THEN
          RAISE EXCEPTION 'Requisition kitchen/item key immutable' USING ERRCODE='42501';
        END IF;
        UPDATE public.requisitions SET quantity_grams=v_qty,
          revision=revision+1,updated_at=clock_timestamp()
        WHERE id=p_entity_id AND workspace_id=p_workspace_id AND
          revision=p_expected_revision
        RETURNING revision INTO v_revision;
      ELSIF p_entity_type='purchase_addition' THEN
        IF (v_existing->>'item_id')::uuid IS DISTINCT FROM v_item THEN
          RAISE EXCEPTION 'Addition item key immutable' USING ERRCODE='42501';
        END IF;
        UPDATE public.purchase_additions SET quantity_grams=v_qty,
          price_cents_per_kg=v_price,revision=revision+1,
          updated_at=clock_timestamp()
        WHERE id=p_entity_id AND workspace_id=p_workspace_id AND
          revision=p_expected_revision
        RETURNING revision INTO v_revision;
      ELSE
        UPDATE public.daily_cash_received SET amount_cents=v_amount,
          revision=revision+1,updated_at=clock_timestamp()
        WHERE id=p_entity_id AND workspace_id=p_workspace_id AND
          revision=p_expected_revision
        RETURNING revision INTO v_revision;
      END IF;
      IF v_revision IS NULL THEN
        RAISE EXCEPTION 'Record revision conflict' USING ERRCODE='40001';
      END IF;
    END IF;

    v_after := pg_catalog.jsonb_build_object(
      'business_date',v_date,'revision',v_revision,
      'entity_type',p_entity_type,'fields',p_payload
    );
    INSERT INTO public.sync_operations(
      operation_id,workspace_id,device_id,actor_user_id,
      entity_type,entity_id,operation_type,payload_hash
    ) VALUES(
      p_operation_id,p_workspace_id,p_device_id,v_user,
      p_entity_type,p_entity_id,'upsert',v_hash
    );
    INSERT INTO marketpro_private.team_financial_audit(
      operation_id,workspace_id,device_id,actor_user_id,
      entity_type,entity_id,action,before_row,after_row,resulting_revision
    ) VALUES(
      p_operation_id,p_workspace_id,p_device_id,v_user,
      p_entity_type,p_entity_id,p_action,
      CASE WHEN v_record_exists THEN v_existing ELSE NULL END,
      v_after,v_revision
    );

    RETURN pg_catalog.jsonb_build_object(
      'accepted',true,'replayed',false,'operation_id',p_operation_id,
      'revision',v_revision,'actor_role',v_role
    );
END;
$rpc$;

REVOKE ALL ON FUNCTION public.marketpro_apply_team_financial_change(
  uuid,uuid,uuid,text,uuid,text,bigint,jsonb
) FROM PUBLIC,anon;
GRANT EXECUTE ON FUNCTION public.marketpro_apply_team_financial_change(
  uuid,uuid,uuid,text,uuid,text,bigint,jsonb
) TO authenticated;

COMMIT;
