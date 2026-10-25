-- Market Pro - Owner-controlled staff P-256 public key enrollment
-- SECURITY REVIEW ONLY. NEVER EXECUTE ON LIVE SUPABASE.
-- Must follow audited V358/V359/V368/V372/V375 rollout, Staff RLS and
-- Owner phone enrollment. Staff key PROOF is not Owner APPROVAL or a lease.
BEGIN;
CREATE TABLE IF NOT EXISTS marketpro_private.staff_key_enrollment_challenges(
 challenge_id uuid PRIMARY KEY DEFAULT extensions.gen_random_uuid(),
 workspace_id uuid NOT NULL,
 staff_user_id uuid NOT NULL,
 device_id uuid NOT NULL,
 session_id uuid NOT NULL,
 spki_der bytea NOT NULL CHECK(octet_length(spki_der) BETWEEN 64 AND 512),
 fingerprint_sha256 text NOT NULL CHECK(fingerprint_sha256 ~ '^[a-f0-9]{64}$'),
 nonce_hex text NOT NULL CHECK(nonce_hex ~ '^[a-f0-9]{64}$'),
 issued_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 expires_at timestamptz NOT NULL,
 consumed_at timestamptz,
 CHECK(expires_at>issued_at AND expires_at<=issued_at+interval '120 seconds')
);
CREATE INDEX IF NOT EXISTS staff_key_enroll_device_idx ON
 marketpro_private.staff_key_enrollment_challenges(
 workspace_id,device_id,session_id,expires_at);
REVOKE ALL ON marketpro_private.staff_key_enrollment_challenges
 FROM PUBLIC,anon,authenticated;

