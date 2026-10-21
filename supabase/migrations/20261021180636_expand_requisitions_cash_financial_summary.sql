-- Cloud V1 additive expansion. No invoice photos, files, buckets or Android changes.
-- Requires migration 20261021174526_cloud_v1_purchase_only_foundation.
create table public.requisitions (
 id uuid primary key,
 workspace_id uuid not null references public.workspaces(id) on delete restrict,
 business_date date not null,
 kitchen text not null check(kitchen in ('Main Kitchen','Indian Kitchen','Pastry Kitchen')),
 item_id uuid not null,
 quantity_grams bigint not null default 0 check(quantity_grams between 0 and 1000000000),
 revision bigint not null default 1 check(revision>0),
 deleted_at timestamptz,
 created_at timestamptz not null default now(),
 updated_at timestamptz not null default now(),
 unique(workspace_id,id),
 unique(workspace_id,business_date,kitchen,item_id),
 foreign key(workspace_id,item_id) references public.items(workspace_id,id) on delete restrict
);
create index requisitions_workspace_date_idx on public.requisitions(workspace_id,business_date);
create table public.daily_cash_received (
 id uuid primary key,
 workspace_id uuid not null references public.workspaces(id) on delete restrict,
 business_date date not null,
 amount_cents bigint not null default 0 check(amount_cents between 0 and 9999999999),
 revision bigint not null default 1 check(revision>0),
 deleted_at timestamptz,
 created_at timestamptz not null default now(),
 updated_at timestamptz not null default now(),
 unique(workspace_id,id),
 unique(workspace_id,business_date)
);
create index daily_cash_workspace_date_idx on public.daily_cash_received(workspace_id,business_date);
alter table public.requisitions enable row level security;
revoke all privileges on public.requisitions from public,anon,authenticated;
grant select on public.requisitions to authenticated;
create policy workspace_requisitions_read on public.requisitions
 for select to authenticated using(marketpro_private.is_workspace_member(workspace_id));
alter table public.daily_cash_received enable row level security;
revoke all privileges on public.daily_cash_received from public,anon,authenticated;
grant select on public.daily_cash_received to authenticated;
create policy workspace_cash_read on public.daily_cash_received
 for select to authenticated using(marketpro_private.is_workspace_member(workspace_id));
alter table public.sync_operations drop constraint sync_operations_entity_type_check;
alter table public.sync_operations add constraint sync_operations_entity_type_check
 check(entity_type in
  ('category','item','purchase','purchase_addition','requisition','daily_cash_received'));

-- Sum per-lot rounded cents, not rounded total quantity times average price.
-- Match Kotlin BigDecimal HALF_UP for all nonnegative purchase quantities/prices.
-- Enforce underlying RLS via security_invoker, not owner privileges.
create view public.daily_financial_summary
with(security_invoker=true,security_barrier=true) as
with lot_costs as (
 select workspace_id,business_date,'initial'::text as lot_type,
 round(quantity_grams::numeric*price_cents_per_kg::numeric/1000,0) as cost_cents
 from public.purchases where deleted_at is null
 union all
 select workspace_id,business_date,'additional'::text as lot_type,
 round(quantity_grams::numeric*price_cents_per_kg::numeric/1000,0) as cost_cents
 from public.purchase_additions where deleted_at is null
),
daily_lots as (
 select workspace_id,business_date,sum(cost_cents) as total_expenses_cents,
 sum(cost_cents) filter(where lot_type='initial') as initial_purchase_expenses_cents,
 sum(cost_cents) filter(where lot_type='additional') as additional_purchase_expenses_cents
 from lot_costs group by workspace_id,business_date
),
daily_req as (
 select workspace_id,business_date,sum(quantity_grams::numeric) as required_grams
 from public.requisitions where deleted_at is null group by workspace_id,business_date
),
daily_cash as (
 select workspace_id,business_date,sum(amount_cents::numeric) as cash_received_cents
 from public.daily_cash_received where deleted_at is null group by workspace_id,business_date
),
all_dates as (
 select workspace_id,business_date from daily_lots
 union select workspace_id,business_date from daily_req
 union select workspace_id,business_date from daily_cash
)
select d.workspace_id,d.business_date,
 coalesce(r.required_grams,0::numeric) as required_grams,
 coalesce(l.initial_purchase_expenses_cents,0::numeric) as initial_purchase_expenses_cents,
 coalesce(l.additional_purchase_expenses_cents,0::numeric) as additional_purchase_expenses_cents,
 coalesce(l.total_expenses_cents,0::numeric) as total_expenses_cents,
 coalesce(c.cash_received_cents,0::numeric) as cash_received_cents,
 coalesce(c.cash_received_cents,0::numeric)-coalesce(l.total_expenses_cents,0::numeric) as cash_balance_cents
from all_dates d
left join daily_lots l using(workspace_id,business_date)
left join daily_req r using(workspace_id,business_date)
left join daily_cash c using(workspace_id,business_date);
revoke all privileges on public.daily_financial_summary from public,anon,authenticated;
grant select on public.daily_financial_summary to authenticated;
