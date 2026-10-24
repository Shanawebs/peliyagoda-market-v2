-- MARKET PRO TWO-PHONE: consolidated cash-balance summary
-- REVIEW-ONLY. STAGING DB FIRST, never deploy alone to the current Owner.
-- Depends on existing Cloud V1 financial summary & reviewed Other Expenses table.
-- Keeps prior six columns in the SAME ORDER AND NUMERIC TYPES for Owner
-- dashboard backwards compatibility. Adds extra breakdown columns at end.
BEGIN;
CREATE OR REPLACE VIEW public.daily_financial_summary
WITH (security_invoker=true,security_barrier=true)
AS
WITH lot_costs AS (
 SELECT workspace_id,business_date,'initial'::text AS lot_type,
   round(quantity_grams::numeric*price_cents_per_kg::numeric/1000,0) AS cost_cents
 FROM public.purchases WHERE deleted_at IS NULL
 UNION ALL
 SELECT workspace_id,business_date,'additional'::text AS lot_type,
   round(quantity_grams::numeric*price_cents_per_kg::numeric/1000,0) AS cost_cents
 FROM public.purchase_additions WHERE deleted_at IS NULL
),
daily_lots AS (
 SELECT workspace_id,business_date,
   sum(cost_cents) AS purchase_expenses_cents,
   sum(cost_cents) FILTER(WHERE lot_type='initial') AS initial_purchase_expenses_cents,
   sum(cost_cents) FILTER(WHERE lot_type='additional') AS additional_purchase_expenses_cents
 FROM lot_costs GROUP BY workspace_id,business_date
),
daily_reqs AS (
 SELECT workspace_id,business_date,
   sum(quantity_grams::numeric) AS required_grams
 FROM public.requisitions WHERE deleted_at IS NULL
 GROUP BY workspace_id,business_date
),
daily_cash AS (
 SELECT workspace_id,business_date,sum(amount_cents::numeric) AS cash_received_cents
 FROM public.daily_cash_received WHERE deleted_at IS NULL
 GROUP BY workspace_id,business_date
),
daily_other AS (
 SELECT workspace_id,business_date,
   sum(transport_cents::numeric) AS transport_expenses_cents,
   sum(labor_charge_cents::numeric) AS labor_charge_expenses_cents,
   sum(meals_drinks_cents::numeric) AS meals_drinks_expenses_cents,
   sum(others_cents::numeric) AS others_expenses_cents
 FROM public.daily_other_expenses
 GROUP BY workspace_id,business_date
),
all_dates AS (
 SELECT workspace_id,business_date FROM daily_lots
 UNION SELECT workspace_id,business_date FROM daily_reqs
 UNION SELECT workspace_id,business_date FROM daily_cash
 UNION SELECT workspace_id,business_date FROM daily_other
)
SELECT d.workspace_id,d.business_date,
  coalesce(r.required_grams,0::numeric) AS required_grams,
  coalesce(l.initial_purchase_expenses_cents,0::numeric) AS initial_purchase_expenses_cents,
  coalesce(l.additional_purchase_expenses_cents,0::numeric) AS additional_purchase_expenses_cents,
  coalesce(l.purchase_expenses_cents,0::numeric) +
     coalesce(e.transport_expenses_cents,0::numeric) +
     coalesce(e.labor_charge_expenses_cents,0::numeric) +
     coalesce(e.meals_drinks_expenses_cents,0::numeric) +
     coalesce(e.others_expenses_cents,0::numeric) AS total_expenses_cents,
  coalesce(c.cash_received_cents,0::numeric) AS cash_received_cents,
  coalesce(c.cash_received_cents,0::numeric) -
     coalesce(l.purchase_expenses_cents,0::numeric) -
     coalesce(e.transport_expenses_cents,0::numeric) -
     coalesce(e.labor_charge_expenses_cents,0::numeric) -
     coalesce(e.meals_drinks_expenses_cents,0::numeric) -
     coalesce(e.others_expenses_cents,0::numeric) AS cash_balance_cents,
  coalesce(l.purchase_expenses_cents,0::numeric) AS purchase_expenses_cents,
  coalesce(e.transport_expenses_cents,0::numeric) AS transport_expenses_cents,
  coalesce(e.labor_charge_expenses_cents,0::numeric) AS labor_charge_expenses_cents,
  coalesce(e.meals_drinks_expenses_cents,0::numeric) AS meals_drinks_expenses_cents,
  coalesce(e.others_expenses_cents,0::numeric) AS others_expenses_cents
FROM all_dates d
LEFT JOIN daily_lots l USING(workspace_id,business_date)
LEFT JOIN daily_reqs r USING(workspace_id,business_date)
LEFT JOIN daily_cash c USING(workspace_id,business_date)
LEFT JOIN daily_other e USING(workspace_id,business_date);

-- Never rely on definer-view privilege or broad membership for Staff reads.
ALTER VIEW public.daily_financial_summary
  SET (security_invoker=true,security_barrier=true);
REVOKE ALL ON public.daily_financial_summary FROM PUBLIC,anon;
GRANT SELECT ON public.daily_financial_summary TO authenticated;
COMMIT;
