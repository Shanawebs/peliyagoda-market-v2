-- Market Pro v4.2.8. Non-destructive migration: no existing phone is changed.
ALTER TABLE public.registered_devices ADD COLUMN IF NOT EXISTS retired_at timestamptz;

-- A revoked device cannot be reactivated using the old application/RPC.
DO $$
BEGIN
 IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conrelid='public.registered_devices'::regclass
   AND conname='marketpro_retired_disabled_v428') THEN
  ALTER TABLE public.registered_devices ADD CONSTRAINT marketpro_retired_disabled_v428
  CHECK (retired_at IS NULL OR (NOT enabled AND NOT can_write));
 END IF;
END $$;

CREATE OR REPLACE FUNCTION marketpro_private.qa_prevent_duplicate_active_phone_v428()
RETURNS trigger LANGUAGE plpgsql SECURITY DEFINER SET search_path=''
AS $fn$
BEGIN
 IF NEW.enabled AND NOT NEW.can_write
   AND (TG_OP='INSERT' OR NOT OLD.enabled)
   AND EXISTS (SELECT 1 FROM public.workspace_members m
       WHERE m.workspace_id=NEW.workspace_id AND m.user_id=NEW.user_id AND m.role='editor')
   AND EXISTS (SELECT 1 FROM public.registered_devices d
       WHERE d.workspace_id=NEW.workspace_id AND d.user_id=NEW.user_id
         AND d.id<>NEW.id AND d.enabled)
 THEN
  RAISE EXCEPTION 'Team account already has an approved phone. Remove the old phone first'
   USING ERRCODE='23505';
 END IF;
 RETURN NEW;
END $fn$;
REVOKE ALL ON FUNCTION marketpro_private.qa_prevent_duplicate_active_phone_v428()
 FROM public,anon,authenticated;
DROP TRIGGER IF EXISTS qa_prevent_duplicate_active_phone_v428 ON public.registered_devices;
CREATE TRIGGER qa_prevent_duplicate_active_phone_v428
BEFORE INSERT OR UPDATE OF enabled,can_write ON public.registered_devices
FOR EACH ROW EXECUTE FUNCTION marketpro_private.qa_prevent_duplicate_active_phone_v428();

