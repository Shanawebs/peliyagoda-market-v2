-- MARKET PRO - OWNER KEY APPROVAL V2 MFA STEP-UP, REVIEW ONLY.
-- Supabase Auth MFA docs: aal2 JWT + amr method totp timestamp epoch.
-- NOT DEPLOYED. Requires reviewed Staff key proposal and RLS cutover.
BEGIN;
CREATE OR REPLACE FUNCTION marketpro_private.require_fresh_owner_key_mfa(
  p_workspace_id uuid,p_owner_device_id uuid
) RETURNS uuid
LANGUAGE plpgsql VOLATILE SECURITY DEFINER SET search_path=''
AS $fresh$
DECLARE
 v_owner uuid;
 v_claims jsonb:=auth.jwt();
 v_sid_text text:=v_claims->>'session_id';
 v_sid uuid;
 v_amr jsonb:=v_claims->'amr';
 v_now_epoch bigint:=pg_catalog.floor(
   EXTRACT(epoch FROM clock_timestamp()))::bigint;
BEGIN
 v_owner:=marketpro_private.require_enrolled_owner(
     p_workspace_id,p_owner_device_id);
 IF v_sid_text IS NULL OR
    v_sid_text !~* '^[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}$'
    OR v_claims->>'aal' IS DISTINCT FROM 'aal2'
    OR pg_catalog.jsonb_typeof(v_amr) IS DISTINCT FROM 'array' THEN
  RAISE EXCEPTION 'Fresh Owner MFA verification required'
    USING ERRCODE='42501';
 END IF;
 v_sid:=v_sid_text::uuid;
 IF NOT EXISTS(
   SELECT 1 FROM auth.sessions s
   WHERE s.id=v_sid AND s.user_id=v_owner
     AND (s.not_after IS NULL OR s.not_after>clock_timestamp())
 ) THEN
  RAISE EXCEPTION 'Owner MFA session was revoked or expired'
    USING ERRCODE='42501';
 END IF;
 -- Actual TOTP authentication, within 5 minutes. Avoid malformed date casts.
 IF NOT EXISTS(
   SELECT 1
   FROM pg_catalog.jsonb_array_elements(v_amr) AS m(value)
   WHERE m.value->>'method'='totp'
     AND CASE
       WHEN (m.value->>'timestamp') ~ '^[0-9]{1,12}$'
       THEN ((m.value->>'timestamp')::bigint BETWEEN
                    v_now_epoch-300 AND v_now_epoch+15)
       ELSE false
     END
 ) THEN
  RAISE EXCEPTION 'Owner MFA TOTP is stale or missing'
    USING ERRCODE='42501';
 END IF;
 RETURN v_owner;
END;
$fresh$;
REVOKE ALL ON FUNCTION marketpro_private.require_fresh_owner_key_mfa(
 uuid,uuid) FROM PUBLIC,anon,authenticated;

-- No authenticated user can directly invoke the old pre-MFA approval.
REVOKE ALL ON FUNCTION public.marketpro_owner_approve_staff_key(
 uuid,uuid,uuid,uuid,uuid,text,text) FROM PUBLIC,anon,authenticated;

CREATE OR REPLACE FUNCTION public.marketpro_owner_approve_staff_key_mfa(
 p_workspace_id uuid,p_owner_device_id uuid,p_staff_user_id uuid,
 p_staff_device_id uuid,p_challenge_id uuid,
 p_expected_fingerprint text,p_confirmation text
) RETURNS jsonb
LANGUAGE plpgsql VOLATILE SECURITY DEFINER SET search_path=''
AS $approve$
DECLARE
 v_owner uuid;
 v_result jsonb;
BEGIN
 v_owner:=marketpro_private.require_fresh_owner_key_mfa(
    p_workspace_id,p_owner_device_id);
 PERFORM pg_catalog.pg_advisory_xact_lock(
   pg_catalog.hashtextextended(p_workspace_id::text,0));
 IF marketpro_private.require_fresh_owner_key_mfa(
    p_workspace_id,p_owner_device_id) IS DISTINCT FROM v_owner THEN
  RAISE EXCEPTION 'Owner authorization changed' USING ERRCODE='42501';
 END IF;
 v_result:=public.marketpro_owner_approve_staff_key(
    p_workspace_id,p_owner_device_id,p_staff_user_id,
    p_staff_device_id,p_challenge_id,p_expected_fingerprint,p_confirmation);
 IF v_result->>'key_approved' IS DISTINCT FROM 'true' OR
    v_result->>'writer_approved' IS DISTINCT FROM 'false' THEN
  RAISE EXCEPTION 'Key approval cannot activate Staff writer'
    USING ERRCODE='42501';
 END IF;
 RETURN v_result||pg_catalog.jsonb_build_object(
   'owner_mfa_verified',true,'key_approval_only',true,'writer_approved',false);
END;
$approve$;
REVOKE ALL ON FUNCTION public.marketpro_owner_approve_staff_key_mfa(
 uuid,uuid,uuid,uuid,uuid,text,text) FROM PUBLIC,anon;
GRANT EXECUTE ON FUNCTION public.marketpro_owner_approve_staff_key_mfa(
 uuid,uuid,uuid,uuid,uuid,text,text) TO authenticated;
COMMIT;
