-- Phase 2B: Secure idempotent single-writer cloud change acceptance RPC.
-- WARNING: DEV until accepted; no Android/photo upload is enabled by this migration.
-- A primary enrolled Supabase Auth user/device is required before the RPC can write.
-- Security definer is intentionally limited to this audited function.
-- p_payload keys are individually whitelisted; no photo/image/file bytes accepted.

create function public.marketpro_apply_change(
  p_workspace_id uuid,
  p_device_id uuid,
  p_operation_id uuid,
  p_entity_type text,
  p_entity_id uuid,
  p_action text,
  p_payload jsonb
) returns jsonb
language plpgsql
security definer
set search_path = ''
as $fn$
declare
  v_user uuid := auth.uid();
  v_hash text;
  v_previous public.sync_operations%rowtype;
  v_count integer;
  v_key text;
begin
  if v_user is null or coalesce((auth.jwt()->>'is_anonymous')::boolean,false) then
    raise exception 'Verified Supabase login required' using errcode='42501';
  end if;
  if p_workspace_id is null or p_device_id is null
     or p_operation_id is null or p_entity_id is null
     or p_entity_type not in ('category','item','requisition','purchase',
                              'purchase_addition','daily_cash_received')
     or p_action not in ('upsert','delete')
     or p_payload is null or jsonb_typeof(p_payload) <> 'object'
     or octet_length(p_payload::text) > 2048 then
    raise exception 'Invalid cloud change envelope' using errcode='22023';
  end if;

  -- V1: authorization is not a client flag. Only the enrolled owner+device
  -- may write. A second device cannot upload until an explicit rollout.
  if not exists (
    select 1 from public.registered_devices d
    join public.workspace_members m
      on m.workspace_id=d.workspace_id and m.user_id=d.user_id
    where d.workspace_id=p_workspace_id and d.id=p_device_id
      and d.user_id=v_user and d.enabled and d.can_write
      and m.role='owner'
  ) then
    raise exception 'Writer device is not authorized for this workspace'
      using errcode='42501';
  end if;

  -- Whitelist every property. Never accept invoice photos or paths,
  -- even accidentally through arbitrary JSON keys.
  for v_key in select jsonb_object_keys(p_payload) loop
    if not (
      (p_entity_type='category' and v_key in ('name'))
      or (p_entity_type='item' and v_key in ('name','category_id','is_active'))
      or (p_entity_type='requisition' and
          v_key in ('business_date','kitchen','item_id','quantity_grams'))
      or (p_entity_type in ('purchase','purchase_addition') and
          v_key in ('business_date','item_id','quantity_grams','price_cents_per_kg'))
      or (p_entity_type='daily_cash_received' and
          v_key in ('business_date','amount_cents'))
    ) then
      raise exception 'Cloud payload property is not allowed' using errcode='22023';
    end if;
  end loop;

  -- Each workspace has a single writer in V1; serialize retries and edits.
  perform pg_advisory_xact_lock(hashtextextended(p_workspace_id::text,0));

  -- Compute the payload hash on the server. Never trust a caller-provided hash.
  v_hash := encode(
    extensions.digest(
      convert_to(p_entity_type || ':' || p_entity_id::text || ':' || p_action ||
                 ':' || p_payload::text,'UTF8'),
      'sha256'
    ), 'hex'
  );

  insert into public.sync_operations (
    operation_id,workspace_id,device_id,actor_user_id,
    entity_type,entity_id,operation_type,payload_hash
  ) values (
    p_operation_id,p_workspace_id,p_device_id,v_user,
    p_entity_type,p_entity_id,p_action,v_hash
  ) on conflict (operation_id) do nothing;
  get diagnostics v_count = row_count;

  if v_count=0 then
    select * into v_previous from public.sync_operations
    where operation_id=p_operation_id;
    if v_previous.operation_id is null
       or v_previous.workspace_id <> p_workspace_id
       or v_previous.device_id <> p_device_id
       or v_previous.actor_user_id <> v_user
       or v_previous.entity_id <> p_entity_id
       or v_previous.entity_type <> p_entity_type
       or v_previous.operation_type <> p_action
       or v_previous.payload_hash <> v_hash then
      raise exception 'Operation ID reused with different payload'
        using errcode='23505';
    end if;
    return jsonb_build_object('accepted',true,'replayed',true,'operation_id',p_operation_id);
  end if;

  if p_action='delete' then
    if p_entity_type='requisition' then
      update public.requisitions
      set deleted_at=now(),revision=revision+1,updated_at=now()
      where id=p_entity_id and workspace_id=p_workspace_id;
    elsif p_entity_type='purchase' then
      update public.purchases
      set deleted_at=now(),revision=revision+1,updated_at=now()
      where id=p_entity_id and workspace_id=p_workspace_id;
    elsif p_entity_type='purchase_addition' then
      update public.purchase_additions
      set deleted_at=now(),revision=revision+1,updated_at=now()
      where id=p_entity_id and workspace_id=p_workspace_id;
    else
      raise exception 'Delete not supported for this entity type' using errcode='22023';
    end if;
  elsif p_entity_type='category' then
    insert into public.categories(id,workspace_id,name)
    values(p_entity_id,p_workspace_id,p_payload->>'name')
    on conflict(id) do update
    set name=excluded.name,deleted_at=null,revision=categories.revision+1,updated_at=now()
    where categories.workspace_id=excluded.workspace_id;
    get diagnostics v_count=row_count;
    if v_count<>1 then raise exception 'Category ID belongs to another workspace' using errcode='42501'; end if;
  elsif p_entity_type='item' then
    insert into public.items(id,workspace_id,category_id,name,is_active)
    values(p_entity_id,p_workspace_id,(p_payload->>'category_id')::uuid,
           p_payload->>'name',(p_payload->>'is_active')::boolean)
    on conflict(id) do update
    set category_id=excluded.category_id,name=excluded.name,is_active=excluded.is_active,
        deleted_at=null,revision=items.revision+1,updated_at=now()
    where items.workspace_id=excluded.workspace_id;
    get diagnostics v_count=row_count;
    if v_count<>1 then raise exception 'Item ID belongs to another workspace' using errcode='42501'; end if;
  elsif p_entity_type='requisition' then
    insert into public.requisitions
      (id,workspace_id,business_date,kitchen,item_id,quantity_grams)
    values(p_entity_id,p_workspace_id,(p_payload->>'business_date')::date,
           p_payload->>'kitchen',(p_payload->>'item_id')::uuid,
           (p_payload->>'quantity_grams')::bigint)
    on conflict(id) do update
    set business_date=excluded.business_date,kitchen=excluded.kitchen,
        item_id=excluded.item_id,quantity_grams=excluded.quantity_grams,
        deleted_at=null,revision=requisitions.revision+1,updated_at=now()
    where requisitions.workspace_id=excluded.workspace_id;
    get diagnostics v_count=row_count;
    if v_count<>1 then raise exception 'Requisition ID belongs to another workspace' using errcode='42501'; end if;
  elsif p_entity_type='purchase' then
    insert into public.purchases
      (id,workspace_id,business_date,item_id,quantity_grams,price_cents_per_kg)
    values(p_entity_id,p_workspace_id,(p_payload->>'business_date')::date,
           (p_payload->>'item_id')::uuid,(p_payload->>'quantity_grams')::bigint,
           (p_payload->>'price_cents_per_kg')::bigint)
    on conflict(id) do update
    set business_date=excluded.business_date,item_id=excluded.item_id,
        quantity_grams=excluded.quantity_grams,
        price_cents_per_kg=excluded.price_cents_per_kg,
        deleted_at=null,revision=purchases.revision+1,updated_at=now()
    where purchases.workspace_id=excluded.workspace_id;
    get diagnostics v_count=row_count;
    if v_count<>1 then raise exception 'Purchase ID belongs to another workspace' using errcode='42501'; end if;
  elsif p_entity_type='purchase_addition' then
    insert into public.purchase_additions
      (id,workspace_id,business_date,item_id,quantity_grams,price_cents_per_kg)
    values(p_entity_id,p_workspace_id,(p_payload->>'business_date')::date,
           (p_payload->>'item_id')::uuid,(p_payload->>'quantity_grams')::bigint,
           (p_payload->>'price_cents_per_kg')::bigint)
    on conflict(id) do update
    set business_date=excluded.business_date,item_id=excluded.item_id,
        quantity_grams=excluded.quantity_grams,
        price_cents_per_kg=excluded.price_cents_per_kg,
        deleted_at=null,revision=purchase_additions.revision+1,updated_at=now()
    where purchase_additions.workspace_id=excluded.workspace_id;
    get diagnostics v_count=row_count;
    if v_count<>1 then raise exception 'Lot ID belongs to another workspace' using errcode='42501'; end if;
  elsif p_entity_type='daily_cash_received' then
    insert into public.daily_cash_received
      (id,workspace_id,business_date,amount_cents)
    values(p_entity_id,p_workspace_id,(p_payload->>'business_date')::date,
           (p_payload->>'amount_cents')::bigint)
    on conflict(id) do update
    set business_date=excluded.business_date,amount_cents=excluded.amount_cents,
        deleted_at=null,revision=daily_cash_received.revision+1,updated_at=now()
    where daily_cash_received.workspace_id=excluded.workspace_id;
    get diagnostics v_count=row_count;
    if v_count<>1 then raise exception 'Cash ID belongs to another workspace' using errcode='42501'; end if;
  end if;

  return jsonb_build_object('accepted',true,'replayed',false,'operation_id',p_operation_id);
end;
$fn$;
revoke all on function public.marketpro_apply_change(uuid,uuid,uuid,text,uuid,text,jsonb)
  from public,anon,authenticated;
grant execute on function public.marketpro_apply_change(uuid,uuid,uuid,text,uuid,text,jsonb)
  to authenticated;
