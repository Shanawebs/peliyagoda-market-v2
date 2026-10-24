-- MARKET PRO TWO-PHONE RELEASE CANDIDATE: OTHER EXPENSES
-- REVIEW ONLY: DO NOT APPLY TO LIVE SUPABASE.
-- Additive schema + new RPC. Does NOT alter marketpro_apply_change owner V1.
-- Requires existing v352-v372 reviewed SQL and its fully audited RLS cutover.
BEGIN;

CREATE TABLE IF NOT EXISTS public.daily_other_expenses(
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
CREATE INDEX IF NOT EXISTS other_expenses_workspace_day_idx
 ON public.daily_other_expenses(workspace_id,business_date);
ALTER TABLE public.daily_other_expenses ENABLE ROW LEVEL SECURITY;
REVOKE ALL ON public.daily_other_expenses FROM PUBLIC,anon,authenticated;
GRANT SELECT ON public.daily_other_expenses TO authenticated;

-- Same privacy model as other v359 datasets: no Staff direct REST SELECT;
-- Staff must go through a device-verified paginated pull after cutover.
DROP POLICY IF EXISTS other_expenses_owner_read ON public.daily_other_expenses;
CREATE POLICY other_expenses_owner_read ON public.daily_other_expenses
 FOR SELECT TO authenticated USING(EXISTS(
   SELECT 1 FROM public.workspace_members m
   WHERE m.workspace_id=daily_other_expenses.workspace_id
     AND m.user_id=(SELECT auth.uid())
     AND m.role IN('owner','editor','viewer')
 ));

CREATE SCHEMA IF NOT EXISTS marketpro_private;
CREATE TABLE IF NOT EXISTS marketpro_private.other_expenses_operation_audit(
 operation_id uuid PRIMARY KEY,
 workspace_id uuid NOT NULL,
 actor_user_id uuid NOT NULL,
 device_id uuid NOT NULL,
 entity_id uuid NOT NULL,
 action text NOT NULL CHECK(action IN('create','correct')),
 request_sha256 text NOT NULL CHECK(request_sha256 ~ '^[0-9a-f]{64}$'),
 revision bigint NOT NULL CHECK(revision>0),
 changed_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
REVOKE ALL ON marketpro_private.other_expenses_operation_audit
 FROM PUBLIC,anon,authenticated;

CREATE OR REPLACE FUNCTION public.marketpro_apply_team_other_expense(
 p_workspace_id uuid,p_device_id uuid,p_operation_id uuid,p_entity_id uuid,
 p_action text,p_expected_revision bigint,p_payload jsonb
) RETURNS jsonb
LANGUAGE plpgsql VOLATILE SECURITY DEFINER SET search_path=''
AS $expenses$
DECLARE
 v_actor uuid:=auth.uid();
 v_key text;
 v_date date;
 v_existing public.daily_other_expenses%ROWTYPE;
 v_old_operation marketpro_private.other_expenses_operation_audit%ROWTYPE;
 v_hash text;
 v_role text;
 v_revision bigint;
 v_transport bigint;
 v_labor bigint;
 v_meals bigint;
 v_others bigint;
 v_remarks text;
BEGIN
 IF v_actor IS NULL OR coalesce((auth.jwt()->>'is_anonymous')::boolean,false)
 OR p_workspace_id IS NULL OR p_device_id IS NULL OR p_operation_id IS NULL
 OR p_entity_id IS NULL OR p_action NOT IN('create','correct') OR p_action IS NULL
 OR p_payload IS NULL OR jsonb_typeof(p_payload)<>'object'
 OR octet_length(p_payload::text)>2048
 OR (p_action='create' AND p_expected_revision IS NOT NULL)
 OR (p_action='correct' AND (p_expected_revision IS NULL OR p_expected_revision<1))
 THEN
  RAISE EXCEPTION 'Invalid other-expenses request' USING ERRCODE='22023';
 END IF;
 IF (SELECT COUNT(*) FROM jsonb_object_keys(p_payload)) <> 6 THEN
  RAISE EXCEPTION 'All six fields required' USING ERRCODE='22023';
 END IF;
 FOR v_key IN SELECT jsonb_object_keys(p_payload) LOOP
   IF v_key NOT IN ('business_date','transport_cents','labor_charge_cents',
                   'meals_drinks_cents','others_cents','remarks') THEN
     RAISE EXCEPTION 'Unrecognized expenses field' USING ERRCODE='22023';
   END IF;
 END LOOP;
 IF NOT (p_payload ? 'business_date' AND p_payload ? 'transport_cents'
  AND p_payload ? 'labor_charge_cents' AND p_payload ? 'meals_drinks_cents'
  AND p_payload ? 'others_cents' AND p_payload ? 'remarks') THEN
  RAISE EXCEPTION 'Incomplete expenses payload' USING ERRCODE='22023';
 END IF;
 v_date:=(p_payload->>'business_date')::date;
 v_transport:=(p_payload->>'transport_cents')::bigint;
 v_labor:=(p_payload->>'labor_charge_cents')::bigint;
 v_meals:=(p_payload->>'meals_drinks_cents')::bigint;
 v_others:=(p_payload->>'others_cents')::bigint;
 v_remarks:=p_payload->>'remarks';
 IF v_date IS NULL OR v_transport IS NULL OR v_labor IS NULL
 OR v_meals IS NULL OR v_others IS NULL OR v_remarks IS NULL OR
 v_transport NOT BETWEEN 0 AND 9999999999 OR
 v_labor NOT BETWEEN 0 AND 9999999999 OR
 v_meals NOT BETWEEN 0 AND 9999999999 OR
 v_others NOT BETWEEN 0 AND 9999999999 OR
 char_length(v_remarks)>500 THEN
  RAISE EXCEPTION 'Invalid expense value' USING ERRCODE='22023';
 END IF;

 -- Serialize ALL workspace Staff changes with Owner revoke/replacement.
 PERFORM pg_catalog.pg_advisory_xact_lock(
   pg_catalog.hashtextextended(p_workspace_id::text,0));
 -- v372 verifies signed Supabase JWT session for Staff; original Owner
 -- writer remains valid while separate Staff lease/gates are required.
 v_role:=marketpro_private.require_dual_channel_writer(p_workspace_id,p_device_id);
 IF v_role NOT IN('owner','purchasing_staff') THEN
  RAISE EXCEPTION 'Unauthorized expense writer' USING ERRCODE='42501';
 END IF;
 v_hash:=pg_catalog.encode(extensions.digest(pg_catalog.convert_to(
   p_entity_id::text||':'||p_action||':'||
   coalesce(p_expected_revision::text,'null')||':'||p_payload::text,'UTF8'
 ),'sha256'),'hex');
 SELECT * INTO v_old_operation FROM marketpro_private.other_expenses_operation_audit
 WHERE operation_id=p_operation_id;
 IF FOUND THEN
  IF v_old_operation.workspace_id IS DISTINCT FROM p_workspace_id OR
     v_old_operation.actor_user_id IS DISTINCT FROM v_actor OR
     v_old_operation.device_id IS DISTINCT FROM p_device_id OR
     v_old_operation.entity_id IS DISTINCT FROM p_entity_id OR
     v_old_operation.action IS DISTINCT FROM p_action OR
     v_old_operation.request_sha256 IS DISTINCT FROM v_hash THEN
   RAISE EXCEPTION 'Idempotency ID reused with changed content' USING ERRCODE='23505';
  END IF;
  RETURN pg_catalog.jsonb_build_object('accepted',true,'replayed',true,
   'operation_id',p_operation_id,'revision',v_old_operation.revision);
 END IF;

 SELECT * INTO v_existing FROM public.daily_other_expenses
 WHERE workspace_id=p_workspace_id AND id=p_entity_id FOR UPDATE;
 -- Date check is repeated by the authenticated server with Colombo clock.
 -- Staff can correct TODAY repeatedly; old days are Owner-only.
 PERFORM marketpro_private.authorize_team_financial_write(
   p_workspace_id,p_device_id,v_date,p_action,
   CASE WHEN FOUND THEN v_existing.business_date ELSE NULL END);
 IF p_action='create' THEN
  IF v_existing.id IS NOT NULL THEN
   RAISE EXCEPTION 'Expense UUID already exists' USING ERRCODE='23505';
  END IF;
  INSERT INTO public.daily_other_expenses(
   id,workspace_id,business_date,transport_cents,labor_charge_cents,
   meals_drinks_cents,others_cents,remarks
  ) VALUES(
   p_entity_id,p_workspace_id,v_date,v_transport,v_labor,v_meals,v_others,v_remarks
  ) RETURNING revision INTO v_revision;
 ELSE
  IF v_existing.id IS NULL OR v_existing.business_date IS DISTINCT FROM v_date THEN
   RAISE EXCEPTION 'Expense date must not change' USING ERRCODE='42501';
  END IF;
  IF v_existing.revision<>p_expected_revision THEN
   RAISE EXCEPTION 'Stale expense revision' USING ERRCODE='40001';
  END IF;
  UPDATE public.daily_other_expenses SET
   transport_cents=v_transport,labor_charge_cents=v_labor,
   meals_drinks_cents=v_meals,others_cents=v_others,remarks=v_remarks,
   revision=revision+1,updated_at=clock_timestamp()
  WHERE workspace_id=p_workspace_id AND id=p_entity_id
    AND revision=p_expected_revision
  RETURNING revision INTO v_revision;
  IF v_revision IS NULL THEN
   RAISE EXCEPTION 'Expense revision changed' USING ERRCODE='40001';
  END IF;
 END IF;
 INSERT INTO marketpro_private.other_expenses_operation_audit(
   operation_id,workspace_id,actor_user_id,device_id,entity_id,
   action,request_sha256,revision
 ) VALUES(
   p_operation_id,p_workspace_id,v_actor,p_device_id,p_entity_id,
   p_action,v_hash,v_revision
 );
 RETURN pg_catalog.jsonb_build_object(
   'accepted',true,'replayed',false,
   'operation_id',p_operation_id,'revision',v_revision);
END;
$expenses$;
REVOKE ALL ON FUNCTION public.marketpro_apply_team_other_expense(
 uuid,uuid,uuid,uuid,text,bigint,jsonb) FROM PUBLIC,anon;
GRANT EXECUTE ON FUNCTION public.marketpro_apply_team_other_expense(
 uuid,uuid,uuid,uuid,text,bigint,jsonb) TO authenticated;
COMMIT;
