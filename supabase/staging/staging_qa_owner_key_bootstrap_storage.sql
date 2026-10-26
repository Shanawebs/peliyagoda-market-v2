-- Market Pro staging QA: inert Owner AndroidKeyStore enrollment storage.
-- Project scope: qqiheexpbuzohllugxrk ONLY. NEVER execute on production.
-- No phone registration, no callable API/Edge endpoint, no Owner/Staff writer.
-- Cryptographic signature handling is source-only pending real device QA.
BEGIN;

DO $stage_preflight$
BEGIN
 IF (SELECT count(*) FROM public.workspaces)<>1
   OR NOT EXISTS(
      SELECT 1 FROM public.workspaces WHERE
        id='af77cb66-76b2-492a-a460-06132f468713'::uuid
        AND name='Market Pro Two-Phone QA'
        AND timezone='Asia/Colombo'
   )
   OR (SELECT count(*) FROM public.workspace_members)<>1
   OR (SELECT count(*) FROM public.workspace_members WHERE role='owner')<>1
   OR (SELECT count(*) FROM public.registered_devices)<>0
   OR (SELECT count(*) FROM marketpro_private.pending_team_enrollments)<>0
   OR (SELECT count(*) FROM marketpro_private.qa_invite_gate WHERE enabled)<>0
   OR (SELECT count(*) FROM auth.mfa_factors f
        JOIN public.workspace_members m ON m.user_id=f.user_id
        WHERE m.role='owner' AND f.factor_type='totp'
          AND f.status='verified')<>1
   OR to_regclass('marketpro_private.qa_owner_bootstrap_gate') IS NOT NULL
   OR to_regclass('marketpro_private.qa_owner_bootstrap_challenges') IS NOT NULL
   OR to_regclass('marketpro_private.qa_owner_device_public_keys') IS NOT NULL
 THEN
    RAISE EXCEPTION 'Owner bootstrap stage not isolated or already initialized';
 END IF;
END;
$stage_preflight$;

-- Separate switch from Staff invitation and writer switches.
CREATE TABLE marketpro_private.qa_owner_bootstrap_gate(
 workspace_id uuid PRIMARY KEY REFERENCES public.workspaces(id),
 enabled boolean NOT NULL DEFAULT false,
 audited_at timestamptz
);
REVOKE ALL ON marketpro_private.qa_owner_bootstrap_gate
 FROM PUBLIC, anon, authenticated;
ALTER TABLE marketpro_private.qa_owner_bootstrap_gate ENABLE ROW LEVEL SECURITY;
INSERT INTO marketpro_private.qa_owner_bootstrap_gate(workspace_id,enabled)
VALUES('af77cb66-76b2-492a-a460-06132f468713'::uuid,false);

-- A future server-generated nonce, not a device-generated challenge.
-- The spki is a PUBLIC key, never Android's nonexportable PRIVATE key.
-- Challenge fields match the verified 10-line marketpro-owner-bootstrap-key-v1
-- transcript in _shared/owner_bootstrap_key_proof.mjs.
CREATE TABLE marketpro_private.qa_owner_bootstrap_challenges(
 challenge_id uuid PRIMARY KEY DEFAULT extensions.gen_random_uuid(),
 workspace_id uuid NOT NULL REFERENCES public.workspaces(id),
 owner_user_id uuid NOT NULL REFERENCES auth.users(id),
 device_id uuid NOT NULL,
 session_id uuid NOT NULL,
 spki_der bytea NOT NULL
   CHECK(octet_length(spki_der) BETWEEN 64 AND 512),
 fingerprint_sha256 text NOT NULL
   CHECK(fingerprint_sha256 ~ '^[a-f0-9]{64}$'),
 nonce_hex text NOT NULL
   CHECK(nonce_hex ~ '^[a-f0-9]{64}$'),
 issued_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 expires_at timestamptz NOT NULL,
 consumed_at timestamptz,
 signature_verified_at timestamptz,
 CHECK(expires_at>issued_at
   AND expires_at<=issued_at+interval '60 seconds'),
 CHECK(signature_verified_at IS NULL OR consumed_at IS NOT NULL),
 CHECK(signature_verified_at IS NULL OR signature_verified_at>=issued_at)
);
CREATE INDEX qa_owner_bootstrap_open_device_idx
 ON marketpro_private.qa_owner_bootstrap_challenges(
   workspace_id,owner_user_id,device_id,expires_at)
 WHERE consumed_at IS NULL;
REVOKE ALL ON marketpro_private.qa_owner_bootstrap_challenges
 FROM PUBLIC, anon, authenticated;
ALTER TABLE marketpro_private.qa_owner_bootstrap_challenges ENABLE ROW LEVEL SECURITY;

-- This is a private storage placeholder; no row can be written by users.
-- No hardware attestation exists yet: never mark a key attested on trust of
-- a client boolean. No Owner device is activated by inserting key material.
CREATE TABLE marketpro_private.qa_owner_device_public_keys(
 workspace_id uuid PRIMARY KEY REFERENCES public.workspaces(id),
 owner_user_id uuid NOT NULL REFERENCES auth.users(id),
 device_id uuid NOT NULL UNIQUE,
 spki_der bytea NOT NULL CHECK(octet_length(spki_der) BETWEEN 64 AND 512),
 fingerprint_sha256 text NOT NULL
   CHECK(fingerprint_sha256 ~ '^[a-f0-9]{64}$'),
 challenge_id uuid NOT NULL UNIQUE
   REFERENCES marketpro_private.qa_owner_bootstrap_challenges(challenge_id),
 activated boolean NOT NULL DEFAULT false
   CHECK(activated=false),
 attestation_verified boolean NOT NULL DEFAULT false
   CHECK(attestation_verified=false),
 recorded_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
REVOKE ALL ON marketpro_private.qa_owner_device_public_keys
 FROM PUBLIC, anon, authenticated;
ALTER TABLE marketpro_private.qa_owner_device_public_keys ENABLE ROW LEVEL SECURITY;

-- The key registry is not the public.registered_devices table. It never
-- grants writer or pairing rights, even after future signature verification.

DO $postcheck$
BEGIN
 IF (SELECT count(*) FROM marketpro_private.qa_owner_bootstrap_gate WHERE enabled)<>0
  OR (SELECT count(*) FROM marketpro_private.qa_owner_bootstrap_challenges)<>0
  OR (SELECT count(*) FROM marketpro_private.qa_owner_device_public_keys)<>0
  OR (SELECT count(*) FROM public.registered_devices)<>0
  OR has_table_privilege('authenticated',
    'marketpro_private.qa_owner_device_public_keys','SELECT')
  OR has_table_privilege('authenticated',
    'marketpro_private.qa_owner_device_public_keys','INSERT')
  OR has_table_privilege('authenticated',
    'marketpro_private.qa_owner_bootstrap_challenges','UPDATE')
  OR has_table_privilege('anon',
    'marketpro_private.qa_owner_bootstrap_challenges','SELECT')
  OR (SELECT count(*) FROM pg_policies
      WHERE schemaname='marketpro_private' AND tablename IN (
        'qa_owner_bootstrap_gate','qa_owner_bootstrap_challenges',
        'qa_owner_device_public_keys'))<>0
 THEN RAISE EXCEPTION 'Owner key registration tables unexpectedly exposed';
 END IF;
END;
$postcheck$;
COMMIT;
