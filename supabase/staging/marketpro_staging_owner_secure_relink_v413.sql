-- Market Pro STAGING ONLY: recovery after the Owner reinstalled an APK.
-- This migration creates a guarded RPC. It DOES NOT change any current device permissions.
-- On explicit app confirmation, a fresh Supabase TOTP-verified AAL2 session can
-- atomically retire the prior Owner device and enroll the replacement.
-- Owner auth user and workspace role are always read from signed JWT/server tables;
-- never from client-supplied identity fields.

create table if not exists marketpro_private.qa_owner_relink_audit_v1 (
    id bigint generated always as identity primary key,
    workspace_id uuid not null,
    owner_user_id uuid not null,
    previous_device_id uuid not null,
    replacement_device_id uuid not null,
    auth_session_id uuid not null,
    mfa_totp_verified_at timestamptz not null,
    recovered_at timestamptz not null default clock_timestamp(),
    constraint qa_owner_relink_distinct_devices check (previous_device_id <> replacement_device_id)
);

alter table marketpro_private.qa_owner_relink_audit_v1 enable row level security;
revoke all on table marketpro_private.qa_owner_relink_audit_v1 from public, anon, authenticated;
revoke all on sequence marketpro_private.qa_owner_relink_audit_v1_id_seq from public, anon, authenticated;

create or replace function public.marketpro_qa_owner_secure_relink_v1(
    p_workspace_id uuid,
    p_new_device_id uuid,
    p_new_device_label text,
    p_confirm boolean
)
returns jsonb
language plpgsql
security definer
set search_path = ''
as $function$
declare
    v_uid uuid := auth.uid();
    v_jwt jsonb := auth.jwt();
    v_session_id uuid;
    v_now_epoch bigint := floor(extract(epoch from clock_timestamp()))::bigint;
    v_totp_epoch bigint;
    v_previous_device_id uuid;
    v_owner_count int;
    v_existing_user_id uuid;
    v_existing_workspace_id uuid;
begin
    if p_workspace_id is distinct from 'af77cb66-76b2-492a-a460-06132f468713'::uuid
       or p_new_device_id is null or p_confirm is distinct from true
       or p_new_device_label is null
       or char_length(btrim(p_new_device_label)) not between 1 and 100
       or v_uid is null
       or coalesce((v_jwt->>'is_anonymous')::boolean,false)
       or v_jwt->>'aal' is distinct from 'aal2' then
        raise exception 'Owner re-link requires explicit confirmation and MFA'
            using errcode='42501';
    end if;

    begin
        v_session_id := (v_jwt->>'session_id')::uuid;
    exception when others then
        raise exception 'Invalid active Owner session' using errcode='42501';
    end;

    -- Requiring a genuinely recent Authenticator challenge (not merely an old AAL2
    -- JWT) prevents reused persistent sessions from silently swapping devices.
    if jsonb_typeof(v_jwt->'amr') is distinct from 'array' then
        raise exception 'Fresh Owner Authenticator verification required' using errcode='42501';
    end if;
    select max((a.value->>'timestamp')::bigint)
      into v_totp_epoch
      from jsonb_array_elements(v_jwt->'amr') as a(value)
     where a.value->>'method' = 'totp'
       and a.value->>'timestamp' ~ '^[0-9]{10}$';
    if v_totp_epoch is null
       or v_totp_epoch < v_now_epoch-300
       or v_totp_epoch > v_now_epoch+30 then
        raise exception 'Fresh Owner Authenticator verification required' using errcode='42501';
    end if;

    if not exists (
         select 1 from auth.sessions s
         where s.id=v_session_id and s.user_id=v_uid and s.aal='aal2'
           and (s.not_after is null or s.not_after>clock_timestamp())
       ) or not exists (
         select 1 from auth.users u
         where u.id=v_uid and u.email_confirmed_at is not null
       ) or not exists (
         select 1 from public.workspace_members wm
         where wm.workspace_id=p_workspace_id and wm.user_id=v_uid
           and wm.role='owner'
       ) or not exists (
         select 1 from auth.mfa_factors f
         where f.user_id=v_uid and f.factor_type='totp' and f.status='verified'
       ) then
        raise exception 'Owner account and current MFA session are not authorized'
            using errcode='42501';
    end if;

    -- Serializes with existing qa_cloud_request_phone and qa_decide_team_phone RPCs.
    perform pg_catalog.pg_advisory_xact_lock(
        pg_catalog.hashtextextended(p_workspace_id::text,0)
    );

    select count(*), min(d.id)
      into v_owner_count,v_previous_device_id
      from public.registered_devices d
      join public.workspace_members m
        on m.workspace_id=d.workspace_id and m.user_id=d.user_id
     where d.workspace_id=p_workspace_id
       and m.role='owner' and d.enabled;
    if v_owner_count <> 1 then
        raise exception 'Expected exactly one active Owner phone before recovery'
            using errcode='42501';
    end if;

    -- Protect existing active Owner identity, and never allow claiming Staff
    -- phone UUIDs or UUIDs belonging to another workspace/account.
    if v_previous_device_id=p_new_device_id then
        return pg_catalog.jsonb_build_object(
            'ok',true,'status','already_active','device_id',p_new_device_id,
            'enabled',true,'can_write',true
        );
    end if;

    select d.user_id,d.workspace_id into v_existing_user_id,v_existing_workspace_id
      from public.registered_devices d where d.id=p_new_device_id for update;
    if found and (v_existing_user_id<>v_uid or
                  v_existing_workspace_id<>p_workspace_id) then
        raise exception 'Replacement phone identity belongs to another account'
            using errcode='42501';
    end if;

    update public.registered_devices
       set enabled=false, can_write=false
     where id=v_previous_device_id and workspace_id=p_workspace_id
       and user_id=v_uid and enabled;

    if found then
        -- Explicit existing disabled device belonging to this Owner can be reused.
        update public.registered_devices
           set enabled=true,can_write=true,
               device_label=btrim(p_new_device_label)
         where id=p_new_device_id and workspace_id=p_workspace_id
           and user_id=v_uid;
        if not found then
            insert into public.registered_devices (
                id,workspace_id,user_id,device_label,enabled,can_write
            ) values (
                p_new_device_id,p_workspace_id,v_uid,
                btrim(p_new_device_label),true,true
            );
        end if;
    else
        raise exception 'Owner device changed concurrently, retry after refresh'
            using errcode='42501';
    end if;

    insert into marketpro_private.qa_owner_relink_audit_v1 (
        workspace_id,owner_user_id,previous_device_id,
        replacement_device_id,auth_session_id,mfa_totp_verified_at
    ) values (
        p_workspace_id,v_uid,v_previous_device_id,
        p_new_device_id,v_session_id,to_timestamp(v_totp_epoch)
    );

    return pg_catalog.jsonb_build_object(
        'ok',true,'status','relinked','device_id',p_new_device_id,
        'previous_device_id',v_previous_device_id,
        'enabled',true,'can_write',true
    );
end;
$function$;

revoke all on function public.marketpro_qa_owner_secure_relink_v1(
    uuid,uuid,text,boolean
) from public,anon,authenticated;
grant execute on function public.marketpro_qa_owner_secure_relink_v1(
    uuid,uuid,text,boolean
) to authenticated;

comment on function public.marketpro_qa_owner_secure_relink_v1(
    uuid,uuid,text,boolean
) is 'STAGING QA. Fresh TOTP AAL2 authenticated owner re-links a reinstalled owner device after explicit confirmation; atomically disables old UUID with private audit. No staff escalation.';
