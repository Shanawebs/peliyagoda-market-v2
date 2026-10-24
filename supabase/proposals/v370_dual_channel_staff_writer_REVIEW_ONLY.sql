-- MARKET PRO V3.7.0 — TWO-CHANNEL STAFF WRITER SECURITY, REVIEW ONLY
-- NO LIVE DEPLOYMENT: standalone proposal applied AFTER v352/v353/v354
-- to a disposable PG17 test. Never selectively deploy before the full
-- Owner/Staff authenticated cutover, Staff RLS hardening and two-phone QA.
--
-- Keep legacy registered_devices_one_enabled_writer_idx untouched.
-- Registered Staff device MUST keep can_write=false, so legacy V1 RPC
-- cannot grant it Owner access or break the Owner's single-writer index.
-- Real Staff writes require an independently administrator-enabled PRIVATE lease,
-- a verified Staff JWT, current registered enabled device and v2 revision RPC.
BEGIN;
CREATE SCHEMA IF NOT EXISTS marketpro_private;

CREATE TABLE IF NOT EXISTS marketpro_private.staff_write_leases (
    workspace_id uuid PRIMARY KEY,
    staff_user_id uuid NOT NULL,
    device_id uuid NOT NULL,
    enabled boolean NOT NULL DEFAULT false,
    granted_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    revoked_at timestamptz,
    CONSTRAINT staff_write_lease_member_fk FOREIGN KEY (workspace_id,staff_user_id)
      REFERENCES public.workspace_members(workspace_id,user_id) ON DELETE RESTRICT,
    CONSTRAINT staff_write_lease_device_fk FOREIGN KEY (workspace_id,device_id)
      REFERENCES public.registered_devices(workspace_id,id) ON DELETE RESTRICT
);
REVOKE ALL ON marketpro_private.staff_write_leases FROM PUBLIC,anon,authenticated;

CREATE OR REPLACE FUNCTION marketpro_private.require_dual_channel_writer(
  p_workspace_id uuid,p_device_id uuid
) RETURNS text
LANGUAGE plpgsql SECURITY DEFINER SET search_path=''
AS $guard$
DECLARE
  v_user uuid := auth.uid();
  v_role text;
BEGIN
  IF v_user IS NULL OR
     coalesce((auth.jwt()->>'is_anonymous')::boolean,false)
     OR p_workspace_id IS NULL OR p_device_id IS NULL THEN
    RAISE EXCEPTION 'Verified User, Workspace and Device required'
      USING ERRCODE='42501';
  END IF;

  SELECT m.role INTO v_role
  FROM public.workspace_members m
  JOIN public.registered_devices d
    ON d.workspace_id=m.workspace_id AND d.user_id=m.user_id
  WHERE m.workspace_id=p_workspace_id AND m.user_id=v_user
    AND d.id=p_device_id AND d.enabled
    AND (
      (m.role='owner' AND d.can_write)
      OR (m.role='purchasing_staff' AND NOT d.can_write AND EXISTS (
        SELECT 1 FROM marketpro_private.staff_write_leases l
        WHERE l.workspace_id=m.workspace_id AND l.staff_user_id=m.user_id
          AND l.device_id=d.id AND l.enabled
      ))
    )
  LIMIT 1;
  IF v_role IS NULL THEN
    RAISE EXCEPTION 'Workspace writer not authorized for this device'
      USING ERRCODE='42501';
  END IF;
  RETURN v_role;
END;
$guard$;

REVOKE ALL ON FUNCTION marketpro_private.require_dual_channel_writer(uuid,uuid)
  FROM PUBLIC,anon,authenticated;
COMMIT;

-- The following function overrides are required together. They update the
-- pre-replay authorization AND same-day helper; omission of any one fails audit.
BEGIN;

CREATE OR REPLACE FUNCTION marketpro_private.authorize_team_financial_write(
    p_workspace_id uuid,
    p_device_id uuid,
    p_business_date date,
    p_action text,                -- 'create' or 'correct'
    p_locked_existing_date date  -- extracted from SELECT ... FOR UPDATE by RPC
) RETURNS text
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = ''
AS $function$
DECLARE
    v_user uuid := auth.uid();
    v_role text;
    v_today date := (clock_timestamp() AT TIME ZONE 'Asia/Colombo')::date;
BEGIN
    IF v_user IS NULL OR
       COALESCE((auth.jwt()->>'is_anonymous')::boolean, false) THEN
        RAISE EXCEPTION 'Authenticated, nonanonymous user required'
            USING ERRCODE = '42501';
    END IF;
    IF p_workspace_id IS NULL OR p_device_id IS NULL OR
       p_business_date IS NULL OR
       p_action NOT IN ('create','correct') OR p_action IS NULL THEN
        RAISE EXCEPTION 'Missing or invalid team write envelope'
            USING ERRCODE = '22023';
    END IF;

    -- This SELECT is made under the function owner's controlled privileges.
    -- Membership and phone enrolment cannot be forged with client claims.
    -- Dual-channel: legacy Owner retains the sole d.can_write=true device.
    -- Staff instead needs a private workspace-scoped lease; d.can_write remains false.
    v_role := marketpro_private.require_dual_channel_writer(
        p_workspace_id,p_device_id
    );

    IF v_role NOT IN ('owner','purchasing_staff') OR v_role IS NULL THEN
        RAISE EXCEPTION 'No approved team writer permission for this phone'
            USING ERRCODE = '42501';
    END IF;

    IF p_business_date > v_today THEN
        RAISE EXCEPTION 'Future business dates are read-only'
            USING ERRCODE = '42501';
    END IF;

    -- All previous days are OWNER-ONLY for both new and corrected records.
    -- No owner review/approval for today's staff entries, even repeated edits.
    IF v_role = 'purchasing_staff' AND p_business_date <> v_today THEN
        RAISE EXCEPTION 'Previous-day entries are Owner-only'
            USING ERRCODE = '42501';
    END IF;

    -- Prevent shifting an existing purchase from a previous day into today.
    -- The caller must supply the REAL prior date from a locked DB row.
    IF p_action = 'create' AND p_locked_existing_date IS NOT NULL THEN
        RAISE EXCEPTION 'Use correct with expected revision for an existing row'
            USING ERRCODE = '23505';
    END IF;

    IF p_action = 'correct' AND (
        p_locked_existing_date IS NULL OR
        p_locked_existing_date IS DISTINCT FROM p_business_date
    ) THEN
        RAISE EXCEPTION 'Cannot move a record to a different business date'
            USING ERRCODE = '42501';
    END IF;

    RETURN v_role;
END;
$function$;

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
    ) THEN
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
    PERFORM marketpro_private.require_dual_channel_writer(
        p_workspace_id,p_device_id
    );

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
    PERFORM marketpro_private.require_dual_channel_writer(
      p_workspace_id,p_device_id
    );

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

-- Reassert privileges; old grants for both v2 RPCs remain authenticated only.
REVOKE ALL ON FUNCTION marketpro_private.authorize_team_financial_write(
  uuid,uuid,date,text,date) FROM PUBLIC,anon,authenticated;
REVOKE ALL ON FUNCTION public.marketpro_apply_team_purchase_change(
  uuid,uuid,uuid,uuid,text,bigint,jsonb) FROM PUBLIC,anon;
REVOKE ALL ON FUNCTION public.marketpro_apply_team_financial_change(
  uuid,uuid,uuid,text,uuid,text,bigint,jsonb) FROM PUBLIC,anon;
COMMIT;
