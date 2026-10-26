-- Market Pro staging Owner P-256 key bootstrap — DORMANT REVIEW-ONLY RPCs.
-- EXACT STAGING PROJECT: qqiheexpbuzohllugxrk. DO NOT APPLY TO PRODUCTION.
-- Prerequisite: hosted QA private vault migration 20261026040419.
-- No new member, phone activation, grants to client roles, or Staff permissions.
-- This implementation is intentionally disabled by qa_owner_bootstrap_gate.
BEGIN;

DO $pre$
BEGIN
 IF to_regclass('marketpro_private.qa_owner_bootstrap_challenges') IS NULL
    OR to_regclass('marketpro_private.qa_owner_device_public_keys') IS NULL
    OR to_regclass('marketpro_private.qa_owner_bootstrap_gate') IS NULL
    OR (SELECT count(*) FROM marketpro_private.qa_owner_bootstrap_gate
        WHERE enabled) <> 0
    OR (SELECT count(*) FROM marketpro_private.qa_owner_bootstrap_challenges)<>0
    OR (SELECT count(*) FROM marketpro_private.qa_owner_device_public_keys)<>0
    OR (SELECT count(*) FROM public.registered_devices)<>0
    OR (SELECT count(*) FROM public.workspace_members WHERE role='owner')<>1
    OR (SELECT count(*) FROM public.workspace_members WHERE role<>'owner')<>0
 THEN RAISE EXCEPTION 'QA Owner bootstrap RPC preflight denied';
 END IF;
END;
$pre$;

-- IMPORTANT bootstrap distinction: first Owner phone CANNOT use existing
-- qa_current_owner_is_fresh, which requires an ALREADY enrolled device.
-- Require verified Owner, active authenticated Supabase session, and
-- a fresh TOTP AMR; first phone is proofed before separately enrolling.
CREATE OR REPLACE FUNCTION marketpro_private.qa_owner_bootstrap_auth(
 p_workspace uuid
) RETURNS TABLE(owner_id uuid, session_id uuid)
LANGUAGE plpgsql VOLATILE SECURITY DEFINER SET search_path=''
AS $auth$
DECLARE
  actor uuid := auth.uid();
  claims jsonb := auth.jwt();
  sid_text text;
  sid uuid;
  now_sec bigint := floor(extract(epoch FROM clock_timestamp()))::bigint;
BEGIN
  IF p_workspace IS DISTINCT FROM
      'af77cb66-76b2-492a-a460-06132f468713'::uuid
     OR actor IS NULL
     OR claims->>'sub' IS DISTINCT FROM actor::text
     OR claims->>'aal' IS DISTINCT FROM 'aal2'
     OR COALESCE(claims->>'is_anonymous','false') <> 'false'
     OR pg_catalog.jsonb_typeof(claims->'amr') <> 'array'
  THEN RETURN; END IF;

  sid_text := claims->>'session_id';
  IF sid_text IS NULL OR sid_text !~
     '^[a-fA-F0-9]{8}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{12}$'
  THEN RETURN; END IF;
  sid := sid_text::uuid;

  IF NOT EXISTS (
    SELECT 1 FROM pg_catalog.jsonb_array_elements(claims->'amr') AS m(value)
    WHERE m.value->>'method'='totp'
      AND CASE WHEN (m.value->>'timestamp') ~ '^[0-9]{10}$'
          THEN (m.value->>'timestamp')::bigint
               BETWEEN now_sec-300 AND now_sec+15
          ELSE false END
  ) THEN RETURN; END IF;

  IF NOT EXISTS(
    SELECT 1 FROM public.workspace_members wm
    JOIN auth.users u ON u.id=wm.user_id
    JOIN auth.sessions s ON s.user_id=u.id
    WHERE wm.workspace_id=p_workspace AND wm.role='owner'
      AND wm.user_id=actor AND u.email_confirmed_at IS NOT NULL
      AND s.id=sid AND (s.not_after IS NULL OR s.not_after>clock_timestamp())
      AND EXISTS(
        SELECT 1 FROM auth.mfa_factors f
        WHERE f.user_id=u.id AND f.factor_type='totp' AND f.status='verified'
      )
  ) THEN RETURN; END IF;
  owner_id:=actor; session_id:=sid; RETURN NEXT;
