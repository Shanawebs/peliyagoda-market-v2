-- Market Pro isolated QA: DORMANT first-Owner pending-key proposal, v0.5.
-- STAGING ONLY qqiheexpbuzohllugxrk. NEVER apply to production.
-- Does not enroll/activate a device, issue Staff access, or grant writes.
-- This schema intentionally creates no executable client-facing RPCs.
BEGIN;
DO $guard$
BEGIN
 IF (SELECT count(*) FROM public.workspaces WHERE
 id='af77cb66-76b2-492a-a460-06132f468713'::uuid
 AND name='Market Pro Two-Phone QA') <> 1
 OR (SELECT count(*) FROM public.workspace_members WHERE role='owner')<>1
 OR (SELECT count(*) FROM public.workspace_members WHERE role<>'owner')<>0
 OR (SELECT count(*) FROM public.registered_devices)<>0
 OR (SELECT count(*) FROM marketpro_private.qa_owner_device_public_keys)<>0
 OR (SELECT count(*) FROM marketpro_private.qa_owner_bootstrap_gate WHERE enabled)<>0
 OR (SELECT count(*) FROM marketpro_private.qa_invite_gate WHERE enabled)<>0
 OR (SELECT count(*) FROM marketpro_private.pending_team_enrollments)<>0
 OR to_regclass('marketpro_private.qa_owner_pending_gate') IS NOT NULL
 OR to_regclass('marketpro_private.qa_owner_pending_challenges') IS NOT NULL
 OR to_regclass('marketpro_private.qa_owner_pending_devices') IS NOT NULL
 THEN RAISE EXCEPTION 'Isolated pending-owner preflight failed'; END IF;
END;$guard$;

CREATE TABLE marketpro_private.qa_owner_pending_gate (
  workspace_id uuid PRIMARY KEY REFERENCES public.workspaces(id),
  enabled boolean NOT NULL DEFAULT false,
  reviewed_at timestamptz
);
INSERT INTO marketpro_private.qa_owner_pending_gate(workspace_id,enabled)
 VALUES('af77cb66-76b2-492a-a460-06132f468713',false);

CREATE TABLE marketpro_private.qa_owner_pending_challenges(
  challenge_id uuid PRIMARY KEY DEFAULT extensions.gen_random_uuid(),
  workspace_id uuid NOT NULL REFERENCES public.workspaces(id),
  owner_user_id uuid NOT NULL REFERENCES auth.users(id),
  session_id uuid NOT NULL,
  device_id uuid NOT NULL,
  spki_der bytea NOT NULL CHECK(octet_length(spki_der) BETWEEN 64 AND 512),
  fingerprint_sha256 text NOT NULL CHECK(fingerprint_sha256 ~ '^[a-f0-9]{64}$'),
  nonce_hex text NOT NULL CHECK(nonce_hex ~ '^[a-f0-9]{64}$'),
  issued_at timestamptz NOT NULL,
  expires_at timestamptz NOT NULL,
  consumed_at timestamptz,
  verified_at timestamptz,
  CHECK(expires_at>issued_at AND expires_at<=issued_at+interval '60 seconds'),
  CHECK(verified_at IS NULL OR consumed_at IS NOT NULL)
);
CREATE INDEX qa_pending_challenge_by_workspace
 ON marketpro_private.qa_owner_pending_challenges(workspace_id,issued_at DESC);

CREATE TABLE marketpro_private.qa_owner_pending_devices(
  workspace_id uuid PRIMARY KEY REFERENCES public.workspaces(id),
  owner_user_id uuid NOT NULL REFERENCES auth.users(id),
  device_id uuid UNIQUE NOT NULL,
  key_fingerprint_sha256 text NOT NULL CHECK(key_fingerprint_sha256 ~ '^[a-f0-9]{64}$'),
  spki_der bytea NOT NULL CHECK(octet_length(spki_der) BETWEEN 64 AND 512),
  proof_challenge_id uuid UNIQUE NOT NULL
     REFERENCES marketpro_private.qa_owner_pending_challenges(challenge_id),
  status text NOT NULL DEFAULT 'pending' CHECK(status='pending'),
  hardware_attestation_verified boolean NOT NULL DEFAULT false
     CHECK(hardware_attestation_verified=false),
  enabled boolean NOT NULL DEFAULT false CHECK(enabled=false),
  can_write boolean NOT NULL DEFAULT false CHECK(can_write=false),
  created_at timestamptz NOT NULL DEFAULT clock_timestamp()
);

