-- Peliyagoda Market Pro — Cloud V1 phase 1 (STAGING ONLY).
-- Offline-first Android V2.28 -> Purchase-only cloud mirror schema foundation.
-- A single Android writer will be enabled via a separately reviewed RPC in Phase 2.
-- IMPORTANT: Do not run on production or import existing purchases without approved backups.
-- Sync scope: initial purchases + additional purchase lots + item/category references ONLY.
-- No requisitions, cash entries, invoice photos, Storage buckets or photo uploads.
-- Migration deliberately DOES NOT create anonymous writes, direct client writes, or real data.
--
-- Prerequisite: a fresh Supabase development project with Supabase Auth installed.

create table public.workspaces (
  id uuid primary key default gen_random_uuid(),
  name text not null check (char_length(btrim(name)) between 1 and 120),
  timezone text not null default 'Asia/Colombo',
  created_at timestamptz not null default now()
);

create table public.workspace_members (
  workspace_id uuid not null references public.workspaces(id) on delete restrict,
  user_id uuid not null references auth.users(id) on delete cascade,
  role text not null check (role in ('owner','editor','viewer')),
  joined_at timestamptz not null default now(),
  primary key (workspace_id, user_id)
);
create index workspace_members_user_id_idx on public.workspace_members(user_id, workspace_id);

create table public.registered_devices (
  id uuid primary key,
  workspace_id uuid not null references public.workspaces(id) on delete restrict,
  user_id uuid not null references auth.users(id) on delete restrict,
  device_label text not null check (char_length(btrim(device_label)) between 1 and 100),
  enabled boolean not null default false,
  can_write boolean not null default false,
  enrolled_at timestamptz not null default now(),
  last_seen_at timestamptz,
  unique (workspace_id, id),
  foreign key (workspace_id, user_id)
    references public.workspace_members(workspace_id, user_id) on delete restrict
);
create index registered_devices_user_id_idx on public.registered_devices(user_id, workspace_id);
create unique index registered_devices_one_enabled_writer_idx
  on public.registered_devices(workspace_id) where enabled and can_write;
-- Future multiwriter rollout must explicitly replace the partial index.

create table public.categories (
  id uuid primary key,
  workspace_id uuid not null references public.workspaces(id) on delete restrict,
  name text not null check (char_length(btrim(name)) between 1 and 40),
  name_key text generated always as (lower(btrim(name))) stored,
  revision bigint not null default 1 check (revision > 0),
  deleted_at timestamptz,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  unique (workspace_id,id),
  unique (workspace_id,name_key)
);
create index categories_workspace_idx on public.categories(workspace_id);

create table public.items (
  id uuid primary key,
  workspace_id uuid not null references public.workspaces(id) on delete restrict,
  category_id uuid not null,
  name text not null check (char_length(btrim(name)) between 1 and 80),
  name_key text generated always as (lower(btrim(name))) stored,
  is_active boolean not null default true,
  revision bigint not null default 1 check (revision > 0),
  deleted_at timestamptz,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  unique (workspace_id,id),
  unique (workspace_id,name_key),
  foreign key (workspace_id,category_id)
    references public.categories(workspace_id,id) on delete restrict
);
create index items_category_idx on public.items(workspace_id, category_id);
create index items_workspace_updated_idx on public.items(workspace_id,updated_at);

-- IMPORTANT: Kitchen values are persisted identifiers in the existing Room DB.
-- "Pastry Kitchen" may appear as "Bar/Pastry" in the Android presentation only.
create table public.purchases (
  id uuid primary key,
  workspace_id uuid not null references public.workspaces(id) on delete restrict,
  business_date date not null,
  item_id uuid not null,
  quantity_grams bigint not null default 0 check (quantity_grams between 0 and 1000000000),
  price_cents_per_kg bigint not null default 0 check (price_cents_per_kg between 0 and 1000000000),
  revision bigint not null default 1 check (revision > 0),
  deleted_at timestamptz,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  unique (workspace_id,business_date,item_id),
  foreign key (workspace_id,item_id)
    references public.items(workspace_id,id) on delete restrict
);
create index purchases_by_date_idx on public.purchases(workspace_id,business_date);

-- Every extra purchase lot has a durable UUID; never infer identity from a date/item alone.
create table public.purchase_additions (
  id uuid primary key,
  workspace_id uuid not null references public.workspaces(id) on delete restrict,
  business_date date not null,
  item_id uuid not null,
  quantity_grams bigint not null check (quantity_grams between 0 and 1000000000),
  price_cents_per_kg bigint not null check (price_cents_per_kg between 0 and 1000000000),
  revision bigint not null default 1 check (revision > 0),
  deleted_at timestamptz,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  foreign key (workspace_id,item_id)
    references public.items(workspace_id,id) on delete restrict
);
create index purchase_additions_by_date_idx
  on public.purchase_additions(workspace_id,business_date,item_id);

