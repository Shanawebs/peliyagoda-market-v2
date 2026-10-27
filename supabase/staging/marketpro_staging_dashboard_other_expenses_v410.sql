-- STAGING ONLY: Market Pro v4.1.0 dashboard totals.
-- Production is intentionally untouched until two physical phones pass QA.
create or replace view public.daily_financial_summary
with (security_invoker=true, security_barrier=true)
as
with lot_costs as (
    select p.workspace_id,p.business_date,'initial'::text as lot_type,
           round(p.quantity_grams::numeric*p.price_cents_per_kg::numeric/1000::numeric,0) as cost_cents
    from public.purchases p
    where p.deleted_at is null
    union all
    select a.workspace_id,a.business_date,'additional'::text as lot_type,
           round(a.quantity_grams::numeric*a.price_cents_per_kg::numeric/1000::numeric,0) as cost_cents
    from public.purchase_additions a
    where a.deleted_at is null
),
daily_lots as (
    select workspace_id,business_date,
           sum(cost_cents) as purchase_expenses_cents,
           sum(cost_cents) filter (where lot_type='initial') as initial_purchase_expenses_cents,
           sum(cost_cents) filter (where lot_type='additional') as additional_purchase_expenses_cents
    from lot_costs
    group by workspace_id,business_date
),
daily_other as (
    select e.workspace_id,e.business_date,
           sum(e.transport_cents::numeric+e.labor_charge_cents::numeric+
               e.meals_drinks_cents::numeric+e.others_cents::numeric) as other_expenses_cents
    from public.daily_other_expenses e
    group by e.workspace_id,e.business_date
),
daily_req as (
    select r.workspace_id,r.business_date,sum(r.quantity_grams::numeric) as required_grams
    from public.requisitions r
    where r.deleted_at is null
    group by r.workspace_id,r.business_date
),
daily_cash as (
    select c.workspace_id,c.business_date,sum(c.amount_cents::numeric) as cash_received_cents
    from public.daily_cash_received c
    where c.deleted_at is null
    group by c.workspace_id,c.business_date
),
all_dates as (
    select workspace_id,business_date from daily_lots
    union select workspace_id,business_date from daily_other
    union select workspace_id,business_date from daily_req
    union select workspace_id,business_date from daily_cash
)
select d.workspace_id,
       d.business_date,
       coalesce(r.required_grams,0::numeric) as required_grams,
       coalesce(l.initial_purchase_expenses_cents,0::numeric) as initial_purchase_expenses_cents,
       coalesce(l.additional_purchase_expenses_cents,0::numeric) as additional_purchase_expenses_cents,
       coalesce(l.purchase_expenses_cents,0::numeric)+coalesce(o.other_expenses_cents,0::numeric)
           as total_expenses_cents,
       coalesce(c.cash_received_cents,0::numeric) as cash_received_cents,
       coalesce(c.cash_received_cents,0::numeric)
         - coalesce(l.purchase_expenses_cents,0::numeric)
         - coalesce(o.other_expenses_cents,0::numeric) as cash_balance_cents,
       coalesce(o.other_expenses_cents,0::numeric) as other_expenses_cents
from all_dates d
left join daily_lots l using(workspace_id,business_date)
left join daily_other o using(workspace_id,business_date)
left join daily_req r using(workspace_id,business_date)
left join daily_cash c using(workspace_id,business_date);

revoke all on public.daily_financial_summary from anon;
grant select on public.daily_financial_summary to authenticated;