REVOKE ALL ON marketpro_private.qa_owner_pending_gate,
 marketpro_private.qa_owner_pending_challenges,
 marketpro_private.qa_owner_pending_devices
 FROM PUBLIC,anon,authenticated;
ALTER TABLE marketpro_private.qa_owner_pending_gate ENABLE ROW LEVEL SECURITY;
ALTER TABLE marketpro_private.qa_owner_pending_challenges ENABLE ROW LEVEL SECURITY;
ALTER TABLE marketpro_private.qa_owner_pending_devices ENABLE ROW LEVEL SECURITY;

-- This RPC is service_role-only. The Edge layer must additionally enforce
-- fresh Owner TOTP AAL2 and user-approved intent BEFORE calling it.
CREATE FUNCTION public.marketpro_qa_service_pending_owner_issue(
  p_owner uuid,p_session uuid,p_device uuid,p_spki_base64 text
) RETURNS jsonb
LANGUAGE plpgsql VOLATILE SECURITY DEFINER SET search_path=''
AS $issue$
DECLARE
 w uuid:='af77cb66-76b2-492a-a460-06132f468713';
 k bytea;fp text;when_issued timestamptz; cid uuid:=extensions.gen_random_uuid();
 nonce text:=pg_catalog.encode(extensions.gen_random_bytes(32),'hex');
BEGIN
 IF p_owner IS DISTINCT FROM '98c3b101-d452-4b6b-902c-0b1a31fc340c'::uuid
 OR p_session IS NULL OR p_device IS NULL OR p_spki_base64 IS NULL
 OR pg_catalog.length(p_spki_base64) NOT BETWEEN 85 AND 800
 OR p_spki_base64 !~ '^[A-Za-z0-9+/]+={0,2}$'
 THEN RAISE EXCEPTION 'Pending registration denied' USING ERRCODE='42501'; END IF;
 BEGIN k:=pg_catalog.decode(p_spki_base64,'base64');
 EXCEPTION WHEN others THEN
 RAISE EXCEPTION 'Invalid registration public key' USING ERRCODE='22023'; END;
 IF pg_catalog.octet_length(k) NOT BETWEEN 64 AND 512
 THEN RAISE EXCEPTION 'Invalid registration key size' USING ERRCODE='22023'; END IF;
 PERFORM pg_catalog.pg_advisory_xact_lock(pg_catalog.hashtextextended(w::text,0));
 IF NOT EXISTS (SELECT 1 FROM marketpro_private.qa_owner_pending_gate
      WHERE workspace_id=w AND enabled)
 OR NOT EXISTS(SELECT 1 FROM public.workspace_members m
       JOIN auth.users u ON u.id=m.user_id
       JOIN auth.sessions s ON s.user_id=u.id
       WHERE m.workspace_id=w AND m.user_id=p_owner AND m.role='owner'
         AND s.id=p_session AND u.email_confirmed_at IS NOT NULL
         AND (s.not_after IS NULL OR s.not_after>clock_timestamp())
         AND EXISTS(SELECT 1 FROM auth.mfa_factors f
           WHERE f.user_id=u.id AND f.factor_type='totp' AND f.status='verified'))
 OR EXISTS(SELECT 1 FROM marketpro_private.qa_owner_pending_devices WHERE workspace_id=w)
 OR EXISTS(SELECT 1 FROM public.registered_devices WHERE workspace_id=w)
 OR EXISTS(SELECT 1 FROM public.workspace_members WHERE role<>'owner')
 OR EXISTS(SELECT 1 FROM marketpro_private.pending_team_enrollments)
 OR EXISTS(SELECT 1 FROM marketpro_private.qa_owner_bootstrap_gate WHERE enabled)
 OR EXISTS(SELECT 1 FROM marketpro_private.qa_invite_gate WHERE enabled)
 OR (SELECT count(*) FROM marketpro_private.qa_owner_pending_challenges
     WHERE owner_user_id=p_owner AND issued_at>clock_timestamp()-interval '1 day')>=6
 THEN RAISE EXCEPTION 'Owner pending gate closed' USING ERRCODE='42501'; END IF;
 UPDATE marketpro_private.qa_owner_pending_challenges SET consumed_at=clock_timestamp()
 WHERE workspace_id=w AND consumed_at IS NULL;
 when_issued:=clock_timestamp();
 fp:=pg_catalog.encode(extensions.digest(k,'sha256'),'hex');
 INSERT INTO marketpro_private.qa_owner_pending_challenges(
 challenge_id,workspace_id,owner_user_id,session_id,device_id,
 spki_der,fingerprint_sha256,nonce_hex,issued_at,expires_at)
 VALUES(cid,w,p_owner,p_session,p_device,k,fp,nonce,when_issued,
   when_issued+interval '60 seconds');
 RETURN pg_catalog.jsonb_build_object(
   'proof_version','marketpro-owner-pending-registration-v1',
   'workspace_id',w,'owner_user_id',p_owner,'session_id',p_session,
   'device_id',p_device,'challenge_id',cid,'nonce_hex',nonce,
   'fingerprint_sha256',fp,
   'issued_at_millis',floor(extract(epoch from when_issued)*1000)::bigint,
   'expires_at_millis',floor(extract(epoch from when_issued+interval '60 seconds')*1000)::bigint,
   'registration_status','not_registered',
   'device_activated',false,'staff_access',false);
