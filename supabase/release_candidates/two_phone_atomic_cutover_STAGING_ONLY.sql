-- MARKET PRO TWO-PHONE STAGING-ONLY ATOMIC CUTOVER BUNDLE
-- 2026-10-25 / REVIEW ONLY / NEVER EXECUTE ON LIVE PROJECT.
-- Generated deterministically from reviewed proposals in dependency order.
-- Existing three Owner V1 migrations MUST already exist; does not overwrite
-- existing purchase, catalog or Cash rows. All proposed Team DDL/functions
-- and RLS changes are ONE PostgreSQL transaction (no intermediate exposure).
--
-- SAFETY: this bundle refuses to start unless disposable test sentinel is
-- set for THIS SESSION: SELECT set_config('marketpro.staging_disposable','yes',false);
-- This is an accidental-application guard, not a replacement for IAM approval.
--
DO $guard_disposable$
BEGIN
 IF pg_catalog.current_setting('marketpro.staging_disposable',true)
      IS DISTINCT FROM 'yes' THEN
   RAISE EXCEPTION 'Review-only two-phone cutover: disposable STAGING database required'
     USING ERRCODE='42501';
 END IF;
 IF pg_catalog.to_regclass('public.registered_devices') IS NULL OR
    pg_catalog.to_regclass('public.daily_cash_received') IS NULL OR
    pg_catalog.to_regclass('public.workspace_members') IS NULL THEN
   RAISE EXCEPTION 'Owner Cloud V1 baseline schema missing'
     USING ERRCODE='55000';
 END IF;
END;
$guard_disposable$;
BEGIN;
-- Explicit lock prevents concurrent migrations/role enrollment in test.
SELECT pg_catalog.pg_advisory_xact_lock(
  pg_catalog.hashtextextended('marketpro_two_phone_schema_cutover',0)
);

-- SOURCE: supabase/proposals/v352_team_write_gate_REVIEW_ONLY.sql
-- MARKET PRO V3.5.2 — REVIEW-ONLY SERVER TEAM WRITE GATE
-- NOT APPLIED TO SUPABASE; no live DDL, roles, devices, data or RPC changed.
-- An owner-approved DB backup, two-user enrolment, client download pipeline and
-- reviewed transactional team RPC v2 are still mandatory before deployment.
--
-- This private authorization helper is the predicate to CALL INSIDE THE SAME
-- TRANSACTION as each staff/owner upsert. It cannot by itself protect data:
-- never enable a staff writer on old public.marketpro_apply_change(v1)!
--
-- Existing RPC v1, RLS policies and old dashboard stay untouched.
-- TOP-LEVEL BEGIN moved to one enclosing transaction

-- Preserve legacy owner/editor/viewer roles; append purchasing_staff only.
ALTER TABLE public.workspace_members
  DROP CONSTRAINT workspace_members_role_check;
ALTER TABLE public.workspace_members
  ADD CONSTRAINT workspace_members_role_check
  CHECK (role IN ('owner','editor','viewer','purchasing_staff'));

CREATE SCHEMA IF NOT EXISTS marketpro_private;

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
    SELECT m.role INTO v_role
    FROM public.workspace_members AS m
    JOIN public.registered_devices AS d
      ON d.workspace_id = m.workspace_id
     AND d.user_id = m.user_id
    WHERE m.workspace_id = p_workspace_id
      AND m.user_id = v_user
      AND d.id = p_device_id
      AND d.enabled = true
      AND d.can_write = true
    LIMIT 1;

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

-- Fail closed. This is a PRIVATE helper for future SECURITY DEFINER RPC v2,
-- not an independently callable public/anon/authenticated Data API endpoint.
REVOKE ALL ON FUNCTION marketpro_private.authorize_team_financial_write(
    uuid,uuid,date,text,date
) FROM PUBLIC, anon, authenticated;

-- TOP-LEVEL COMMIT moved to one enclosing transaction


-- SOURCE: supabase/proposals/v353_team_purchase_rpc_REVIEW_ONLY.sql
-- MARKET PRO V3.5.3 — purchase-only team RPC proof of concept
-- REVIEW ONLY. NOT APPLIED TO LIVE SUPABASE. Works only after V3.5.2 draft
-- helper/role check is installed in a disposable PostgreSQL test database.
-- Preserves public.marketpro_apply_change (single-owner V3.3.7 live API).
-- TOP-LEVEL BEGIN moved to one enclosing transaction

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

-- TOP-LEVEL COMMIT moved to one enclosing transaction


-- SOURCE: supabase/proposals/v354_team_financial_rpc_REVIEW_ONLY.sql
-- MARKET PRO V3.5.4 — REVIEW ONLY, NOT APPLIED TO PRODUCTION SUPABASE
-- Authenticated staff/owner create or correct: requisition, purchase_addition, daily_cash_received.
-- This is a sibling to v353 purchase-only RPC, NOT a replacement for the old live V3.3.7 RPC.
-- Requires v352 private authorize_team_financial_write() in a test DB.
-- TOP-LEVEL BEGIN moved to one enclosing transaction

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

-- TOP-LEVEL COMMIT moved to one enclosing transaction


-- SOURCE: supabase/proposals/v354_team_paged_pull_REVIEW_ONLY.sql
-- MARKET PRO V3.5.4 — AUTHENTICATED, KEYSET-PAGINATED CLOUD PULL (REVIEW ONLY)
-- NOT DEPLOYED. Preserves the existing live Cloud writer & old Dashboard.
-- Fetches non-photo workspace rows, including tombstones, for both enrolled phones.
-- TOP-LEVEL BEGIN moved to one enclosing transaction

CREATE OR REPLACE FUNCTION public.marketpro_team_pull_page(
 p_workspace_id uuid,
 p_device_id uuid,
 p_entity_type text,
 p_after_updated_at timestamptz DEFAULT NULL,
 p_after_id uuid DEFAULT NULL,
 p_page_size integer DEFAULT 50
) RETURNS jsonb
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = ''
AS $pull$
DECLARE
 v_user uuid := auth.uid();
 v_table text;
 v_rows jsonb;
 v_count integer;
 v_more boolean;
 v_next_updated timestamptz;
 v_next_id uuid;
 v_today date := (clock_timestamp() AT TIME ZONE 'Asia/Colombo')::date;
BEGIN
 IF v_user IS NULL OR coalesce((auth.jwt()->>'is_anonymous')::boolean,false) THEN
  RAISE EXCEPTION 'Authenticated user required for download' USING ERRCODE='42501';
 END IF;
 IF p_workspace_id IS NULL OR p_device_id IS NULL OR
    p_entity_type NOT IN ('category','item','requisition','purchase',
                          'purchase_addition','daily_cash_received') OR
    p_entity_type IS NULL OR
    p_page_size IS NULL OR p_page_size NOT BETWEEN 1 AND 100 OR
    ((p_after_updated_at IS NULL) <> (p_after_id IS NULL)) THEN
  RAISE EXCEPTION 'Invalid pull request' USING ERRCODE='22023';
 END IF;
 -- Readers must be known users and enrolled devices, not arbitrary members.
 -- can_write is deliberately NOT required for a read-only phone.
 IF NOT EXISTS (
  SELECT 1 FROM public.workspace_members m
  JOIN public.registered_devices d
    ON d.workspace_id=m.workspace_id AND d.user_id=m.user_id
  WHERE m.workspace_id=p_workspace_id AND m.user_id=v_user
    AND m.role IN ('owner','purchasing_staff','viewer')
    AND d.id=p_device_id AND d.enabled=true
 ) THEN
  RAISE EXCEPTION 'Unenrolled or revoked reader' USING ERRCODE='42501';
 END IF;

 -- Closed allowlist: NO invoice photo bytes, no raw server Auth information.
 v_table := CASE p_entity_type
  WHEN 'category' THEN 'categories'
  WHEN 'item' THEN 'items'
  WHEN 'requisition' THEN 'requisitions'
  WHEN 'purchase' THEN 'purchases'
  WHEN 'purchase_addition' THEN 'purchase_additions'
  WHEN 'daily_cash_received' THEN 'daily_cash_received'
 END;
 EXECUTE pg_catalog.format(
   'SELECT coalesce(pg_catalog.jsonb_agg(to_jsonb(r) ORDER BY r.updated_at,r.id),
                     ''[]''::jsonb)
    FROM (
      SELECT * FROM public.%I
      WHERE workspace_id=$1 AND
            ($2::timestamptz IS NULL OR
             (updated_at,id) > ($2::timestamptz,$3::uuid))
      ORDER BY updated_at ASC,id ASC
      LIMIT $4
    ) AS r',v_table
 ) INTO v_rows USING p_workspace_id,p_after_updated_at,p_after_id,p_page_size+1;
 v_count := pg_catalog.jsonb_array_length(v_rows);
 v_more := v_count > p_page_size;
 IF v_more THEN
  v_rows := v_rows - (v_count - 1);
 END IF;
 v_count := pg_catalog.jsonb_array_length(v_rows);
 IF v_count>0 THEN
  v_next_updated := (v_rows->(v_count-1)->>'updated_at')::timestamptz;
  v_next_id := (v_rows->(v_count-1)->>'id')::uuid;
 ELSE
  v_next_updated := p_after_updated_at;
  v_next_id := p_after_id;
 END IF;
 RETURN pg_catalog.jsonb_build_object(
  'entity_type',p_entity_type,'rows',v_rows,'has_more',v_more,
  'next_updated_at',v_next_updated,'next_id',v_next_id,
  'server_today_colombo',v_today,
  'server_time_utc',clock_timestamp()
 );
END;
$pull$;

REVOKE ALL ON FUNCTION public.marketpro_team_pull_page(
 uuid,uuid,text,timestamptz,uuid,integer
) FROM PUBLIC,anon;
GRANT EXECUTE ON FUNCTION public.marketpro_team_pull_page(
 uuid,uuid,text,timestamptz,uuid,integer
) TO authenticated;

-- TOP-LEVEL COMMIT moved to one enclosing transaction


-- SOURCE: supabase/proposals/v358_owner_invite_staff_PENDING_ONLY.sql
-- Market Pro V3.5.8 — STAFF ENROLLMENT INVITATION (REVIEW ONLY).
-- NEVER EXECUTE ON THE LIVE SUPABASE PROJECT BEFORE APP/RLS CUTOVER.
-- Preserves original v3.3.7 owner writer, original RLS, all legacy data and dashboard.
--
-- Deliberate two-phase safety: preparing/claiming invitation does NOT create
-- public.workspace_members or public.registered_devices. The pre-existing
-- workspace SELECT policy allows *any* member to read, so prematurely creating
-- a staff membership would already expose data even with can_write=false.
-- TOP-LEVEL BEGIN moved to one enclosing transaction
CREATE SCHEMA IF NOT EXISTS marketpro_private;

CREATE TABLE marketpro_private.pending_team_enrollments (
    workspace_id uuid NOT NULL,
    staff_user_id uuid NOT NULL,
    inviter_user_id uuid NOT NULL,
    intended_device_id uuid NOT NULL,
    device_label text NOT NULL CHECK (length(btrim(device_label)) BETWEEN 1 AND 100),
    token_sha256 bytea NOT NULL,
    expires_at timestamptz NOT NULL,
    status text NOT NULL DEFAULT 'invited'
      CHECK (status IN ('invited','accepted','revoked')),
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    accepted_at timestamptz,
    revoked_at timestamptz,
    PRIMARY KEY (workspace_id, staff_user_id),
    UNIQUE (intended_device_id)
);
REVOKE ALL ON marketpro_private.pending_team_enrollments
    FROM PUBLIC, anon, authenticated;

CREATE OR REPLACE FUNCTION marketpro_private.require_enrolled_owner(
    p_workspace_id uuid, p_owner_device_id uuid
) RETURNS uuid
LANGUAGE plpgsql SECURITY DEFINER SET search_path=''
AS $f$
DECLARE
    v_actor uuid := auth.uid();
BEGIN
    IF v_actor IS NULL OR
       coalesce((auth.jwt()->>'is_anonymous')::boolean,false) OR
       p_workspace_id IS NULL OR p_owner_device_id IS NULL THEN
       RAISE EXCEPTION 'Verified owner login and device required' USING ERRCODE='42501';
    END IF;
    IF NOT EXISTS (
        SELECT 1 FROM public.workspace_members AS m
        JOIN public.registered_devices AS d
          ON d.workspace_id=m.workspace_id AND d.user_id=m.user_id
        WHERE m.workspace_id=p_workspace_id AND m.user_id=v_actor
          AND m.role='owner' AND d.id=p_owner_device_id
          AND d.enabled AND d.can_write
    ) THEN
       RAISE EXCEPTION 'Only registered workspace owner may invite or revoke'
           USING ERRCODE='42501';
    END IF;
    RETURN v_actor;
END;
$f$;

REVOKE ALL ON FUNCTION marketpro_private.require_enrolled_owner(uuid,uuid)
  FROM PUBLIC, anon, authenticated;

CREATE OR REPLACE FUNCTION public.marketpro_owner_invite_staff(
    p_workspace_id uuid,
    p_owner_device_id uuid,
    p_staff_user_id uuid,
    p_staff_device_id uuid,
    p_staff_device_label text
) RETURNS jsonb
LANGUAGE plpgsql SECURITY DEFINER SET search_path=''
AS $f$
DECLARE
    v_owner uuid;
    v_verified boolean;
    v_token text;
    v_expiry timestamptz := clock_timestamp() + interval '30 minutes';
BEGIN
    v_owner := marketpro_private.require_enrolled_owner(
        p_workspace_id,p_owner_device_id
    );
    IF p_staff_user_id IS NULL OR p_staff_device_id IS NULL OR
       p_staff_user_id=v_owner OR
       p_staff_device_label IS NULL OR
       length(btrim(p_staff_device_label)) NOT BETWEEN 1 AND 100 THEN
       RAISE EXCEPTION 'Invalid staff identity/device/label' USING ERRCODE='22023';
    END IF;
    -- Only a verified existing Supabase Auth user may receive an invitation.
    SELECT (email IS NOT NULL AND email_confirmed_at IS NOT NULL)
       INTO v_verified FROM auth.users WHERE id=p_staff_user_id;
    IF v_verified IS DISTINCT FROM true THEN
       RAISE EXCEPTION 'Staff Auth email not confirmed' USING ERRCODE='42501';
    END IF;
    -- No self-escalation and no reassignment of existing workspace roles.
    IF EXISTS (
       SELECT 1 FROM public.workspace_members
       WHERE workspace_id=p_workspace_id AND user_id=p_staff_user_id
    ) OR EXISTS (
       SELECT 1 FROM public.registered_devices
       WHERE id=p_staff_device_id
    ) THEN
       RAISE EXCEPTION 'Staff user/device already enrolled; separate transfer required'
           USING ERRCODE='23505';
    END IF;
    PERFORM pg_catalog.pg_advisory_xact_lock(
       pg_catalog.hashtextextended(p_workspace_id::text,0)
    );
    v_token := pg_catalog.encode(extensions.gen_random_bytes(32),'hex');
    INSERT INTO marketpro_private.pending_team_enrollments(
       workspace_id,staff_user_id,inviter_user_id,intended_device_id,
       device_label,token_sha256,expires_at,status,created_at,accepted_at,revoked_at
    ) VALUES (
       p_workspace_id,p_staff_user_id,v_owner,p_staff_device_id,
       btrim(p_staff_device_label),
       extensions.digest(pg_catalog.convert_to(v_token,'UTF8'),'sha256'),
       v_expiry,'invited',clock_timestamp(),NULL,NULL
    )
    ON CONFLICT (workspace_id,staff_user_id)
    DO UPDATE SET
       inviter_user_id=EXCLUDED.inviter_user_id,
       intended_device_id=EXCLUDED.intended_device_id,
       device_label=EXCLUDED.device_label,
       token_sha256=EXCLUDED.token_sha256,
       expires_at=EXCLUDED.expires_at,
       status='invited',
       created_at=clock_timestamp(),
       accepted_at=NULL,revoked_at=NULL;
    -- Token returned ONCE to owner only; never stored plaintext in DB/logs.
    RETURN pg_catalog.jsonb_build_object(
       'invitation_created',true,
       'staff_user_id',p_staff_user_id,
       'intended_device_id',p_staff_device_id,
       'expires_at',v_expiry,
       'one_time_code',v_token
    );
END;
$f$;

REVOKE ALL ON FUNCTION public.marketpro_owner_invite_staff(
  uuid,uuid,uuid,uuid,text
) FROM PUBLIC,anon;
GRANT EXECUTE ON FUNCTION public.marketpro_owner_invite_staff(
  uuid,uuid,uuid,uuid,text
) TO authenticated;

CREATE OR REPLACE FUNCTION public.marketpro_staff_accept_invitation(
    p_workspace_id uuid,
    p_staff_device_id uuid,
    p_one_time_code text
) RETURNS jsonb
LANGUAGE plpgsql SECURITY DEFINER SET search_path=''
AS $f$
DECLARE
    v_user uuid := auth.uid();
    v_row marketpro_private.pending_team_enrollments%ROWTYPE;
    v_verified boolean;
BEGIN
    IF v_user IS NULL OR
       coalesce((auth.jwt()->>'is_anonymous')::boolean,false) THEN
       RAISE EXCEPTION 'Verified staff login required' USING ERRCODE='42501';
    END IF;
    IF p_workspace_id IS NULL OR p_staff_device_id IS NULL OR
       p_one_time_code IS NULL OR
       length(p_one_time_code) <> 64 OR
       p_one_time_code !~ '^[0-9a-f]{64}$' THEN
       RAISE EXCEPTION 'Invalid enrollment request' USING ERRCODE='22023';
    END IF;
    SELECT email_confirmed_at IS NOT NULL INTO v_verified
    FROM auth.users WHERE id=v_user;
    IF v_verified IS DISTINCT FROM true THEN
        RAISE EXCEPTION 'Verified staff email required' USING ERRCODE='42501';
    END IF;
    -- The staff account alone cannot select an invitation or change its role.
    SELECT * INTO v_row
    FROM marketpro_private.pending_team_enrollments
    WHERE workspace_id=p_workspace_id AND staff_user_id=v_user
    FOR UPDATE;
    IF NOT FOUND OR
       v_row.intended_device_id IS DISTINCT FROM p_staff_device_id OR
       v_row.status <> 'invited' OR v_row.expires_at <= clock_timestamp() OR
       v_row.token_sha256 IS DISTINCT FROM
         extensions.digest(pg_catalog.convert_to(p_one_time_code,'UTF8'),'sha256') THEN
       RAISE EXCEPTION 'Enrollment invitation invalid, expired or consumed'
           USING ERRCODE='42501';
    END IF;
    UPDATE marketpro_private.pending_team_enrollments
    SET status='accepted',accepted_at=clock_timestamp()
    WHERE workspace_id=p_workspace_id AND staff_user_id=v_user;
    -- CRITICAL: no real membership or registered writer is created here.
    RETURN pg_catalog.jsonb_build_object(
       'invitation_accepted',true,
       'status','awaiting_owner_activation_and_server_cutover',
       'workspace_id',p_workspace_id
    );
END;
$f$;

REVOKE ALL ON FUNCTION public.marketpro_staff_accept_invitation(
  uuid,uuid,text
) FROM PUBLIC,anon;
GRANT EXECUTE ON FUNCTION public.marketpro_staff_accept_invitation(
  uuid,uuid,text
) TO authenticated;

CREATE OR REPLACE FUNCTION public.marketpro_owner_revoke_staff_invitation(
    p_workspace_id uuid,p_owner_device_id uuid,p_staff_user_id uuid
) RETURNS jsonb
LANGUAGE plpgsql SECURITY DEFINER SET search_path=''
AS $f$
DECLARE
    v_rows integer;
BEGIN
    PERFORM marketpro_private.require_enrolled_owner(
       p_workspace_id,p_owner_device_id
    );
    IF p_staff_user_id IS NULL THEN
       RAISE EXCEPTION 'Missing staff ID' USING ERRCODE='22023';
    END IF;
    UPDATE marketpro_private.pending_team_enrollments
    SET status='revoked',revoked_at=clock_timestamp()
    WHERE workspace_id=p_workspace_id AND staff_user_id=p_staff_user_id
      AND status IN ('invited','accepted');
    GET DIAGNOSTICS v_rows=ROW_COUNT;
    RETURN pg_catalog.jsonb_build_object('revoked',v_rows=1);
END;
$f$;

REVOKE ALL ON FUNCTION public.marketpro_owner_revoke_staff_invitation(
  uuid,uuid,uuid
) FROM PUBLIC,anon;
GRANT EXECUTE ON FUNCTION public.marketpro_owner_revoke_staff_invitation(
  uuid,uuid,uuid
) TO authenticated;
-- TOP-LEVEL COMMIT moved to one enclosing transaction


-- SOURCE: supabase/proposals/v359_staff_readonly_activation_rls_REVIEW_ONLY.sql
-- MARKET PRO V3.5.9 — REVIEW-ONLY STAFF READ-ONLY ACTIVATION + DIRECT REST READ GATE
-- DO NOT EXECUTE ON LIVE SUPABASE. This file is for isolated PostgreSQL tests.
-- Requires v358 pending invitation RPC, and v354 team-pull RPC rollout.
-- The legacy owner-only v3.3.7 writer and dashboard visual UI must remain unchanged.
-- TOP-LEVEL BEGIN moved to one enclosing transaction

