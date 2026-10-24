-- MARKET PRO V3.6.2 — OWNER-APPROVED PER-DEVICE CATALOG LINK (REVIEW ONLY)
-- THIS IS NOT A PRODUCTION MIGRATION. NO LIVE CLOUD DDL OR RECORDS TO CHANGE.
-- Designed for coordinated cutover after v359 member/device enrollment + RLS.
BEGIN;
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
COMMIT;
