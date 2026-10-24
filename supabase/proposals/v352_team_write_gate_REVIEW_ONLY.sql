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
BEGIN;

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

COMMIT;