END;$issue$;
REVOKE ALL ON FUNCTION public.marketpro_qa_service_pending_owner_issue(uuid,uuid,uuid,text)
 FROM PUBLIC,anon,authenticated;
GRANT EXECUTE ON FUNCTION public.marketpro_qa_service_pending_owner_issue(uuid,uuid,uuid,text)
 TO service_role;

CREATE FUNCTION public.marketpro_qa_service_pending_owner_material(
 p_challenge uuid,p_owner uuid,p_session uuid
) RETURNS jsonb
LANGUAGE plpgsql VOLATILE SECURITY DEFINER SET search_path=''
AS $material$
DECLARE m jsonb;
BEGIN
 SELECT pg_catalog.jsonb_build_object(
  'proof_version','marketpro-owner-pending-registration-v1',
  'workspace_id',c.workspace_id,'owner_user_id',c.owner_user_id,
  'session_id',c.session_id,'device_id',c.device_id,'challenge_id',c.challenge_id,
  'nonce_hex',c.nonce_hex,'fingerprint_sha256',c.fingerprint_sha256,
  'issued_at_millis',floor(extract(epoch from c.issued_at)*1000)::bigint,
  'expires_at_millis',floor(extract(epoch from c.expires_at)*1000)::bigint,
  'spki_base64',replace(pg_catalog.encode(c.spki_der,'base64'),E'\n','')
 ) INTO m
 FROM marketpro_private.qa_owner_pending_challenges c
 JOIN auth.sessions s ON s.id=c.session_id AND s.user_id=c.owner_user_id
 JOIN marketpro_private.qa_owner_pending_gate g ON g.workspace_id=c.workspace_id
 WHERE c.challenge_id=p_challenge AND c.owner_user_id=p_owner
 AND c.session_id=p_session AND c.consumed_at IS NULL
 AND c.issued_at<=clock_timestamp() AND c.expires_at>clock_timestamp()
 AND g.enabled AND (s.not_after IS NULL OR s.not_after>clock_timestamp())
 AND NOT EXISTS(SELECT 1 FROM marketpro_private.qa_owner_pending_devices
   WHERE workspace_id=c.workspace_id)
 AND NOT EXISTS(SELECT 1 FROM public.registered_devices
   WHERE workspace_id=c.workspace_id);
 IF m IS NULL THEN RAISE EXCEPTION 'Pending challenge invalid' USING ERRCODE='42501'; END IF;
 RETURN m;
END;$material$;
REVOKE ALL ON FUNCTION public.marketpro_qa_service_pending_owner_material(uuid,uuid,uuid)
 FROM PUBLIC,anon,authenticated;
GRANT EXECUTE ON FUNCTION public.marketpro_qa_service_pending_owner_material(uuid,uuid,uuid)
 TO service_role;

