-- Market Pro v4.3.3: user-approved, one-step Team device and Photo Key recovery.
-- Additive RPC; legacy RPCs remain unchanged for currently installed APKs.
-- A signed-in Team phone first requests its NEW UUID and proves possession of
-- its NEW Android Keystore P-256 key while still pending. A separate, freshly
-- MFA-verified Owner explicitly accepts that UUID and simultaneously activates
-- the phone + pinned photo key.
--
-- If this is a reinstall of an existing device LABEL on the same Team account,
-- the Owner's single explicit action retires exactly the earlier enabled UUID.
-- Historic purchases, Box photos and their origin_device_id FKs stay intact.
-- No background, client-side, or password-only key re-approval occurs.
create or replace function public.marketpro_qa_approve_team_phone_and_photo_v433(
    p_workspace_id uuid,
    p_owner_device_id uuid,
    p_team_device_id uuid,
    p_confirm boolean
) returns jsonb
language plpgsql
security definer
set search_path to ''
as $function$
declare
    v_uid uuid := auth.uid();
    v_session uuid;
    v_now bigint := floor(extract(epoch from pg_catalog.clock_timestamp()))::bigint;
    v_fresh_mfa boolean := false;
    v_team public.registered_devices%rowtype;
    v_prior uuid;
    v_key_status text;
    v_active_team integer;
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

    update public.registered_devices d
       set enabled=true,can_write=false
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
      'can_write',false,'photo_key_status','approved',
      'replaced_old_device',v_prior is not null
    );
end
$function$;
revoke all on function public.marketpro_qa_approve_team_phone_and_photo_v433(uuid,uuid,uuid,boolean) from public;
revoke all on function public.marketpro_qa_approve_team_phone_and_photo_v433(uuid,uuid,uuid,boolean) from anon;
grant execute on function public.marketpro_qa_approve_team_phone_and_photo_v433(uuid,uuid,uuid,boolean) to authenticated;