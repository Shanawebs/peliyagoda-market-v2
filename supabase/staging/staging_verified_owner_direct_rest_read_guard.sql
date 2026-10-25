-- Market Pro Two-Phone QA — STAGING ONLY (never Production).
-- Prerequisite hardening: direct REST financial/catalog SELECT must NOT
-- grant purchasing_staff workspace data access. Signed, device-bound RPC
-- reads are a separate future deployment, NOT enabled by this migration.
-- Target project ref: qqiheexpbuzohllugxrk
-- Requires verified MFA Owner, a single staging QA workspace, 0 devices.
-- Expected prior policy: marketpro_private.is_workspace_member(workspace_id)
-- Matches the GitHub v359 review concept without deploying invitation RPCs.
BEGIN;
DO $preflight$
DECLARE
  v_workspace uuid := 'af77cb66-76b2-492a-a460-06132f468713'::uuid;
  v_expected int;
BEGIN
  IF (SELECT count(*) FROM public.workspaces) <> 1
     OR NOT EXISTS (SELECT 1 FROM public.workspaces WHERE id=v_workspace AND name='Market Pro Two-Phone QA' AND timezone='Asia/Colombo')
     OR (SELECT count(*) FROM public.workspace_members) <> 1
     OR NOT EXISTS (
       SELECT 1 FROM public.workspace_members m
       JOIN auth.users u ON u.id=m.user_id
       WHERE m.workspace_id=v_workspace
         AND m.role='owner' AND u.email_confirmed_at IS NOT NULL
         AND EXISTS (
            SELECT 1 FROM auth.mfa_factors f
            WHERE f.user_id=u.id AND f.factor_type='totp' AND f.status='verified'
         )
     )
     OR (SELECT count(*) FROM public.registered_devices) <> 0
  THEN
    RAISE EXCEPTION 'Fail closed: expected isolated Staging Owner+MFA workspace not present';
  END IF;

  SELECT count(*) INTO v_expected FROM pg_policies AS p
  WHERE p.schemaname='public'
    AND (p.tablename,p.policyname) IN (
      ('categories','member_categories_read'),
      ('items','member_items_read'),
      ('purchases','member_purchases_read'),
      ('purchase_additions','member_purchase_additions_read'),
      ('requisitions','workspace_requisitions_read'),
      ('daily_cash_received','workspace_cash_read')
    )
    AND p.cmd='SELECT'
    AND p.roles=ARRAY['authenticated']::name[]
    AND p.qual='marketpro_private.is_workspace_member(workspace_id)';

  IF v_expected <> 6 OR (
    SELECT count(*) FROM pg_policies p
    WHERE p.schemaname='public'
      AND p.tablename IN (
        'categories','items','purchases','purchase_additions',
        'requisitions','daily_cash_received'
      )
  ) <> 6 THEN
    RAISE EXCEPTION 'Fail closed: actual RLS policy baseline differs from reviewed six-table configuration';
  END IF;
END;
$preflight$;

-- Keep RLS ON and SELECT privileges unchanged; restrict only row visibility.
DROP POLICY member_categories_read ON public.categories;
DROP POLICY member_items_read ON public.items;
DROP POLICY member_purchases_read ON public.purchases;
DROP POLICY member_purchase_additions_read ON public.purchase_additions;
DROP POLICY workspace_requisitions_read ON public.requisitions;
DROP POLICY workspace_cash_read ON public.daily_cash_received;

CREATE POLICY member_categories_read ON public.categories
  FOR SELECT TO authenticated
  USING (EXISTS (
    SELECT 1 FROM public.workspace_members AS wm
    WHERE wm.workspace_id = categories.workspace_id
      AND wm.user_id = (SELECT auth.uid())
      AND wm.role IN ('owner', 'editor', 'viewer')
  ));

CREATE POLICY member_items_read ON public.items
  FOR SELECT TO authenticated
  USING (EXISTS (
    SELECT 1 FROM public.workspace_members AS wm
    WHERE wm.workspace_id = items.workspace_id
      AND wm.user_id = (SELECT auth.uid())
      AND wm.role IN ('owner', 'editor', 'viewer')
  ));

CREATE POLICY member_purchases_read ON public.purchases
  FOR SELECT TO authenticated
  USING (EXISTS (
    SELECT 1 FROM public.workspace_members AS wm
    WHERE wm.workspace_id = purchases.workspace_id
      AND wm.user_id = (SELECT auth.uid())
      AND wm.role IN ('owner', 'editor', 'viewer')
  ));

CREATE POLICY member_purchase_additions_read ON public.purchase_additions
  FOR SELECT TO authenticated
  USING (EXISTS (
    SELECT 1 FROM public.workspace_members AS wm
    WHERE wm.workspace_id = purchase_additions.workspace_id
      AND wm.user_id = (SELECT auth.uid())
      AND wm.role IN ('owner', 'editor', 'viewer')
  ));

CREATE POLICY workspace_requisitions_read ON public.requisitions
  FOR SELECT TO authenticated
  USING (EXISTS (
    SELECT 1 FROM public.workspace_members AS wm
    WHERE wm.workspace_id = requisitions.workspace_id
      AND wm.user_id = (SELECT auth.uid())
      AND wm.role IN ('owner', 'editor', 'viewer')
  ));

CREATE POLICY workspace_cash_read ON public.daily_cash_received
  FOR SELECT TO authenticated
  USING (EXISTS (
    SELECT 1 FROM public.workspace_members AS wm
    WHERE wm.workspace_id = daily_cash_received.workspace_id
      AND wm.user_id = (SELECT auth.uid())
      AND wm.role IN ('owner', 'editor', 'viewer')
  ));

DO $postcheck$
DECLARE
  v_valid int;
BEGIN
  SELECT count(*) INTO v_valid
    FROM pg_policies p
   WHERE p.schemaname='public'
     AND p.tablename IN (
       'categories','items','purchases','purchase_additions',
       'requisitions','daily_cash_received'
     )
     AND p.cmd='SELECT'
     AND p.roles=ARRAY['authenticated']::name[]
     AND p.qual LIKE '%owner%'
     AND p.qual LIKE '%editor%'
     AND p.qual LIKE '%viewer%'
     AND p.qual NOT LIKE '%purchasing_staff%'
     AND p.qual NOT LIKE '%is_workspace_member%';
  IF v_valid <> 6 THEN
    RAISE EXCEPTION 'Fail closed: one or more direct REST policies were not restricted';
  END IF;
END;
$postcheck$;
COMMIT;