-- Edge must cryptographically verify P-256 signature of this domain-separated
-- transcript before invoking this privileged single-use commit.
CREATE FUNCTION public.marketpro_qa_service_pending_owner_commit(
 p_challenge uuid,p_owner uuid,p_session uuid
) RETURNS jsonb
LANGUAGE plpgsql VOLATILE SECURITY DEFINER SET search_path=''
AS $commit$
DECLARE c marketpro_private.qa_owner_pending_challenges%ROWTYPE; changed integer;
BEGIN
 PERFORM pg_catalog.pg_advisory_xact_lock(
   pg_catalog.hashtextextended('af77cb66-76b2-492a-a460-06132f468713',0));
 SELECT * INTO c FROM marketpro_private.qa_owner_pending_challenges
 WHERE challenge_id=p_challenge AND owner_user_id=p_owner AND session_id=p_session
 FOR UPDATE;
 IF NOT FOUND OR c.workspace_id<>'af77cb66-76b2-492a-a460-06132f468713'::uuid
 OR c.consumed_at IS NOT NULL OR c.expires_at<=clock_timestamp()
 OR c.issued_at>clock_timestamp()
 OR NOT EXISTS(SELECT 1 FROM marketpro_private.qa_owner_pending_gate
    WHERE workspace_id=c.workspace_id AND enabled)
 OR NOT EXISTS(SELECT 1 FROM auth.sessions s WHERE s.id=c.session_id
    AND s.user_id=c.owner_user_id
    AND (s.not_after IS NULL OR s.not_after>clock_timestamp()))
 OR NOT EXISTS(SELECT 1 FROM public.workspace_members m
    JOIN auth.users u ON u.id=m.user_id
    WHERE m.workspace_id=c.workspace_id AND m.user_id=c.owner_user_id
    AND m.role='owner' AND u.email_confirmed_at IS NOT NULL
    AND EXISTS(SELECT 1 FROM auth.mfa_factors f WHERE f.user_id=u.id
      AND f.factor_type='totp' AND f.status='verified'))
 OR EXISTS(SELECT 1 FROM marketpro_private.qa_owner_pending_devices
    WHERE workspace_id=c.workspace_id)
 OR EXISTS(SELECT 1 FROM public.registered_devices WHERE workspace_id=c.workspace_id)
 OR EXISTS(SELECT 1 FROM public.workspace_members WHERE role<>'owner')
 THEN RAISE EXCEPTION 'Pending verification denied' USING ERRCODE='42501'; END IF;
 UPDATE marketpro_private.qa_owner_pending_challenges
 SET consumed_at=clock_timestamp(),verified_at=clock_timestamp()
 WHERE challenge_id=p_challenge AND consumed_at IS NULL;
 GET DIAGNOSTICS changed=ROW_COUNT;
 IF changed<>1 THEN RAISE EXCEPTION 'Pending nonce replay' USING ERRCODE='42501'; END IF;
 INSERT INTO marketpro_private.qa_owner_pending_devices(
 workspace_id,owner_user_id,device_id,key_fingerprint_sha256,spki_der,proof_challenge_id)
 VALUES(c.workspace_id,c.owner_user_id,c.device_id,c.fingerprint_sha256,c.spki_der,c.challenge_id);
 RETURN pg_catalog.jsonb_build_object('ok',true,'registration_status','pending',
  'owner_user_id',c.owner_user_id,'device_id',c.device_id,
  'fingerprint_sha256',c.fingerprint_sha256,
  'nonce_consumed_once',true,'device_activated',false,
  'hardware_attestation_verified',false,'can_write',false,
  'staff_access',false);
END;$commit$;
REVOKE ALL ON FUNCTION public.marketpro_qa_service_pending_owner_commit(uuid,uuid,uuid)
 FROM PUBLIC,anon,authenticated;
GRANT EXECUTE ON FUNCTION public.marketpro_qa_service_pending_owner_commit(uuid,uuid,uuid)
 TO service_role;

DO $post$
BEGIN
 IF (SELECT count(*) FROM marketpro_private.qa_owner_pending_gate WHERE enabled)<>0
 OR (SELECT count(*) FROM marketpro_private.qa_owner_pending_devices)<>0
 OR (SELECT count(*) FROM marketpro_private.qa_owner_pending_challenges)<>0
 OR (SELECT count(*) FROM public.registered_devices)<>0
 OR (SELECT count(*) FROM public.workspace_members WHERE role<>'owner')<>0
 OR has_function_privilege('authenticated',
  'public.marketpro_qa_service_pending_owner_issue(uuid,uuid,uuid,text)','EXECUTE')
 OR has_function_privilege('authenticated',
  'public.marketpro_qa_service_pending_owner_material(uuid,uuid,uuid)','EXECUTE')
 OR has_function_privilege('authenticated',
  'public.marketpro_qa_service_pending_owner_commit(uuid,uuid,uuid)','EXECUTE')
 OR has_function_privilege('anon',
  'public.marketpro_qa_service_pending_owner_commit(uuid,uuid,uuid)','EXECUTE')
 OR NOT (SELECT relrowsecurity FROM pg_class
  WHERE oid='marketpro_private.qa_owner_pending_devices'::regclass)
 THEN RAISE EXCEPTION 'Pending registration security postflight failed'; END IF;
END;$post$;
COMMIT;
