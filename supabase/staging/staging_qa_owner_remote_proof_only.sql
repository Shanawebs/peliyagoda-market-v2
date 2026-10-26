-- Market Pro 2-phone QA: one-time remote Owner ECDSA self-test ONLY.
-- SAFE HOST: qqiheexpbuzohllugxrk, NEVER deploy to Production.
-- No device activation, Staff role, Cloud Sync, Owner key enrollment or writer.
BEGIN;
DO $preflight$
BEGIN
 IF (SELECT count(*) FROM public.workspaces WHERE id='af77cb66-76b2-492a-a460-06132f468713'::uuid AND name='Market Pro Two-Phone QA')<>1
 OR (SELECT count(*) FROM public.workspace_members WHERE role='owner')<>1
 OR (SELECT count(*) FROM public.workspace_members WHERE role<>'owner')<>0
 OR (SELECT count(*) FROM public.registered_devices)<>0
 OR (SELECT count(*) FROM marketpro_private.pending_team_enrollments)<>0
 OR (SELECT count(*) FROM marketpro_private.qa_owner_bootstrap_gate WHERE enabled)<>0
 OR (SELECT count(*) FROM marketpro_private.qa_invite_gate WHERE enabled)<>0
 OR to_regclass('marketpro_private.qa_owner_remote_proof_trials') IS NOT NULL
 THEN RAISE EXCEPTION 'Staging remote proof preflight failed'; END IF;
END;$preflight$;

CREATE TABLE marketpro_private.qa_owner_remote_proof_trials(
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
 CHECK(expires_at>issued_at AND expires_at<=issued_at+interval '60 seconds')
);
REVOKE ALL ON marketpro_private.qa_owner_remote_proof_trials FROM PUBLIC, anon, authenticated;
ALTER TABLE marketpro_private.qa_owner_remote_proof_trials ENABLE ROW LEVEL SECURITY;

CREATE FUNCTION public.marketpro_qa_service_remote_proof_issue(
 p_owner uuid,p_session uuid,p_device uuid,p_spki text
) RETURNS jsonb
LANGUAGE plpgsql VOLATILE SECURITY DEFINER SET search_path=''
AS $issue$
DECLARE
 w uuid:='af77cb66-76b2-492a-a460-06132f468713';
 k bytea;
 fp text;
 now_at timestamptz;
 new_id uuid:=extensions.gen_random_uuid();
 nonce text:=pg_catalog.encode(extensions.gen_random_bytes(32),'hex');
