-- Market Pro: exactly five separately identified Purchasing Staff accounts.
-- Keeps current Admin and Samsung identities, photos and purchases unchanged.
CREATE OR REPLACE FUNCTION public.marketpro_qa_approve_team_phone_and_photo_v433(p_workspace_id uuid, p_owner_device_id uuid, p_team_device_id uuid, p_confirm boolean)
 RETURNS jsonb
 LANGUAGE plpgsql
 SECURITY DEFINER
 SET search_path TO ''
AS $function$
declare
    v_uid uuid := auth.uid();
    v_session uuid;
    v_now bigint := floor(extract(epoch from pg_catalog.clock_timestamp()))::bigint;
    v_fresh_mfa boolean := false;
    v_team public.registered_devices%rowtype;
    v_prior uuid;
    v_key_status text;
    v_active_team integer;
    v_active_writers integer;
begin
    if p_workspace_id is distinct from 'af77cb66-76b2-492a-a460-06132f468713'::uuid
       or p_owner_device_id is null or p_team_device_id is null
       or p_confirm is distinct from true
       or p_owner_device_id = p_team_device_id
       or v_uid is null
       or coalesce((auth.jwt()->>'is_anonymous')::boolean,false)
       or auth.jwt()->>'aal' <> 'aal2'
    then
        raise exception 'Secure Owner confirmation and MFA required' using errcode='42501';
    end if;

    begin
       v_session := (auth.jwt()->>'session_id')::uuid;
       select exists(
          select 1
          from pg_catalog.jsonb_array_elements(
             coalesce(auth.jwt()->'amr','[]'::jsonb)
          ) as m(value)
          where m.value->>'method' = 'totp'
            and (m.value->>'timestamp') ~ '^[0-9]{9,12}$'
            and (m.value->>'timestamp')::bigint between v_now-300 and v_now+15
       ) into v_fresh_mfa;
    exception when others then
       raise exception 'Fresh Owner Authenticator verification required'
         using errcode='42501';
    end;

    if not v_fresh_mfa then
       raise exception 'Fresh Owner Authenticator verification required'
         using errcode='42501';
    end if;

    perform pg_catalog.pg_advisory_xact_lock(
       pg_catalog.hashtextextended(p_workspace_id::text,0));

    if not exists(
        select 1 from auth.sessions s
        where s.user_id=v_uid and s.id=v_session
          and (s.not_after is null or s.not_after>pg_catalog.clock_timestamp())
    ) or not exists(
        select 1 from public.workspace_members m
        join public.registered_devices d
          on d.workspace_id=m.workspace_id and d.user_id=m.user_id
        where m.workspace_id=p_workspace_id and m.user_id=v_uid
          and m.role='owner' and d.id=p_owner_device_id
          and d.enabled and d.can_write and d.retired_at is null
    ) or not exists(
        select 1 from auth.mfa_factors f
        where f.user_id=v_uid and f.factor_type='totp' and f.status='verified'
    ) then
        raise exception 'Owner phone is not actively authorized' using errcode='42501';
    end if;

    select d.* into v_team
    from public.registered_devices d
    join public.workspace_members m
      on m.workspace_id=d.workspace_id and m.user_id=d.user_id
    where d.workspace_id=p_workspace_id and d.id=p_team_device_id
      and m.role='editor' and d.user_id<>v_uid
      and d.retired_at is null and not d.can_write
    for update of d;
    if not found then
        raise exception 'Pending Team phone not found' using errcode='42501';
    end if;

    select k.status into v_key_status
    from public.marketpro_photo_device_keys k
    where k.workspace_id=p_workspace_id and k.device_id=p_team_device_id
      and k.user_id=v_team.user_id
    for update;
    if v_key_status is distinct from 'pending'
       and v_key_status is distinct from 'approved' then
       raise exception 'Team must request Photo Key enrollment before approval'
         using errcode='23514';
    end if;

    -- Exactly one same-user/label active old UUID can be securely retired by
    -- this explicit Owner operation. Never match across different accounts.
    select d.id into v_prior
    from public.registered_devices d
    where d.workspace_id=p_workspace_id and d.user_id=v_team.user_id
      and d.id<>p_team_device_id and d.enabled and d.retired_at is null
      and pg_catalog.lower(pg_catalog.btrim(d.device_label)) =
          pg_catalog.lower(pg_catalog.btrim(v_team.device_label))
    for update;
    if found then
       update public.registered_devices d
          set enabled=false,can_write=false,
              retired_at=pg_catalog.clock_timestamp()
       where d.workspace_id=p_workspace_id and d.id=v_prior
         and d.enabled and d.retired_at is null;
       update public.marketpro_photo_device_keys k
          set status='revoked'
       where k.workspace_id=p_workspace_id and k.device_id=v_prior
         and k.status in ('approved','pending');
    end if;

    select count(*) into v_active_team
    from public.registered_devices d
    where d.workspace_id=p_workspace_id and d.user_id=v_team.user_id
      and d.id<>p_team_device_id and d.enabled and d.retired_at is null;
    if v_active_team>=2 and not v_team.enabled then
       raise exception 'Team already has two approved phones; revoke one first'
         using errcode='23505';
    end if;

    -- 5 different Subs accounts may write; a user still has one writer phone.
    if exists(
        select 1 from public.registered_devices d
        where d.workspace_id=p_workspace_id and d.user_id=v_team.user_id
          and d.id<>p_team_device_id and d.enabled and d.can_write
          and d.retired_at is null
    ) then
        raise exception 'Existing Subs writer phone must be retired before replacement'
          using errcode='23505';
    end if;
    select count(*) into v_active_writers
    from public.registered_devices d
    join public.workspace_members m on m.workspace_id=d.workspace_id and m.user_id=d.user_id
    where d.workspace_id=p_workspace_id and m.role='editor'
      and d.enabled and d.can_write and d.retired_at is null;
    if v_active_writers >= 5 then
        raise exception 'All five Subs writer slots are in use'
          using errcode='23514';
    end if;
    update public.registered_devices d
       set enabled=true,can_write=true
     where d.workspace_id=p_workspace_id and d.id=p_team_device_id
       and d.retired_at is null;

    update public.marketpro_photo_device_keys k
       set status='approved',
           approved_by=v_uid,
           approved_at=pg_catalog.clock_timestamp()
     where k.workspace_id=p_workspace_id and k.device_id=p_team_device_id
       and k.user_id=v_team.user_id
       and k.status='pending';
    -- Existing approved key is idempotently preserved as-is. New key never
    -- becomes approved without this separate Owner confirmation.
    return pg_catalog.jsonb_build_object(
      'ok',true,'device_id',p_team_device_id,'enabled',true,
      'can_write',true,'photo_key_status','approved',
      'replaced_old_device',v_prior is not null
    );