END;
$auth$;
REVOKE ALL ON FUNCTION marketpro_private.qa_owner_bootstrap_auth(uuid)
 FROM PUBLIC,anon,authenticated;

-- Client RPC is presently NOT executable, and private gate is OFF.
-- Future rollout must separately grant authenticated EXEC after hosted JWT QA.
CREATE OR REPLACE FUNCTION public.marketpro_qa_owner_issue_bootstrap(
  p_workspace uuid, p_device uuid, p_spki_base64 text
) RETURNS jsonb
LANGUAGE plpgsql VOLATILE SECURITY DEFINER SET search_path=''
AS $issue$
DECLARE
  identity_record record;
  bytes bytea;
  fp text;
  challenge uuid:=extensions.gen_random_uuid();
  nonce text:=pg_catalog.encode(extensions.gen_random_bytes(32),'hex');
  issued timestamptz;
BEGIN
  SELECT * INTO identity_record
  FROM marketpro_private.qa_owner_bootstrap_auth(p_workspace);
  IF NOT FOUND
     OR p_device IS NULL OR p_spki_base64 IS NULL
     OR (length(p_spki_base64) NOT BETWEEN 85 AND 800)
     OR p_spki_base64 !~ '^[A-Za-z0-9+/]+={0,2}$'
  THEN RAISE EXCEPTION 'Owner bootstrap authorization denied'
     USING ERRCODE='42501'; END IF;

  -- PostgreSQL decode rejects invalid base64, Edge additionally enforces
  -- authentic EC P-256 SPKI syntax with WebCrypto before calling commit.
  BEGIN
    bytes:=pg_catalog.decode(p_spki_base64,'base64');
  EXCEPTION WHEN others THEN
    RAISE EXCEPTION 'Invalid Owner public key encoding'
      USING ERRCODE='22023';
  END;
  IF octet_length(bytes) NOT BETWEEN 64 AND 512
  THEN RAISE EXCEPTION 'Invalid Owner public key'
    USING ERRCODE='22023'; END IF;

  PERFORM pg_catalog.pg_advisory_xact_lock(
    pg_catalog.hashtextextended(p_workspace::text,0)
  );
  IF NOT EXISTS(
      SELECT 1 FROM marketpro_private.qa_owner_bootstrap_gate
      WHERE workspace_id=p_workspace AND enabled
  ) OR EXISTS (SELECT 1 FROM public.registered_devices
      WHERE workspace_id=p_workspace)
    OR EXISTS (SELECT 1 FROM marketpro_private.qa_owner_device_public_keys
      WHERE workspace_id=p_workspace)
  THEN RAISE EXCEPTION 'Owner bootstrap not permitted'
    USING ERRCODE='42501'; END IF;

  UPDATE marketpro_private.qa_owner_bootstrap_challenges
    SET consumed_at=clock_timestamp()
    WHERE workspace_id=p_workspace AND consumed_at IS NULL;

  fp:=pg_catalog.encode(extensions.digest(bytes,'sha256'),'hex');
  issued:=clock_timestamp();
  INSERT INTO marketpro_private.qa_owner_bootstrap_challenges(
    challenge_id,workspace_id,owner_user_id,device_id,session_id,
    spki_der,fingerprint_sha256,nonce_hex,issued_at,expires_at
  ) VALUES (
    challenge,p_workspace,identity_record.owner_id,p_device,
    identity_record.session_id,bytes,fp,nonce,issued,
    issued+interval '60 seconds'
  );
  RETURN pg_catalog.jsonb_build_object(
    'proof_version','marketpro-owner-bootstrap-key-v1',
    'workspace_id',p_workspace,'owner_user_id',identity_record.owner_id,
    'device_id',p_device,'session_id',identity_record.session_id,
    'challenge_id',challenge,'nonce_hex',nonce,
    'fingerprint_sha256',fp,
    'issued_at_millis',floor(extract(epoch from issued)*1000)::bigint,
    'expires_at_millis',floor(extract(epoch from
      issued+interval '60 seconds')*1000)::bigint,
    'phone_activated',false
  );