CREATE TABLE IF NOT EXISTS marketpro_private.qa_phone_retirement_audit_v428(
 id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
 workspace_id uuid NOT NULL,
 owner_user_id uuid NOT NULL,
 retired_device_id uuid NOT NULL,
 phone_role text NOT NULL,
 was_active boolean NOT NULL,
 retired_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
ALTER TABLE marketpro_private.qa_phone_retirement_audit_v428 ENABLE ROW LEVEL SECURITY;
REVOKE ALL ON marketpro_private.qa_phone_retirement_audit_v428 FROM public,anon,authenticated;

CREATE OR REPLACE FUNCTION public.marketpro_qa_retire_phone_v428(
 p_workspace_id uuid,p_owner_device_id uuid,p_target_device_id uuid,p_confirm boolean)
RETURNS jsonb LANGUAGE plpgsql SECURITY DEFINER SET search_path=''
AS $fn$
DECLARE
 v_uid uuid:=auth.uid();
 v_jwt jsonb:=auth.jwt();
 v_session uuid;
 v_role text;
 v_target public.registered_devices%ROWTYPE;
BEGIN
 IF p_workspace_id IS DISTINCT FROM 'af77cb66-76b2-492a-a460-06132f468713'::uuid
  OR p_owner_device_id IS NULL OR p_target_device_id IS NULL
  OR p_owner_device_id=p_target_device_id OR p_confirm IS DISTINCT FROM true
  OR v_uid IS NULL OR coalesce((v_jwt->>'is_anonymous')::boolean,false)
  OR v_jwt->>'aal' IS DISTINCT FROM 'aal2'
 THEN RAISE EXCEPTION 'Owner phone and MFA confirmation required' USING ERRCODE='42501'; END IF;
 BEGIN
  v_session:=(v_jwt->>'session_id')::uuid;
 EXCEPTION WHEN others THEN
  RAISE EXCEPTION 'Invalid Owner session' USING ERRCODE='42501';
 END;
 PERFORM pg_catalog.pg_advisory_xact_lock(pg_catalog.hashtextextended(p_workspace_id::text,0));
 IF NOT EXISTS (SELECT 1 FROM auth.sessions s WHERE s.id=v_session AND s.user_id=v_uid
     AND s.aal='aal2' AND (s.not_after IS NULL OR s.not_after>clock_timestamp()))
  OR NOT EXISTS (SELECT 1 FROM public.workspace_members m WHERE m.workspace_id=p_workspace_id
     AND m.user_id=v_uid AND m.role='owner')
  OR NOT EXISTS (SELECT 1 FROM auth.mfa_factors f WHERE f.user_id=v_uid AND f.factor_type='totp'
     AND f.status='verified')
  OR NOT EXISTS (SELECT 1 FROM public.registered_devices d WHERE d.id=p_owner_device_id
     AND d.workspace_id=p_workspace_id AND d.user_id=v_uid AND d.enabled AND d.can_write)
 THEN RAISE EXCEPTION 'Active Owner authorization failed' USING ERRCODE='42501'; END IF;

 SELECT d.* INTO v_target FROM public.registered_devices d
 WHERE d.id=p_target_device_id AND d.workspace_id=p_workspace_id FOR UPDATE;
 IF NOT FOUND THEN RAISE EXCEPTION 'Unknown phone ID' USING ERRCODE='42501'; END IF;
 SELECT m.role INTO v_role FROM public.workspace_members m
 WHERE m.workspace_id=v_target.workspace_id AND m.user_id=v_target.user_id;
 IF v_role IS NULL OR v_role NOT IN ('owner','editor') THEN
  RAISE EXCEPTION 'Unrecognized member role' USING ERRCODE='42501'; END IF;
 IF v_role='owner' AND v_target.enabled THEN
  RAISE EXCEPTION 'Active Owner phone cannot be removed' USING ERRCODE='42501'; END IF;
 IF v_target.retired_at IS NOT NULL THEN
  RETURN pg_catalog.jsonb_build_object('ok',true,'status','already_removed',
   'device_id',v_target.id,'enabled',false,'can_write',false);
 END IF;
 UPDATE public.registered_devices SET enabled=false,can_write=false,
    retired_at=clock_timestamp()
 WHERE id=v_target.id AND workspace_id=p_workspace_id AND retired_at IS NULL;
 IF NOT FOUND THEN RAISE EXCEPTION 'Concurrent device update' USING ERRCODE='42501'; END IF;
 INSERT INTO marketpro_private.qa_phone_retirement_audit_v428(
   workspace_id,owner_user_id,retired_device_id,phone_role,was_active)
 VALUES(p_workspace_id,v_uid,v_target.id,v_role,v_target.enabled);
 RETURN pg_catalog.jsonb_build_object('ok',true,'status','removed','device_id',v_target.id,
   'enabled',false,'can_write',false);
END $fn$;
REVOKE ALL ON FUNCTION public.marketpro_qa_retire_phone_v428(uuid,uuid,uuid,boolean)
 FROM public,anon,authenticated;
GRANT EXECUTE ON FUNCTION public.marketpro_qa_retire_phone_v428(uuid,uuid,uuid,boolean)
 TO authenticated;

CREATE OR REPLACE FUNCTION public.marketpro_qa_list_visible_phones_v428(p_workspace_id uuid)
RETURNS jsonb LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path=''
AS $fn$
DECLARE
 v_uid uuid:=auth.uid(); v_jwt jsonb:=auth.jwt(); v_session uuid;
 v_role text; v_rows jsonb;
BEGIN
 IF p_workspace_id IS DISTINCT FROM 'af77cb66-76b2-492a-a460-06132f468713'::uuid
  OR v_uid IS NULL OR coalesce((v_jwt->>'is_anonymous')::boolean,false)
 THEN RAISE EXCEPTION 'Unauthorized phone listing' USING ERRCODE='42501'; END IF;
 BEGIN v_session:=(v_jwt->>'session_id')::uuid;
 EXCEPTION WHEN others THEN RAISE EXCEPTION 'Invalid auth session' USING ERRCODE='42501'; END;
 SELECT m.role INTO v_role FROM public.workspace_members m WHERE m.workspace_id=p_workspace_id
  AND m.user_id=v_uid AND m.role IN ('owner','editor');
 IF v_role IS NULL OR NOT EXISTS (SELECT 1 FROM auth.sessions s
    WHERE s.user_id=v_uid AND s.id=v_session
      AND (s.not_after IS NULL OR s.not_after>clock_timestamp()))
 THEN RAISE EXCEPTION 'Phone viewer not authorized' USING ERRCODE='42501'; END IF;
 SELECT coalesce(jsonb_agg(pg_catalog.jsonb_build_object(
  'device_id',d.id,'user_id',d.user_id,'label',d.device_label,
  'role',m.role,'enabled',d.enabled,'can_write',d.can_write)
  ORDER BY d.enabled DESC,d.enrolled_at DESC,d.id),'[]'::jsonb) INTO v_rows
 FROM public.registered_devices d
 JOIN public.workspace_members m ON m.workspace_id=d.workspace_id AND m.user_id=d.user_id
 WHERE d.workspace_id=p_workspace_id AND d.retired_at IS NULL
 AND (v_role='owner' OR d.user_id=v_uid);
 RETURN pg_catalog.jsonb_build_object('ok',true,'role',v_role,'devices',v_rows);
END $fn$;
REVOKE ALL ON FUNCTION public.marketpro_qa_list_visible_phones_v428(uuid)
 FROM public,anon,authenticated;
GRANT EXECUTE ON FUNCTION public.marketpro_qa_list_visible_phones_v428(uuid)
 TO authenticated;

-- No DELETE, no existing enabled flag changes, no data migration.
