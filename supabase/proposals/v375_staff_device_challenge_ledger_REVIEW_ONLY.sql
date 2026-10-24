-- MARKET PRO V3.7.5 — STAFF DEVICE-PROOF CHALLENGE LEDGER
-- REVIEW ONLY: NEVER APPLY THIS FILE TO LIVE SUPABASE.
-- Requires V358/V359/V370/V371/V372 full cutover, hardened Staff RLS and
-- audited PostgREST/Edge Function deployment before activation.
-- This phase DOES NOT approve public keys or grant any business data rights.
BEGIN;
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
      pg_catalog.extract(epoch from v_time)*1000)::bigint,
  'expires_at_millis',pg_catalog.floor(
      pg_catalog.extract(epoch from v_expires)*1000)::bigint
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
      pg_catalog.extract(epoch from c.issued_at)*1000)::bigint,
   'expires_at_millis',pg_catalog.floor(
      pg_catalog.extract(epoch from c.expires_at)*1000)::bigint,
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
COMMIT;