END;
$issue$;
REVOKE ALL ON FUNCTION public.marketpro_qa_owner_issue_bootstrap(uuid,uuid,text)
 FROM PUBLIC,anon,authenticated;

-- Trusted Edge service-role LOOKUP: only after verifying the caller's JWT
-- and deriving owner + session from Supabase, never from request JSON.
CREATE OR REPLACE FUNCTION public.marketpro_qa_service_owner_bootstrap_material(
  p_challenge uuid,p_owner uuid,p_session uuid
) RETURNS jsonb
LANGUAGE plpgsql VOLATILE SECURITY DEFINER SET search_path=''
AS $material$
DECLARE
 m jsonb;
BEGIN
 SELECT pg_catalog.jsonb_build_object(
   'proof_version','marketpro-owner-bootstrap-key-v1',
   'workspace_id',c.workspace_id,'owner_user_id',c.owner_user_id,
   'device_id',c.device_id,'session_id',c.session_id,
   'challenge_id',c.challenge_id,'nonce_hex',c.nonce_hex,
   'fingerprint_sha256',c.fingerprint_sha256,
   'issued_at_millis',floor(extract(epoch from c.issued_at)*1000)::bigint,
   'expires_at_millis',floor(extract(epoch from c.expires_at)*1000)::bigint,
   'spki_base64',replace(pg_catalog.encode(c.spki_der,'base64'),E'\n','')
 ) INTO m
 FROM marketpro_private.qa_owner_bootstrap_challenges c
 JOIN auth.sessions s ON s.id=c.session_id AND s.user_id=c.owner_user_id
 WHERE c.challenge_id=p_challenge AND c.owner_user_id=p_owner
   AND c.session_id=p_session
   AND c.consumed_at IS NULL AND c.expires_at>clock_timestamp()
   AND c.issued_at<=clock_timestamp()
   AND (s.not_after IS NULL OR s.not_after>clock_timestamp())
   AND EXISTS (
     SELECT 1 FROM marketpro_private.qa_owner_bootstrap_gate g
     WHERE g.workspace_id=c.workspace_id AND g.enabled
   )
   AND NOT EXISTS (
     SELECT 1 FROM public.registered_devices d
     WHERE d.workspace_id=c.workspace_id
   )
   AND NOT EXISTS (
     SELECT 1 FROM marketpro_private.qa_owner_device_public_keys k
     WHERE k.workspace_id=c.workspace_id
   );
 IF m IS NULL THEN
   RAISE EXCEPTION 'Bootstrap challenge unavailable'
     USING ERRCODE='42501'; END IF;
 RETURN m;
END;
$material$;
REVOKE ALL ON FUNCTION
 public.marketpro_qa_service_owner_bootstrap_material(uuid,uuid,uuid)
 FROM PUBLIC,anon,authenticated;
GRANT EXECUTE ON FUNCTION
 public.marketpro_qa_service_owner_bootstrap_material(uuid,uuid,uuid)
 TO service_role;

-- Edge MUST validate ECDSA P-256 DER signature against material first.
-- This privileged transaction atomically consumes the challenge and records
-- ONLY a pending PUBLIC key. It NEVER activates a phone or Staff membership.
CREATE OR REPLACE FUNCTION public.marketpro_qa_service_owner_bootstrap_commit(
 p_challenge uuid,p_owner uuid,p_session uuid
) RETURNS jsonb
LANGUAGE plpgsql VOLATILE SECURITY DEFINER SET search_path=''
AS $commit$
DECLARE
 c marketpro_private.qa_owner_bootstrap_challenges%ROWTYPE;
 changed int;
