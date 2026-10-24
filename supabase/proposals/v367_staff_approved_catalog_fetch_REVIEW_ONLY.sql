-- V3.6.7 REVIEW ONLY. Prerequisites: v362 and v364 proposals.
-- DO NOT RUN AGAINST LIVE SUPABASE. This replaces the earlier permissive
-- device approval lookup with an APPROVED-CANDIDATE and current-revision gate.
BEGIN;

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
COMMIT;