CREATE TABLE IF NOT EXISTS marketpro_private.staff_key_pending_approval(
 workspace_id uuid PRIMARY KEY,
 staff_user_id uuid NOT NULL,
 device_id uuid NOT NULL,
 session_id uuid NOT NULL,
 proof_challenge_id uuid NOT NULL UNIQUE,
 spki_der bytea NOT NULL CHECK(octet_length(spki_der) BETWEEN 64 AND 512),
 fingerprint_sha256 text NOT NULL CHECK(fingerprint_sha256 ~ '^[a-f0-9]{64}$'),
 proof_verified_at timestamptz NOT NULL,
 submitted_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
REVOKE ALL ON marketpro_private.staff_key_pending_approval
 FROM PUBLIC,anon,authenticated;
CREATE TABLE IF NOT EXISTS marketpro_private.staff_key_owner_approval_audit(
 audit_id uuid PRIMARY KEY DEFAULT extensions.gen_random_uuid(),
 workspace_id uuid NOT NULL,
 owner_user_id uuid NOT NULL,
 staff_user_id uuid NOT NULL,
 device_id uuid NOT NULL,
 approved_fingerprint_sha256 text NOT NULL,
 prior_fingerprint_sha256 text,
 candidate_challenge_id uuid NOT NULL,
 approved_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
REVOKE ALL ON marketpro_private.staff_key_owner_approval_audit
 FROM PUBLIC,anon,authenticated;

-- Explicit helper checks signed Staff JWT session, accepted invitation,
-- enabled READONLY device, not yet a Staff writer, restricted Staff REST
-- and the actual auth.sessions record. Owner writer always separate.
CREATE OR REPLACE FUNCTION marketpro_private.key_enrollment_allowed(
 p_workspace uuid,p_staff uuid,p_device uuid,p_session uuid
) RETURNS boolean
LANGUAGE sql VOLATILE SECURITY DEFINER SET search_path=''
AS $eligibility$
 SELECT p_workspace IS NOT NULL AND p_staff IS NOT NULL AND
    p_device IS NOT NULL AND p_session IS NOT NULL AND EXISTS(
    SELECT 1 FROM marketpro_private.staff_session_bindings b
    JOIN auth.sessions s ON s.id=b.session_id AND s.user_id=b.staff_user_id
    JOIN marketpro_private.pending_team_enrollments i
      ON i.workspace_id=b.workspace_id AND i.staff_user_id=b.staff_user_id
    JOIN public.workspace_members m
      ON m.workspace_id=b.workspace_id AND m.user_id=b.staff_user_id
    JOIN public.registered_devices d
      ON d.workspace_id=b.workspace_id AND d.id=b.device_id
      AND d.user_id=b.staff_user_id
    JOIN marketpro_private.team_rollout_gate g
      ON g.workspace_id=b.workspace_id
    WHERE b.workspace_id=p_workspace AND b.staff_user_id=p_staff
      AND b.device_id=p_device AND b.session_id=p_session
      AND b.revoked_at IS NULL AND
      (s.not_after IS NULL OR s.not_after>clock_timestamp())
      AND i.intended_device_id=b.device_id AND i.status='accepted'
      AND i.revoked_at IS NULL AND i.accepted_at IS NOT NULL
      AND i.activated_at IS NOT NULL
      AND m.role='purchasing_staff' AND d.enabled AND NOT d.can_write
      AND g.staff_direct_rest_restricted AND g.team_pull_rpc_reviewed
    );
$eligibility$;
REVOKE ALL ON FUNCTION marketpro_private.key_enrollment_allowed(
 uuid,uuid,uuid,uuid) FROM PUBLIC,anon,authenticated;

-- Staff can generate their private key on the phone and submit only the
-- public SPKI bytes. No phone chooses the challenge nonce or fingerprint.
CREATE OR REPLACE FUNCTION public.marketpro_staff_issue_key_enrollment(
 p_workspace_id uuid,p_device_id uuid,p_spki_base64 text
) RETURNS jsonb
LANGUAGE plpgsql VOLATILE SECURITY DEFINER SET search_path=''
AS $issue$
DECLARE
 v_staff uuid:=auth.uid();
 v_sid_txt text:=auth.jwt()->>'session_id';
 v_sid uuid;
 v_spki bytea;
 v_hash text;
 v_id uuid:=extensions.gen_random_uuid();
 v_nonce text:=pg_catalog.encode(extensions.gen_random_bytes(32),'hex');
 v_time timestamptz:=clock_timestamp();
BEGIN
 IF v_staff IS NULL OR coalesce((auth.jwt()->>'is_anonymous')::boolean,false)
    OR v_sid_txt IS NULL OR
      v_sid_txt !~* '^[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}$'
    OR p_workspace_id IS NULL OR p_device_id IS NULL
    OR p_spki_base64 IS NULL OR
      length(p_spki_base64) NOT BETWEEN 85 AND 800 THEN
  RAISE EXCEPTION 'Invalid Staff enrollment session/key'
    USING ERRCODE='42501'; END IF;
 v_sid:=v_sid_txt::uuid;
 v_spki:=pg_catalog.decode(p_spki_base64,'base64');
 IF octet_length(v_spki) NOT BETWEEN 64 AND 512 THEN
  RAISE EXCEPTION 'Invalid public-key length' USING ERRCODE='22023'; END IF;
 v_hash:=pg_catalog.encode(extensions.digest(v_spki,'sha256'),'hex');

 PERFORM pg_catalog.pg_advisory_xact_lock(
   pg_catalog.hashtextextended(p_workspace_id::text,0));
 IF NOT marketpro_private.key_enrollment_allowed(
  p_workspace_id,v_staff,p_device_id,v_sid) THEN
  RAISE EXCEPTION 'Staff device not Owner-activated and paired'
     USING ERRCODE='42501'; END IF;
 -- One active candidate challenge for this exact device. Racing and
 -- replayed challenges are invalidated inside the workspace lock.
 UPDATE marketpro_private.staff_key_enrollment_challenges
 SET consumed_at=v_time
 WHERE workspace_id=p_workspace_id AND device_id=p_device_id
   AND consumed_at IS NULL;
 INSERT INTO marketpro_private.staff_key_enrollment_challenges(
   challenge_id,workspace_id,staff_user_id,device_id,session_id,
   spki_der,fingerprint_sha256,nonce_hex,issued_at,expires_at
 ) VALUES (
   v_id,p_workspace_id,v_staff,p_device_id,v_sid,
   v_spki,v_hash,v_nonce,v_time,v_time+interval '60 seconds'
 );
 RETURN pg_catalog.jsonb_build_object(
  'proof_version','marketpro-staff-key-enrollment-v1',
  'workspace_id',p_workspace_id,'staff_user_id',v_staff,
  'device_id',p_device_id,'session_id',v_sid,
  'challenge_id',v_id,'nonce_hex',v_nonce,
  'fingerprint_sha256',v_hash,
  'issued_at_millis',pg_catalog.floor(extract(epoch FROM v_time)*1000)::bigint,
  'expires_at_millis',pg_catalog.floor(extract(epoch FROM
    v_time+interval '60 seconds')*1000)::bigint
 );
END;
$issue$;
REVOKE ALL ON FUNCTION public.marketpro_staff_issue_key_enrollment(
 uuid,uuid,text) FROM PUBLIC,anon;
GRANT EXECUTE ON FUNCTION public.marketpro_staff_issue_key_enrollment(
 uuid,uuid,text) TO authenticated;

-- Only the Edge verifier may obtain the candidate SPKI. No direct
-- authenticated Data API SELECT is possible on this private table.
CREATE OR REPLACE FUNCTION public.marketpro_service_key_enrollment_material(
 p_challenge_id uuid,p_staff_user_id uuid,p_session_id uuid
) RETURNS jsonb
LANGUAGE plpgsql VOLATILE SECURITY DEFINER SET search_path=''
AS $material$
DECLARE
 v jsonb;
BEGIN
 SELECT pg_catalog.jsonb_build_object(
   'proof_version','marketpro-staff-key-enrollment-v1',
   'workspace_id',c.workspace_id,'staff_user_id',c.staff_user_id,
   'device_id',c.device_id,'session_id',c.session_id,
   'challenge_id',c.challenge_id,'nonce_hex',c.nonce_hex,
   'fingerprint_sha256',c.fingerprint_sha256,
   'issued_at_millis',pg_catalog.floor(
       extract(epoch FROM c.issued_at)*1000)::bigint,
   'expires_at_millis',pg_catalog.floor(
       extract(epoch FROM c.expires_at)*1000)::bigint,
   'spki_base64',pg_catalog.encode(c.spki_der,'base64')
 ) INTO v
 FROM marketpro_private.staff_key_enrollment_challenges c
 WHERE c.challenge_id=p_challenge_id
   AND c.staff_user_id=p_staff_user_id AND c.session_id=p_session_id
   AND c.consumed_at IS NULL
   AND c.issued_at<=clock_timestamp() AND c.expires_at>clock_timestamp()
   AND marketpro_private.key_enrollment_allowed(
     c.workspace_id,c.staff_user_id,c.device_id,c.session_id);
 IF v IS NULL THEN RAISE EXCEPTION 'Key enrollment proof is expired or revoked'
   USING ERRCODE='42501'; END IF;
 RETURN v;
END;
$material$;
REVOKE ALL ON FUNCTION public.marketpro_service_key_enrollment_material(
 uuid,uuid,uuid) FROM PUBLIC,anon,authenticated;
GRANT EXECUTE ON FUNCTION public.marketpro_service_key_enrollment_material(
 uuid,uuid,uuid) TO service_role;

-- Called only AFTER Edge verifies ECDSA P256 signature using the *candidate*
-- SPKI. This is proof of KEY possession, NOT Owner approval or writer rights.
CREATE OR REPLACE FUNCTION public.marketpro_service_stage_verified_staff_key(
 p_challenge_id uuid,p_staff_user_id uuid,p_session_id uuid
) RETURNS jsonb
LANGUAGE plpgsql VOLATILE SECURITY DEFINER SET search_path=''
AS $commit$
DECLARE
 v_workspace uuid;
 v_material jsonb;
 v_record marketpro_private.staff_key_enrollment_challenges%ROWTYPE;
 v_changed int;
BEGIN
 SELECT workspace_id INTO v_workspace
 FROM marketpro_private.staff_key_enrollment_challenges
 WHERE challenge_id=p_challenge_id
   AND staff_user_id=p_staff_user_id AND session_id=p_session_id;
 IF v_workspace IS NULL THEN RAISE EXCEPTION 'Invalid Staff key proof'
  USING ERRCODE='42501'; END IF;
 PERFORM pg_catalog.pg_advisory_xact_lock(
   pg_catalog.hashtextextended(v_workspace::text,0));
 v_material:=public.marketpro_service_key_enrollment_material(
     p_challenge_id,p_staff_user_id,p_session_id);
 SELECT * INTO v_record FROM marketpro_private.staff_key_enrollment_challenges
 WHERE challenge_id=p_challenge_id FOR UPDATE;
 UPDATE marketpro_private.staff_key_enrollment_challenges
 SET consumed_at=clock_timestamp()
 WHERE challenge_id=p_challenge_id AND consumed_at IS NULL
   AND expires_at>clock_timestamp();
 GET DIAGNOSTICS v_changed=ROW_COUNT;
 IF v_changed<>1 THEN RAISE EXCEPTION 'Key proof already consumed'
   USING ERRCODE='42501'; END IF;
 INSERT INTO marketpro_private.staff_key_pending_approval(
   workspace_id,staff_user_id,device_id,session_id,proof_challenge_id,
   spki_der,fingerprint_sha256,proof_verified_at)
 VALUES (
   v_record.workspace_id,v_record.staff_user_id,v_record.device_id,
   v_record.session_id,v_record.challenge_id,
   v_record.spki_der,v_record.fingerprint_sha256,clock_timestamp()
 )
 ON CONFLICT(workspace_id) DO UPDATE SET
   staff_user_id=excluded.staff_user_id,
   device_id=excluded.device_id,
   session_id=excluded.session_id,
   proof_challenge_id=excluded.proof_challenge_id,
   spki_der=excluded.spki_der,
   fingerprint_sha256=excluded.fingerprint_sha256,
   proof_verified_at=clock_timestamp(),
   submitted_at=clock_timestamp();
 RETURN pg_catalog.jsonb_build_object(
   'proof_verified',true,'owner_approval_granted',false,
   'workspace_id',v_record.workspace_id,
   'device_id',v_record.device_id,
   'fingerprint_sha256',v_record.fingerprint_sha256
 );
END;
$commit$;
REVOKE ALL ON FUNCTION public.marketpro_service_stage_verified_staff_key(
 uuid,uuid,uuid) FROM PUBLIC,anon,authenticated;
GRANT EXECUTE ON FUNCTION public.marketpro_service_stage_verified_staff_key(
 uuid,uuid,uuid) TO service_role;

-- Owner reads only the candidate fingerprint and device identifiers, never
-- private keys, OTPs, JWTs, raw SPKI or the challenge random nonce.
CREATE OR REPLACE FUNCTION public.marketpro_owner_review_staff_key(
 p_workspace_id uuid,p_owner_device_id uuid,p_staff_user_id uuid
) RETURNS jsonb
LANGUAGE plpgsql VOLATILE SECURITY DEFINER SET search_path=''
AS $review$
DECLARE
 v_owner uuid;
 v_candidate marketpro_private.staff_key_pending_approval%ROWTYPE;
BEGIN
 v_owner:=marketpro_private.require_enrolled_owner(
    p_workspace_id,p_owner_device_id);
 PERFORM pg_catalog.pg_advisory_xact_lock(
  pg_catalog.hashtextextended(p_workspace_id::text,0));
 SELECT * INTO v_candidate
 FROM marketpro_private.staff_key_pending_approval
 WHERE workspace_id=p_workspace_id AND staff_user_id=p_staff_user_id;
 IF NOT FOUND OR v_candidate.submitted_at<
    clock_timestamp()-interval '24 hours' OR
    NOT marketpro_private.key_enrollment_allowed(
       p_workspace_id,v_candidate.staff_user_id,
       v_candidate.device_id,v_candidate.session_id) THEN
  RAISE EXCEPTION 'No current verified Staff public key candidate'
    USING ERRCODE='42501'; END IF;
 RETURN pg_catalog.jsonb_build_object(
  'workspace_id',p_workspace_id,'staff_user_id',v_candidate.staff_user_id,
  'device_id',v_candidate.device_id,
  'fingerprint_sha256',v_candidate.fingerprint_sha256,
  'candidate_challenge_id',v_candidate.proof_challenge_id,
  'owner_review_required',true,'writer_approved',false
 );
END;
$review$;
REVOKE ALL ON FUNCTION public.marketpro_owner_review_staff_key(
 uuid,uuid,uuid) FROM PUBLIC,anon;
GRANT EXECUTE ON FUNCTION public.marketpro_owner_review_staff_key(
 uuid,uuid,uuid) TO authenticated;

-- No key is approved by Staff login or upload. Owner must compare the
-- full fingerprint displayed on the *physical Staff phone* (out-of-band),
-- acknowledge its exact candidate proof/challenge/device and confirm phrase.
-- This only approves key material; separate Owner Staff writer lease remains
-- a second action after the key is approved.
CREATE OR REPLACE FUNCTION public.marketpro_owner_approve_staff_key(
 p_workspace_id uuid,p_owner_device_id uuid,p_staff_user_id uuid,
 p_staff_device_id uuid,p_challenge_id uuid,
 p_expected_fingerprint text,p_confirmation text
) RETURNS jsonb
LANGUAGE plpgsql VOLATILE SECURITY DEFINER SET search_path=''
AS $approve$
DECLARE
 v_owner uuid;
 v_candidate marketpro_private.staff_key_pending_approval%ROWTYPE;
 v_old marketpro_private.staff_device_public_keys%ROWTYPE;
BEGIN
 v_owner:=marketpro_private.require_enrolled_owner(
  p_workspace_id,p_owner_device_id);
 IF p_staff_user_id IS NULL OR p_staff_device_id IS NULL OR
    p_challenge_id IS NULL OR p_staff_user_id=v_owner OR
    p_staff_device_id=p_owner_device_id OR
    p_expected_fingerprint IS NULL OR
    p_expected_fingerprint !~ '^[a-f0-9]{64}$' OR
    p_confirmation IS DISTINCT FROM 'APPROVE KEY' THEN
  RAISE EXCEPTION 'Explicit Owner verified-key approval required'
    USING ERRCODE='42501'; END IF;
 PERFORM pg_catalog.pg_advisory_xact_lock(
   pg_catalog.hashtextextended(p_workspace_id::text,0));
 SELECT * INTO v_candidate
 FROM marketpro_private.staff_key_pending_approval
 WHERE workspace_id=p_workspace_id AND staff_user_id=p_staff_user_id
   AND device_id=p_staff_device_id
   AND proof_challenge_id=p_challenge_id
   AND fingerprint_sha256=p_expected_fingerprint
   AND submitted_at>=clock_timestamp()-interval '24 hours'
 FOR UPDATE;
 IF NOT FOUND OR NOT marketpro_private.key_enrollment_allowed(
    p_workspace_id,p_staff_user_id,p_staff_device_id,
    v_candidate.session_id) THEN
  RAISE EXCEPTION 'Staff public key candidate changed or revoked'
   USING ERRCODE='42501'; END IF;
 SELECT * INTO v_old FROM marketpro_private.staff_device_public_keys
 WHERE workspace_id=p_workspace_id FOR UPDATE;
 IF FOUND AND v_old.device_id<>p_staff_device_id AND EXISTS(
   SELECT 1 FROM public.registered_devices d
   WHERE d.id=v_old.device_id AND d.workspace_id=p_workspace_id
     AND d.enabled
 ) THEN
  RAISE EXCEPTION 'Disable old Staff phone before replacing its approved key'
     USING ERRCODE='42501'; END IF;
 INSERT INTO marketpro_private.staff_device_public_keys(
   workspace_id,staff_user_id,device_id,spki_der,fingerprint_sha256,
   approved,approved_at,revoked_at)
 VALUES (
   p_workspace_id,p_staff_user_id,p_staff_device_id,
   v_candidate.spki_der,v_candidate.fingerprint_sha256,
   true,clock_timestamp(),NULL)
 ON CONFLICT(workspace_id) DO UPDATE SET
   staff_user_id=excluded.staff_user_id,
   device_id=excluded.device_id,
   spki_der=excluded.spki_der,
   fingerprint_sha256=excluded.fingerprint_sha256,
   approved=true,approved_at=clock_timestamp(),revoked_at=NULL;
 INSERT INTO marketpro_private.staff_key_owner_approval_audit(
   workspace_id,owner_user_id,staff_user_id,device_id,
   approved_fingerprint_sha256,prior_fingerprint_sha256,
   candidate_challenge_id)
 VALUES (
   p_workspace_id,v_owner,p_staff_user_id,p_staff_device_id,
   v_candidate.fingerprint_sha256,v_old.fingerprint_sha256,
   p_challenge_id);
 DELETE FROM marketpro_private.staff_key_pending_approval
 WHERE workspace_id=p_workspace_id AND proof_challenge_id=p_challenge_id;
 RETURN pg_catalog.jsonb_build_object(
  'key_approved',true,'staff_user_id',p_staff_user_id,
  'device_id',p_staff_device_id,
  'fingerprint_sha256',p_expected_fingerprint,
  'writer_approved',false,
  'requires_separate_owner_writer_approval',true
 );
END;
$approve$;
REVOKE ALL ON FUNCTION public.marketpro_owner_approve_staff_key(
 uuid,uuid,uuid,uuid,uuid,text,text) FROM PUBLIC,anon;
GRANT EXECUTE ON FUNCTION public.marketpro_owner_approve_staff_key(
 uuid,uuid,uuid,uuid,uuid,text,text) TO authenticated;
COMMIT;