end
$function$
;

CREATE OR REPLACE FUNCTION public.marketpro_owner_add_subs_v448(
 p_workspace_id uuid,p_owner_device_id uuid,p_email text,p_confirm boolean)
RETURNS jsonb LANGUAGE plpgsql SECURITY DEFINER SET search_path TO ''
AS $$
DECLARE
 v_uid uuid:=auth.uid();
 v_session uuid;
 v_target uuid;
 v_count integer;
 v_now bigint:=floor(extract(epoch from clock_timestamp()))::bigint;
BEGIN
 IF p_workspace_id IS DISTINCT FROM 'af77cb66-76b2-492a-a460-06132f468713'::uuid
 OR p_owner_device_id IS NULL OR p_confirm IS DISTINCT FROM true OR
 p_email IS NULL OR length(btrim(p_email)) NOT BETWEEN 5 AND 254
 OR v_uid IS NULL OR auth.jwt()->>'aal' <> 'aal2'
 OR coalesce((auth.jwt()->>'is_anonymous')::boolean,false)
 THEN RAISE EXCEPTION 'Owner verification required' USING ERRCODE='42501'; END IF;
 BEGIN v_session:=(auth.jwt()->>'session_id')::uuid;
 EXCEPTION WHEN others THEN RAISE EXCEPTION 'Owner Auth session invalid' USING ERRCODE='42501'; END;
 PERFORM pg_catalog.pg_advisory_xact_lock(pg_catalog.hashtextextended(p_workspace_id::text,0));
 IF NOT EXISTS (
   SELECT 1 FROM auth.sessions s
   WHERE s.user_id=v_uid AND s.id=v_session
     AND (s.not_after IS NULL OR s.not_after>clock_timestamp())
 ) OR NOT EXISTS (
   SELECT 1 FROM public.workspace_members m
   JOIN public.registered_devices d ON d.workspace_id=m.workspace_id AND d.user_id=m.user_id
   WHERE m.workspace_id=p_workspace_id AND m.user_id=v_uid AND m.role='owner'
     AND d.id=p_owner_device_id AND d.enabled AND d.can_write AND d.retired_at IS NULL
 ) OR NOT EXISTS (
   SELECT 1 FROM auth.mfa_factors f WHERE f.user_id=v_uid AND f.factor_type='totp' AND f.status='verified'
 ) OR NOT EXISTS (
   SELECT 1 FROM pg_catalog.jsonb_array_elements(coalesce(auth.jwt()->'amr','[]'::jsonb)) amr(method)
   WHERE amr.method->>'method'='totp'
     AND amr.method->>'timestamp' ~ '^[0-9]{9,12}$'
     AND (amr.method->>'timestamp')::bigint BETWEEN v_now-300 AND v_now+15
 ) THEN RAISE EXCEPTION 'Fresh Owner MFA confirmation required' USING ERRCODE='42501'; END IF;
 SELECT u.id INTO v_target FROM auth.users u
 WHERE lower(u.email)=lower(btrim(p_email)) AND u.email_confirmed_at IS NOT NULL AND u.id<>v_uid;
 IF v_target IS NULL THEN RAISE EXCEPTION 'Verified Subs account not found' USING ERRCODE='23514'; END IF;
 IF EXISTS(SELECT 1 FROM public.workspace_members m WHERE m.workspace_id=p_workspace_id AND m.user_id=v_target) THEN
   IF NOT EXISTS(SELECT 1 FROM public.workspace_members m WHERE m.workspace_id=p_workspace_id AND m.user_id=v_target AND m.role='editor') THEN
     RAISE EXCEPTION 'Existing member is not Subs' USING ERRCODE='42501';
   END IF;
   RETURN jsonb_build_object('ok',true,'status','already_member','max_subs',5);
 END IF;
 SELECT count(*) INTO v_count FROM public.workspace_members m
 WHERE m.workspace_id=p_workspace_id AND m.role='editor';
 IF v_count>=5 THEN RAISE EXCEPTION 'All five Subs accounts are assigned' USING ERRCODE='23514'; END IF;
 INSERT INTO public.workspace_members(workspace_id,user_id,role)
 VALUES(p_workspace_id,v_target,'editor');
 RETURN jsonb_build_object('ok',true,'status','member_added','max_subs',5);
END
$$;
REVOKE ALL ON FUNCTION public.marketpro_owner_add_subs_v448(uuid,uuid,text,boolean) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.marketpro_owner_add_subs_v448(uuid,uuid,text,boolean) TO authenticated;