BEGIN
 IF p_owner IS DISTINCT FROM '98c3b101-d452-4b6b-902c-0b1a31fc340c'::uuid
 OR p_device IS NULL OR p_session IS NULL OR p_spki IS NULL
 OR pg_catalog.length(p_spki) NOT BETWEEN 85 AND 800
 OR p_spki !~ '^[A-Za-z0-9+/]+={0,2}$'
 THEN RAISE EXCEPTION 'Remote proof denied' USING ERRCODE='42501'; END IF;
 BEGIN
   k:=pg_catalog.decode(p_spki,'base64');
 EXCEPTION WHEN others THEN
   RAISE EXCEPTION 'Invalid SPKI Base64' USING ERRCODE='22023';
 END;
 IF octet_length(k) NOT BETWEEN 64 AND 512
 THEN RAISE EXCEPTION 'Invalid SPKI length' USING ERRCODE='22023'; END IF;

 PERFORM pg_catalog.pg_advisory_xact_lock(pg_catalog.hashtextextended(w::text,0));
 IF NOT EXISTS(
   SELECT 1 FROM public.workspace_members m JOIN auth.users u ON u.id=m.user_id
   JOIN auth.sessions s ON s.user_id=u.id
   WHERE m.workspace_id=w AND m.user_id=p_owner AND m.role='owner'
     AND u.email_confirmed_at IS NOT NULL
     AND s.id=p_session AND (s.not_after IS NULL OR s.not_after>clock_timestamp())
     AND EXISTS(SELECT 1 FROM auth.mfa_factors f WHERE f.user_id=u.id
       AND f.factor_type='totp' AND f.status='verified')
 ) OR EXISTS(SELECT 1 FROM public.registered_devices WHERE workspace_id=w)
   OR EXISTS(SELECT 1 FROM public.workspace_members WHERE role<>'owner')
   OR EXISTS(SELECT 1 FROM marketpro_private.pending_team_enrollments)
   OR EXISTS(SELECT 1 FROM marketpro_private.qa_owner_bootstrap_gate WHERE enabled)
   OR EXISTS(SELECT 1 FROM marketpro_private.qa_invite_gate WHERE enabled)
   OR (SELECT count(*) FROM marketpro_private.qa_owner_remote_proof_trials
       WHERE owner_user_id=p_owner AND issued_at>clock_timestamp()-interval '1 day')>=30
 THEN RAISE EXCEPTION 'Staging Owner remote proof not permitted' USING ERRCODE='42501'; END IF;

 -- Consume previous pending challenges before issuing a new one.
 UPDATE marketpro_private.qa_owner_remote_proof_trials
 SET consumed_at=clock_timestamp()
 WHERE workspace_id=w AND owner_user_id=p_owner AND consumed_at IS NULL;
 now_at:=clock_timestamp();
 fp:=pg_catalog.encode(extensions.digest(k,'sha256'),'hex');
 INSERT INTO marketpro_private.qa_owner_remote_proof_trials(
   challenge_id,workspace_id,owner_user_id,session_id,device_id,
   spki_der,fingerprint_sha256,nonce_hex,issued_at,expires_at
 ) VALUES(new_id,w,p_owner,p_session,p_device,k,fp,nonce,now_at,now_at+interval '60 seconds');
 RETURN pg_catalog.jsonb_build_object(
  'proof_version','marketpro-owner-bootstrap-key-v1',
  'workspace_id',w,'owner_user_id',p_owner,'session_id',p_session,
  'device_id',p_device,'challenge_id',new_id,'nonce_hex',nonce,
  'fingerprint_sha256',fp,
  'issued_at_millis',pg_catalog.floor(extract(epoch from now_at)*1000)::bigint,
  'expires_at_millis',pg_catalog.floor(extract(epoch from now_at+interval '60 seconds')*1000)::bigint,
  'phone_activated',false,'staff_access_granted',false
 );
END;$issue$;
REVOKE ALL ON FUNCTION public.marketpro_qa_service_remote_proof_issue(uuid,uuid,uuid,text) FROM PUBLIC,anon,authenticated;
GRANT EXECUTE ON FUNCTION public.marketpro_qa_service_remote_proof_issue(uuid,uuid,uuid,text) TO service_role;

CREATE FUNCTION public.marketpro_qa_service_remote_proof_material(
 p_challenge uuid,p_owner uuid,p_session uuid
) RETURNS jsonb
LANGUAGE plpgsql VOLATILE SECURITY DEFINER SET search_path=''
AS $lookup$
DECLARE result jsonb;
BEGIN
 SELECT pg_catalog.jsonb_build_object(
  'proof_version','marketpro-owner-bootstrap-key-v1',
  'workspace_id',c.workspace_id,'owner_user_id',c.owner_user_id,
  'session_id',c.session_id,'device_id',c.device_id,'challenge_id',c.challenge_id,
  'nonce_hex',c.nonce_hex,'fingerprint_sha256',c.fingerprint_sha256,
  'issued_at_millis',pg_catalog.floor(extract(epoch from c.issued_at)*1000)::bigint,
  'expires_at_millis',pg_catalog.floor(extract(epoch from c.expires_at)*1000)::bigint,
  'spki_base64',replace(pg_catalog.encode(c.spki_der,'base64'),E'\n','')
 ) INTO result FROM marketpro_private.qa_owner_remote_proof_trials c
 JOIN auth.sessions s ON s.id=c.session_id AND s.user_id=c.owner_user_id
 WHERE c.challenge_id=p_challenge AND c.owner_user_id=p_owner AND c.session_id=p_session
  AND c.consumed_at IS NULL AND c.issued_at<=clock_timestamp() AND c.expires_at>clock_timestamp()
  AND (s.not_after IS NULL OR s.not_after>clock_timestamp())
  AND NOT EXISTS(SELECT 1 FROM public.registered_devices WHERE workspace_id=c.workspace_id);
 IF result IS NULL THEN RAISE EXCEPTION 'No valid remote proof challenge' USING ERRCODE='42501'; END IF;
 RETURN result;
