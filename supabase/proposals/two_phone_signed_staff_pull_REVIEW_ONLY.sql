-- REVIEW ONLY: source-accurate, per-page P-256 proof for Staff Cloud Pull.
-- Requires v375, Approved Staff keys, v372 session binding, Other Expenses
-- and two_phone_other_expenses_pull. Apply only as part of audited atomic cutover.
-- Owner's v1 writer, original Owner/Viewer pull and Dashboard stay unchanged.
BEGIN;
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
COMMIT;