-- Server-side idempotency ledger. NEVER grant direct client reads or writes.
create table public.sync_operations (
  operation_id uuid primary key,
  workspace_id uuid not null references public.workspaces(id) on delete restrict,
  device_id uuid not null,
  actor_user_id uuid not null references auth.users(id) on delete restrict,
  entity_type text not null check (entity_type in
     ('category','item','purchase','purchase_addition')),
  entity_id uuid not null,
  operation_type text not null check (operation_type in ('upsert','delete')),
  payload_hash text not null check (payload_hash ~ '^[a-f0-9]{64}$'),
  applied_at timestamptz not null default now(),
  foreign key (workspace_id,device_id)
    references public.registered_devices(workspace_id,id) on delete restrict,
  foreign key (workspace_id,actor_user_id)
    references public.workspace_members(workspace_id,user_id) on delete restrict
);
create index sync_operations_workspace_device_idx
  on public.sync_operations(workspace_id,device_id,applied_at);

-- Helper lives outside the exposed public schema and cannot alter data.
-- Creator is the database migration owner (postgres); execute limited to authenticated.
create schema if not exists marketpro_private;
revoke all on schema marketpro_private from public, anon, authenticated;
grant usage on schema marketpro_private to authenticated;

create function marketpro_private.is_workspace_member(p_workspace_id uuid)
returns boolean language sql stable security definer
set search_path = ''
as $body$
  select (select auth.uid()) is not null
    and exists (
      select 1 from public.workspace_members m
      where m.workspace_id = p_workspace_id
        and m.user_id = (select auth.uid())
    );
$body$;
revoke all on function marketpro_private.is_workspace_member(uuid) from public, anon, authenticated;
grant execute on function marketpro_private.is_workspace_member(uuid) to authenticated;

-- Harden API access: explicit RLS + revoke any default/public grants FIRST.
alter table public.workspaces enable row level security;
revoke all privileges on table public.workspaces from public, anon, authenticated;
alter table public.workspace_members enable row level security;
revoke all privileges on table public.workspace_members from public, anon, authenticated;
alter table public.registered_devices enable row level security;
revoke all privileges on table public.registered_devices from public, anon, authenticated;
alter table public.categories enable row level security;
revoke all privileges on table public.categories from public, anon, authenticated;
alter table public.items enable row level security;
revoke all privileges on table public.items from public, anon, authenticated;
alter table public.purchases enable row level security;
revoke all privileges on table public.purchases from public, anon, authenticated;
alter table public.purchase_additions enable row level security;
revoke all privileges on table public.purchase_additions from public, anon, authenticated;
alter table public.sync_operations enable row level security;
revoke all privileges on table public.sync_operations from public, anon, authenticated;
grant usage on schema public to authenticated;
grant select on table public.workspaces to authenticated;
grant select on table public.workspace_members to authenticated;
grant select on table public.registered_devices to authenticated;
grant select on table public.categories to authenticated;
grant select on table public.items to authenticated;
grant select on table public.purchases to authenticated;
grant select on table public.purchase_additions to authenticated;

-- Membership list itself is limited to the caller's own membership row.
create policy members_own_membership
  on public.workspace_members for select to authenticated
  using (user_id = (select auth.uid()));

create policy member_workspace_read
  on public.workspaces for select to authenticated
  using ((select marketpro_private.is_workspace_member(id)));

create policy own_device_read
  on public.registered_devices for select to authenticated
  using (
    user_id = (select auth.uid())
    and marketpro_private.is_workspace_member(workspace_id)
  );

create policy member_categories_read
  on public.categories for select to authenticated
  using (marketpro_private.is_workspace_member(workspace_id));

create policy member_items_read
  on public.items for select to authenticated
  using (marketpro_private.is_workspace_member(workspace_id));

create policy member_purchases_read
  on public.purchases for select to authenticated
  using (marketpro_private.is_workspace_member(workspace_id));

create policy member_purchase_additions_read
  on public.purchase_additions for select to authenticated
  using (marketpro_private.is_workspace_member(workspace_id));

-- Intentionally NO INSERT/UPDATE/DELETE grants or policies for authenticated clients.
-- Phase 2 must introduce an audited, transactional, device-authorized sync RPC.
-- Bootstrap workspace + members + device only with admin credentials on the DEV project.
