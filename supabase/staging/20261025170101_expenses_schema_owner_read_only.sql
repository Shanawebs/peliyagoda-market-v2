-- Applied to isolated STAGING project qqiheexpbuzohllugxrk ONLY on 2026-10-25.
-- Audit trail: Supabase migration marketpro_staging_expenses_schema_owner_read_only.
-- THIS IS STORAGE + RESTRICTED READ ONLY; all five Staff signed finance
-- server transactions remain separately review-only/not deployed.
CREATE TABLE public.daily_other_expenses(
 id uuid PRIMARY KEY,
 workspace_id uuid NOT NULL REFERENCES public.workspaces(id) ON DELETE RESTRICT,
 business_date date NOT NULL,
 transport_cents bigint NOT NULL DEFAULT 0
  CHECK(transport_cents BETWEEN 0 AND 9999999999),
 labor_charge_cents bigint NOT NULL DEFAULT 0
  CHECK(labor_charge_cents BETWEEN 0 AND 9999999999),
 meals_drinks_cents bigint NOT NULL DEFAULT 0
  CHECK(meals_drinks_cents BETWEEN 0 AND 9999999999),
 others_cents bigint NOT NULL DEFAULT 0
  CHECK(others_cents BETWEEN 0 AND 9999999999),
 remarks text NOT NULL DEFAULT '' CHECK(char_length(remarks)<=500),
 revision bigint NOT NULL DEFAULT 1 CHECK(revision>0),
 created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 UNIQUE(workspace_id,business_date)
);
CREATE INDEX other_expenses_workspace_day_idx
 ON public.daily_other_expenses(workspace_id,business_date);
ALTER TABLE public.daily_other_expenses ENABLE ROW LEVEL SECURITY;
REVOKE ALL ON public.daily_other_expenses FROM PUBLIC,anon,authenticated;
GRANT SELECT ON public.daily_other_expenses TO authenticated;
CREATE POLICY staging_only_owner_expense_read ON public.daily_other_expenses
  FOR SELECT TO authenticated USING (
    EXISTS (
      SELECT 1 FROM public.workspace_members m
      WHERE m.workspace_id=daily_other_expenses.workspace_id
        AND m.user_id=(SELECT auth.uid())
        AND m.role='owner'
    )
  );