BEGIN
 PERFORM pg_catalog.pg_advisory_xact_lock(
   pg_catalog.hashtextextended(
     'af77cb66-76b2-492a-a460-06132f468713',0)
 );
 SELECT * INTO c FROM marketpro_private.qa_owner_bootstrap_challenges
 WHERE challenge_id=p_challenge AND owner_user_id=p_owner
   AND session_id=p_session FOR UPDATE;
 IF NOT FOUND OR c.workspace_id IS DISTINCT FROM
      'af77cb66-76b2-492a-a460-06132f468713'::uuid
    OR c.consumed_at IS NOT NULL OR c.expires_at<=clock_timestamp()
    OR c.issued_at>clock_timestamp()
    OR NOT EXISTS (
      SELECT 1 FROM marketpro_private.qa_owner_bootstrap_gate
      WHERE workspace_id=c.workspace_id AND enabled
    )
    OR NOT EXISTS (
      SELECT 1 FROM auth.sessions s
      WHERE s.id=c.session_id AND s.user_id=c.owner_user_id
        AND (s.not_after IS NULL OR s.not_after>clock_timestamp())
    )
    OR NOT EXISTS(
      SELECT 1 FROM public.workspace_members m
      JOIN auth.users u ON u.id=m.user_id
      WHERE m.workspace_id=c.workspace_id AND m.user_id=c.owner_user_id
        AND m.role='owner' AND u.email_confirmed_at IS NOT NULL
        AND EXISTS (SELECT 1 FROM auth.mfa_factors f
          WHERE f.user_id=u.id AND f.factor_type='totp' AND f.status='verified')
    )
    OR EXISTS (
      SELECT 1 FROM public.registered_devices d
      WHERE d.workspace_id=c.workspace_id
    )
    OR EXISTS (
      SELECT 1 FROM marketpro_private.qa_owner_device_public_keys k
      WHERE k.workspace_id=c.workspace_id
    )
 THEN RAISE EXCEPTION 'Owner bootstrap challenge denied or replayed'
   USING ERRCODE='42501'; END IF;

 UPDATE marketpro_private.qa_owner_bootstrap_challenges
 SET consumed_at=clock_timestamp(),signature_verified_at=clock_timestamp()
 WHERE challenge_id=p_challenge AND consumed_at IS NULL;
 GET DIAGNOSTICS changed=ROW_COUNT;
 IF changed<>1 THEN RAISE EXCEPTION 'Owner bootstrap replay'
   USING ERRCODE='42501'; END IF;

 INSERT INTO marketpro_private.qa_owner_device_public_keys(
   workspace_id,owner_user_id,device_id,spki_der,
   fingerprint_sha256,challenge_id,activated,attestation_verified
 ) VALUES(
   c.workspace_id,c.owner_user_id,c.device_id,c.spki_der,
   c.fingerprint_sha256,c.challenge_id,false,false
 );
 RETURN pg_catalog.jsonb_build_object(
   'proof_verified_and_consumed',true,
   'device_id',c.device_id,
   'fingerprint_sha256',c.fingerprint_sha256,
   'phone_activated',false,'can_write',false
 );
END;
$commit$;
REVOKE ALL ON FUNCTION
 public.marketpro_qa_service_owner_bootstrap_commit(uuid,uuid,uuid)
 FROM PUBLIC,anon,authenticated;
GRANT EXECUTE ON FUNCTION
 public.marketpro_qa_service_owner_bootstrap_commit(uuid,uuid,uuid)
 TO service_role;

DO $post$
BEGIN
 IF (SELECT count(*) FROM marketpro_private.qa_owner_bootstrap_gate
    WHERE enabled)<>0
    OR (SELECT count(*) FROM marketpro_private.qa_owner_bootstrap_challenges)<>0
    OR (SELECT count(*) FROM marketpro_private.qa_owner_device_public_keys)<>0
    OR (SELECT count(*) FROM public.registered_devices)<>0
    OR has_function_privilege('authenticated',
      'public.marketpro_qa_owner_issue_bootstrap(uuid,uuid,text)','EXECUTE')
    OR has_function_privilege('authenticated',
      'public.marketpro_qa_service_owner_bootstrap_material(uuid,uuid,uuid)','EXECUTE')
    OR has_function_privilege('authenticated',
      'public.marketpro_qa_service_owner_bootstrap_commit(uuid,uuid,uuid)','EXECUTE')
    OR has_function_privilege('anon',
      'public.marketpro_qa_service_owner_bootstrap_commit(uuid,uuid,uuid)','EXECUTE')
 THEN RAISE EXCEPTION 'Unsafe bootstrap server privileges';
 END IF;
END;
$post$;
COMMIT;
