-- MARKET PRO V3.5.4 — AUTHENTICATED, KEYSET-PAGINATED CLOUD PULL (REVIEW ONLY)
-- NOT DEPLOYED. Preserves the existing live Cloud writer & old Dashboard.
-- Fetches non-photo workspace rows, including tombstones, for both enrolled phones.
BEGIN;

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

COMMIT;