-- The private gates are changed only via an audited ADMINISTRATOR-controlled
-- migration after v2 RPC/RLS review; no public function may toggle them.
CREATE TABLE IF NOT EXISTS marketpro_private.team_rollout_gate (
    workspace_id uuid PRIMARY KEY,
    staff_direct_rest_restricted boolean NOT NULL DEFAULT false,
    team_pull_rpc_reviewed boolean NOT NULL DEFAULT false,
    team_writes_reviewed boolean NOT NULL DEFAULT false,
    updated_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
REVOKE ALL ON marketpro_private.team_rollout_gate FROM PUBLIC,anon,authenticated;

-- Existing constraint is enforced in live Cloud; do NOT replace roles with staff
-- only or remove compatibility for legacy owner, editor, viewer.
ALTER TABLE public.workspace_members
  DROP CONSTRAINT workspace_members_role_check;
ALTER TABLE public.workspace_members
  ADD CONSTRAINT workspace_members_role_check
    CHECK (role IN ('owner','editor','viewer','purchasing_staff'));

-- Legacy Owner/Editor/Viewer read access is preserved, but Staff gets no direct
-- PostgREST SELECT. STAFF CAN ONLY READ via audited authenticated, device-bound
-- marketpro_team_pull_page RPC after the separate future server cutover.
-- The qualified outer-table workspace_id is critical to prevent an accidental
-- all-workspaces access control mistake when joining workspace_members.
DROP POLICY IF EXISTS member_categories_read ON public.categories;
CREATE POLICY member_categories_read ON public.categories FOR SELECT
TO authenticated USING (EXISTS (
  SELECT 1 FROM public.workspace_members AS wm
  WHERE wm.workspace_id = categories.workspace_id
    AND wm.user_id = (SELECT auth.uid())
    AND wm.role IN ('owner','editor','viewer')
));

DROP POLICY IF EXISTS member_items_read ON public.items;
CREATE POLICY member_items_read ON public.items FOR SELECT
TO authenticated USING (EXISTS (
  SELECT 1 FROM public.workspace_members AS wm
  WHERE wm.workspace_id = items.workspace_id
    AND wm.user_id = (SELECT auth.uid())
    AND wm.role IN ('owner','editor','viewer')
));

DROP POLICY IF EXISTS workspace_requisitions_read ON public.requisitions;
CREATE POLICY workspace_requisitions_read ON public.requisitions FOR SELECT
TO authenticated USING (EXISTS (
  SELECT 1 FROM public.workspace_members AS wm
  WHERE wm.workspace_id = requisitions.workspace_id
    AND wm.user_id = (SELECT auth.uid())
    AND wm.role IN ('owner','editor','viewer')
));

DROP POLICY IF EXISTS member_purchases_read ON public.purchases;
CREATE POLICY member_purchases_read ON public.purchases FOR SELECT
TO authenticated USING (EXISTS (
  SELECT 1 FROM public.workspace_members AS wm
  WHERE wm.workspace_id = purchases.workspace_id
    AND wm.user_id = (SELECT auth.uid())
    AND wm.role IN ('owner','editor','viewer')
));

DROP POLICY IF EXISTS member_purchase_additions_read ON public.purchase_additions;
CREATE POLICY member_purchase_additions_read ON public.purchase_additions FOR SELECT
TO authenticated USING (EXISTS (
  SELECT 1 FROM public.workspace_members AS wm
  WHERE wm.workspace_id = purchase_additions.workspace_id
    AND wm.user_id = (SELECT auth.uid())
    AND wm.role IN ('owner','editor','viewer')
));

DROP POLICY IF EXISTS workspace_cash_read ON public.daily_cash_received;
CREATE POLICY workspace_cash_read ON public.daily_cash_received FOR SELECT
TO authenticated USING (EXISTS (
  SELECT 1 FROM public.workspace_members AS wm
  WHERE wm.workspace_id = daily_cash_received.workspace_id
    AND wm.user_id = (SELECT auth.uid())
    AND wm.role IN ('owner','editor','viewer')
));

ALTER TABLE marketpro_private.pending_team_enrollments
  ADD COLUMN IF NOT EXISTS activated_at timestamptz;

CREATE OR REPLACE FUNCTION public.marketpro_owner_activate_staff_readonly(
   p_workspace_id uuid, p_owner_device_id uuid, p_staff_user_id uuid
) RETURNS jsonb
LANGUAGE plpgsql SECURITY DEFINER SET search_path=''
AS $activate$
DECLARE
  v_owner uuid;
  v_invite marketpro_private.pending_team_enrollments%ROWTYPE;
  v_gate marketpro_private.team_rollout_gate%ROWTYPE;
  v_email_confirmed boolean;
BEGIN
  v_owner := marketpro_private.require_enrolled_owner(
    p_workspace_id,p_owner_device_id
  );
  IF p_staff_user_id IS NULL OR p_staff_user_id=v_owner THEN
    RAISE EXCEPTION 'Different verified staff user required' USING ERRCODE='42501';
  END IF;
  PERFORM pg_catalog.pg_advisory_xact_lock(
    pg_catalog.hashtextextended(p_workspace_id::text,0)
  );
  SELECT * INTO v_gate
    FROM marketpro_private.team_rollout_gate
   WHERE workspace_id=p_workspace_id FOR UPDATE;
  IF NOT FOUND OR NOT v_gate.staff_direct_rest_restricted
     OR NOT v_gate.team_pull_rpc_reviewed THEN
    RAISE EXCEPTION 'Staff activation blocked until server policies and pull API are audited'
       USING ERRCODE='42501';
  END IF;
  SELECT * INTO v_invite
    FROM marketpro_private.pending_team_enrollments
   WHERE workspace_id=p_workspace_id AND staff_user_id=p_staff_user_id
   FOR UPDATE;
  IF NOT FOUND OR v_invite.status<>'accepted' OR
     v_invite.activated_at IS NOT NULL OR
     v_invite.accepted_at IS NULL OR
     v_invite.accepted_at < clock_timestamp()-interval '24 hours' THEN
    RAISE EXCEPTION 'Staff invitation not freshly accepted or already activated'
      USING ERRCODE='42501';
  END IF;
  SELECT email_confirmed_at IS NOT NULL INTO v_email_confirmed
  FROM auth.users WHERE id=p_staff_user_id;
  IF v_email_confirmed IS DISTINCT FROM true OR
     EXISTS (SELECT 1 FROM public.workspace_members
              WHERE workspace_id=p_workspace_id AND user_id=p_staff_user_id) OR
     EXISTS (SELECT 1 FROM public.registered_devices
              WHERE id=v_invite.intended_device_id) THEN
    RAISE EXCEPTION 'Staff identity/device already used or no longer verified'
      USING ERRCODE='42501';
  END IF;
  -- This read-only enrollment is not a writer. Existing v1 owner RPC is
  -- unchanged; staff only becomes writer with future separately reviewed RPC.
  INSERT INTO public.workspace_members(workspace_id,user_id,role)
  VALUES (p_workspace_id,p_staff_user_id,'purchasing_staff');
  INSERT INTO public.registered_devices(
    id,workspace_id,user_id,device_label,enabled,can_write
  ) VALUES (
    v_invite.intended_device_id,p_workspace_id,p_staff_user_id,
    v_invite.device_label,true,false
  );
  UPDATE marketpro_private.pending_team_enrollments
  SET activated_at=clock_timestamp()
  WHERE workspace_id=p_workspace_id AND staff_user_id=p_staff_user_id;
  RETURN pg_catalog.jsonb_build_object(
    'activated',true, 'staff_role','purchasing_staff',
    'read_only',true,'can_write',false
  );
END;
$activate$;

REVOKE ALL ON FUNCTION public.marketpro_owner_activate_staff_readonly(
  uuid,uuid,uuid
) FROM PUBLIC,anon;
GRANT EXECUTE ON FUNCTION public.marketpro_owner_activate_staff_readonly(
  uuid,uuid,uuid
) TO authenticated;

CREATE OR REPLACE FUNCTION public.marketpro_owner_revoke_staff_device(
   p_workspace_id uuid,p_owner_device_id uuid,p_staff_user_id uuid
) RETURNS jsonb
LANGUAGE plpgsql SECURITY DEFINER SET search_path=''
AS $revoke$
DECLARE
   v_owner uuid;
   v_changed integer;
BEGIN
   v_owner := marketpro_private.require_enrolled_owner(
      p_workspace_id,p_owner_device_id
   );
   IF p_staff_user_id IS NULL OR p_staff_user_id=v_owner THEN
      RAISE EXCEPTION 'Cannot revoke owner device via staff endpoint'
        USING ERRCODE='42501';
   END IF;
   PERFORM pg_catalog.pg_advisory_xact_lock(
      pg_catalog.hashtextextended(p_workspace_id::text,0)
   );
   IF NOT EXISTS (
     SELECT 1 FROM public.workspace_members
     WHERE workspace_id=p_workspace_id AND user_id=p_staff_user_id
       AND role='purchasing_staff'
   ) THEN
     RAISE EXCEPTION 'Target is not an enrolled purchasing staff account'
       USING ERRCODE='42501';
   END IF;
   UPDATE public.registered_devices SET enabled=false,can_write=false
   WHERE workspace_id=p_workspace_id AND user_id=p_staff_user_id
     AND (enabled OR can_write);
   GET DIAGNOSTICS v_changed=ROW_COUNT;
   RETURN pg_catalog.jsonb_build_object('revoked_devices',v_changed);
END;
$revoke$;

REVOKE ALL ON FUNCTION public.marketpro_owner_revoke_staff_device(
  uuid,uuid,uuid
) FROM PUBLIC,anon;
GRANT EXECUTE ON FUNCTION public.marketpro_owner_revoke_staff_device(
  uuid,uuid,uuid
) TO authenticated;

-- TOP-LEVEL COMMIT moved to one enclosing transaction


-- SOURCE: supabase/proposals/v362_owner_approved_catalog_links_REVIEW_ONLY.sql
-- MARKET PRO V3.6.2 — OWNER-APPROVED PER-DEVICE CATALOG LINK (REVIEW ONLY)
-- THIS IS NOT A PRODUCTION MIGRATION. NO LIVE CLOUD DDL OR RECORDS TO CHANGE.
-- Designed for coordinated cutover after v359 member/device enrollment + RLS.
-- TOP-LEVEL BEGIN moved to one enclosing transaction
CREATE SCHEMA IF NOT EXISTS marketpro_private;

CREATE TABLE IF NOT EXISTS marketpro_private.owner_catalog_link_approvals (
    approval_id uuid PRIMARY KEY DEFAULT extensions.gen_random_uuid(),
    workspace_id uuid NOT NULL,
    target_device_id uuid NOT NULL,
    entity_type text NOT NULL CHECK(entity_type IN ('category','item')),
    local_key text NOT NULL CHECK(length(local_key) BETWEEN 1 AND 200),
    cloud_id uuid NOT NULL,
    cloud_revision bigint NOT NULL CHECK(cloud_revision>0),
    local_fingerprint_sha256 text NOT NULL
      CHECK(local_fingerprint_sha256 ~ '^[0-9a-f]{64}$'),
    approved_by uuid NOT NULL,
    approved_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    expires_at timestamptz NOT NULL DEFAULT (clock_timestamp()+interval '24 hours'),
    UNIQUE(workspace_id,target_device_id,entity_type,local_key),
    UNIQUE(workspace_id,target_device_id,entity_type,cloud_id)
);
REVOKE ALL ON marketpro_private.owner_catalog_link_approvals
    FROM PUBLIC,anon,authenticated;

-- Owner must VIEW candidate details on a separate secure approval screen first.
-- The fingerprint is a digest of the actual staff-phone Item record, and MUST
-- be compared again on the device before binding any Room local item ID.
CREATE OR REPLACE FUNCTION public.marketpro_owner_approve_catalog_link(
    p_workspace_id uuid,
    p_owner_device_id uuid,
    p_target_device_id uuid,
    p_entity_type text,
    p_local_key text,
    p_cloud_id uuid,
    p_cloud_revision bigint,
    p_local_fingerprint text
) RETURNS jsonb
LANGUAGE plpgsql SECURITY DEFINER SET search_path=''
AS $fn$
DECLARE
    v_actor uuid := auth.uid();
    v_actual_revision bigint;
    v_id uuid;
BEGIN
    IF v_actor IS NULL OR
       coalesce((auth.jwt()->>'is_anonymous')::boolean,false) OR
       p_workspace_id IS NULL OR p_owner_device_id IS NULL OR
       p_target_device_id IS NULL OR p_cloud_id IS NULL OR
       p_entity_type NOT IN ('category','item') OR
       p_entity_type IS NULL OR p_local_key IS NULL OR
       p_local_fingerprint IS NULL OR p_cloud_revision IS NULL OR
       p_cloud_revision<1 OR
       length(p_local_key)>200 OR length(p_local_key)<1 OR
       p_local_fingerprint !~ '^[0-9a-f]{64}$' THEN
        RAISE EXCEPTION 'Invalid catalog link request or anonymous actor'
          USING ERRCODE='42501';
    END IF;

    -- Only enrolled OWNER with an active writing phone authorizes mapping.
    IF NOT EXISTS (
        SELECT 1 FROM public.workspace_members m
        JOIN public.registered_devices d
          ON d.workspace_id=m.workspace_id AND d.user_id=m.user_id
        WHERE m.workspace_id=p_workspace_id AND m.user_id=v_actor
          AND m.role='owner' AND d.id=p_owner_device_id
          AND d.enabled=true AND d.can_write=true
    ) THEN
        RAISE EXCEPTION 'Owner enrollment required' USING ERRCODE='42501';
    END IF;

    -- Approval is to one specific enrolled active PHONE, never only a username.
    IF NOT EXISTS (
        SELECT 1 FROM public.registered_devices d
        JOIN public.workspace_members m
          ON m.workspace_id=d.workspace_id AND m.user_id=d.user_id
        WHERE d.workspace_id=p_workspace_id AND d.id=p_target_device_id
          AND d.enabled=true AND m.role IN ('owner','purchasing_staff')
    ) THEN
        RAISE EXCEPTION 'Target phone must be enrolled and active'
          USING ERRCODE='42501';
    END IF;

    IF p_entity_type='item' THEN
      IF p_local_key !~ '^[1-9][0-9]{0,17}$' THEN
          RAISE EXCEPTION 'Item Room ID must be canonical positive Long'
            USING ERRCODE='22023';
      END IF;
      SELECT revision INTO v_actual_revision
      FROM public.items
      WHERE id=p_cloud_id AND workspace_id=p_workspace_id
        AND deleted_at IS NULL;
    ELSE
      SELECT revision INTO v_actual_revision
      FROM public.categories
      WHERE id=p_cloud_id AND workspace_id=p_workspace_id
        AND deleted_at IS NULL;
    END IF;
    IF v_actual_revision IS NULL OR v_actual_revision<>p_cloud_revision THEN
        RAISE EXCEPTION 'Cloud catalog revision changed / wrong workspace'
          USING ERRCODE='42501';
    END IF;

    PERFORM pg_catalog.pg_advisory_xact_lock(
       pg_catalog.hashtextextended(p_workspace_id::text,0)
    );
    -- Fail on name/UUID collisions; an old owner approval cannot silently
    -- be repointed to a different canonical Item UUID or local Room item.
    INSERT INTO marketpro_private.owner_catalog_link_approvals(
        workspace_id,target_device_id,entity_type,local_key,cloud_id,
        cloud_revision,local_fingerprint_sha256,approved_by
    ) VALUES (
        p_workspace_id,p_target_device_id,p_entity_type,p_local_key,
        p_cloud_id,p_cloud_revision,p_local_fingerprint,v_actor
    ) RETURNING approval_id INTO v_id;
    RETURN pg_catalog.jsonb_build_object(
       'approval_id',v_id,
       'cloud_revision',p_cloud_revision,
       'target_device_id',p_target_device_id,
       'status','owner_approved_pending_device_validation'
    );
END;
$fn$;

REVOKE ALL ON FUNCTION public.marketpro_owner_approve_catalog_link(
    uuid,uuid,uuid,text,text,uuid,bigint,text
) FROM PUBLIC,anon;
GRANT EXECUTE ON FUNCTION public.marketpro_owner_approve_catalog_link(
    uuid,uuid,uuid,text,text,uuid,bigint,text
) TO authenticated;

-- Device-bound read of an approved decision; no broad SELECT on private table.
-- It does not authorize a Staff write or elevate a role.
CREATE OR REPLACE FUNCTION public.marketpro_device_catalog_link_approval(
    p_workspace_id uuid,
    p_device_id uuid,
    p_entity_type text,
    p_cloud_id uuid
) RETURNS jsonb
LANGUAGE plpgsql SECURITY DEFINER SET search_path=''
AS $fn$
DECLARE
    v_actor uuid := auth.uid();
    v_approval marketpro_private.owner_catalog_link_approvals%ROWTYPE;
BEGIN
    IF v_actor IS NULL OR
       coalesce((auth.jwt()->>'is_anonymous')::boolean,false) OR
       p_workspace_id IS NULL OR p_device_id IS NULL OR
       p_cloud_id IS NULL OR p_entity_type NOT IN ('category','item') OR
       p_entity_type IS NULL THEN
        RAISE EXCEPTION 'Verified user and device required' USING ERRCODE='42501';
    END IF;
    IF NOT EXISTS (
      SELECT 1 FROM public.registered_devices d
      JOIN public.workspace_members m
        ON m.workspace_id=d.workspace_id AND m.user_id=d.user_id
      WHERE d.id=p_device_id AND d.workspace_id=p_workspace_id
        AND d.user_id=v_actor AND d.enabled=true
        AND m.role IN ('owner','purchasing_staff')
    ) THEN
      RAISE EXCEPTION 'This device is not enrolled for this account'
        USING ERRCODE='42501';
    END IF;
    SELECT * INTO v_approval
    FROM marketpro_private.owner_catalog_link_approvals
    WHERE workspace_id=p_workspace_id AND target_device_id=p_device_id
      AND entity_type=p_entity_type AND cloud_id=p_cloud_id
      AND expires_at>clock_timestamp();
    IF NOT FOUND THEN
       RAISE EXCEPTION 'No current owner approval for that phone/catalog'
         USING ERRCODE='42501';
    END IF;
    RETURN pg_catalog.jsonb_build_object(
       'approval_id',v_approval.approval_id,
       'workspace_id',v_approval.workspace_id,
       'target_device_id',v_approval.target_device_id,
       'entity_type',v_approval.entity_type,
       'local_key',v_approval.local_key,
       'cloud_id',v_approval.cloud_id,
       'cloud_revision',v_approval.cloud_revision,
       'local_fingerprint_sha256',v_approval.local_fingerprint_sha256,
       'approved_by',v_approval.approved_by,
       'approved_at',v_approval.approved_at,
       'expires_at',v_approval.expires_at
    );
END;
$fn$;

REVOKE ALL ON FUNCTION public.marketpro_device_catalog_link_approval(
    uuid,uuid,text,uuid
) FROM PUBLIC,anon;
GRANT EXECUTE ON FUNCTION public.marketpro_device_catalog_link_approval(
    uuid,uuid,text,uuid
) TO authenticated;
-- TOP-LEVEL COMMIT moved to one enclosing transaction


-- SOURCE: supabase/proposals/v364_staff_candidate_owner_review_REVIEW_ONLY.sql
-- MARKET PRO V3.6.4 — STAFF CATALOG CANDIDATE INBOX (REVIEW ONLY)
-- Depends on v362 owner approvals. NEVER execute on live Supabase yet.
-- No live catalog/financial writes, no staff activation or RLS changes.
-- TOP-LEVEL BEGIN moved to one enclosing transaction
CREATE SCHEMA IF NOT EXISTS marketpro_private;
CREATE TABLE marketpro_private.staff_catalog_review_candidates (
    candidate_id uuid PRIMARY KEY DEFAULT extensions.gen_random_uuid(),
    workspace_id uuid NOT NULL,
    device_id uuid NOT NULL,
    submitted_by uuid NOT NULL,
    entity_type text NOT NULL CHECK (entity_type IN ('category','item')),
    cloud_id uuid NOT NULL,
    cloud_revision bigint NOT NULL CHECK(cloud_revision>0),
    cloud_category_id uuid,
    local_key text NOT NULL CHECK(length(local_key) BETWEEN 1 AND 200),
    local_name text NOT NULL CHECK(length(local_name) BETWEEN 1 AND 250),
    local_category text,
    local_is_active boolean,
    local_fingerprint_sha256 text NOT NULL CHECK (
      local_fingerprint_sha256 ~ '^[0-9a-f]{64}$'),
    status text NOT NULL DEFAULT 'pending'
      CHECK(status IN ('pending','approved')),
    submitted_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    expires_at timestamptz NOT NULL DEFAULT(clock_timestamp()+interval '24 hours'),
    reviewed_by uuid,
    reviewed_at timestamptz,
    UNIQUE(workspace_id,device_id,entity_type,local_key),
    UNIQUE(workspace_id,device_id,entity_type,cloud_id),
    CHECK(
     (entity_type='item' AND cloud_category_id IS NOT NULL
       AND local_category IS NOT NULL AND local_is_active IS NOT NULL) OR
     (entity_type='category' AND cloud_category_id IS NULL
       AND local_category IS NULL AND local_is_active IS NULL)
    )
);
REVOKE ALL ON marketpro_private.staff_catalog_review_candidates
  FROM PUBLIC,anon,authenticated;

CREATE OR REPLACE FUNCTION public.marketpro_staff_submit_catalog_candidate(
 p_workspace_id uuid, p_device_id uuid, p_entity_type text,
 p_cloud_id uuid, p_cloud_revision bigint,
 p_local_key text, p_local_name text, p_local_category text,
 p_local_is_active boolean, p_local_fingerprint text
) RETURNS jsonb
LANGUAGE plpgsql SECURITY DEFINER SET search_path=''
AS $fn$
DECLARE
 v_user uuid := auth.uid();
 v_actual_revision bigint;
 v_cloud_category_id uuid;
 v_candidate_id uuid;
BEGIN
 IF v_user IS NULL OR coalesce((auth.jwt()->>'is_anonymous')::boolean,false) THEN
   RAISE EXCEPTION 'Authenticated staff JWT required' USING ERRCODE='42501';
 END IF;
 IF p_workspace_id IS NULL OR p_device_id IS NULL
    OR p_cloud_id IS NULL OR p_entity_type NOT IN ('category','item')
    OR p_entity_type IS NULL OR p_cloud_revision IS NULL OR p_cloud_revision<1
    OR p_local_key IS NULL OR length(p_local_key) NOT BETWEEN 1 AND 200
    OR p_local_name IS NULL OR length(btrim(p_local_name)) NOT BETWEEN 1 AND 250
    OR p_local_name <> btrim(p_local_name)
    OR p_local_fingerprint IS NULL OR
       p_local_fingerprint !~ '^[0-9a-f]{64}$'
    OR (p_entity_type='item' AND (
      p_local_key !~ '^[1-9][0-9]{0,17}$'
      OR p_local_category IS NULL
      OR length(btrim(p_local_category)) NOT BETWEEN 1 AND 250
      OR p_local_category <> btrim(p_local_category)
      OR p_local_is_active IS NULL))
    OR (p_entity_type='category' AND (
      p_local_key<>pg_catalog.lower(p_local_name) OR p_local_category IS NOT NULL
      OR p_local_is_active IS NOT NULL)) THEN
   RAISE EXCEPTION 'Invalid catalog candidate data' USING ERRCODE='22023';
 END IF;

 -- Approved device must be bound to the CURRENT JWT user and staff role.
 IF NOT EXISTS (
   SELECT 1 FROM public.registered_devices d
   JOIN public.workspace_members m
     ON m.workspace_id=d.workspace_id AND m.user_id=d.user_id
   WHERE d.workspace_id=p_workspace_id AND d.id=p_device_id
     AND d.user_id=v_user AND d.enabled
     AND m.role='purchasing_staff'
 ) THEN
   RAISE EXCEPTION 'Staff phone not enrolled for this login'
     USING ERRCODE='42501';
 END IF;

 -- Cloud identity must already exist in exactly this workspace.
 IF p_entity_type='item' THEN
   SELECT revision,category_id INTO v_actual_revision,v_cloud_category_id
     FROM public.items
    WHERE id=p_cloud_id AND workspace_id=p_workspace_id
      AND deleted_at IS NULL;
 ELSE
   SELECT revision INTO v_actual_revision
     FROM public.categories
    WHERE id=p_cloud_id AND workspace_id=p_workspace_id
      AND deleted_at IS NULL;
 END IF;
 IF v_actual_revision IS NULL OR v_actual_revision<>p_cloud_revision THEN
    RAISE EXCEPTION 'Remote catalog revision changed'
      USING ERRCODE='42501';
 END IF;

 PERFORM pg_catalog.pg_advisory_xact_lock(
   pg_catalog.hashtextextended(p_workspace_id::text,0)
 );
 INSERT INTO marketpro_private.staff_catalog_review_candidates (
  workspace_id,device_id,submitted_by,entity_type,cloud_id,
  cloud_revision,cloud_category_id,local_key,local_name,local_category,
  local_is_active,local_fingerprint_sha256
 ) VALUES (
  p_workspace_id,p_device_id,v_user,p_entity_type,p_cloud_id,
  p_cloud_revision,v_cloud_category_id,p_local_key,p_local_name,
  p_local_category,p_local_is_active,p_local_fingerprint
 )
 ON CONFLICT (workspace_id,device_id,entity_type,cloud_id)
 DO UPDATE SET
  candidate_id=extensions.gen_random_uuid(),
  cloud_revision=EXCLUDED.cloud_revision,
  cloud_category_id=EXCLUDED.cloud_category_id,
  local_name=EXCLUDED.local_name,
  local_category=EXCLUDED.local_category,
  local_is_active=EXCLUDED.local_is_active,
  local_fingerprint_sha256=EXCLUDED.local_fingerprint_sha256,
  submitted_at=clock_timestamp(),
  expires_at=clock_timestamp()+interval '24 hours'
 WHERE marketpro_private.staff_catalog_review_candidates.status='pending'
   AND marketpro_private.staff_catalog_review_candidates.local_key=EXCLUDED.local_key
   AND marketpro_private.staff_catalog_review_candidates.submitted_by=v_user
 RETURNING candidate_id INTO v_candidate_id;
 IF v_candidate_id IS NULL THEN
   RAISE EXCEPTION 'Approved or changed candidate cannot be silently replaced'
     USING ERRCODE='23505';
 END IF;
 RETURN pg_catalog.jsonb_build_object(
  'candidate_id',v_candidate_id,
  'status','awaiting_owner_review',
  'cloud_revision',p_cloud_revision
 );
END;
$fn$;
REVOKE ALL ON FUNCTION public.marketpro_staff_submit_catalog_candidate(
 uuid,uuid,text,uuid,bigint,text,text,text,boolean,text
) FROM PUBLIC,anon;
GRANT EXECUTE ON FUNCTION public.marketpro_staff_submit_catalog_candidate(
 uuid,uuid,text,uuid,bigint,text,text,text,boolean,text
) TO authenticated;

CREATE OR REPLACE FUNCTION public.marketpro_owner_list_catalog_candidates(
 p_workspace_id uuid,p_owner_device_id uuid,p_after_id uuid DEFAULT NULL,
 p_page_size integer DEFAULT 30
) RETURNS jsonb LANGUAGE plpgsql SECURITY DEFINER SET search_path=''
AS $fn$
DECLARE
 v_actor uuid := auth.uid();
 v_rows jsonb;
 v_next uuid;
BEGIN
 IF v_actor IS NULL OR coalesce((auth.jwt()->>'is_anonymous')::boolean,false)
    OR p_workspace_id IS NULL OR p_owner_device_id IS NULL
    OR p_page_size IS NULL OR p_page_size NOT BETWEEN 1 AND 50 THEN
   RAISE EXCEPTION 'Invalid Owner review request' USING ERRCODE='42501';
 END IF;
 IF NOT EXISTS (
  SELECT 1 FROM public.workspace_members m
  JOIN public.registered_devices d
    ON d.workspace_id=m.workspace_id AND d.user_id=m.user_id
  WHERE m.workspace_id=p_workspace_id AND m.user_id=v_actor
    AND m.role='owner' AND d.id=p_owner_device_id
    AND d.enabled AND d.can_write
 ) THEN
   RAISE EXCEPTION 'Owner enrolled device required' USING ERRCODE='42501';
 END IF;
 SELECT coalesce(pg_catalog.jsonb_agg(to_jsonb(c) ORDER BY c.candidate_id),
                 '[]'::jsonb)
 INTO v_rows
 FROM (
  SELECT c.candidate_id,c.workspace_id,c.device_id,c.submitted_by,
         c.entity_type,c.cloud_id,c.cloud_revision,c.cloud_category_id,
         c.local_key,c.local_name,c.local_category,c.local_is_active,
         c.local_fingerprint_sha256,c.submitted_at,c.expires_at,c.status
    FROM marketpro_private.staff_catalog_review_candidates c
    JOIN public.registered_devices d ON d.id=c.device_id
      AND d.workspace_id=c.workspace_id AND d.user_id=c.submitted_by
    JOIN public.workspace_members m ON m.user_id=c.submitted_by
      AND m.workspace_id=c.workspace_id
   WHERE c.workspace_id=p_workspace_id
     AND c.status='pending' AND c.expires_at>clock_timestamp()
     AND d.enabled=true AND m.role='purchasing_staff'
     AND (p_after_id IS NULL OR c.candidate_id>p_after_id)
   ORDER BY c.candidate_id LIMIT p_page_size
 ) c;
 v_next := (v_rows->(pg_catalog.jsonb_array_length(v_rows)-1)->>'candidate_id')::uuid;
 RETURN pg_catalog.jsonb_build_object(
    'candidates',v_rows,'next_id',v_next,
    'requires_explicit_owner_approval',true
 );
END;
$fn$;
REVOKE ALL ON FUNCTION public.marketpro_owner_list_catalog_candidates(
 uuid,uuid,uuid,integer
) FROM PUBLIC,anon;
GRANT EXECUTE ON FUNCTION public.marketpro_owner_list_catalog_candidates(
 uuid,uuid,uuid,integer
) TO authenticated;

-- Only THIS reviewed-candidate path should authorize a mapping.
-- Revoke old v362 arbitrary caller-supplied local-key/fingerprint endpoint.
REVOKE ALL ON FUNCTION public.marketpro_owner_approve_catalog_link(
 uuid,uuid,uuid,text,text,uuid,bigint,text
) FROM PUBLIC,anon,authenticated;

CREATE OR REPLACE FUNCTION public.marketpro_owner_approve_submitted_catalog(
 p_workspace_id uuid,p_owner_device_id uuid,p_candidate_id uuid
) RETURNS jsonb LANGUAGE plpgsql SECURITY DEFINER SET search_path=''
AS $fn$
DECLARE
 v_actor uuid := auth.uid();
 v_c marketpro_private.staff_catalog_review_candidates%ROWTYPE;
 v_revision bigint;
 v_cloud_category uuid;
 v_approval_id uuid;
BEGIN
 IF v_actor IS NULL OR coalesce((auth.jwt()->>'is_anonymous')::boolean,false)
    OR p_workspace_id IS NULL OR p_owner_device_id IS NULL
    OR p_candidate_id IS NULL THEN
  RAISE EXCEPTION 'Owner session required' USING ERRCODE='42501';
 END IF;
 IF NOT EXISTS (
    SELECT 1 FROM public.workspace_members m
    JOIN public.registered_devices d
      ON d.workspace_id=m.workspace_id AND d.user_id=m.user_id
    WHERE m.workspace_id=p_workspace_id AND m.user_id=v_actor
      AND m.role='owner' AND d.id=p_owner_device_id
      AND d.enabled AND d.can_write
 ) THEN
  RAISE EXCEPTION 'Owner device denied' USING ERRCODE='42501';
 END IF;
 PERFORM pg_catalog.pg_advisory_xact_lock(
   pg_catalog.hashtextextended(p_workspace_id::text,0)
 );
 SELECT * INTO v_c
 FROM marketpro_private.staff_catalog_review_candidates
 WHERE workspace_id=p_workspace_id AND candidate_id=p_candidate_id
 FOR UPDATE;
 IF NOT FOUND OR v_c.status<>'pending' OR
    v_c.expires_at<=clock_timestamp() THEN
  RAISE EXCEPTION 'Candidate missing, expired or consumed'
    USING ERRCODE='42501';
 END IF;
 IF NOT EXISTS(
   SELECT 1 FROM public.registered_devices d
   JOIN public.workspace_members m ON m.workspace_id=d.workspace_id
     AND m.user_id=d.user_id
   WHERE d.workspace_id=p_workspace_id AND d.id=v_c.device_id
     AND d.user_id=v_c.submitted_by AND d.enabled
     AND m.role='purchasing_staff'
 ) THEN
  RAISE EXCEPTION 'Staff device revoked or role changed'
    USING ERRCODE='42501';
 END IF;
 IF v_c.entity_type='item' THEN
   SELECT revision,category_id INTO v_revision,v_cloud_category
   FROM public.items WHERE id=v_c.cloud_id
     AND workspace_id=p_workspace_id AND deleted_at IS NULL;
   IF v_cloud_category IS DISTINCT FROM v_c.cloud_category_id THEN
     RAISE EXCEPTION 'Cloud category changed' USING ERRCODE='42501';
   END IF;
 ELSE
   SELECT revision INTO v_revision
   FROM public.categories WHERE id=v_c.cloud_id
     AND workspace_id=p_workspace_id AND deleted_at IS NULL;
 END IF;
 IF v_revision IS NULL OR v_revision<>v_c.cloud_revision THEN
   RAISE EXCEPTION 'Cloud revision changed after staff snapshot'
     USING ERRCODE='42501';
 END IF;
 INSERT INTO marketpro_private.owner_catalog_link_approvals(
   workspace_id,target_device_id,entity_type,local_key,cloud_id,
   cloud_revision,local_fingerprint_sha256,approved_by
 ) VALUES (
   p_workspace_id,v_c.device_id,v_c.entity_type,v_c.local_key,
   v_c.cloud_id,v_c.cloud_revision,v_c.local_fingerprint_sha256,v_actor
 ) RETURNING approval_id INTO v_approval_id;
 UPDATE marketpro_private.staff_catalog_review_candidates
 SET status='approved',reviewed_by=v_actor,reviewed_at=clock_timestamp()
 WHERE candidate_id=v_c.candidate_id;
 RETURN pg_catalog.jsonb_build_object(
   'approved',true,'approval_id',v_approval_id,
   'candidate_id',p_candidate_id,'target_device_id',v_c.device_id,
   'requires_target_device_recheck',true
 );
END;
$fn$;
REVOKE ALL ON FUNCTION public.marketpro_owner_approve_submitted_catalog(
 uuid,uuid,uuid
) FROM PUBLIC,anon;
GRANT EXECUTE ON FUNCTION public.marketpro_owner_approve_submitted_catalog(
 uuid,uuid,uuid
) TO authenticated;
-- TOP-LEVEL COMMIT moved to one enclosing transaction


-- SOURCE: supabase/proposals/v367_staff_approved_catalog_fetch_REVIEW_ONLY.sql
-- V3.6.7 REVIEW ONLY. Prerequisites: v362 and v364 proposals.
-- DO NOT RUN AGAINST LIVE SUPABASE. This replaces the earlier permissive
-- device approval lookup with an APPROVED-CANDIDATE and current-revision gate.
-- TOP-LEVEL BEGIN moved to one enclosing transaction

CREATE OR REPLACE FUNCTION public.marketpro_device_catalog_link_approval(
 p_workspace_id uuid, p_device_id uuid, p_entity_type text, p_cloud_id uuid
) RETURNS jsonb
LANGUAGE plpgsql SECURITY DEFINER SET search_path=''
AS $fn$
DECLARE
 v_actor uuid := auth.uid();
 v_approval marketpro_private.owner_catalog_link_approvals%ROWTYPE;
 v_candidate marketpro_private.staff_catalog_review_candidates%ROWTYPE;
 v_server_now timestamptz := clock_timestamp();
 v_revision bigint;
 v_category uuid;
BEGIN
 IF v_actor IS NULL OR
    coalesce((auth.jwt()->>'is_anonymous')::boolean,false) OR
    p_workspace_id IS NULL OR p_device_id IS NULL OR p_cloud_id IS NULL OR
    p_entity_type NOT IN ('category','item') OR p_entity_type IS NULL THEN
   RAISE EXCEPTION 'Verified enrolled staff device required'
     USING ERRCODE='42501';
 END IF;

 -- Reading permission requires the CURRENT staff JWT and an enrolled, enabled
 -- phone. Knowing a device UUID alone does not permit this RPC.
 IF NOT EXISTS (
   SELECT 1 FROM public.registered_devices d
   JOIN public.workspace_members m ON m.workspace_id=d.workspace_id
      AND m.user_id=d.user_id
   WHERE d.workspace_id=p_workspace_id AND d.id=p_device_id
     AND d.user_id=v_actor AND d.enabled
     AND m.role='purchasing_staff'
 ) THEN
   RAISE EXCEPTION 'Staff device not enrolled or was revoked'
     USING ERRCODE='42501';
 END IF;

 SELECT * INTO v_approval
 FROM marketpro_private.owner_catalog_link_approvals a
 WHERE a.workspace_id=p_workspace_id AND a.target_device_id=p_device_id
   AND a.entity_type=p_entity_type AND a.cloud_id=p_cloud_id
   AND a.expires_at > v_server_now
 FOR SHARE;
 IF NOT FOUND THEN
   RAISE EXCEPTION 'Owner approval missing or expired'
     USING ERRCODE='42501';
 END IF;

 -- Reject arbitrary old v362 approvals. ONLY v364 staff-submitted candidates
 -- that the verified Owner actually reviewed are eligible.
 SELECT * INTO v_candidate
 FROM marketpro_private.staff_catalog_review_candidates c
 WHERE c.workspace_id=p_workspace_id
   AND c.device_id=p_device_id
   AND c.submitted_by=v_actor
   AND c.entity_type=p_entity_type
   AND c.cloud_id=p_cloud_id
   AND c.cloud_revision=v_approval.cloud_revision
   AND c.local_key=v_approval.local_key
   AND c.local_fingerprint_sha256=v_approval.local_fingerprint_sha256
   AND c.status='approved'
   AND c.reviewed_by=v_approval.approved_by
   AND c.reviewed_at IS NOT NULL
   AND c.reviewed_at <= v_server_now
   AND c.submitted_at <= c.reviewed_at
 LIMIT 1;
 IF NOT FOUND THEN
   RAISE EXCEPTION 'Not backed by a current Owner-reviewed Staff submission'
     USING ERRCODE='42501';
 END IF;

 -- Reject approval after Owner role/device revocation; Owner must remain an
 -- active enrolled owner for this workspace at acceptance.
 IF NOT EXISTS (
   SELECT 1 FROM public.workspace_members m
   JOIN public.registered_devices d ON d.workspace_id=m.workspace_id
     AND d.user_id=m.user_id
   WHERE m.workspace_id=p_workspace_id
     AND m.user_id=v_approval.approved_by AND m.role='owner'
     AND d.enabled AND d.can_write
 ) THEN
   RAISE EXCEPTION 'Reviewing Owner access no longer valid'
     USING ERRCODE='42501';
 END IF;

 IF p_entity_type='item' THEN
   SELECT revision,category_id INTO v_revision,v_category
   FROM public.items
   WHERE id=p_cloud_id AND workspace_id=p_workspace_id AND deleted_at IS NULL;
   IF v_category IS DISTINCT FROM v_candidate.cloud_category_id THEN
     RAISE EXCEPTION 'Approved item category has since changed'
       USING ERRCODE='42501';
   END IF;
 ELSE
   SELECT revision INTO v_revision
   FROM public.categories
   WHERE id=p_cloud_id AND workspace_id=p_workspace_id AND deleted_at IS NULL;
 END IF;
 IF v_revision IS NULL OR v_revision<>v_approval.cloud_revision THEN
   RAISE EXCEPTION 'Canonical Cloud catalog revision no longer matches'
     USING ERRCODE='42501';
 END IF;

 RETURN pg_catalog.jsonb_build_object(
   'approval_id',v_approval.approval_id,
   'workspace_id',v_approval.workspace_id,
   'target_device_id',v_approval.target_device_id,
   'entity_type',v_approval.entity_type,
   'local_key',v_approval.local_key,
   'cloud_id',v_approval.cloud_id,
   'cloud_revision',v_approval.cloud_revision,
   'local_fingerprint_sha256',v_approval.local_fingerprint_sha256,
   'approved_by',v_approval.approved_by,
   'approved_at',v_approval.approved_at,
   'expires_at',v_approval.expires_at,
   'server_now',v_server_now,
   'requires_current_room_recheck',true,
   'status','owner_approved_for_device_recheck'
 );
END;
$fn$;

REVOKE ALL ON FUNCTION public.marketpro_device_catalog_link_approval(
 uuid,uuid,text,uuid
) FROM PUBLIC,anon;
GRANT EXECUTE ON FUNCTION public.marketpro_device_catalog_link_approval(
 uuid,uuid,text,uuid
) TO authenticated;
-- TOP-LEVEL COMMIT moved to one enclosing transaction


-- SOURCE: supabase/proposals/v368_owner_replace_team_phone_REVIEW_ONLY.sql
-- MARKET PRO V3.6.8 -- OWNER REVOKE / REPLACE STAFF DEVICE
-- REVIEW ONLY. DO NOT DEPLOY TO PRODUCTION. Requires v358 + v359 secure RLS,
-- Auth, enrollment and device-bound pull/write RPC review.
-- Old device denied immediately at DB transaction commit. Unsynced offline
-- entries on old handset CANNOT be recovered automatically.
-- TOP-LEVEL BEGIN moved to one enclosing transaction

CREATE TABLE IF NOT EXISTS marketpro_private.staff_device_transfer_audit (
    transfer_id uuid PRIMARY KEY DEFAULT extensions.gen_random_uuid(),
    workspace_id uuid NOT NULL,
    staff_user_id uuid NOT NULL,
    old_device_id uuid NOT NULL,
    new_device_id uuid,
    changed_by uuid NOT NULL,
    action text NOT NULL CHECK(action IN ('revoke','replace','activate')),
    changed_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    reason text NOT NULL CHECK(length(reason) BETWEEN 1 AND 120)
);
REVOKE ALL ON marketpro_private.staff_device_transfer_audit
    FROM PUBLIC,anon,authenticated;

-- Can be called immediately if the old phone is lost, stolen or being retired
-- and the replacement phone has not been purchased yet.
CREATE OR REPLACE FUNCTION public.marketpro_owner_disable_team_phone(
 p_workspace_id uuid,p_owner_device_id uuid,p_staff_user_id uuid,
 p_old_device_id uuid
) RETURNS jsonb
LANGUAGE plpgsql SECURITY DEFINER SET search_path=''
AS $fn$
DECLARE
 v_owner uuid;
 v_changed integer;
BEGIN
 v_owner:=marketpro_private.require_enrolled_owner(p_workspace_id,p_owner_device_id);
 IF p_staff_user_id IS NULL OR p_old_device_id IS NULL OR
    p_staff_user_id=v_owner OR p_old_device_id=p_owner_device_id THEN
   RAISE EXCEPTION 'Refusing to disable the Owner or invalid staff phone'
     USING ERRCODE='42501';
 END IF;
 PERFORM pg_catalog.pg_advisory_xact_lock(
    pg_catalog.hashtextextended(p_workspace_id::text,0)
 );
 IF NOT EXISTS(
   SELECT 1 FROM public.workspace_members m
   JOIN public.registered_devices d ON d.workspace_id=m.workspace_id
      AND d.user_id=m.user_id
   WHERE m.workspace_id=p_workspace_id AND m.user_id=p_staff_user_id
     AND m.role='purchasing_staff' AND d.id=p_old_device_id
 ) THEN
    RAISE EXCEPTION 'Phone is not a member of this Staff account'
      USING ERRCODE='42501';
 END IF;

 UPDATE public.registered_devices
 SET enabled=false,can_write=false
 WHERE workspace_id=p_workspace_id AND user_id=p_staff_user_id
   AND id=p_old_device_id AND (enabled OR can_write);
 GET DIAGNOSTICS v_changed=ROW_COUNT;
 -- The current device may have an outstanding, accepted or invited token
 -- tied to a previously authorized phone. Invalidate it without deleting data.
 UPDATE marketpro_private.pending_team_enrollments
 SET status='revoked',revoked_at=clock_timestamp()
 WHERE workspace_id=p_workspace_id AND staff_user_id=p_staff_user_id
   AND intended_device_id=p_old_device_id AND status IN ('invited','accepted');
 IF v_changed=1 THEN
   INSERT INTO marketpro_private.staff_device_transfer_audit(
      workspace_id,staff_user_id,old_device_id,new_device_id,changed_by,
      action,reason
   ) VALUES (
      p_workspace_id,p_staff_user_id,p_old_device_id,NULL,v_owner,
      'revoke','Owner disabled a team device'
   );
 END IF;
 RETURN pg_catalog.jsonb_build_object(
    'old_device_disabled',true,
    'changed',v_changed=1,
    'old_device_id',p_old_device_id,
    'cloud_history_retained',true,
    'unsynced_old_phone_data_must_be_backed_up_before_retirement',true
 );
END;
$fn$;
REVOKE ALL ON FUNCTION public.marketpro_owner_disable_team_phone(
  uuid,uuid,uuid,uuid
) FROM PUBLIC,anon;
GRANT EXECUTE ON FUNCTION public.marketpro_owner_disable_team_phone(
  uuid,uuid,uuid,uuid
) TO authenticated;

-- One atomic owner action: disable old phone FIRST, issue a fresh short-lived
-- one-time invitation to one new device ID, keep same verified Staff user.
CREATE OR REPLACE FUNCTION public.marketpro_owner_replace_team_phone(
 p_workspace_id uuid,p_owner_device_id uuid,p_staff_user_id uuid,
 p_old_device_id uuid,p_new_device_id uuid,p_new_device_label text
) RETURNS jsonb
LANGUAGE plpgsql SECURITY DEFINER SET search_path=''
AS $fn$
DECLARE
 v_owner uuid;
 v_token text;
 v_expires timestamptz:=clock_timestamp()+interval '30 minutes';
 v_verified boolean;
 v_last_old uuid;
 v_last_new uuid;
BEGIN
 v_owner:=marketpro_private.require_enrolled_owner(
   p_workspace_id,p_owner_device_id
 );
 IF p_staff_user_id IS NULL OR p_old_device_id IS NULL OR
    p_new_device_id IS NULL OR p_staff_user_id=v_owner OR
    p_old_device_id=p_owner_device_id OR p_new_device_id=p_owner_device_id OR
    p_new_device_id=p_old_device_id OR
    p_new_device_label IS NULL OR
    length(btrim(p_new_device_label)) NOT BETWEEN 1 AND 100 THEN
   RAISE EXCEPTION 'Invalid replacement target or device label'
     USING ERRCODE='22023';
 END IF;
 PERFORM pg_catalog.pg_advisory_xact_lock(
    pg_catalog.hashtextextended(p_workspace_id::text,0)
 );
 SELECT email_confirmed_at IS NOT NULL INTO v_verified
 FROM auth.users WHERE id=p_staff_user_id;
 IF v_verified IS DISTINCT FROM true THEN
   RAISE EXCEPTION 'Staff account must have verified email'
     USING ERRCODE='42501';
 END IF;
 IF NOT EXISTS(
    SELECT 1 FROM public.workspace_members m
    JOIN public.registered_devices d
      ON d.workspace_id=m.workspace_id AND d.user_id=m.user_id
    WHERE m.workspace_id=p_workspace_id AND m.user_id=p_staff_user_id
      AND m.role='purchasing_staff' AND d.id=p_old_device_id
 ) OR EXISTS (
    SELECT 1 FROM public.registered_devices
    WHERE id=p_new_device_id
 ) THEN
   RAISE EXCEPTION 'Staff/old phone mismatch or new device identity already used'
     USING ERRCODE='42501';
 END IF;
 -- Only the most recently replaced pair is eligible for reissue.
 -- Without this guard a twice-retired UUID could be passed when no staff
 -- device is currently enabled, bypassing the replacement chain.
 SELECT a.old_device_id,a.new_device_id INTO v_last_old,v_last_new
 FROM marketpro_private.staff_device_transfer_audit a
 WHERE a.workspace_id=p_workspace_id AND a.staff_user_id=p_staff_user_id
   AND a.action='replace'
 ORDER BY a.changed_at DESC,a.transfer_id DESC LIMIT 1;
 IF v_last_old IS NOT NULL AND p_old_device_id NOT IN (v_last_old,v_last_new) THEN
   RAISE EXCEPTION 'Stale retired Staff device cannot start a new replacement'
     USING ERRCODE='42501';
 END IF;
 -- Cannot replace an unrelated active staff device using a stale old ID;
 -- if the old phone was disabled earlier, no new active device may exist.
 IF EXISTS (
    SELECT 1 FROM public.registered_devices
    WHERE workspace_id=p_workspace_id AND user_id=p_staff_user_id
      AND id<>p_old_device_id AND enabled
 ) THEN
   RAISE EXCEPTION 'Different current Staff phone must be disabled first'
     USING ERRCODE='42501';
 END IF;

 UPDATE public.registered_devices
 SET enabled=false,can_write=false
 WHERE workspace_id=p_workspace_id AND user_id=p_staff_user_id
   AND id=p_old_device_id;
 -- Purge previous pending token hash by replacing it, never return
 -- plaintext of an earlier invitation. Existing membership is NOT removed.
 v_token:=pg_catalog.encode(extensions.gen_random_bytes(32),'hex');
 INSERT INTO marketpro_private.pending_team_enrollments(
    workspace_id,staff_user_id,inviter_user_id,intended_device_id,
    device_label,token_sha256,expires_at,status,created_at,
    accepted_at,revoked_at,activated_at
 ) VALUES (
    p_workspace_id,p_staff_user_id,v_owner,p_new_device_id,
    btrim(p_new_device_label),
    extensions.digest(pg_catalog.convert_to(v_token,'UTF8'),'sha256'),
    v_expires,'invited',clock_timestamp(),NULL,NULL,NULL
 )
 ON CONFLICT (workspace_id,staff_user_id)
 DO UPDATE SET inviter_user_id=EXCLUDED.inviter_user_id,
   intended_device_id=EXCLUDED.intended_device_id,
   device_label=EXCLUDED.device_label,
   token_sha256=EXCLUDED.token_sha256,
   expires_at=EXCLUDED.expires_at,
   status='invited',created_at=clock_timestamp(),
   accepted_at=NULL,revoked_at=NULL,activated_at=NULL;

 INSERT INTO marketpro_private.staff_device_transfer_audit(
   workspace_id,staff_user_id,old_device_id,new_device_id,changed_by,
   action,reason
 ) VALUES (
   p_workspace_id,p_staff_user_id,p_old_device_id,p_new_device_id,v_owner,
   'replace','Owner issued replacement phone invitation'
 );
 RETURN pg_catalog.jsonb_build_object(
  'old_device_disabled',true,'replacement_pending',true,
  'new_device_not_active',true,'new_device_can_write',false,
  'new_device_id',p_new_device_id,'expires_at',v_expires,
  'one_time_code',v_token,
  'cloud_history_retained',true,
  'unsynced_old_phone_data_must_be_backed_up_before_retirement',true
 );
END;
$fn$;
REVOKE ALL ON FUNCTION public.marketpro_owner_replace_team_phone(
 uuid,uuid,uuid,uuid,uuid,text
) FROM PUBLIC,anon;
GRANT EXECUTE ON FUNCTION public.marketpro_owner_replace_team_phone(
 uuid,uuid,uuid,uuid,uuid,text
) TO authenticated;

-- Existing v359 activation intentionally rejects already-enrolled users.
-- Replacements use this separate, guarded READ-ONLY activation path. This
-- does not turn on staff writing to old v1 unsafe RPC.
CREATE OR REPLACE FUNCTION public.marketpro_owner_activate_replacement_readonly(
 p_workspace_id uuid,p_owner_device_id uuid,p_staff_user_id uuid
) RETURNS jsonb LANGUAGE plpgsql SECURITY DEFINER SET search_path=''
AS $fn$
DECLARE
 v_owner uuid;
 v_gate marketpro_private.team_rollout_gate%ROWTYPE;
 v_invite marketpro_private.pending_team_enrollments%ROWTYPE;
 v_verified boolean;
BEGIN
 v_owner:=marketpro_private.require_enrolled_owner(
     p_workspace_id,p_owner_device_id
 );
 IF p_staff_user_id IS NULL OR p_staff_user_id=v_owner THEN
   RAISE EXCEPTION 'Valid Staff account required' USING ERRCODE='42501';
 END IF;
 PERFORM pg_catalog.pg_advisory_xact_lock(
    pg_catalog.hashtextextended(p_workspace_id::text,0)
 );
 SELECT * INTO v_gate FROM marketpro_private.team_rollout_gate
 WHERE workspace_id=p_workspace_id FOR UPDATE;
 IF NOT FOUND OR NOT v_gate.staff_direct_rest_restricted
    OR NOT v_gate.team_pull_rpc_reviewed THEN
   RAISE EXCEPTION 'Staff RLS and device-bound pull not audited for deployment'
     USING ERRCODE='42501';
 END IF;
 IF NOT EXISTS (
    SELECT 1 FROM public.workspace_members
    WHERE workspace_id=p_workspace_id AND user_id=p_staff_user_id
      AND role='purchasing_staff'
 ) OR EXISTS (
    SELECT 1 FROM public.registered_devices
    WHERE workspace_id=p_workspace_id AND user_id=p_staff_user_id
      AND enabled
 ) THEN
   RAISE EXCEPTION 'Existing Staff role required with no other active phone'
     USING ERRCODE='42501';
 END IF;
 SELECT * INTO v_invite
 FROM marketpro_private.pending_team_enrollments
 WHERE workspace_id=p_workspace_id AND staff_user_id=p_staff_user_id
 FOR UPDATE;
 IF NOT FOUND OR v_invite.status<>'accepted'
    OR v_invite.activated_at IS NOT NULL
    OR v_invite.accepted_at IS NULL
    OR v_invite.accepted_at < clock_timestamp()-interval '24 hours'
    OR v_invite.expires_at <= v_invite.accepted_at
    OR EXISTS(SELECT 1 FROM public.registered_devices
       WHERE id=v_invite.intended_device_id)
    OR NOT EXISTS (
       SELECT 1 FROM marketpro_private.staff_device_transfer_audit a
       WHERE a.workspace_id=p_workspace_id
         AND a.staff_user_id=p_staff_user_id
         AND a.new_device_id=v_invite.intended_device_id
         AND a.action='replace'
    ) THEN
   RAISE EXCEPTION 'Replacement claim invalid, stale or used'
     USING ERRCODE='42501';
 END IF;
 SELECT email_confirmed_at IS NOT NULL INTO v_verified
 FROM auth.users WHERE id=p_staff_user_id;
 IF v_verified IS DISTINCT FROM true THEN
   RAISE EXCEPTION 'Staff email no longer verified'
     USING ERRCODE='42501';
 END IF;

 INSERT INTO public.registered_devices(
    id,workspace_id,user_id,device_label,enabled,can_write
 ) VALUES(
    v_invite.intended_device_id,p_workspace_id,p_staff_user_id,
    v_invite.device_label,true,false
 );
 UPDATE marketpro_private.pending_team_enrollments
 SET activated_at=clock_timestamp()
 WHERE workspace_id=p_workspace_id AND staff_user_id=p_staff_user_id;
 INSERT INTO marketpro_private.staff_device_transfer_audit(
   workspace_id,staff_user_id,old_device_id,new_device_id,changed_by,
   action,reason
 )
 SELECT p_workspace_id,p_staff_user_id,a.old_device_id,
   v_invite.intended_device_id,v_owner,'activate',
   'Owner enabled read-only replacement Staff phone'
 FROM marketpro_private.staff_device_transfer_audit a
 WHERE a.workspace_id=p_workspace_id
   AND a.staff_user_id=p_staff_user_id
   AND a.new_device_id=v_invite.intended_device_id
   AND a.action='replace'
 ORDER BY a.changed_at DESC LIMIT 1;
 RETURN pg_catalog.jsonb_build_object(
   'replacement_activated',true,
   'new_device_id',v_invite.intended_device_id,
   'new_device_enabled',true,'new_device_can_write',false,
   'old_phone_stays_disabled',true,'cloud_history_retained',true
 );
END;
$fn$;
REVOKE ALL ON FUNCTION public.marketpro_owner_activate_replacement_readonly(
 uuid,uuid,uuid
) FROM PUBLIC,anon;
GRANT EXECUTE ON FUNCTION public.marketpro_owner_activate_replacement_readonly(
 uuid,uuid,uuid
) TO authenticated;
-- TOP-LEVEL COMMIT moved to one enclosing transaction


-- SOURCE: supabase/proposals/v370_dual_channel_staff_writer_REVIEW_ONLY.sql
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
-- TOP-LEVEL BEGIN moved to one enclosing transaction
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
-- TOP-LEVEL COMMIT moved to one enclosing transaction

-- The following function overrides are required together. They update the
-- pre-replay authorization AND same-day helper; omission of any one fails audit.
-- TOP-LEVEL BEGIN moved to one enclosing transaction

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
-- TOP-LEVEL COMMIT moved to one enclosing transaction


-- SOURCE: supabase/proposals/v371_owner_staff_write_approval_REVIEW_ONLY.sql
-- MARKET PRO V3.7.1: OWNER-APPROVED STAFF WRITE LEASES — REVIEW ONLY
-- DO NOT APPLY TO LIVE SUPABASE. Full RLS/auth/session, Android, and
-- two-physical-phone cutover remain NOT deployed.
-- Requires reviewed v358 pending_team_enrollments, v359 rollout gates and
-- read-only activation, v368 replacement, v370 private staff_write_leases.
-- Owner legacy writer and registered_devices_one_enabled_writer_idx unchanged.
-- TOP-LEVEL BEGIN moved to one enclosing transaction
CREATE TABLE IF NOT EXISTS marketpro_private.staff_writer_activation_audit (
    id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    workspace_id uuid NOT NULL,
    staff_user_id uuid NOT NULL,
    old_device_id uuid,
    new_device_id uuid,
    owner_user_id uuid NOT NULL,
    action text NOT NULL CHECK (action IN ('grant','revoke')),
    changed_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
REVOKE ALL ON marketpro_private.staff_writer_activation_audit
  FROM PUBLIC,anon,authenticated;

-- Enforce the private ADMIN-controlled gate ON EVERY financial write, not
-- only when a grant is issued. Turning the gate off invalidates active
-- Staff leases immediately without disabling the legacy Owner phone.
CREATE OR REPLACE FUNCTION marketpro_private.require_dual_channel_writer(
    p_workspace_id uuid,p_device_id uuid
) RETURNS text
LANGUAGE plpgsql SECURITY DEFINER SET search_path=''
AS $guard$
DECLARE
    v_user uuid := auth.uid();
    v_role text;
BEGIN
    IF v_user IS NULL OR coalesce((auth.jwt()->>'is_anonymous')::boolean,false)
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
        OR
        (m.role='purchasing_staff' AND NOT d.can_write
          AND EXISTS (
            SELECT 1 FROM marketpro_private.staff_write_leases l
            JOIN marketpro_private.team_rollout_gate g
              ON g.workspace_id=l.workspace_id
            WHERE l.workspace_id=m.workspace_id
              AND l.staff_user_id=m.user_id AND l.device_id=d.id
              AND l.enabled AND g.staff_direct_rest_restricted
              AND g.team_pull_rpc_reviewed AND g.team_writes_reviewed
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

-- Only the verified, enrolled Owner on the actual Owner device may grant
-- one already ACTIVATED, accepted, verified Staff phone its v2 write lease.
-- Staff phones remain can_write=false, preserving the original v1 restriction.
CREATE OR REPLACE FUNCTION public.marketpro_owner_enable_staff_writer(
    p_workspace_id uuid,p_owner_device_id uuid,
    p_staff_user_id uuid,p_staff_device_id uuid
) RETURNS jsonb
LANGUAGE plpgsql SECURITY DEFINER SET search_path=''
AS $enable$
DECLARE
    v_owner uuid;
    v_old marketpro_private.staff_write_leases%ROWTYPE;
    v_has_previous boolean;
BEGIN
    v_owner:=marketpro_private.require_enrolled_owner(
       p_workspace_id,p_owner_device_id);
    IF p_staff_user_id IS NULL OR p_staff_device_id IS NULL
       OR p_staff_user_id=v_owner OR p_staff_device_id=p_owner_device_id THEN
        RAISE EXCEPTION 'Invalid Staff account or phone'
          USING ERRCODE='42501';
    END IF;
    PERFORM pg_catalog.pg_advisory_xact_lock(
      pg_catalog.hashtextextended(p_workspace_id::text,0));

    IF NOT EXISTS (
      SELECT 1 FROM marketpro_private.team_rollout_gate g
      WHERE g.workspace_id=p_workspace_id
        AND g.staff_direct_rest_restricted
        AND g.team_pull_rpc_reviewed AND g.team_writes_reviewed
    ) THEN
        RAISE EXCEPTION 'Staff write rollout not independently audited'
          USING ERRCODE='42501';
    END IF;
    IF NOT EXISTS (
      SELECT 1 FROM auth.users u
      JOIN public.workspace_members m
        ON m.user_id=u.id AND m.workspace_id=p_workspace_id
      JOIN public.registered_devices d
        ON d.user_id=m.user_id AND d.workspace_id=m.workspace_id
      JOIN marketpro_private.pending_team_enrollments i
        ON i.workspace_id=m.workspace_id AND i.staff_user_id=m.user_id
      WHERE u.id=p_staff_user_id AND u.email IS NOT NULL
        AND u.email_confirmed_at IS NOT NULL
        AND m.role='purchasing_staff'
        AND d.id=p_staff_device_id AND d.enabled AND NOT d.can_write
        AND i.intended_device_id=d.id
        AND i.status='accepted' AND i.revoked_at IS NULL
        AND i.accepted_at IS NOT NULL AND i.activated_at IS NOT NULL
        AND i.expires_at>i.accepted_at
    ) THEN
        RAISE EXCEPTION 'Verified accepted and activated Staff phone required'
          USING ERRCODE='42501';
    END IF;
    -- Exactly one Staff phone per workspace, even across different accounts.
    IF EXISTS (
      SELECT 1 FROM public.registered_devices d
      JOIN public.workspace_members m
        ON m.workspace_id=d.workspace_id AND m.user_id=d.user_id
      WHERE m.workspace_id=p_workspace_id AND m.role='purchasing_staff'
        AND d.enabled AND d.id<>p_staff_device_id
    ) THEN
        RAISE EXCEPTION 'Another active Staff phone must be revoked first'
          USING ERRCODE='42501';
    END IF;
    SELECT * INTO v_old FROM marketpro_private.staff_write_leases
    WHERE workspace_id=p_workspace_id FOR UPDATE;
    v_has_previous:=FOUND;

    IF v_has_previous AND v_old.enabled
       AND v_old.staff_user_id=p_staff_user_id
       AND v_old.device_id=p_staff_device_id THEN
       RETURN pg_catalog.jsonb_build_object(
         'writer_enabled',true,'already_enabled',true,
         'staff_device_id',p_staff_device_id,
         'legacy_owner_writer_unchanged',true);
    END IF;
    IF v_has_previous AND v_old.enabled
       AND v_old.device_id<>p_staff_device_id
       AND EXISTS (
         SELECT 1 FROM public.registered_devices d
         WHERE d.id=v_old.device_id AND d.workspace_id=p_workspace_id
           AND d.enabled
       ) THEN
       RAISE EXCEPTION 'Existing active Staff writer must be disabled'
          USING ERRCODE='42501';
    END IF;

    INSERT INTO marketpro_private.staff_write_leases (
      workspace_id,staff_user_id,device_id,enabled,granted_at,revoked_at
    ) VALUES (
      p_workspace_id,p_staff_user_id,p_staff_device_id,true,
      clock_timestamp(),NULL
    ) ON CONFLICT (workspace_id)
    DO UPDATE SET staff_user_id=EXCLUDED.staff_user_id,
                  device_id=EXCLUDED.device_id,
                  enabled=true,granted_at=clock_timestamp(),revoked_at=NULL;

    INSERT INTO marketpro_private.staff_writer_activation_audit(
      workspace_id,staff_user_id,old_device_id,new_device_id,
      owner_user_id,action
    ) VALUES (
      p_workspace_id,p_staff_user_id,
      CASE WHEN v_has_previous THEN v_old.device_id ELSE NULL END,
      p_staff_device_id,v_owner,'grant'
    );
    RETURN pg_catalog.jsonb_build_object(
      'writer_enabled',true,'already_enabled',false,
      'staff_device_id',p_staff_device_id,
      'legacy_owner_writer_unchanged',true);
END;
$enable$;
REVOKE ALL ON FUNCTION public.marketpro_owner_enable_staff_writer(
 uuid,uuid,uuid,uuid) FROM PUBLIC,anon,authenticated;
GRANT EXECUTE ON FUNCTION public.marketpro_owner_enable_staff_writer(
 uuid,uuid,uuid,uuid) TO authenticated;

-- Owner may revoke Staff WRITING without revoking read-only workspace access.
-- For LOST/STOLEN phones also call V368 disable_team_phone: it disables all
-- device-bound RPCs. This routine never changes the legacy Owner's device.
CREATE OR REPLACE FUNCTION public.marketpro_owner_disable_staff_writer(
    p_workspace_id uuid,p_owner_device_id uuid,
    p_staff_user_id uuid,p_staff_device_id uuid
) RETURNS jsonb
LANGUAGE plpgsql SECURITY DEFINER SET search_path=''
AS $revoke$
DECLARE
    v_owner uuid;
    v_lease marketpro_private.staff_write_leases%ROWTYPE;
    v_changed boolean:=false;
BEGIN
    v_owner:=marketpro_private.require_enrolled_owner(
      p_workspace_id,p_owner_device_id);
    IF p_staff_user_id IS NULL OR p_staff_device_id IS NULL
       OR p_staff_user_id=v_owner OR p_staff_device_id=p_owner_device_id THEN
      RAISE EXCEPTION 'Cannot disable Owner or unspecified phone'
        USING ERRCODE='42501';
    END IF;
    PERFORM pg_catalog.pg_advisory_xact_lock(
      pg_catalog.hashtextextended(p_workspace_id::text,0));

    SELECT * INTO v_lease FROM marketpro_private.staff_write_leases
      WHERE workspace_id=p_workspace_id FOR UPDATE;
    IF NOT FOUND OR v_lease.staff_user_id<>p_staff_user_id
       OR v_lease.device_id<>p_staff_device_id THEN
      RAISE EXCEPTION 'No matching Staff write authorization found'
        USING ERRCODE='42501';
    END IF;
    IF v_lease.enabled THEN
      UPDATE marketpro_private.staff_write_leases
        SET enabled=false,revoked_at=clock_timestamp()
      WHERE workspace_id=p_workspace_id;
      v_changed:=true;
      INSERT INTO marketpro_private.staff_writer_activation_audit(
        workspace_id,staff_user_id,old_device_id,new_device_id,
        owner_user_id,action
      ) VALUES (
        p_workspace_id,p_staff_user_id,p_staff_device_id,
        NULL,v_owner,'revoke'
      );
    END IF;
    RETURN pg_catalog.jsonb_build_object(
      'staff_write_disabled',true,'changed',v_changed,
      'staff_read_only_device_still_enabled',true,
      'disable_device_separately_if_lost',true,
      'legacy_owner_writer_unchanged',true
    );
END;
$revoke$;
REVOKE ALL ON FUNCTION public.marketpro_owner_disable_staff_writer(
 uuid,uuid,uuid,uuid) FROM PUBLIC,anon,authenticated;
GRANT EXECUTE ON FUNCTION public.marketpro_owner_disable_staff_writer(
 uuid,uuid,uuid,uuid) TO authenticated;
-- TOP-LEVEL COMMIT moved to one enclosing transaction


-- SOURCE: supabase/proposals/v372_staff_auth_session_binding_REVIEW_ONLY.sql
-- MARKET PRO V3.7.2 — STAFF AUTH SESSION + ONE-TIME PAIRING GUARD
-- REVIEW ONLY. NEVER EXECUTE ON LIVE SUPABASE.
-- Requires V358 pending invitation, V359 activated_at/RLS rollout,
-- V354 paged pull, V370 Staff leases, V371 Owner approval.
-- The existing Owner v1 writer, device index and dashboard are untouched.
-- TOP-LEVEL BEGIN moved to one enclosing transaction

-- No device secret or Supabase refresh/access token is saved in this table.
-- Only a signed session_id from a successfully redeemed ONE-TIME pairing code.
CREATE TABLE IF NOT EXISTS marketpro_private.staff_session_bindings(
    workspace_id uuid PRIMARY KEY,
    staff_user_id uuid NOT NULL,
    device_id uuid NOT NULL,
    session_id uuid NOT NULL,
    bound_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    revoked_at timestamptz
);
REVOKE ALL ON marketpro_private.staff_session_bindings
  FROM PUBLIC,anon,authenticated;

-- Strict fail-closed session validation: JWT is already signed/validated
-- by Supabase/PostgREST, but a signed-out session's JWT may still be valid
-- until expiry. Rechecking auth.sessions is necessary for immediate deny.
-- This is NOT hardware attestation or full defense from stolen bearer tokens.
CREATE OR REPLACE FUNCTION marketpro_private.is_verified_staff_session(
 p_workspace_id uuid,p_device_id uuid
) RETURNS boolean
LANGUAGE plpgsql VOLATILE SECURITY DEFINER SET search_path=''
AS $verify_session$
DECLARE
 v_user uuid:=auth.uid();
 v_session_claim text:=auth.jwt()->>'session_id';
 v_session_id uuid;
BEGIN
 IF v_user IS NULL OR
    coalesce((auth.jwt()->>'is_anonymous')::boolean,false) OR
    p_workspace_id IS NULL OR p_device_id IS NULL OR
    v_session_claim IS NULL OR
    v_session_claim !~*
      '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'
 THEN RETURN false; END IF;
 v_session_id:=v_session_claim::uuid;

 RETURN EXISTS(
   SELECT 1 FROM marketpro_private.staff_session_bindings b
   JOIN auth.sessions s ON s.id=b.session_id AND s.user_id=b.staff_user_id
   JOIN marketpro_private.pending_team_enrollments i
     ON i.workspace_id=b.workspace_id AND i.staff_user_id=b.staff_user_id
   WHERE b.workspace_id=p_workspace_id AND b.staff_user_id=v_user
     AND b.device_id=p_device_id AND b.session_id=v_session_id
     AND b.revoked_at IS NULL
     AND (s.not_after IS NULL OR s.not_after>clock_timestamp())
     AND i.intended_device_id=b.device_id
     AND i.status='accepted' AND i.revoked_at IS NULL
     AND i.accepted_at IS NOT NULL AND i.activated_at IS NOT NULL
 );
END;
$verify_session$;
REVOKE ALL ON FUNCTION marketpro_private.is_verified_staff_session(uuid,uuid)
 FROM PUBLIC,anon,authenticated;

-- Invitation must be accepted from the signed in Staff Supabase session.
-- On replacement, the new one-time invitation replaces the prior binding.
CREATE OR REPLACE FUNCTION public.marketpro_staff_accept_invitation(
    p_workspace_id uuid,
    p_staff_device_id uuid,
    p_one_time_code text
) RETURNS jsonb
LANGUAGE plpgsql SECURITY DEFINER SET search_path=''
AS $f$
DECLARE
    v_user uuid := auth.uid();
    v_row marketpro_private.pending_team_enrollments%ROWTYPE;
    v_verified boolean;
    v_session_claim text;
    v_session_id uuid;
BEGIN
    IF v_user IS NULL OR
       coalesce((auth.jwt()->>'is_anonymous')::boolean,false) THEN
       RAISE EXCEPTION 'Verified staff login required' USING ERRCODE='42501';
    END IF;
    IF p_workspace_id IS NULL OR p_staff_device_id IS NULL OR
       p_one_time_code IS NULL OR
       length(p_one_time_code) <> 64 OR
       p_one_time_code !~ '^[0-9a-f]{64}$' THEN
       RAISE EXCEPTION 'Invalid enrollment request' USING ERRCODE='22023';
    END IF;
    SELECT email_confirmed_at IS NOT NULL INTO v_verified
    FROM auth.users WHERE id=v_user;
    IF v_verified IS DISTINCT FROM true THEN
        RAISE EXCEPTION 'Verified staff email required' USING ERRCODE='42501';
    END IF;
    -- Signed JWT session_id must still belong to an ACTIVE Auth session.
    v_session_claim := auth.jwt()->>'session_id';
    IF v_session_claim IS NULL OR
       v_session_claim !~* '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$' THEN
       RAISE EXCEPTION 'Verified active Supabase session required'
         USING ERRCODE='42501';
    END IF;
    v_session_id := v_session_claim::uuid;
    IF NOT EXISTS (
       SELECT 1 FROM auth.sessions s
       WHERE s.id=v_session_id AND s.user_id=v_user
         AND (s.not_after IS NULL OR s.not_after>clock_timestamp())
    ) THEN
       RAISE EXCEPTION 'Expired or revoked Auth session'
         USING ERRCODE='42501';
    END IF;
    -- The staff account alone cannot select an invitation or change its role.
    SELECT * INTO v_row
    FROM marketpro_private.pending_team_enrollments
    WHERE workspace_id=p_workspace_id AND staff_user_id=v_user
    FOR UPDATE;
    IF NOT FOUND OR
       v_row.intended_device_id IS DISTINCT FROM p_staff_device_id OR
       v_row.status <> 'invited' OR v_row.expires_at <= clock_timestamp() OR
       v_row.token_sha256 IS DISTINCT FROM
         extensions.digest(pg_catalog.convert_to(p_one_time_code,'UTF8'),'sha256') THEN
       RAISE EXCEPTION 'Enrollment invitation invalid, expired or consumed'
           USING ERRCODE='42501';
    END IF;
    UPDATE marketpro_private.pending_team_enrollments
    SET status='accepted',accepted_at=clock_timestamp()
    WHERE workspace_id=p_workspace_id AND staff_user_id=v_user;
    INSERT INTO marketpro_private.staff_session_bindings(
      workspace_id,staff_user_id,device_id,session_id,bound_at,revoked_at)
    VALUES(p_workspace_id,v_user,p_staff_device_id,v_session_id,
           clock_timestamp(),NULL)
    ON CONFLICT(workspace_id) DO UPDATE SET
      staff_user_id=EXCLUDED.staff_user_id,
      device_id=EXCLUDED.device_id,
      session_id=EXCLUDED.session_id,
      bound_at=clock_timestamp(),revoked_at=NULL;
    -- CRITICAL: no real membership or registered writer is created here.
    RETURN pg_catalog.jsonb_build_object(
       'invitation_accepted',true,
       'status','awaiting_owner_activation_and_server_cutover',
       'workspace_id',p_workspace_id
    );
END;
$f$;

REVOKE ALL ON FUNCTION public.marketpro_staff_accept_invitation(
 uuid,uuid,text) FROM PUBLIC,anon;
GRANT EXECUTE ON FUNCTION public.marketpro_staff_accept_invitation(
 uuid,uuid,text) TO authenticated;

-- Every Staff financial write still requires private lease AND live
-- Auth session + matching one-time pairing, plus the V371 rollout gate.
CREATE OR REPLACE FUNCTION marketpro_private.require_dual_channel_writer(
    p_workspace_id uuid,p_device_id uuid
) RETURNS text
LANGUAGE plpgsql SECURITY DEFINER SET search_path=''
AS $guard$
DECLARE
    v_user uuid := auth.uid();
    v_role text;
BEGIN
    IF v_user IS NULL OR coalesce((auth.jwt()->>'is_anonymous')::boolean,false)
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
        OR
        (m.role='purchasing_staff' AND NOT d.can_write
          AND EXISTS (
            SELECT 1 FROM marketpro_private.staff_write_leases l
            JOIN marketpro_private.team_rollout_gate g
              ON g.workspace_id=l.workspace_id
            WHERE l.workspace_id=m.workspace_id
              AND l.staff_user_id=m.user_id AND l.device_id=d.id
              AND l.enabled
              AND marketpro_private.is_verified_staff_session(
                   p_workspace_id,p_device_id
              )
              AND g.staff_direct_rest_restricted
              AND g.team_pull_rpc_reviewed AND g.team_writes_reviewed
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

-- Owner can only approve a Staff writer after verified session pairing.
CREATE OR REPLACE FUNCTION public.marketpro_owner_enable_staff_writer(
    p_workspace_id uuid,p_owner_device_id uuid,
    p_staff_user_id uuid,p_staff_device_id uuid
) RETURNS jsonb
LANGUAGE plpgsql SECURITY DEFINER SET search_path=''
AS $enable$
DECLARE
    v_owner uuid;
    v_old marketpro_private.staff_write_leases%ROWTYPE;
    v_has_previous boolean;
BEGIN
    v_owner:=marketpro_private.require_enrolled_owner(
       p_workspace_id,p_owner_device_id);
    IF p_staff_user_id IS NULL OR p_staff_device_id IS NULL
       OR p_staff_user_id=v_owner OR p_staff_device_id=p_owner_device_id THEN
        RAISE EXCEPTION 'Invalid Staff account or phone'
          USING ERRCODE='42501';
    END IF;
    PERFORM pg_catalog.pg_advisory_xact_lock(
      pg_catalog.hashtextextended(p_workspace_id::text,0));

    IF NOT EXISTS (
      SELECT 1 FROM marketpro_private.team_rollout_gate g
      WHERE g.workspace_id=p_workspace_id
        AND g.staff_direct_rest_restricted
        AND g.team_pull_rpc_reviewed AND g.team_writes_reviewed
    ) THEN
        RAISE EXCEPTION 'Staff write rollout not independently audited'
          USING ERRCODE='42501';
    END IF;
    IF NOT EXISTS (
      SELECT 1 FROM auth.users u
      JOIN public.workspace_members m
        ON m.user_id=u.id AND m.workspace_id=p_workspace_id
      JOIN public.registered_devices d
        ON d.user_id=m.user_id AND d.workspace_id=m.workspace_id
      JOIN marketpro_private.pending_team_enrollments i
        ON i.workspace_id=m.workspace_id AND i.staff_user_id=m.user_id
      WHERE u.id=p_staff_user_id AND u.email IS NOT NULL
        AND u.email_confirmed_at IS NOT NULL
        AND m.role='purchasing_staff'
        AND d.id=p_staff_device_id AND d.enabled AND NOT d.can_write
        AND i.intended_device_id=d.id
        AND i.status='accepted' AND i.revoked_at IS NULL
        AND i.accepted_at IS NOT NULL AND i.activated_at IS NOT NULL
        AND i.expires_at>i.accepted_at
    ) THEN
        RAISE EXCEPTION 'Verified accepted and activated Staff phone required'
          USING ERRCODE='42501';
    END IF;
    -- A copied device ID is not enough. The Staff invitation must have
    -- been accepted by a real current Supabase Auth session for this device.
    IF NOT EXISTS (
      SELECT 1 FROM marketpro_private.staff_session_bindings b
      JOIN auth.sessions s ON s.id=b.session_id AND s.user_id=b.staff_user_id
      WHERE b.workspace_id=p_workspace_id
        AND b.staff_user_id=p_staff_user_id AND b.device_id=p_staff_device_id
        AND b.revoked_at IS NULL
        AND (s.not_after IS NULL OR s.not_after>clock_timestamp())
    ) THEN
      RAISE EXCEPTION 'Staff phone has no active paired Auth session'
        USING ERRCODE='42501';
    END IF;
    -- Exactly one Staff phone per workspace, even across different accounts.
    IF EXISTS (
      SELECT 1 FROM public.registered_devices d
      JOIN public.workspace_members m
        ON m.workspace_id=d.workspace_id AND m.user_id=d.user_id
      WHERE m.workspace_id=p_workspace_id AND m.role='purchasing_staff'
        AND d.enabled AND d.id<>p_staff_device_id
    ) THEN
        RAISE EXCEPTION 'Another active Staff phone must be revoked first'
          USING ERRCODE='42501';
    END IF;
    SELECT * INTO v_old FROM marketpro_private.staff_write_leases
    WHERE workspace_id=p_workspace_id FOR UPDATE;
    v_has_previous:=FOUND;

    IF v_has_previous AND v_old.enabled
       AND v_old.staff_user_id=p_staff_user_id
       AND v_old.device_id=p_staff_device_id THEN
       RETURN pg_catalog.jsonb_build_object(
         'writer_enabled',true,'already_enabled',true,
         'staff_device_id',p_staff_device_id,
         'legacy_owner_writer_unchanged',true);
    END IF;
    IF v_has_previous AND v_old.enabled
       AND v_old.device_id<>p_staff_device_id
       AND EXISTS (
         SELECT 1 FROM public.registered_devices d
         WHERE d.id=v_old.device_id AND d.workspace_id=p_workspace_id
           AND d.enabled
       ) THEN
       RAISE EXCEPTION 'Existing active Staff writer must be disabled'
          USING ERRCODE='42501';
    END IF;

    INSERT INTO marketpro_private.staff_write_leases (
      workspace_id,staff_user_id,device_id,enabled,granted_at,revoked_at
    ) VALUES (
      p_workspace_id,p_staff_user_id,p_staff_device_id,true,
      clock_timestamp(),NULL
    ) ON CONFLICT (workspace_id)
    DO UPDATE SET staff_user_id=EXCLUDED.staff_user_id,
                  device_id=EXCLUDED.device_id,
                  enabled=true,granted_at=clock_timestamp(),revoked_at=NULL;

    INSERT INTO marketpro_private.staff_writer_activation_audit(
      workspace_id,staff_user_id,old_device_id,new_device_id,
      owner_user_id,action
    ) VALUES (
      p_workspace_id,p_staff_user_id,
      CASE WHEN v_has_previous THEN v_old.device_id ELSE NULL END,
      p_staff_device_id,v_owner,'grant'
    );
    RETURN pg_catalog.jsonb_build_object(
      'writer_enabled',true,'already_enabled',false,
      'staff_device_id',p_staff_device_id,
      'legacy_owner_writer_unchanged',true);
END;
$enable$;
REVOKE ALL ON FUNCTION public.marketpro_owner_enable_staff_writer(
 uuid,uuid,uuid,uuid) FROM PUBLIC,anon;
GRANT EXECUTE ON FUNCTION public.marketpro_owner_enable_staff_writer(
 uuid,uuid,uuid,uuid) TO authenticated;

-- Secure even READ-ONLY Staff pulls: otherwise a second login plus copied
-- device UUID could bypass the new writer-only session guard.
CREATE OR REPLACE FUNCTION public.marketpro_team_pull_page(
 p_workspace_id uuid,
 p_device_id uuid,
 p_entity_type text,
 p_after_updated_at timestamptz DEFAULT NULL,
 p_after_id uuid DEFAULT NULL,
 p_page_size integer DEFAULT 50
) RETURNS jsonb
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = ''
AS $pull$
DECLARE
 v_user uuid := auth.uid();
 v_table text;
 v_rows jsonb;
 v_count integer;
 v_more boolean;
 v_next_updated timestamptz;
 v_next_id uuid;
 v_today date := (clock_timestamp() AT TIME ZONE 'Asia/Colombo')::date;
BEGIN
 IF v_user IS NULL OR coalesce((auth.jwt()->>'is_anonymous')::boolean,false) THEN
  RAISE EXCEPTION 'Authenticated user required for download' USING ERRCODE='42501';
 END IF;
 IF p_workspace_id IS NULL OR p_device_id IS NULL OR
    p_entity_type NOT IN ('category','item','requisition','purchase',
                          'purchase_addition','daily_cash_received') OR
    p_entity_type IS NULL OR
    p_page_size IS NULL OR p_page_size NOT BETWEEN 1 AND 100 OR
    ((p_after_updated_at IS NULL) <> (p_after_id IS NULL)) THEN
  RAISE EXCEPTION 'Invalid pull request' USING ERRCODE='22023';
 END IF;
 -- Serialize with Owner replacement/revoke actions before reading rows.
 PERFORM pg_catalog.pg_advisory_xact_lock(
   pg_catalog.hashtextextended(p_workspace_id::text,0)
 );
 -- Readers must be known users and enrolled devices, not arbitrary members.
 -- can_write is deliberately NOT required for a read-only phone.
 IF NOT EXISTS (
  SELECT 1 FROM public.workspace_members m
  JOIN public.registered_devices d
    ON d.workspace_id=m.workspace_id AND d.user_id=m.user_id
  WHERE m.workspace_id=p_workspace_id AND m.user_id=v_user
    AND m.role IN ('owner','purchasing_staff','viewer')
    AND d.id=p_device_id AND d.enabled=true
    AND (m.role<>'purchasing_staff' OR
      marketpro_private.is_verified_staff_session(p_workspace_id,p_device_id))
 ) THEN
  RAISE EXCEPTION 'Unenrolled or revoked reader' USING ERRCODE='42501';
 END IF;

 -- Closed allowlist: NO invoice photo bytes, no raw server Auth information.
 v_table := CASE p_entity_type
  WHEN 'category' THEN 'categories'
  WHEN 'item' THEN 'items'
  WHEN 'requisition' THEN 'requisitions'
  WHEN 'purchase' THEN 'purchases'
  WHEN 'purchase_addition' THEN 'purchase_additions'
  WHEN 'daily_cash_received' THEN 'daily_cash_received'
 END;
 EXECUTE pg_catalog.format(
   'SELECT coalesce(pg_catalog.jsonb_agg(to_jsonb(r) ORDER BY r.updated_at,r.id),
                     ''[]''::jsonb)
    FROM (
      SELECT * FROM public.%I
      WHERE workspace_id=$1 AND
            ($2::timestamptz IS NULL OR
             (updated_at,id) > ($2::timestamptz,$3::uuid))
      ORDER BY updated_at ASC,id ASC
      LIMIT $4
    ) AS r',v_table
 ) INTO v_rows USING p_workspace_id,p_after_updated_at,p_after_id,p_page_size+1;
 v_count := pg_catalog.jsonb_array_length(v_rows);
 v_more := v_count > p_page_size;
 IF v_more THEN
  v_rows := v_rows - (v_count - 1);
 END IF;
 v_count := pg_catalog.jsonb_array_length(v_rows);
 IF v_count>0 THEN
  v_next_updated := (v_rows->(v_count-1)->>'updated_at')::timestamptz;
  v_next_id := (v_rows->(v_count-1)->>'id')::uuid;
 ELSE
  v_next_updated := p_after_updated_at;
  v_next_id := p_after_id;
 END IF;
 RETURN pg_catalog.jsonb_build_object(
  'entity_type',p_entity_type,'rows',v_rows,'has_more',v_more,
  'next_updated_at',v_next_updated,'next_id',v_next_id,
  'server_today_colombo',v_today,
  'server_time_utc',clock_timestamp()
 );
END;
$pull$;
REVOKE ALL ON FUNCTION public.marketpro_team_pull_page(
 uuid,uuid,text,timestamptz,uuid,integer) FROM PUBLIC,anon;
GRANT EXECUTE ON FUNCTION public.marketpro_team_pull_page(
 uuid,uuid,text,timestamptz,uuid,integer) TO authenticated;
-- TOP-LEVEL COMMIT moved to one enclosing transaction


-- SOURCE: supabase/proposals/v375_staff_device_challenge_ledger_REVIEW_ONLY.sql
-- MARKET PRO V3.7.5 — STAFF DEVICE-PROOF CHALLENGE LEDGER
-- REVIEW ONLY: NEVER APPLY THIS FILE TO LIVE SUPABASE.
-- Requires V358/V359/V370/V371/V372 full cutover, hardened Staff RLS and
-- audited PostgREST/Edge Function deployment before activation.
-- This phase DOES NOT approve public keys or grant any business data rights.
-- TOP-LEVEL BEGIN moved to one enclosing transaction
CREATE SCHEMA IF NOT EXISTS marketpro_private;

-- Provision/approve only via a separately audited Owner-controlled workflow.
-- There is intentionally NO public key self-enrollment API in this proposal.
CREATE TABLE IF NOT EXISTS marketpro_private.staff_device_public_keys(
    workspace_id uuid PRIMARY KEY,
    staff_user_id uuid NOT NULL,
    device_id uuid NOT NULL,
    spki_der bytea NOT NULL CHECK (octet_length(spki_der) BETWEEN 64 AND 512),
    fingerprint_sha256 text NOT NULL
      CHECK (fingerprint_sha256 ~ '^[0-9a-f]{64}$'),
    approved boolean NOT NULL DEFAULT false,
    approved_at timestamptz,
    revoked_at timestamptz,
    CONSTRAINT staff_key_member_fk FOREIGN KEY(workspace_id,staff_user_id)
      REFERENCES public.workspace_members(workspace_id,user_id),
    CONSTRAINT staff_key_device_fk FOREIGN KEY(workspace_id,device_id)
      REFERENCES public.registered_devices(workspace_id,id)
);
REVOKE ALL ON marketpro_private.staff_device_public_keys
 FROM PUBLIC,anon,authenticated;

CREATE TABLE IF NOT EXISTS marketpro_private.staff_device_challenges(
    challenge_id uuid PRIMARY KEY DEFAULT extensions.gen_random_uuid(),
    workspace_id uuid NOT NULL,
    staff_user_id uuid NOT NULL,
    device_id uuid NOT NULL,
    session_id uuid NOT NULL,
    purpose text NOT NULL CHECK (purpose IN ('read','write')),
    nonce_hex text NOT NULL CHECK(nonce_hex ~ '^[0-9a-f]{64}$'),
    issued_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    expires_at timestamptz NOT NULL,
    consumed_at timestamptz,
    verified_at timestamptz,
    CHECK (expires_at>issued_at AND expires_at<=issued_at+interval '120 seconds')
);
CREATE INDEX IF NOT EXISTS staff_challenge_lookup_idx
 ON marketpro_private.staff_device_challenges(workspace_id,device_id,session_id);
REVOKE ALL ON marketpro_private.staff_device_challenges
 FROM PUBLIC,anon,authenticated;

-- This is the only public RPC in V375. It returns a signed-JWT-scoped,
-- short-lived RANDOM challenge, never a key or Staff writer permission.
CREATE OR REPLACE FUNCTION public.marketpro_staff_issue_device_challenge(
 p_workspace_id uuid,p_device_id uuid,p_purpose text
) RETURNS jsonb
LANGUAGE plpgsql VOLATILE SECURITY DEFINER SET search_path=''
AS $issue$
DECLARE
 v_user uuid:=auth.uid();
 v_session_id uuid;
 v_session_txt text:=auth.jwt()->>'session_id';
 v_time timestamptz:=clock_timestamp();
 v_expires timestamptz;
 v_id uuid:=extensions.gen_random_uuid();
 v_nonce text;
BEGIN
 IF v_user IS NULL OR coalesce((auth.jwt()->>'is_anonymous')::boolean,false)
    OR p_workspace_id IS NULL OR p_device_id IS NULL
    OR p_purpose NOT IN ('read','write') OR p_purpose IS NULL
    OR v_session_txt IS NULL OR
    v_session_txt !~* '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'
 THEN
    RAISE EXCEPTION 'Verified Staff session and challenge context required'
      USING ERRCODE='42501';
 END IF;
 v_session_id:=v_session_txt::uuid;

 -- Lock same workspace as Owner's disable/transfer transactions.
 PERFORM pg_catalog.pg_advisory_xact_lock(
   pg_catalog.hashtextextended(p_workspace_id::text,0));
 IF NOT marketpro_private.is_verified_staff_session(
    p_workspace_id,p_device_id
 ) OR NOT EXISTS (
    SELECT 1 FROM marketpro_private.staff_device_public_keys k
    JOIN public.registered_devices d
      ON d.workspace_id=k.workspace_id AND d.id=k.device_id
    JOIN public.workspace_members m
      ON m.workspace_id=d.workspace_id AND m.user_id=d.user_id
    JOIN marketpro_private.team_rollout_gate g
      ON g.workspace_id=m.workspace_id
    WHERE k.workspace_id=p_workspace_id AND k.staff_user_id=v_user
      AND k.device_id=p_device_id
      AND k.approved AND k.revoked_at IS NULL
      AND d.enabled AND NOT d.can_write
      AND m.role='purchasing_staff'
      AND g.staff_direct_rest_restricted AND g.team_pull_rpc_reviewed
      AND (p_purpose<>'write' OR (
        g.team_writes_reviewed AND EXISTS(
          SELECT 1 FROM marketpro_private.staff_write_leases l
          WHERE l.workspace_id=k.workspace_id
            AND l.staff_user_id=k.staff_user_id
            AND l.device_id=k.device_id AND l.enabled
        )
      ))
 ) THEN
    RAISE EXCEPTION 'No currently authorized registered device proof key'
      USING ERRCODE='42501';
 END IF;

 -- Issue a maximum of one live challenge per Staff workspace/device by
 -- consuming older outstanding challenges in this serializable xact lock.
 UPDATE marketpro_private.staff_device_challenges
 SET consumed_at=v_time
 WHERE workspace_id=p_workspace_id AND device_id=p_device_id
   AND consumed_at IS NULL;
 v_nonce:=pg_catalog.encode(extensions.gen_random_bytes(32),'hex');
 v_expires:=v_time+interval '60 seconds';
 INSERT INTO marketpro_private.staff_device_challenges(
  challenge_id,workspace_id,staff_user_id,device_id,session_id,
  purpose,nonce_hex,issued_at,expires_at
 ) VALUES (
  v_id,p_workspace_id,v_user,p_device_id,v_session_id,
  p_purpose,v_nonce,v_time,v_expires
 );

 RETURN pg_catalog.jsonb_build_object(
  'workspace_id',p_workspace_id,'staff_user_id',v_user,
  'device_id',p_device_id,'session_id',v_session_id,
  'challenge_id',v_id,'nonce_hex',v_nonce,'purpose',p_purpose,
  'issued_at_millis',pg_catalog.floor(
      EXTRACT(epoch from v_time)*1000)::bigint,
  'expires_at_millis',pg_catalog.floor(
      EXTRACT(epoch from v_expires)*1000)::bigint
 );
END;
$issue$;
REVOKE ALL ON FUNCTION public.marketpro_staff_issue_device_challenge(
 uuid,uuid,text) FROM PUBLIC,anon;
GRANT EXECUTE ON FUNCTION public.marketpro_staff_issue_device_challenge(
 uuid,uuid,text) TO authenticated;

-- Internal service-role-only retrieval: denies expired/consumed challenge,
-- stolen/revoked login sessions, disabled devices, unaudited rollout,
-- removed Owner-approved Staff key, or disabled staff write lease.
CREATE OR REPLACE FUNCTION public.marketpro_service_device_challenge_material(
 p_challenge_id uuid,p_staff_user_id uuid,p_session_id uuid
) RETURNS jsonb
LANGUAGE plpgsql VOLATILE SECURITY DEFINER SET search_path=''
AS $material$
DECLARE
 v jsonb;
BEGIN
 IF p_challenge_id IS NULL OR p_staff_user_id IS NULL OR p_session_id IS NULL THEN
  RAISE EXCEPTION 'Invalid proof lookup' USING ERRCODE='42501';
 END IF;
 SELECT pg_catalog.jsonb_build_object(
   'workspace_id',c.workspace_id,'staff_user_id',c.staff_user_id,
   'device_id',c.device_id,'session_id',c.session_id,
   'challenge_id',c.challenge_id,'nonce_hex',c.nonce_hex,
   'purpose',c.purpose,
   'issued_at_millis',pg_catalog.floor(
      EXTRACT(epoch from c.issued_at)*1000)::bigint,
   'expires_at_millis',pg_catalog.floor(
      EXTRACT(epoch from c.expires_at)*1000)::bigint,
   'spki_base64',pg_catalog.encode(k.spki_der,'base64')
 ) INTO v
 FROM marketpro_private.staff_device_challenges c
 JOIN marketpro_private.staff_device_public_keys k
   ON k.workspace_id=c.workspace_id
    AND k.staff_user_id=c.staff_user_id AND k.device_id=c.device_id
 JOIN public.workspace_members m
   ON m.workspace_id=c.workspace_id AND m.user_id=c.staff_user_id
 JOIN public.registered_devices d
   ON d.workspace_id=c.workspace_id AND d.id=c.device_id
    AND d.user_id=c.staff_user_id
 JOIN marketpro_private.staff_session_bindings b
   ON b.workspace_id=c.workspace_id
    AND b.staff_user_id=c.staff_user_id AND b.device_id=c.device_id
    AND b.session_id=c.session_id
 JOIN auth.sessions s
   ON s.id=c.session_id AND s.user_id=c.staff_user_id
 JOIN marketpro_private.pending_team_enrollments i
   ON i.workspace_id=c.workspace_id AND i.staff_user_id=c.staff_user_id
 JOIN marketpro_private.team_rollout_gate g
   ON g.workspace_id=c.workspace_id
 WHERE c.challenge_id=p_challenge_id
   AND c.staff_user_id=p_staff_user_id AND c.session_id=p_session_id
   AND c.consumed_at IS NULL AND c.expires_at>clock_timestamp()
   AND c.issued_at<=clock_timestamp()
   AND k.approved AND k.revoked_at IS NULL
   AND d.enabled AND NOT d.can_write AND m.role='purchasing_staff'
   AND b.revoked_at IS NULL
   AND (s.not_after IS NULL OR s.not_after>clock_timestamp())
   AND i.intended_device_id=c.device_id AND i.status='accepted'
   AND i.revoked_at IS NULL AND i.accepted_at IS NOT NULL
   AND i.activated_at IS NOT NULL
   AND g.staff_direct_rest_restricted AND g.team_pull_rpc_reviewed
   AND (c.purpose<>'write' OR (
     g.team_writes_reviewed AND EXISTS(
       SELECT 1 FROM marketpro_private.staff_write_leases l
       WHERE l.workspace_id=c.workspace_id
         AND l.staff_user_id=c.staff_user_id AND l.device_id=c.device_id
         AND l.enabled
     )
   ));
 IF v IS NULL THEN
   RAISE EXCEPTION 'Challenge expired, revoked, consumed or not authorized'
      USING ERRCODE='42501';
 END IF;
 RETURN v;
END;
$material$;
REVOKE ALL ON FUNCTION public.marketpro_service_device_challenge_material(
 uuid,uuid,uuid) FROM PUBLIC,anon,authenticated;
GRANT EXECUTE ON FUNCTION public.marketpro_service_device_challenge_material(
 uuid,uuid,uuid) TO service_role;

-- IMPORTANT: This endpoint is SERVICE-ROLE-ONLY and must be called ONLY by
-- the independently JWT-validated Edge verifier AFTER it cryptographically
-- verifies the Android signature. It cannot be called from the Android APK.
-- It records evidence, not a durable or transferable business-write grant.
CREATE OR REPLACE FUNCTION public.marketpro_service_consume_device_challenge(
 p_challenge_id uuid,p_staff_user_id uuid,p_session_id uuid
) RETURNS jsonb
LANGUAGE plpgsql VOLATILE SECURITY DEFINER SET search_path=''
AS $consume$
DECLARE
 v_material jsonb;
 v_workspace uuid;
 v_updated integer;
BEGIN
 SELECT c.workspace_id INTO v_workspace
 FROM marketpro_private.staff_device_challenges c
 WHERE c.challenge_id=p_challenge_id AND c.staff_user_id=p_staff_user_id
  AND c.session_id=p_session_id;
 IF v_workspace IS NULL THEN
   RAISE EXCEPTION 'Unknown device proof' USING ERRCODE='42501';
 END IF;
 PERFORM pg_catalog.pg_advisory_xact_lock(
   pg_catalog.hashtextextended(v_workspace::text,0));
 v_material:=public.marketpro_service_device_challenge_material(
  p_challenge_id,p_staff_user_id,p_session_id
 );
 UPDATE marketpro_private.staff_device_challenges
 SET consumed_at=clock_timestamp(),verified_at=clock_timestamp()
 WHERE challenge_id=p_challenge_id AND staff_user_id=p_staff_user_id
  AND session_id=p_session_id AND consumed_at IS NULL
  AND expires_at>clock_timestamp();
 GET DIAGNOSTICS v_updated=ROW_COUNT;
 IF v_updated<>1 THEN
    RAISE EXCEPTION 'Device proof already consumed or expired'
      USING ERRCODE='42501';
 END IF;
 RETURN pg_catalog.jsonb_build_object(
   'proof_verified_once',true,'challenge_id',p_challenge_id,
   'device_id',v_material->>'device_id',
   'business_write_permission_granted',false
 );
END;
$consume$;
REVOKE ALL ON FUNCTION public.marketpro_service_consume_device_challenge(
 uuid,uuid,uuid) FROM PUBLIC,anon,authenticated;
GRANT EXECUTE ON FUNCTION public.marketpro_service_consume_device_challenge(
 uuid,uuid,uuid) TO service_role;
-- TOP-LEVEL COMMIT moved to one enclosing transaction


-- SOURCE: supabase/proposals/two_phone_other_expenses_REVIEW_ONLY.sql
-- MARKET PRO TWO-PHONE RELEASE CANDIDATE: OTHER EXPENSES
-- REVIEW ONLY: DO NOT APPLY TO LIVE SUPABASE.
-- Additive schema + new RPC. Does NOT alter marketpro_apply_change owner V1.
-- Requires existing v352-v372 reviewed SQL and its fully audited RLS cutover.
-- TOP-LEVEL BEGIN moved to one enclosing transaction

CREATE TABLE IF NOT EXISTS public.daily_other_expenses(
 id uuid PRIMARY KEY,
 workspace_id uuid NOT NULL REFERENCES public.workspaces(id) ON DELETE RESTRICT,
 business_date date NOT NULL,
 transport_cents bigint NOT NULL DEFAULT 0
  CHECK(transport_cents BETWEEN 0 AND 9999999999),
 labor_charge_cents bigint NOT NULL DEFAULT 0
  CHECK(labor_charge_cents BETWEEN 0 AND 9999999999),
 meals_drinks_cents bigint NOT NULL DEFAULT 0
  CHECK(meals_drinks_cents BETWEEN 0 AND 9999999999),
 others_cents bigint NOT NULL DEFAULT 0
  CHECK(others_cents BETWEEN 0 AND 9999999999),
 remarks text NOT NULL DEFAULT '' CHECK(char_length(remarks)<=500),
 revision bigint NOT NULL DEFAULT 1 CHECK(revision>0),
 created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 UNIQUE(workspace_id,business_date)
);
CREATE INDEX IF NOT EXISTS other_expenses_workspace_day_idx
 ON public.daily_other_expenses(workspace_id,business_date);
ALTER TABLE public.daily_other_expenses ENABLE ROW LEVEL SECURITY;
REVOKE ALL ON public.daily_other_expenses FROM PUBLIC,anon,authenticated;
GRANT SELECT ON public.daily_other_expenses TO authenticated;

-- Same privacy model as other v359 datasets: no Staff direct REST SELECT;
-- Staff must go through a device-verified paginated pull after cutover.
DROP POLICY IF EXISTS other_expenses_owner_read ON public.daily_other_expenses;
CREATE POLICY other_expenses_owner_read ON public.daily_other_expenses
 FOR SELECT TO authenticated USING(EXISTS(
   SELECT 1 FROM public.workspace_members m
   WHERE m.workspace_id=daily_other_expenses.workspace_id
     AND m.user_id=(SELECT auth.uid())
     AND m.role IN('owner','editor','viewer')
 ));

CREATE SCHEMA IF NOT EXISTS marketpro_private;
CREATE TABLE IF NOT EXISTS marketpro_private.other_expenses_operation_audit(
 operation_id uuid PRIMARY KEY,
 workspace_id uuid NOT NULL,
 actor_user_id uuid NOT NULL,
 device_id uuid NOT NULL,
 entity_id uuid NOT NULL,
 action text NOT NULL CHECK(action IN('create','correct')),
 request_sha256 text NOT NULL CHECK(request_sha256 ~ '^[0-9a-f]{64}$'),
 revision bigint NOT NULL CHECK(revision>0),
 changed_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
REVOKE ALL ON marketpro_private.other_expenses_operation_audit
 FROM PUBLIC,anon,authenticated;

CREATE OR REPLACE FUNCTION public.marketpro_apply_team_other_expense(
 p_workspace_id uuid,p_device_id uuid,p_operation_id uuid,p_entity_id uuid,
 p_action text,p_expected_revision bigint,p_payload jsonb
) RETURNS jsonb
LANGUAGE plpgsql VOLATILE SECURITY DEFINER SET search_path=''
AS $expenses$
DECLARE
 v_actor uuid:=auth.uid();
 v_key text;
 v_date date;
 v_existing public.daily_other_expenses%ROWTYPE;
 v_old_operation marketpro_private.other_expenses_operation_audit%ROWTYPE;
 v_hash text;
 v_role text;
 v_revision bigint;
 v_transport bigint;
 v_labor bigint;
 v_meals bigint;
 v_others bigint;
 v_remarks text;
BEGIN
 IF v_actor IS NULL OR coalesce((auth.jwt()->>'is_anonymous')::boolean,false)
 OR p_workspace_id IS NULL OR p_device_id IS NULL OR p_operation_id IS NULL
 OR p_entity_id IS NULL OR p_action NOT IN('create','correct') OR p_action IS NULL
 OR p_payload IS NULL OR jsonb_typeof(p_payload)<>'object'
 OR octet_length(p_payload::text)>2048
 OR (p_action='create' AND p_expected_revision IS NOT NULL)
 OR (p_action='correct' AND (p_expected_revision IS NULL OR p_expected_revision<1))
 THEN
  RAISE EXCEPTION 'Invalid other-expenses request' USING ERRCODE='22023';
 END IF;
 IF (SELECT COUNT(*) FROM jsonb_object_keys(p_payload)) <> 6 THEN
  RAISE EXCEPTION 'All six fields required' USING ERRCODE='22023';
 END IF;
 FOR v_key IN SELECT jsonb_object_keys(p_payload) LOOP
   IF v_key NOT IN ('business_date','transport_cents','labor_charge_cents',
                   'meals_drinks_cents','others_cents','remarks') THEN
     RAISE EXCEPTION 'Unrecognized expenses field' USING ERRCODE='22023';
   END IF;
 END LOOP;
 IF NOT (p_payload ? 'business_date' AND p_payload ? 'transport_cents'
  AND p_payload ? 'labor_charge_cents' AND p_payload ? 'meals_drinks_cents'
  AND p_payload ? 'others_cents' AND p_payload ? 'remarks') THEN
  RAISE EXCEPTION 'Incomplete expenses payload' USING ERRCODE='22023';
 END IF;
 v_date:=(p_payload->>'business_date')::date;
 v_transport:=(p_payload->>'transport_cents')::bigint;
 v_labor:=(p_payload->>'labor_charge_cents')::bigint;
 v_meals:=(p_payload->>'meals_drinks_cents')::bigint;
 v_others:=(p_payload->>'others_cents')::bigint;
 v_remarks:=p_payload->>'remarks';
 IF v_date IS NULL OR v_transport IS NULL OR v_labor IS NULL
 OR v_meals IS NULL OR v_others IS NULL OR v_remarks IS NULL OR
 v_transport NOT BETWEEN 0 AND 9999999999 OR
 v_labor NOT BETWEEN 0 AND 9999999999 OR
 v_meals NOT BETWEEN 0 AND 9999999999 OR
 v_others NOT BETWEEN 0 AND 9999999999 OR
 char_length(v_remarks)>500 THEN
  RAISE EXCEPTION 'Invalid expense value' USING ERRCODE='22023';
 END IF;

 -- Serialize ALL workspace Staff changes with Owner revoke/replacement.
 PERFORM pg_catalog.pg_advisory_xact_lock(
   pg_catalog.hashtextextended(p_workspace_id::text,0));
 -- v372 verifies signed Supabase JWT session for Staff; original Owner
 -- writer remains valid while separate Staff lease/gates are required.
 v_role:=marketpro_private.require_dual_channel_writer(p_workspace_id,p_device_id);
 IF v_role NOT IN('owner','purchasing_staff') THEN
  RAISE EXCEPTION 'Unauthorized expense writer' USING ERRCODE='42501';
 END IF;
 v_hash:=pg_catalog.encode(extensions.digest(pg_catalog.convert_to(
   p_entity_id::text||':'||p_action||':'||
   coalesce(p_expected_revision::text,'null')||':'||p_payload::text,'UTF8'
 ),'sha256'),'hex');
 SELECT * INTO v_old_operation FROM marketpro_private.other_expenses_operation_audit
 WHERE operation_id=p_operation_id;
 IF FOUND THEN
  IF v_old_operation.workspace_id IS DISTINCT FROM p_workspace_id OR
     v_old_operation.actor_user_id IS DISTINCT FROM v_actor OR
     v_old_operation.device_id IS DISTINCT FROM p_device_id OR
     v_old_operation.entity_id IS DISTINCT FROM p_entity_id OR
     v_old_operation.action IS DISTINCT FROM p_action OR
     v_old_operation.request_sha256 IS DISTINCT FROM v_hash THEN
   RAISE EXCEPTION 'Idempotency ID reused with changed content' USING ERRCODE='23505';
  END IF;
  RETURN pg_catalog.jsonb_build_object('accepted',true,'replayed',true,
   'operation_id',p_operation_id,'revision',v_old_operation.revision);
 END IF;

 SELECT * INTO v_existing FROM public.daily_other_expenses
 WHERE workspace_id=p_workspace_id AND id=p_entity_id FOR UPDATE;
 -- Date check is repeated by the authenticated server with Colombo clock.
 -- Staff can correct TODAY repeatedly; old days are Owner-only.
 PERFORM marketpro_private.authorize_team_financial_write(
   p_workspace_id,p_device_id,v_date,p_action,
   CASE WHEN FOUND THEN v_existing.business_date ELSE NULL END);
 IF p_action='create' THEN
  IF v_existing.id IS NOT NULL THEN
   RAISE EXCEPTION 'Expense UUID already exists' USING ERRCODE='23505';
  END IF;
  INSERT INTO public.daily_other_expenses(
   id,workspace_id,business_date,transport_cents,labor_charge_cents,
   meals_drinks_cents,others_cents,remarks
  ) VALUES(
   p_entity_id,p_workspace_id,v_date,v_transport,v_labor,v_meals,v_others,v_remarks
  ) RETURNING revision INTO v_revision;
 ELSE
  IF v_existing.id IS NULL OR v_existing.business_date IS DISTINCT FROM v_date THEN
   RAISE EXCEPTION 'Expense date must not change' USING ERRCODE='42501';
  END IF;
  IF v_existing.revision<>p_expected_revision THEN
   RAISE EXCEPTION 'Stale expense revision' USING ERRCODE='40001';
  END IF;
  UPDATE public.daily_other_expenses SET
   transport_cents=v_transport,labor_charge_cents=v_labor,
   meals_drinks_cents=v_meals,others_cents=v_others,remarks=v_remarks,
   revision=revision+1,updated_at=clock_timestamp()
  WHERE workspace_id=p_workspace_id AND id=p_entity_id
    AND revision=p_expected_revision
  RETURNING revision INTO v_revision;
  IF v_revision IS NULL THEN
   RAISE EXCEPTION 'Expense revision changed' USING ERRCODE='40001';
  END IF;
 END IF;
 INSERT INTO marketpro_private.other_expenses_operation_audit(
   operation_id,workspace_id,actor_user_id,device_id,entity_id,
   action,request_sha256,revision
 ) VALUES(
   p_operation_id,p_workspace_id,v_actor,p_device_id,p_entity_id,
   p_action,v_hash,v_revision
 );
 RETURN pg_catalog.jsonb_build_object(
   'accepted',true,'replayed',false,
   'operation_id',p_operation_id,'revision',v_revision);
END;
$expenses$;
REVOKE ALL ON FUNCTION public.marketpro_apply_team_other_expense(
 uuid,uuid,uuid,uuid,text,bigint,jsonb) FROM PUBLIC,anon;
GRANT EXECUTE ON FUNCTION public.marketpro_apply_team_other_expense(
 uuid,uuid,uuid,uuid,text,bigint,jsonb) TO authenticated;
-- TOP-LEVEL COMMIT moved to one enclosing transaction


-- SOURCE: supabase/proposals/two_phone_other_expenses_pull_REVIEW_ONLY.sql
-- TWO-PHONE RELEASE: expand VERIFIED V372 read-only paginated pull to expenses
-- REVIEW ONLY, REQUIRES V372 AND OTHER EXPENSES TABLE MIGRATION.
-- No direct staff REST SELECT. Staff JWT session is rechecked on every call.
-- TOP-LEVEL BEGIN moved to one enclosing transaction
CREATE OR REPLACE FUNCTION public.marketpro_team_pull_page(
 p_workspace_id uuid,
 p_device_id uuid,
 p_entity_type text,
 p_after_updated_at timestamptz DEFAULT NULL,
 p_after_id uuid DEFAULT NULL,
 p_page_size integer DEFAULT 50
) RETURNS jsonb
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = ''
AS $pull$
DECLARE
 v_user uuid := auth.uid();
 v_table text;
 v_rows jsonb;
 v_count integer;
 v_more boolean;
 v_next_updated timestamptz;
 v_next_id uuid;
 v_today date := (clock_timestamp() AT TIME ZONE 'Asia/Colombo')::date;
BEGIN
 IF v_user IS NULL OR coalesce((auth.jwt()->>'is_anonymous')::boolean,false) THEN
  RAISE EXCEPTION 'Authenticated user required for download' USING ERRCODE='42501';
 END IF;
 IF p_workspace_id IS NULL OR p_device_id IS NULL OR
    p_entity_type NOT IN ('category','item','requisition','purchase',
                          'purchase_addition','daily_cash_received','daily_other_expenses') OR
    p_entity_type IS NULL OR
    p_page_size IS NULL OR p_page_size NOT BETWEEN 1 AND 100 OR
    ((p_after_updated_at IS NULL) <> (p_after_id IS NULL)) THEN
  RAISE EXCEPTION 'Invalid pull request' USING ERRCODE='22023';
 END IF;
 -- Serialize with Owner replacement/revoke actions before reading rows.
 PERFORM pg_catalog.pg_advisory_xact_lock(
   pg_catalog.hashtextextended(p_workspace_id::text,0)
 );
 -- Readers must be known users and enrolled devices, not arbitrary members.
 -- can_write is deliberately NOT required for a read-only phone.
 IF NOT EXISTS (
  SELECT 1 FROM public.workspace_members m
  JOIN public.registered_devices d
    ON d.workspace_id=m.workspace_id AND d.user_id=m.user_id
  WHERE m.workspace_id=p_workspace_id AND m.user_id=v_user
    AND m.role IN ('owner','purchasing_staff','viewer')
    AND d.id=p_device_id AND d.enabled=true
    AND (m.role<>'purchasing_staff' OR
      marketpro_private.is_verified_staff_session(p_workspace_id,p_device_id))
 ) THEN
  RAISE EXCEPTION 'Unenrolled or revoked reader' USING ERRCODE='42501';
 END IF;

 -- Closed allowlist: NO invoice photo bytes, no raw server Auth information.
 v_table := CASE p_entity_type
  WHEN 'category' THEN 'categories'
  WHEN 'item' THEN 'items'
  WHEN 'requisition' THEN 'requisitions'
  WHEN 'purchase' THEN 'purchases'
  WHEN 'purchase_addition' THEN 'purchase_additions'
  WHEN 'daily_cash_received' THEN 'daily_cash_received'
  WHEN 'daily_other_expenses' THEN 'daily_other_expenses'
 END;
 EXECUTE pg_catalog.format(
   'SELECT coalesce(pg_catalog.jsonb_agg(to_jsonb(r) ORDER BY r.updated_at,r.id),
                     ''[]''::jsonb)
    FROM (
      SELECT * FROM public.%I
      WHERE workspace_id=$1 AND
            ($2::timestamptz IS NULL OR
             (updated_at,id) > ($2::timestamptz,$3::uuid))
      ORDER BY updated_at ASC,id ASC
      LIMIT $4
    ) AS r',v_table
 ) INTO v_rows USING p_workspace_id,p_after_updated_at,p_after_id,p_page_size+1;
 v_count := pg_catalog.jsonb_array_length(v_rows);
 v_more := v_count > p_page_size;
 IF v_more THEN
  v_rows := v_rows - (v_count - 1);
 END IF;
 v_count := pg_catalog.jsonb_array_length(v_rows);
 IF v_count>0 THEN
  v_next_updated := (v_rows->(v_count-1)->>'updated_at')::timestamptz;
  v_next_id := (v_rows->(v_count-1)->>'id')::uuid;
 ELSE
  v_next_updated := p_after_updated_at;
  v_next_id := p_after_id;
 END IF;
 RETURN pg_catalog.jsonb_build_object(
  'entity_type',p_entity_type,'rows',v_rows,'has_more',v_more,
  'next_updated_at',v_next_updated,'next_id',v_next_id,
  'server_today_colombo',v_today,
  'server_time_utc',clock_timestamp()
 );
END;
$pull$;
REVOKE ALL ON FUNCTION public.marketpro_team_pull_page(
 uuid,uuid,text,timestamptz,uuid,integer) FROM PUBLIC,anon;
GRANT EXECUTE ON FUNCTION public.marketpro_team_pull_page(
 uuid,uuid,text,timestamptz,uuid,integer) TO authenticated;
-- TOP-LEVEL COMMIT moved to one enclosing transaction


-- SOURCE: supabase/proposals/two_phone_atomic_proof_financial_write_REVIEW_ONLY.sql
-- MARKET PRO — TWO-PHONE V3.7.7 ATOMIC STAFF DEVICE PROOF + FINANCIAL WRITE
-- REVIEW ONLY. NOT LIVE. Apply only AFTER audited V352..V375 and
-- two_phone_other_expenses_REVIEW_ONLY. This REPLACES direct public Staff
-- business RPC privileges; legacy Owner marketpro_apply_change is UNTOUCHED.
-- Deployment MUST be a single reviewed cutover transaction.
-- TOP-LEVEL BEGIN moved to one enclosing transaction

-- Staff MUST NOT bypass a fresh signed device challenge by calling older
-- SECURITY DEFINER v2 financial RPCs with a copied Staff bearer JWT.
REVOKE ALL ON FUNCTION public.marketpro_apply_team_purchase_change(
 uuid,uuid,uuid,uuid,text,bigint,jsonb) FROM PUBLIC,anon,authenticated;
REVOKE ALL ON FUNCTION public.marketpro_apply_team_financial_change(
 uuid,uuid,uuid,text,uuid,text,bigint,jsonb) FROM PUBLIC,anon,authenticated;
REVOKE ALL ON FUNCTION public.marketpro_apply_team_other_expense(
 uuid,uuid,uuid,uuid,text,bigint,jsonb) FROM PUBLIC,anon,authenticated;

-- Request commitment is issued under a CURRENT Staff JWT before Android
-- signs the device challenge. Prevents an intercepted valid signed nonce from
-- being reused with a different financial operation / amount / revision.
CREATE TABLE IF NOT EXISTS marketpro_private.staff_finance_challenge_requests (
 challenge_id uuid PRIMARY KEY REFERENCES
   marketpro_private.staff_device_challenges(challenge_id) ON DELETE CASCADE,
 request_sha256 text NOT NULL CHECK (request_sha256 ~ '^[0-9a-f]{64}$'),
 issued_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
REVOKE ALL ON marketpro_private.staff_finance_challenge_requests
 FROM PUBLIC,anon,authenticated;

CREATE OR REPLACE FUNCTION marketpro_private.finance_request_digest(
 p_workspace_id uuid,p_device_id uuid,p_staff_user_id uuid,
 p_operation_id uuid,p_entity_type text,p_entity_id uuid,
 p_action text,p_expected_revision bigint,p_payload jsonb
) RETURNS text
LANGUAGE sql IMMUTABLE SET search_path=''
AS $digest$
 SELECT pg_catalog.encode(extensions.digest(
    pg_catalog.convert_to(
      p_workspace_id::text || ':' || p_device_id::text || ':' ||
      p_staff_user_id::text || ':' || p_operation_id::text || ':' ||
      p_entity_type || ':' || p_entity_id::text || ':' || p_action || ':' ||
      COALESCE(p_expected_revision::text,'NULL') || ':' || p_payload::text,
      'UTF8'
    ),'sha256'
 ),'hex');
$digest$;
REVOKE ALL ON FUNCTION marketpro_private.finance_request_digest(
 uuid,uuid,uuid,uuid,text,uuid,text,bigint,jsonb)
 FROM PUBLIC,anon,authenticated;

-- Call only with the actual authenticated Staff JWT and enrolled device.
-- The underlying V375 issuer separately checks current Auth session, public
-- key approval, Staff role, Owner writer lease, and one-live-challenge lock.
CREATE OR REPLACE FUNCTION public.marketpro_staff_issue_finance_challenge(
 p_workspace_id uuid,p_device_id uuid,p_operation_id uuid,
 p_entity_type text,p_entity_id uuid,p_action text,
 p_expected_revision bigint,p_payload jsonb
) RETURNS jsonb
LANGUAGE plpgsql VOLATILE SECURITY DEFINER SET search_path=''
AS $issue_finance$
DECLARE
 v_staff uuid:=auth.uid();
 v_challenge jsonb;
 v_hash text;
 v_approved_fingerprint text;
BEGIN
 IF v_staff IS NULL OR p_workspace_id IS NULL OR p_device_id IS NULL
 OR p_operation_id IS NULL OR p_entity_id IS NULL
 OR p_entity_type NOT IN('purchase','purchase_addition','requisition',
    'daily_cash_received','daily_other_expenses')
 OR p_entity_type IS NULL OR p_action NOT IN('create','correct')
 OR p_action IS NULL OR p_payload IS NULL OR
 pg_catalog.jsonb_typeof(p_payload)<>'object' OR
 pg_catalog.octet_length(p_payload::text)>4096 OR
 (p_action='create' AND p_expected_revision IS NOT NULL) OR
 (p_action='correct' AND
     (p_expected_revision IS NULL OR p_expected_revision<1)) THEN
  RAISE EXCEPTION 'Invalid Staff finance intent' USING ERRCODE='22023';
 END IF;
 v_challenge:=public.marketpro_staff_issue_device_challenge(
   p_workspace_id,p_device_id,'write'
 );
 IF v_challenge->>'staff_user_id'<>v_staff::text OR
    v_challenge->>'device_id'<>p_device_id::text THEN
   RAISE EXCEPTION 'Signed session mismatched finance intent'
     USING ERRCODE='42501';
 END IF;
 v_hash:=marketpro_private.finance_request_digest(
   p_workspace_id,p_device_id,v_staff,p_operation_id,
   p_entity_type,p_entity_id,p_action,p_expected_revision,p_payload
 );
 INSERT INTO marketpro_private.staff_finance_challenge_requests(
   challenge_id,request_sha256
 ) VALUES((v_challenge->>'challenge_id')::uuid,v_hash);
 -- The phone may sign ONLY if its local AndroidKeyStore SPKI fingerprint
 -- is identical to the still Owner-approved public key in the private DB.
 -- This value is advisory for local matching; SQL/Edge independently
 -- recheck the actual approved SPKI on submit.
 SELECT k.fingerprint_sha256 INTO v_approved_fingerprint
 FROM marketpro_private.staff_device_public_keys k
 WHERE k.workspace_id=p_workspace_id AND k.staff_user_id=v_staff
   AND k.device_id=p_device_id AND k.approved AND k.revoked_at IS NULL;
 IF v_approved_fingerprint IS NULL THEN
    RAISE EXCEPTION 'Staff phone signing key is not approved'
       USING ERRCODE='42501';
 END IF;
 RETURN v_challenge || pg_catalog.jsonb_build_object(
   'key_fingerprint_sha256',v_approved_fingerprint
 );
END;
$issue_finance$;
REVOKE ALL ON FUNCTION public.marketpro_staff_issue_finance_challenge(
 uuid,uuid,uuid,text,uuid,text,bigint,jsonb) FROM PUBLIC,anon;
GRANT EXECUTE ON FUNCTION public.marketpro_staff_issue_finance_challenge(
 uuid,uuid,uuid,text,uuid,text,bigint,jsonb) TO authenticated;

-- This function is the *only* service-role entry point for Staff writes.
-- The authenticated Edge verifier must validate the actual P-256 signature
-- using the previously Owner-approved SPKI before invoking this RPC.
-- Signature validation alone never grants access: this function atomically
-- consumes ONE fresh challenge and applies ONE checked financial operation
-- in the same DB transaction. An exception rolls back BOTH effects.
CREATE OR REPLACE FUNCTION public.marketpro_service_apply_proof_bound_finance(
 p_challenge_id uuid,
 p_staff_user_id uuid,
 p_session_id uuid,
 p_operation_id uuid,
 p_entity_type text,
 p_entity_id uuid,
 p_action text,
 p_expected_revision bigint,
 p_payload jsonb
) RETURNS jsonb
LANGUAGE plpgsql VOLATILE SECURITY DEFINER SET search_path=''
AS $atomic$
DECLARE
 v_material jsonb;
 v_workspace uuid;
 v_device uuid;
 v_proof jsonb;
 v_written jsonb;
 v_old_sub text;
 v_old_claims text;
BEGIN
 IF p_challenge_id IS NULL OR p_staff_user_id IS NULL OR
    p_session_id IS NULL OR p_operation_id IS NULL OR
    p_entity_id IS NULL OR p_action NOT IN ('create','correct') OR
    p_action IS NULL OR
    p_entity_type NOT IN (
      'purchase','purchase_addition','requisition',
      'daily_cash_received','daily_other_expenses'
    ) OR p_entity_type IS NULL OR p_payload IS NULL OR
    pg_catalog.jsonb_typeof(p_payload)<>'object' OR
    pg_catalog.octet_length(p_payload::text)>4096 THEN
  RAISE EXCEPTION 'Invalid signed finance request'
    USING ERRCODE='22023';
 END IF;

 -- The same workspace lock is held by challenge issuance, Owner phone
 -- revoke/replacement, Staff leases and all legacy v2 write RPCs.
 -- The first lookup is only used to discover which workspace to lock.
 SELECT c.workspace_id INTO v_workspace
 FROM marketpro_private.staff_device_challenges c
 WHERE c.challenge_id=p_challenge_id
   AND c.staff_user_id=p_staff_user_id AND c.session_id=p_session_id;
 IF v_workspace IS NULL THEN
   RAISE EXCEPTION 'Missing or unrelated Staff proof' USING ERRCODE='42501';
 END IF;
 PERFORM pg_catalog.pg_advisory_xact_lock(
   pg_catalog.hashtextextended(v_workspace::text,0)
 );

 -- Obtain the *current* approved key/registered active device/session/lease
 -- under the workspace transaction lock (including current auth.sessions).
 v_material:=public.marketpro_service_device_challenge_material(
   p_challenge_id,p_staff_user_id,p_session_id
 );
 IF v_material->>'purpose'<>'write' OR
    v_material->>'workspace_id'<>v_workspace::text OR
    v_material->>'staff_user_id'<>p_staff_user_id::text OR
    v_material->>'session_id'<>p_session_id::text THEN
   RAISE EXCEPTION 'Device challenge does not authorize this operation'
      USING ERRCODE='42501';
 END IF;
 v_device:=(v_material->>'device_id')::uuid;

 -- The Staff JWT (not a caller-chosen account) committed to these EXACT
 -- server-side financial fields BEFORE the device signed this challenge.
 -- Reject changed quantities, amounts, business date, UUID, or revision.
 IF NOT EXISTS (
   SELECT 1 FROM marketpro_private.staff_finance_challenge_requests f
   WHERE f.challenge_id=p_challenge_id AND
     f.request_sha256=marketpro_private.finance_request_digest(
       v_workspace,v_device,p_staff_user_id,p_operation_id,p_entity_type,
       p_entity_id,p_action,p_expected_revision,p_payload
     )
 ) THEN
   RAISE EXCEPTION 'Signed proof is not bound to this financial request'
     USING ERRCODE='42501';
 END IF;

 -- Consume and store proof INSIDE the financial transaction. If the
 -- financial write fails/revisions conflict, the nonce remains unconsumed
 -- because PostgreSQL rolls back the entire transaction.
 v_proof:=public.marketpro_service_consume_device_challenge(
   p_challenge_id,p_staff_user_id,p_session_id
 );
 IF v_proof->>'proof_verified_once'<>'true' OR
    v_proof->>'device_id'<>v_device::text OR
    v_proof->>'business_write_permission_granted'<>'false' THEN
   RAISE EXCEPTION 'Invalid atomic proof consume'
     USING ERRCODE='42501';
 END IF;

 -- Nested existing finance RPCs use auth.uid()/auth.jwt(). This restricted
 -- service-only function sets a verified identity into LOCAL PostgreSQL GUCs
 -- solely for this call, instead of accepting arbitrary Android claims.
 -- The DB has already checked current Auth session, enrolled Staff account,
 -- enabled physical device, private Owner lease and server proof.
 v_old_sub:=pg_catalog.current_setting('request.jwt.claim.sub',true);
 v_old_claims:=pg_catalog.current_setting('request.jwt.claims',true);
 PERFORM pg_catalog.set_config('request.jwt.claim.sub',
   p_staff_user_id::text,true);
 PERFORM pg_catalog.set_config('request.jwt.claims',
   pg_catalog.jsonb_build_object(
     'sub',p_staff_user_id,'session_id',p_session_id,
     'role','authenticated','is_anonymous',false
   )::text,true);

 IF p_entity_type='purchase' THEN
   v_written:=public.marketpro_apply_team_purchase_change(
     v_workspace,v_device,p_operation_id,p_entity_id,p_action,
     p_expected_revision,p_payload
   );
 ELSIF p_entity_type='daily_other_expenses' THEN
   v_written:=public.marketpro_apply_team_other_expense(
     v_workspace,v_device,p_operation_id,p_entity_id,p_action,
     p_expected_revision,p_payload
   );
 ELSE
   v_written:=public.marketpro_apply_team_financial_change(
     v_workspace,v_device,p_operation_id,p_entity_type,p_entity_id,
     p_action,p_expected_revision,p_payload
   );
 END IF;

 -- Roll back if an RPC responds without the expected immutable ACK.
 IF v_written->>'accepted'<>'true' OR
    v_written->>'operation_id'<>p_operation_id::text OR
    coalesce((v_written->>'revision')::bigint,0)<1 THEN
   RAISE EXCEPTION 'Financial change was not durably acknowledged'
      USING ERRCODE='40001';
 END IF;

 PERFORM pg_catalog.set_config('request.jwt.claim.sub',
   coalesce(v_old_sub,''),true);
 PERFORM pg_catalog.set_config('request.jwt.claims',
   coalesce(v_old_claims,''),true);

 RETURN pg_catalog.jsonb_build_object(
   'accepted',true,'operation_id',p_operation_id,
   'entity_id',p_entity_id,'entity_type',p_entity_type,
   'revision',(v_written->>'revision')::bigint,
   'replayed',coalesce((v_written->>'replayed')::boolean,false),
   'challenge_consumed',true
 );
END;
$atomic$;

REVOKE ALL ON FUNCTION public.marketpro_service_apply_proof_bound_finance(
 uuid,uuid,uuid,uuid,text,uuid,text,bigint,jsonb)
 FROM PUBLIC,anon,authenticated;
GRANT EXECUTE ON FUNCTION public.marketpro_service_apply_proof_bound_finance(
 uuid,uuid,uuid,uuid,text,uuid,text,bigint,jsonb) TO service_role;

-- Critical invariant: leave Owner's installed V1 writer intact.
-- marketpro_apply_change(UUID,UUID,UUID,text,UUID,text,jsonb) unchanged.
-- TOP-LEVEL COMMIT moved to one enclosing transaction


-- SOURCE: supabase/proposals/two_phone_owner_staff_key_enrollment_REVIEW_ONLY.sql
-- MARKET PRO TWO-PHONE STAFF DEVICE KEY ENROLLMENT — REVIEW ONLY.
-- NEVER RUN AGAINST LIVE. Requires audited V358, V359, V368, V372,
-- V375, finance proof RPC, and secure RLS BEFORE Staff membership.
-- No automatic Staff key approval, no Owner v1 writer modification.
-- TOP-LEVEL BEGIN moved to one enclosing transaction

CREATE TABLE IF NOT EXISTS marketpro_private.staff_key_enrollment_requests (
  workspace_id uuid PRIMARY KEY,
  enrollment_id uuid NOT NULL UNIQUE DEFAULT extensions.gen_random_uuid(),
  staff_user_id uuid NOT NULL,
  device_id uuid NOT NULL,
  session_id uuid NOT NULL,
  nonce_hex text NOT NULL CHECK(nonce_hex ~ '^[0-9a-f]{64}$'),
  spki_der bytea NOT NULL CHECK(octet_length(spki_der) BETWEEN 64 AND 512),
  fingerprint_sha256 text NOT NULL
    CHECK(fingerprint_sha256 ~ '^[0-9a-f]{64}$'),
  issued_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  expires_at timestamptz NOT NULL,
  proof_verified_at timestamptz,
  consumed_at timestamptz,
  CHECK(expires_at>issued_at AND
    expires_at<=issued_at+interval '120 seconds')
);
REVOKE ALL ON marketpro_private.staff_key_enrollment_requests
 FROM PUBLIC,anon,authenticated;

-- Staff must ALREADY have authenticated/accepted/Owner-activated Read-Only
-- device session. This new key is PENDING and gives zero financial rights.
CREATE OR REPLACE FUNCTION public.marketpro_staff_begin_key_enrollment(
 p_workspace_id uuid,p_device_id uuid,p_spki_base64 text
) RETURNS jsonb
LANGUAGE plpgsql VOLATILE SECURITY DEFINER SET search_path=''
AS $begin_key$
DECLARE
 v_actor uuid:=auth.uid();
 v_sid_text text:=auth.jwt()->>'session_id';
 v_sid uuid;
 v_spki bytea;
 v_fp text;
 v_nonce text;
 v_id uuid:=extensions.gen_random_uuid();
 v_now timestamptz:=clock_timestamp();
 v_exp timestamptz:=v_now+interval '60 seconds';
BEGIN
 IF v_actor IS NULL OR p_workspace_id IS NULL OR p_device_id IS NULL
    OR p_spki_base64 IS NULL OR length(p_spki_base64)>720
    OR p_spki_base64 !~ '^[a-zA-Z0-9+/]+={0,2}$'
    OR coalesce((auth.jwt()->>'is_anonymous')::boolean,false)
    OR v_sid_text IS NULL OR
    v_sid_text !~* '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'
 THEN
  RAISE EXCEPTION 'Verified Staff signing-key registration required'
    USING ERRCODE='42501';
 END IF;
 v_sid:=v_sid_text::uuid;
 v_spki:=pg_catalog.decode(p_spki_base64,'base64');
 IF octet_length(v_spki) NOT BETWEEN 64 AND 512 THEN
  RAISE EXCEPTION 'Invalid EC public key size' USING ERRCODE='22023';
 END IF;
 v_fp:=pg_catalog.encode(extensions.digest(v_spki,'sha256'),'hex');

 PERFORM pg_catalog.pg_advisory_xact_lock(
   pg_catalog.hashtextextended(p_workspace_id::text,0));
 IF NOT marketpro_private.is_verified_staff_session(
      p_workspace_id,p_device_id
    ) OR NOT EXISTS(
    SELECT 1 FROM public.workspace_members m
    JOIN public.registered_devices d ON
      d.workspace_id=m.workspace_id AND d.user_id=m.user_id
    JOIN marketpro_private.team_rollout_gate g ON
      g.workspace_id=m.workspace_id
    WHERE m.workspace_id=p_workspace_id AND m.user_id=v_actor
      AND m.role='purchasing_staff' AND d.id=p_device_id
      AND d.enabled AND NOT d.can_write
      AND g.staff_direct_rest_restricted AND g.team_pull_rpc_reviewed
    ) OR EXISTS(
      SELECT 1 FROM marketpro_private.staff_device_public_keys k
      WHERE k.workspace_id=p_workspace_id AND
        k.approved AND k.revoked_at IS NULL
    ) THEN
  RAISE EXCEPTION 'No activated Staff phone or key already approved'
    USING ERRCODE='42501';
 END IF;

 -- Reissue replaces previous unapproved request atomically. Owner must
 -- compare exact pending fingerprint; a newly issued challenge cannot
 -- silently switch an Owner-approved fingerprint.
 v_nonce:=pg_catalog.encode(extensions.gen_random_bytes(32),'hex');
 INSERT INTO marketpro_private.staff_key_enrollment_requests (
   workspace_id,enrollment_id,staff_user_id,device_id,session_id,
   nonce_hex,spki_der,fingerprint_sha256,issued_at,expires_at
 ) VALUES (
   p_workspace_id,v_id,v_actor,p_device_id,v_sid,
   v_nonce,v_spki,v_fp,v_now,v_exp
 )
 ON CONFLICT(workspace_id) DO UPDATE SET
   enrollment_id=excluded.enrollment_id,
   staff_user_id=excluded.staff_user_id,
   device_id=excluded.device_id,
   session_id=excluded.session_id,
   nonce_hex=excluded.nonce_hex,
   spki_der=excluded.spki_der,
   fingerprint_sha256=excluded.fingerprint_sha256,
   issued_at=excluded.issued_at,
   expires_at=excluded.expires_at,
   proof_verified_at=NULL,consumed_at=NULL;

 RETURN pg_catalog.jsonb_build_object(
   'workspace_id',p_workspace_id,'staff_user_id',v_actor,
   'device_id',p_device_id,'session_id',v_sid,
   'challenge_id',v_id,'nonce_hex',v_nonce,'purpose','pair',
   'issued_at_millis',pg_catalog.floor(EXTRACT(epoch FROM v_now)*1000)::bigint,
   'expires_at_millis',pg_catalog.floor(EXTRACT(epoch FROM v_exp)*1000)::bigint,
   'key_fingerprint_sha256',v_fp
 );
END;
$begin_key$;
REVOKE ALL ON FUNCTION public.marketpro_staff_begin_key_enrollment(
 uuid,uuid,text) FROM PUBLIC,anon;
GRANT EXECUTE ON FUNCTION public.marketpro_staff_begin_key_enrollment(
 uuid,uuid,text) TO authenticated;

-- Service-only read: revalidates bound Supabase Auth session and accepted
-- invitation even if the App still holds a once-valid Staff bearer token.
CREATE OR REPLACE FUNCTION public.marketpro_service_key_enrollment_material(
 p_challenge_id uuid,p_staff_user_id uuid,p_session_id uuid
) RETURNS jsonb
LANGUAGE plpgsql VOLATILE SECURITY DEFINER SET search_path=''
AS $material_key$
DECLARE v jsonb;
BEGIN
 SELECT pg_catalog.jsonb_build_object(
   'workspace_id',e.workspace_id,'staff_user_id',e.staff_user_id,
   'device_id',e.device_id,'session_id',e.session_id,
   'challenge_id',e.enrollment_id,'nonce_hex',e.nonce_hex,
   'purpose','pair','issued_at_millis',
      pg_catalog.floor(EXTRACT(epoch FROM e.issued_at)*1000)::bigint,
   'expires_at_millis',
      pg_catalog.floor(EXTRACT(epoch FROM e.expires_at)*1000)::bigint,
   'spki_base64',pg_catalog.encode(e.spki_der,'base64'),
   'key_fingerprint_sha256',e.fingerprint_sha256
 ) INTO v
 FROM marketpro_private.staff_key_enrollment_requests e
 JOIN public.workspace_members m
   ON m.workspace_id=e.workspace_id AND m.user_id=e.staff_user_id
 JOIN public.registered_devices d
   ON d.workspace_id=e.workspace_id AND d.user_id=e.staff_user_id
      AND d.id=e.device_id
 JOIN marketpro_private.staff_session_bindings b
   ON b.workspace_id=e.workspace_id AND b.staff_user_id=e.staff_user_id
      AND b.device_id=e.device_id AND b.session_id=e.session_id
 JOIN auth.sessions s
   ON s.id=e.session_id AND s.user_id=e.staff_user_id
 JOIN marketpro_private.pending_team_enrollments i
   ON i.workspace_id=e.workspace_id AND i.staff_user_id=e.staff_user_id
 JOIN marketpro_private.team_rollout_gate g
   ON g.workspace_id=e.workspace_id
 WHERE e.enrollment_id=p_challenge_id
   AND e.staff_user_id=p_staff_user_id AND e.session_id=p_session_id
   AND e.consumed_at IS NULL AND e.expires_at>clock_timestamp()
   AND e.issued_at<=clock_timestamp()
   AND m.role='purchasing_staff' AND d.enabled AND NOT d.can_write
   AND b.revoked_at IS NULL
   AND (s.not_after IS NULL OR s.not_after>clock_timestamp())
   AND i.intended_device_id=e.device_id AND i.status='accepted'
   AND i.revoked_at IS NULL AND i.accepted_at IS NOT NULL
   AND i.activated_at IS NOT NULL
   AND g.staff_direct_rest_restricted AND g.team_pull_rpc_reviewed
   AND NOT EXISTS(
      SELECT 1 FROM marketpro_private.staff_device_public_keys k
      WHERE k.workspace_id=e.workspace_id
        AND k.approved AND k.revoked_at IS NULL
   );
 IF v IS NULL THEN
   RAISE EXCEPTION 'Staff key challenge not active'
     USING ERRCODE='42501';
 END IF;
 RETURN v;
END;
$material_key$;
REVOKE ALL ON FUNCTION public.marketpro_service_key_enrollment_material(
 uuid,uuid,uuid) FROM PUBLIC,anon,authenticated;
GRANT EXECUTE ON FUNCTION public.marketpro_service_key_enrollment_material(
 uuid,uuid,uuid) TO service_role;

-- MUST be called only by Edge after verifying actual P-256 ECDSA signature
-- of the server nonce using this pending (not yet approved) SPKI.
CREATE OR REPLACE FUNCTION public.marketpro_service_prove_key_enrollment(
 p_challenge_id uuid,p_staff_user_id uuid,p_session_id uuid
) RETURNS jsonb
LANGUAGE plpgsql VOLATILE SECURITY DEFINER SET search_path=''
AS $prove_key$
DECLARE
 v_material jsonb;
 v_workspace uuid;
 v_updated integer;
BEGIN
 SELECT e.workspace_id INTO v_workspace
 FROM marketpro_private.staff_key_enrollment_requests e
 WHERE e.enrollment_id=p_challenge_id AND e.staff_user_id=p_staff_user_id
   AND e.session_id=p_session_id;
 IF v_workspace IS NULL THEN
  RAISE EXCEPTION 'Unknown key enrollment' USING ERRCODE='42501';
 END IF;
 PERFORM pg_catalog.pg_advisory_xact_lock(
   pg_catalog.hashtextextended(v_workspace::text,0));
 v_material:=public.marketpro_service_key_enrollment_material(
   p_challenge_id,p_staff_user_id,p_session_id
 );
 UPDATE marketpro_private.staff_key_enrollment_requests
 SET proof_verified_at=clock_timestamp(),consumed_at=clock_timestamp()
 WHERE enrollment_id=p_challenge_id AND staff_user_id=p_staff_user_id
   AND session_id=p_session_id AND proof_verified_at IS NULL
   AND consumed_at IS NULL AND expires_at>clock_timestamp();
 GET DIAGNOSTICS v_updated=ROW_COUNT;
 IF v_updated<>1 THEN
  RAISE EXCEPTION 'Staff key enrollment proof already consumed'
    USING ERRCODE='42501';
 END IF;
 RETURN pg_catalog.jsonb_build_object(
  'proof_of_possession_verified',true,
  'key_pending_owner_approval',true,
  'challenge_id',p_challenge_id,
  'key_fingerprint_sha256',v_material->>'key_fingerprint_sha256'
 );
END;
$prove_key$;
REVOKE ALL ON FUNCTION public.marketpro_service_prove_key_enrollment(
 uuid,uuid,uuid) FROM PUBLIC,anon,authenticated;
GRANT EXECUTE ON FUNCTION public.marketpro_service_prove_key_enrollment(
 uuid,uuid,uuid) TO service_role;

-- Owner can VIEW the pending fingerprint, not silently approve it.
-- Owner must compare it with the Staff handset out-of-band and type APPROVE.
CREATE OR REPLACE FUNCTION public.marketpro_owner_pending_key_review(
 p_workspace_id uuid,p_owner_device_id uuid,p_staff_user_id uuid,
 p_staff_device_id uuid
) RETURNS jsonb
LANGUAGE plpgsql SECURITY DEFINER SET search_path=''
AS $owner_review_key$
DECLARE v_owner uuid;
        v marketpro_private.staff_key_enrollment_requests%ROWTYPE;
BEGIN
 v_owner:=marketpro_private.require_enrolled_owner(
   p_workspace_id,p_owner_device_id
 );
 IF p_staff_user_id IS NULL OR p_staff_device_id IS NULL OR
    p_staff_user_id=v_owner THEN
   RAISE EXCEPTION 'Invalid Staff key review target'
     USING ERRCODE='42501';
 END IF;
 PERFORM pg_catalog.pg_advisory_xact_lock(
    pg_catalog.hashtextextended(p_workspace_id::text,0)
 );
 SELECT * INTO v
 FROM marketpro_private.staff_key_enrollment_requests
 WHERE workspace_id=p_workspace_id AND staff_user_id=p_staff_user_id
   AND device_id=p_staff_device_id AND consumed_at IS NOT NULL
   AND proof_verified_at IS NOT NULL
 FOR UPDATE;
 IF NOT FOUND THEN
   RAISE EXCEPTION 'No possession-proven pending key for this phone'
     USING ERRCODE='42501';
 END IF;
 RETURN pg_catalog.jsonb_build_object(
   'staff_user_id',v.staff_user_id,'staff_device_id',v.device_id,
   'key_fingerprint_sha256',v.fingerprint_sha256,
   'proof_of_possession_verified',true,'owner_approved',false
 );
END;
$owner_review_key$;
REVOKE ALL ON FUNCTION public.marketpro_owner_pending_key_review(
 uuid,uuid,uuid,uuid) FROM PUBLIC,anon;
GRANT EXECUTE ON FUNCTION public.marketpro_owner_pending_key_review(
 uuid,uuid,uuid,uuid) TO authenticated;

CREATE OR REPLACE FUNCTION public.marketpro_owner_approve_staff_signing_key(
 p_workspace_id uuid,p_owner_device_id uuid,p_staff_user_id uuid,
 p_staff_device_id uuid,p_expected_fingerprint text,
 p_confirmation text
) RETURNS jsonb
LANGUAGE plpgsql SECURITY DEFINER SET search_path=''
AS $approve_key$
DECLARE
 v_owner uuid;
 v marketpro_private.staff_key_enrollment_requests%ROWTYPE;
 v_rows integer;
BEGIN
 v_owner:=marketpro_private.require_enrolled_owner(
   p_workspace_id,p_owner_device_id
 );
 IF p_staff_user_id IS NULL OR p_staff_device_id IS NULL OR
    p_staff_user_id=v_owner OR
    p_expected_fingerprint IS NULL OR
    p_expected_fingerprint !~ '^[0-9a-f]{64}$' OR
    p_confirmation IS DISTINCT FROM 'APPROVE KEY' THEN
   RAISE EXCEPTION 'Explicit Owner matching-key confirmation required'
     USING ERRCODE='42501';
 END IF;
 PERFORM pg_catalog.pg_advisory_xact_lock(
   pg_catalog.hashtextextended(p_workspace_id::text,0)
 );
 SELECT * INTO v
 FROM marketpro_private.staff_key_enrollment_requests
 WHERE workspace_id=p_workspace_id AND staff_user_id=p_staff_user_id
   AND device_id=p_staff_device_id AND
   fingerprint_sha256=p_expected_fingerprint
   AND proof_verified_at IS NOT NULL AND consumed_at IS NOT NULL
 FOR UPDATE;
 IF NOT FOUND OR NOT EXISTS(
     SELECT 1 FROM public.workspace_members m
     JOIN public.registered_devices d
       ON d.workspace_id=m.workspace_id AND d.user_id=m.user_id
     JOIN marketpro_private.staff_session_bindings b
       ON b.workspace_id=m.workspace_id AND b.staff_user_id=m.user_id
         AND b.device_id=d.id AND b.revoked_at IS NULL
     JOIN auth.sessions s ON s.id=b.session_id AND s.user_id=b.staff_user_id
     JOIN marketpro_private.pending_team_enrollments i
       ON i.workspace_id=m.workspace_id AND i.staff_user_id=m.user_id
     WHERE m.workspace_id=p_workspace_id AND m.user_id=p_staff_user_id
       AND m.role='purchasing_staff' AND d.id=p_staff_device_id
       AND d.enabled AND NOT d.can_write
       AND b.session_id=v.session_id
       AND (s.not_after IS NULL OR s.not_after>clock_timestamp())
       AND i.intended_device_id=d.id AND i.status='accepted'
       AND i.revoked_at IS NULL AND i.activated_at IS NOT NULL
 ) THEN
   RAISE EXCEPTION 'Staff key proof, phone or session no longer valid'
     USING ERRCODE='42501';
 END IF;
 -- No silent key rotation while a previously approved device remains active.
 IF EXISTS(
    SELECT 1 FROM marketpro_private.staff_device_public_keys k
    WHERE k.workspace_id=p_workspace_id AND k.approved AND
      k.revoked_at IS NULL
 ) THEN
   RAISE EXCEPTION 'Revoke old Staff device/key before replacement'
      USING ERRCODE='42501';
 END IF;
 INSERT INTO marketpro_private.staff_device_public_keys(
   workspace_id,staff_user_id,device_id,spki_der,fingerprint_sha256,
   approved,approved_at,revoked_at
 ) VALUES (
   p_workspace_id,p_staff_user_id,p_staff_device_id,
   v.spki_der,v.fingerprint_sha256,true,clock_timestamp(),NULL
 )
 ON CONFLICT(workspace_id) DO UPDATE SET
   staff_user_id=excluded.staff_user_id,
   device_id=excluded.device_id,
   spki_der=excluded.spki_der,
   fingerprint_sha256=excluded.fingerprint_sha256,
   approved=true,approved_at=clock_timestamp(),revoked_at=NULL
 WHERE marketpro_private.staff_device_public_keys.revoked_at IS NOT NULL;
 GET DIAGNOSTICS v_rows=ROW_COUNT;
 IF v_rows<>1 THEN
    RAISE EXCEPTION 'Previously approved device key still active'
      USING ERRCODE='42501';
 END IF;
 DELETE FROM marketpro_private.staff_key_enrollment_requests
 WHERE workspace_id=p_workspace_id;
 RETURN pg_catalog.jsonb_build_object(
  'key_approved',true,'staff_device_id',p_staff_device_id,
  'key_fingerprint_sha256',p_expected_fingerprint,
  'staff_writer_enabled',false,
  'legacy_owner_writer_unchanged',true
 );
END;
$approve_key$;
REVOKE ALL ON FUNCTION public.marketpro_owner_approve_staff_signing_key(
 uuid,uuid,uuid,uuid,text,text) FROM PUBLIC,anon;
GRANT EXECUTE ON FUNCTION public.marketpro_owner_approve_staff_signing_key(
 uuid,uuid,uuid,uuid,text,text) TO authenticated;

-- Lost phone: revoke enrolled signing key and current pairing/session/lease
-- in ONE transaction. Owner v1 writer is NOT disabled.
CREATE OR REPLACE FUNCTION public.marketpro_owner_revoke_staff_key_and_phone(
 p_workspace_id uuid,p_owner_device_id uuid,p_staff_user_id uuid,
 p_staff_device_id uuid
) RETURNS jsonb
LANGUAGE plpgsql SECURITY DEFINER SET search_path=''
AS $revoke_key$
DECLARE v_owner uuid;
        v_changed integer;
BEGIN
 v_owner:=marketpro_private.require_enrolled_owner(
   p_workspace_id,p_owner_device_id
 );
 IF p_staff_user_id IS NULL OR p_staff_device_id IS NULL OR
    p_staff_user_id=v_owner OR p_staff_device_id=p_owner_device_id THEN
   RAISE EXCEPTION 'Refusing to revoke Owner device'
     USING ERRCODE='42501';
 END IF;
 PERFORM pg_catalog.pg_advisory_xact_lock(
   pg_catalog.hashtextextended(p_workspace_id::text,0)
 );
 IF NOT EXISTS(
    SELECT 1 FROM public.workspace_members m
    JOIN public.registered_devices d
      ON d.workspace_id=m.workspace_id AND d.user_id=m.user_id
    WHERE m.workspace_id=p_workspace_id AND m.user_id=p_staff_user_id
      AND m.role='purchasing_staff' AND d.id=p_staff_device_id
 ) THEN
   RAISE EXCEPTION 'Staff device not linked to this workspace'
     USING ERRCODE='42501';
 END IF;
 UPDATE marketpro_private.staff_device_public_keys
 SET revoked_at=clock_timestamp(),approved=false
 WHERE workspace_id=p_workspace_id AND staff_user_id=p_staff_user_id
   AND device_id=p_staff_device_id AND revoked_at IS NULL;
 GET DIAGNOSTICS v_changed=ROW_COUNT;
 UPDATE public.registered_devices SET enabled=false,can_write=false
 WHERE workspace_id=p_workspace_id AND user_id=p_staff_user_id
   AND id=p_staff_device_id;
 UPDATE marketpro_private.staff_session_bindings
 SET revoked_at=clock_timestamp()
 WHERE workspace_id=p_workspace_id AND staff_user_id=p_staff_user_id
   AND device_id=p_staff_device_id AND revoked_at IS NULL;
 UPDATE marketpro_private.staff_write_leases
 SET enabled=false,revoked_at=clock_timestamp()
 WHERE workspace_id=p_workspace_id AND staff_user_id=p_staff_user_id
   AND device_id=p_staff_device_id AND enabled;
 UPDATE marketpro_private.pending_team_enrollments
 SET status='revoked',revoked_at=clock_timestamp()
 WHERE workspace_id=p_workspace_id AND staff_user_id=p_staff_user_id
   AND intended_device_id=p_staff_device_id
   AND status IN('invited','accepted');
 DELETE FROM marketpro_private.staff_key_enrollment_requests
 WHERE workspace_id=p_workspace_id AND staff_user_id=p_staff_user_id
   AND device_id=p_staff_device_id;
 UPDATE marketpro_private.staff_device_challenges
 SET consumed_at=clock_timestamp()
 WHERE workspace_id=p_workspace_id AND staff_user_id=p_staff_user_id
   AND device_id=p_staff_device_id AND consumed_at IS NULL;
 RETURN pg_catalog.jsonb_build_object(
   'staff_key_revoked',true,'staff_phone_disabled',true,
   'revoked_key_count',v_changed,
   'legacy_owner_writer_unchanged',true,
   'offline_old_phone_data_not_remotely_wiped',true
 );
END;
$revoke_key$;
REVOKE ALL ON FUNCTION public.marketpro_owner_revoke_staff_key_and_phone(
 uuid,uuid,uuid,uuid) FROM PUBLIC,anon;
GRANT EXECUTE ON FUNCTION public.marketpro_owner_revoke_staff_key_and_phone(
 uuid,uuid,uuid,uuid) TO authenticated;

-- TOP-LEVEL COMMIT moved to one enclosing transaction


-- SOURCE: supabase/proposals/two_phone_daily_summary_expense_reconciliation_REVIEW_ONLY.sql
-- MARKET PRO TWO-PHONE: consolidated cash-balance summary
-- REVIEW-ONLY. STAGING DB FIRST, never deploy alone to the current Owner.
-- Depends on existing Cloud V1 financial summary & reviewed Other Expenses table.
-- Keeps prior six columns in the SAME ORDER AND NUMERIC TYPES for Owner
-- dashboard backwards compatibility. Adds extra breakdown columns at end.
-- TOP-LEVEL BEGIN moved to one enclosing transaction
CREATE OR REPLACE VIEW public.daily_financial_summary
WITH (security_invoker=true,security_barrier=true)
AS
WITH lot_costs AS (
 SELECT workspace_id,business_date,'initial'::text AS lot_type,
   round(quantity_grams::numeric*price_cents_per_kg::numeric/1000,0) AS cost_cents
 FROM public.purchases WHERE deleted_at IS NULL
 UNION ALL
 SELECT workspace_id,business_date,'additional'::text AS lot_type,
   round(quantity_grams::numeric*price_cents_per_kg::numeric/1000,0) AS cost_cents
 FROM public.purchase_additions WHERE deleted_at IS NULL
),
daily_lots AS (
 SELECT workspace_id,business_date,
   sum(cost_cents) AS purchase_expenses_cents,
   sum(cost_cents) FILTER(WHERE lot_type='initial') AS initial_purchase_expenses_cents,
   sum(cost_cents) FILTER(WHERE lot_type='additional') AS additional_purchase_expenses_cents
 FROM lot_costs GROUP BY workspace_id,business_date
),
daily_reqs AS (
 SELECT workspace_id,business_date,
   sum(quantity_grams::numeric) AS required_grams
 FROM public.requisitions WHERE deleted_at IS NULL
 GROUP BY workspace_id,business_date
),
daily_cash AS (
 SELECT workspace_id,business_date,sum(amount_cents::numeric) AS cash_received_cents
 FROM public.daily_cash_received WHERE deleted_at IS NULL
 GROUP BY workspace_id,business_date
),
daily_other AS (
 SELECT workspace_id,business_date,
   sum(transport_cents::numeric) AS transport_expenses_cents,
   sum(labor_charge_cents::numeric) AS labor_charge_expenses_cents,
   sum(meals_drinks_cents::numeric) AS meals_drinks_expenses_cents,
   sum(others_cents::numeric) AS others_expenses_cents
 FROM public.daily_other_expenses
 GROUP BY workspace_id,business_date
),
all_dates AS (
 SELECT workspace_id,business_date FROM daily_lots
 UNION SELECT workspace_id,business_date FROM daily_reqs
 UNION SELECT workspace_id,business_date FROM daily_cash
 UNION SELECT workspace_id,business_date FROM daily_other
)
SELECT d.workspace_id,d.business_date,
  coalesce(r.required_grams,0::numeric) AS required_grams,
  coalesce(l.initial_purchase_expenses_cents,0::numeric) AS initial_purchase_expenses_cents,
  coalesce(l.additional_purchase_expenses_cents,0::numeric) AS additional_purchase_expenses_cents,
  coalesce(l.purchase_expenses_cents,0::numeric) +
     coalesce(e.transport_expenses_cents,0::numeric) +
     coalesce(e.labor_charge_expenses_cents,0::numeric) +
     coalesce(e.meals_drinks_expenses_cents,0::numeric) +
     coalesce(e.others_expenses_cents,0::numeric) AS total_expenses_cents,
  coalesce(c.cash_received_cents,0::numeric) AS cash_received_cents,
  coalesce(c.cash_received_cents,0::numeric) -
     coalesce(l.purchase_expenses_cents,0::numeric) -
     coalesce(e.transport_expenses_cents,0::numeric) -
     coalesce(e.labor_charge_expenses_cents,0::numeric) -
     coalesce(e.meals_drinks_expenses_cents,0::numeric) -
     coalesce(e.others_expenses_cents,0::numeric) AS cash_balance_cents,
  coalesce(l.purchase_expenses_cents,0::numeric) AS purchase_expenses_cents,
  coalesce(e.transport_expenses_cents,0::numeric) AS transport_expenses_cents,
  coalesce(e.labor_charge_expenses_cents,0::numeric) AS labor_charge_expenses_cents,
  coalesce(e.meals_drinks_expenses_cents,0::numeric) AS meals_drinks_expenses_cents,
  coalesce(e.others_expenses_cents,0::numeric) AS others_expenses_cents
FROM all_dates d
LEFT JOIN daily_lots l USING(workspace_id,business_date)
LEFT JOIN daily_reqs r USING(workspace_id,business_date)
LEFT JOIN daily_cash c USING(workspace_id,business_date)
LEFT JOIN daily_other e USING(workspace_id,business_date);

-- Never rely on definer-view privilege or broad membership for Staff reads.
ALTER VIEW public.daily_financial_summary
  SET (security_invoker=true,security_barrier=true);
REVOKE ALL ON public.daily_financial_summary FROM PUBLIC,anon;
GRANT SELECT ON public.daily_financial_summary TO authenticated;
-- TOP-LEVEL COMMIT moved to one enclosing transaction


-- SOURCE: supabase/proposals/two_phone_signed_staff_pull_REVIEW_ONLY.sql
-- REVIEW ONLY: source-accurate, per-page P-256 proof for Staff Cloud Pull.
-- Requires v375, Approved Staff keys, v372 session binding, Other Expenses
-- and two_phone_other_expenses_pull. Apply only as part of audited atomic cutover.
-- Owner's v1 writer, original Owner/Viewer pull and Dashboard stay unchanged.
-- TOP-LEVEL BEGIN moved to one enclosing transaction
CREATE TABLE marketpro_private.staff_read_page_intents (
  challenge_id uuid PRIMARY KEY REFERENCES
    marketpro_private.staff_device_challenges(challenge_id) ON DELETE CASCADE,
  request_sha256 text NOT NULL CHECK(request_sha256 ~ '^[0-9a-f]{64}$'),
  created_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
REVOKE ALL ON marketpro_private.staff_read_page_intents
  FROM PUBLIC,anon,authenticated;

-- Digest includes the exact query fields and Staff user/session/phone.
-- The signature covers random server challenge; DB binds that challenge to
-- this exact paged read intent before it is signed by AndroidKeyStore.
CREATE FUNCTION marketpro_private.read_page_digest(
 p_workspace_id uuid,p_staff_user_id uuid,p_device_id uuid,
 p_entity_type text,p_after_updated_at timestamptz,p_after_id uuid,
 p_page_size integer
) RETURNS text LANGUAGE sql IMMUTABLE SET search_path=''
AS $digest$
 SELECT pg_catalog.encode(extensions.digest(pg_catalog.convert_to(
   p_workspace_id::text || ':' || p_staff_user_id::text || ':' ||
   p_device_id::text || ':' || p_entity_type || ':' ||
   coalesce(p_after_updated_at::text,'NULL') || ':' ||
   coalesce(p_after_id::text,'NULL') || ':' || p_page_size::text,
   'UTF8'),'sha256'),'hex');
$digest$;
REVOKE ALL ON FUNCTION marketpro_private.read_page_digest(
 uuid,uuid,uuid,text,timestamptz,uuid,integer)
 FROM PUBLIC,anon,authenticated;

CREATE FUNCTION public.marketpro_staff_issue_read_page_challenge(
 p_workspace_id uuid,p_device_id uuid,p_entity_type text,
 p_after_updated_at timestamptz DEFAULT NULL,p_after_id uuid DEFAULT NULL,
 p_page_size integer DEFAULT 35
) RETURNS jsonb
LANGUAGE plpgsql VOLATILE SECURITY DEFINER SET search_path=''
AS $issue_read$
DECLARE
 v_staff uuid:=auth.uid();
 v_challenge jsonb;
 v_fp text;
BEGIN
 IF v_staff IS NULL OR p_workspace_id IS NULL OR p_device_id IS NULL
    OR p_entity_type NOT IN(
       'category','item','purchase','purchase_addition','requisition',
       'daily_cash_received','daily_other_expenses'
    ) OR p_entity_type IS NULL OR p_page_size IS NULL
    OR p_page_size NOT BETWEEN 1 AND 50 OR
    (p_after_updated_at IS NULL)<>(p_after_id IS NULL)
 THEN RAISE EXCEPTION 'Invalid Staff signed pull cursor' USING ERRCODE='22023';
 END IF;
 -- Existing issuer uses Auth signed session, Owner-approved key,
 -- registered device, and team rollout gate; no writer lease for READ.
 v_challenge:=public.marketpro_staff_issue_device_challenge(
    p_workspace_id,p_device_id,'read'
 );
 IF v_challenge->>'staff_user_id'<>v_staff::text OR
    v_challenge->>'workspace_id'<>p_workspace_id::text OR
    v_challenge->>'device_id'<>p_device_id::text
 THEN RAISE EXCEPTION 'Read challenge session mismatch' USING ERRCODE='42501';
 END IF;
 SELECT k.fingerprint_sha256 INTO v_fp
 FROM marketpro_private.staff_device_public_keys k
 WHERE k.workspace_id=p_workspace_id AND k.staff_user_id=v_staff
   AND k.device_id=p_device_id AND k.approved AND k.revoked_at IS NULL;
 IF v_fp IS NULL THEN
   RAISE EXCEPTION 'Staff read signing key revoked' USING ERRCODE='42501';
 END IF;
 INSERT INTO marketpro_private.staff_read_page_intents(
  challenge_id,request_sha256
 ) VALUES (
  (v_challenge->>'challenge_id')::uuid,
  marketpro_private.read_page_digest(
    p_workspace_id,v_staff,p_device_id,p_entity_type,
    p_after_updated_at,p_after_id,p_page_size
  )
 );
 RETURN v_challenge || pg_catalog.jsonb_build_object(
   'key_fingerprint_sha256',v_fp
 );
END;
$issue_read$;
REVOKE ALL ON FUNCTION public.marketpro_staff_issue_read_page_challenge(
 uuid,uuid,text,timestamptz,uuid,integer) FROM PUBLIC,anon;
GRANT EXECUTE ON FUNCTION public.marketpro_staff_issue_read_page_challenge(
 uuid,uuid,text,timestamptz,uuid,integer) TO authenticated;

-- Existing original direct pull is still usable by Owner and Viewer.
-- Staff is denied here. Staff read requires the SERVICE-ONLY signed path.
-- Preserve existing direct Owner/Viewer read API but REVOKE Staff JWT-based direct read without P-256 proof.
CREATE OR REPLACE FUNCTION public.marketpro_team_pull_page(
 p_workspace_id uuid,
 p_device_id uuid,
 p_entity_type text,
 p_after_updated_at timestamptz DEFAULT NULL,
 p_after_id uuid DEFAULT NULL,
 p_page_size integer DEFAULT 50
) RETURNS jsonb
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = ''
AS $pull$
DECLARE
 v_user uuid := auth.uid();
 v_table text;
 v_rows jsonb;
 v_count integer;
 v_more boolean;
 v_next_updated timestamptz;
 v_next_id uuid;
 v_today date := (clock_timestamp() AT TIME ZONE 'Asia/Colombo')::date;
BEGIN
 IF v_user IS NULL OR coalesce((auth.jwt()->>'is_anonymous')::boolean,false) THEN
  RAISE EXCEPTION 'Authenticated user required for download' USING ERRCODE='42501';
 END IF;
 IF p_workspace_id IS NULL OR p_device_id IS NULL OR
    p_entity_type NOT IN ('category','item','requisition','purchase',
                          'purchase_addition','daily_cash_received','daily_other_expenses') OR
    p_entity_type IS NULL OR
    p_page_size IS NULL OR p_page_size NOT BETWEEN 1 AND 100 OR
    ((p_after_updated_at IS NULL) <> (p_after_id IS NULL)) THEN
  RAISE EXCEPTION 'Invalid pull request' USING ERRCODE='22023';
 END IF;
 -- Serialize with Owner replacement/revoke actions before reading rows.
 PERFORM pg_catalog.pg_advisory_xact_lock(
   pg_catalog.hashtextextended(p_workspace_id::text,0)
 );
 -- Readers must be known users and enrolled devices, not arbitrary members.
 -- can_write is deliberately NOT required for a read-only phone.
 IF NOT EXISTS (
  SELECT 1 FROM public.workspace_members m
  JOIN public.registered_devices d
    ON d.workspace_id=m.workspace_id AND d.user_id=m.user_id
  WHERE m.workspace_id=p_workspace_id AND m.user_id=v_user
    AND m.role IN ('owner','viewer')
    AND d.id=p_device_id AND d.enabled=true
    AND (m.role<>'purchasing_staff' OR
      marketpro_private.is_verified_staff_session(p_workspace_id,p_device_id))
 ) THEN
  RAISE EXCEPTION 'Unenrolled or revoked reader' USING ERRCODE='42501';
 END IF;

 -- Closed allowlist: NO invoice photo bytes, no raw server Auth information.
 v_table := CASE p_entity_type
  WHEN 'category' THEN 'categories'
  WHEN 'item' THEN 'items'
  WHEN 'requisition' THEN 'requisitions'
  WHEN 'purchase' THEN 'purchases'
  WHEN 'purchase_addition' THEN 'purchase_additions'
  WHEN 'daily_cash_received' THEN 'daily_cash_received'
  WHEN 'daily_other_expenses' THEN 'daily_other_expenses'
 END;
 EXECUTE pg_catalog.format(
   'SELECT coalesce(pg_catalog.jsonb_agg(to_jsonb(r) ORDER BY r.updated_at,r.id),
                     ''[]''::jsonb)
    FROM (
      SELECT * FROM public.%I
      WHERE workspace_id=$1 AND
            ($2::timestamptz IS NULL OR
             (updated_at,id) > ($2::timestamptz,$3::uuid))
      ORDER BY updated_at ASC,id ASC
      LIMIT $4
    ) AS r',v_table
 ) INTO v_rows USING p_workspace_id,p_after_updated_at,p_after_id,p_page_size+1;
 v_count := pg_catalog.jsonb_array_length(v_rows);
 v_more := v_count > p_page_size;
 IF v_more THEN
  v_rows := v_rows - (v_count - 1);
 END IF;
 v_count := pg_catalog.jsonb_array_length(v_rows);
 IF v_count>0 THEN
  v_next_updated := (v_rows->(v_count-1)->>'updated_at')::timestamptz;
  v_next_id := (v_rows->(v_count-1)->>'id')::uuid;
 ELSE
  v_next_updated := p_after_updated_at;
  v_next_id := p_after_id;
 END IF;
 RETURN pg_catalog.jsonb_build_object(
  'entity_type',p_entity_type,'rows',v_rows,'has_more',v_more,
  'next_updated_at',v_next_updated,'next_id',v_next_id,
  'server_today_colombo',v_today,
  'server_time_utc',clock_timestamp()
 );
END;
$pull$;
REVOKE ALL ON FUNCTION public.marketpro_team_pull_page(
 uuid,uuid,text,timestamptz,uuid,integer) FROM PUBLIC,anon;
GRANT EXECUTE ON FUNCTION public.marketpro_team_pull_page(
 uuid,uuid,text,timestamptz,uuid,integer) TO authenticated;

CREATE FUNCTION public.marketpro_service_pull_signed_staff_page(
 p_challenge_id uuid,p_staff_user_id uuid,p_session_id uuid,
 p_workspace_id uuid,p_device_id uuid,p_entity_type text,
 p_after_updated_at timestamptz DEFAULT NULL,p_after_id uuid DEFAULT NULL,
 p_page_size integer DEFAULT 35
) RETURNS jsonb
LANGUAGE plpgsql VOLATILE SECURITY DEFINER SET search_path=''
AS $service_read$
DECLARE
 v_locked_workspace uuid;
 v_material jsonb;
 v_proof jsonb;
 v_table text;
 v_rows jsonb;
 v_count integer;
 v_has_more boolean;
 v_next_updated timestamptz;
 v_next_id uuid;
BEGIN
 IF p_challenge_id IS NULL OR p_staff_user_id IS NULL OR
    p_session_id IS NULL OR p_workspace_id IS NULL OR p_device_id IS NULL OR
    p_entity_type NOT IN(
      'category','item','purchase','purchase_addition','requisition',
      'daily_cash_received','daily_other_expenses'
    ) OR p_entity_type IS NULL OR p_page_size IS NULL OR
    p_page_size NOT BETWEEN 1 AND 50 OR
    (p_after_updated_at IS NULL)<>(p_after_id IS NULL)
 THEN RAISE EXCEPTION 'Invalid signed Staff page request' USING ERRCODE='22023';
 END IF;

 SELECT c.workspace_id INTO v_locked_workspace
 FROM marketpro_private.staff_device_challenges c
 WHERE c.challenge_id=p_challenge_id AND
   c.staff_user_id=p_staff_user_id AND c.session_id=p_session_id;
 IF v_locked_workspace IS DISTINCT FROM p_workspace_id THEN
   RAISE EXCEPTION 'Staff proof workspace mismatch' USING ERRCODE='42501';
 END IF;
 -- Same transactional workspace lock as Owner revoke and client challenges.
 PERFORM pg_catalog.pg_advisory_xact_lock(
   pg_catalog.hashtextextended(p_workspace_id::text,0)
 );
 v_material:=public.marketpro_service_device_challenge_material(
   p_challenge_id,p_staff_user_id,p_session_id
 );
 IF v_material->>'purpose'<>'read' OR
    v_material->>'workspace_id'<>p_workspace_id::text OR
    v_material->>'device_id'<>p_device_id::text OR
    v_material->>'staff_user_id'<>p_staff_user_id::text OR
    v_material->>'session_id'<>p_session_id::text
 THEN RAISE EXCEPTION 'Staff read proof context invalid'
      USING ERRCODE='42501';
 END IF;

 IF NOT EXISTS(
    SELECT 1 FROM marketpro_private.staff_read_page_intents i
    WHERE i.challenge_id=p_challenge_id AND
    i.request_sha256=marketpro_private.read_page_digest(
       p_workspace_id,p_staff_user_id,p_device_id,p_entity_type,
       p_after_updated_at,p_after_id,p_page_size
    )
 ) THEN
   RAISE EXCEPTION 'Signed nonce not committed to requested page'
     USING ERRCODE='42501';
 END IF;
 -- Signature verified by trusted Edge before this service-only RPC.
 -- Consume nonce and read the page within ONE transaction, under same lock.
 v_proof:=public.marketpro_service_consume_device_challenge(
   p_challenge_id,p_staff_user_id,p_session_id
 );
 IF v_proof->>'proof_verified_once'<>'true' OR
    v_proof->>'device_id'<>p_device_id::text OR
    v_proof->>'business_write_permission_granted'<>'false'
 THEN RAISE EXCEPTION 'Invalid one-use signed Staff read proof'
    USING ERRCODE='42501';
 END IF;
 v_table:=CASE p_entity_type
   WHEN 'category' THEN 'categories'
   WHEN 'item' THEN 'items'
   WHEN 'purchase' THEN 'purchases'
   WHEN 'purchase_addition' THEN 'purchase_additions'
   WHEN 'requisition' THEN 'requisitions'
   WHEN 'daily_cash_received' THEN 'daily_cash_received'
   WHEN 'daily_other_expenses' THEN 'daily_other_expenses'
 END;
 EXECUTE pg_catalog.format(
   'SELECT coalesce(pg_catalog.jsonb_agg(to_jsonb(r) ORDER BY r.updated_at,r.id),
                      ''[]''::jsonb)
    FROM (SELECT * FROM public.%I WHERE workspace_id=$1 AND
           ($2::timestamptz IS NULL OR
            (updated_at,id)>($2::timestamptz,$3::uuid))
          ORDER BY updated_at,id LIMIT $4) AS r',
   v_table
 ) INTO v_rows USING p_workspace_id,p_after_updated_at,p_after_id,p_page_size+1;
 v_count:=pg_catalog.jsonb_array_length(v_rows);
 v_has_more:=v_count>p_page_size;
 IF v_has_more THEN v_rows:=v_rows-(v_count-1); END IF;
 v_count:=pg_catalog.jsonb_array_length(v_rows);
 IF v_count>0 THEN
  v_next_updated:=(v_rows->(v_count-1)->>'updated_at')::timestamptz;
  v_next_id:=(v_rows->(v_count-1)->>'id')::uuid;
 ELSE
  v_next_updated:=p_after_updated_at;
  v_next_id:=p_after_id;
 END IF;
 RETURN pg_catalog.jsonb_build_object(
   'entity_type',p_entity_type,'rows',v_rows,'has_more',v_has_more,
   'next_updated_at',v_next_updated,'next_id',v_next_id,
   'server_today_colombo',
      (clock_timestamp() AT TIME ZONE 'Asia/Colombo')::date,
   'server_time_utc',clock_timestamp()
 );
END;
$service_read$;
REVOKE ALL ON FUNCTION public.marketpro_service_pull_signed_staff_page(
 uuid,uuid,uuid,uuid,uuid,text,timestamptz,uuid,integer)
 FROM PUBLIC,anon,authenticated;
GRANT EXECUTE ON FUNCTION public.marketpro_service_pull_signed_staff_page(
 uuid,uuid,uuid,uuid,uuid,text,timestamptz,uuid,integer)
 TO service_role;
-- TOP-LEVEL COMMIT moved to one enclosing transaction


-- All SQL proposal fragments applied as one all-or-nothing migration.
COMMIT;
