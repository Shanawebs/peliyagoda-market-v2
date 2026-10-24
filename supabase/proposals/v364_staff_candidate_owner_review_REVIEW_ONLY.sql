-- MARKET PRO V3.6.4 — STAFF CATALOG CANDIDATE INBOX (REVIEW ONLY)
-- Depends on v362 owner approvals. NEVER execute on live Supabase yet.
-- No live catalog/financial writes, no staff activation or RLS changes.
BEGIN;
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
      p_local_key<>p_local_name OR p_local_category IS NOT NULL
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
COMMIT;