END;$lookup$;
REVOKE ALL ON FUNCTION public.marketpro_qa_service_remote_proof_material(uuid,uuid,uuid) FROM PUBLIC,anon,authenticated;
GRANT EXECUTE ON FUNCTION public.marketpro_qa_service_remote_proof_material(uuid,uuid,uuid) TO service_role;

CREATE FUNCTION public.marketpro_qa_service_remote_proof_consume(
 p_challenge uuid,p_owner uuid,p_session uuid
) RETURNS jsonb
LANGUAGE plpgsql VOLATILE SECURITY DEFINER SET search_path=''
AS $consume$
DECLARE
 trial marketpro_private.qa_owner_remote_proof_trials%ROWTYPE;
 n int;
BEGIN
 PERFORM pg_catalog.pg_advisory_xact_lock(
  pg_catalog.hashtextextended('af77cb66-76b2-492a-a460-06132f468713',0)
 );
 SELECT * INTO trial FROM marketpro_private.qa_owner_remote_proof_trials
 WHERE challenge_id=p_challenge AND owner_user_id=p_owner AND session_id=p_session FOR UPDATE;
 IF NOT FOUND OR trial.workspace_id<>'af77cb66-76b2-492a-a460-06132f468713'::uuid
  OR trial.consumed_at IS NOT NULL OR trial.expires_at<=clock_timestamp()
  OR NOT EXISTS(SELECT 1 FROM auth.sessions s WHERE s.id=trial.session_id
       AND s.user_id=trial.owner_user_id AND (s.not_after IS NULL OR s.not_after>clock_timestamp()))
  OR NOT EXISTS(SELECT 1 FROM public.workspace_members m WHERE m.user_id=trial.owner_user_id
       AND m.workspace_id=trial.workspace_id AND m.role='owner')
  OR EXISTS(SELECT 1 FROM public.registered_devices WHERE workspace_id=trial.workspace_id)
 THEN RAISE EXCEPTION 'Remote proof already consumed or invalid' USING ERRCODE='42501'; END IF;
 UPDATE marketpro_private.qa_owner_remote_proof_trials
 SET consumed_at=clock_timestamp()
 WHERE challenge_id=p_challenge AND consumed_at IS NULL;
 GET DIAGNOSTICS n=ROW_COUNT;
 IF n<>1 THEN RAISE EXCEPTION 'Remote proof replayed' USING ERRCODE='42501'; END IF;
 RETURN pg_catalog.jsonb_build_object('verified_and_consumed',true,
  'device_id',trial.device_id,'fingerprint_sha256',trial.fingerprint_sha256,
  'device_registered',false,'staff_access',false,'can_write',false);
END;$consume$;
REVOKE ALL ON FUNCTION public.marketpro_qa_service_remote_proof_consume(uuid,uuid,uuid) FROM PUBLIC,anon,authenticated;
GRANT EXECUTE ON FUNCTION public.marketpro_qa_service_remote_proof_consume(uuid,uuid,uuid) TO service_role;

DO $post$
BEGIN
 IF (SELECT count(*) FROM marketpro_private.qa_owner_remote_proof_trials)<>0
 OR has_table_privilege('authenticated','marketpro_private.qa_owner_remote_proof_trials','SELECT')
 OR has_table_privilege('authenticated','marketpro_private.qa_owner_remote_proof_trials','INSERT')
 OR (SELECT relrowsecurity FROM pg_class WHERE oid='marketpro_private.qa_owner_remote_proof_trials'::regclass)=false
 OR has_function_privilege('authenticated','public.marketpro_qa_service_remote_proof_issue(uuid,uuid,uuid,text)','EXECUTE')
 OR has_function_privilege('authenticated','public.marketpro_qa_service_remote_proof_consume(uuid,uuid,uuid)','EXECUTE')
 THEN RAISE EXCEPTION 'Remote proof private gate failed'; END IF;
END;$post$;
COMMIT;
