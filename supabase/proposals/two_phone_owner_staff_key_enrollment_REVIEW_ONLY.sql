-- MARKET PRO TWO-PHONE STAFF DEVICE KEY ENROLLMENT — REVIEW ONLY.
-- NEVER RUN AGAINST LIVE. Requires audited V358, V359, V368, V372,
-- V375, finance proof RPC, and secure RLS BEFORE Staff membership.
-- No automatic Staff key approval, no Owner v1 writer modification.
BEGIN;

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

COMMIT;
