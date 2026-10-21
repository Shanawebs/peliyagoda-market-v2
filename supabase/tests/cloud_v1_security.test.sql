-- Run with 'supabase test db' against a disposable LOCAL Supabase database only.
-- Every fixture is transaction-scoped and rolls back. NEVER run these tests on live data.
begin;
create extension if not exists pgtap with schema extensions;

-- Keep pgtap plan count aligned with the assertions below.
select plan(35);

-- Synthetic JWT users; cannot log in (no password or authentication identity).
insert into auth.users(id,email) values
  ('10000000-0000-4000-8000-000000000001','mp.owner@example.invalid'),
  ('10000000-0000-4000-8000-000000000002','mp.viewer@example.invalid'),
  ('10000000-0000-4000-8000-000000000003','mp.other@example.invalid');

insert into public.workspaces(id,name) values
  ('20000000-0000-4000-8000-000000000001','Market Pro A'),
  ('20000000-0000-4000-8000-000000000002','Market Pro B');

insert into public.workspace_members(workspace_id,user_id,role) values
  ('20000000-0000-4000-8000-000000000001','10000000-0000-4000-8000-000000000001','owner'),
  ('20000000-0000-4000-8000-000000000001','10000000-0000-4000-8000-000000000002','viewer'),
  ('20000000-0000-4000-8000-000000000002','10000000-0000-4000-8000-000000000003','owner');

insert into public.registered_devices(id,workspace_id,user_id,device_label,enabled,can_write)
values ('30000000-0000-4000-8000-000000000001',
        '20000000-0000-4000-8000-000000000001',
        '10000000-0000-4000-8000-000000000001','Primary Android',true,true);

insert into public.categories(id,workspace_id,name) values
  ('40000000-0000-4000-8000-000000000001','20000000-0000-4000-8000-000000000001','Vegetables'),
  ('40000000-0000-4000-8000-000000000002','20000000-0000-4000-8000-000000000002','Vegetables');

insert into public.items(id,workspace_id,category_id,name) values
  ('50000000-0000-4000-8000-000000000001',
   '20000000-0000-4000-8000-000000000001',
   '40000000-0000-4000-8000-000000000001','Carrot'),
  ('50000000-0000-4000-8000-000000000002',
   '20000000-0000-4000-8000-000000000002',
   '40000000-0000-4000-8000-000000000002','Carrot');

insert into public.requisitions(id,workspace_id,business_date,kitchen,item_id,quantity_grams)
values ('60000000-0000-4000-8000-000000000001',
        '20000000-0000-4000-8000-000000000001','2026-10-21','Main Kitchen',
        '50000000-0000-4000-8000-000000000001',8000);

insert into public.purchases(id,workspace_id,business_date,item_id,quantity_grams,price_cents_per_kg)
values ('70000000-0000-4000-8000-000000000001',
        '20000000-0000-4000-8000-000000000001','2026-10-21',
        '50000000-0000-4000-8000-000000000001',10000,30000);

insert into public.purchase_additions
  (id,workspace_id,business_date,item_id,quantity_grams,price_cents_per_kg)
values ('80000000-0000-4000-8000-000000000001',
        '20000000-0000-4000-8000-000000000001','2026-10-21',
        '50000000-0000-4000-8000-000000000001',2000,32000);

insert into public.daily_cash_received(id,workspace_id,business_date,amount_cents)
values ('90000000-0000-4000-8000-000000000001',
        '20000000-0000-4000-8000-000000000001','2026-10-21',500000);

-- Verify EVERY exposed business table has row level security enabled.
select ok((select relrowsecurity from pg_class where oid = 'public.workspaces'::regclass), 'RLS enabled on workspaces');
select ok((select relrowsecurity from pg_class where oid = 'public.workspace_members'::regclass), 'RLS enabled on workspace_members');
select ok((select relrowsecurity from pg_class where oid = 'public.registered_devices'::regclass), 'RLS enabled on registered_devices');
select ok((select relrowsecurity from pg_class where oid = 'public.categories'::regclass), 'RLS enabled on categories');
select ok((select relrowsecurity from pg_class where oid = 'public.items'::regclass), 'RLS enabled on items');
select ok((select relrowsecurity from pg_class where oid = 'public.requisitions'::regclass), 'RLS enabled on requisitions');
select ok((select relrowsecurity from pg_class where oid = 'public.purchases'::regclass), 'RLS enabled on purchases');
select ok((select relrowsecurity from pg_class where oid = 'public.purchase_additions'::regclass), 'RLS enabled on purchase_additions');
select ok((select relrowsecurity from pg_class where oid = 'public.daily_cash_received'::regclass), 'RLS enabled on daily_cash_received');
select ok((select relrowsecurity from pg_class where oid = 'public.sync_operations'::regclass), 'RLS enabled on sync_operations');

-- Grants: anonymous gets NOTHING; direct application writes are locked.
select ok(not has_table_privilege('anon','public.items','SELECT'), 'anon cannot read catalog');
select ok(not has_table_privilege('anon','public.purchases','SELECT'), 'anon cannot read purchases');
select ok(not has_table_privilege('anon','public.workspace_members','SELECT'), 'anon cannot enumerate members');
select ok(has_table_privilege('authenticated','public.items','SELECT'), 'authenticated has gated catalog SELECT');
select ok(not has_table_privilege('authenticated','public.items','INSERT'), 'authenticated cannot bypass sync with direct INSERT');
select ok(not has_table_privilege('authenticated','public.purchases','UPDATE'), 'authenticated cannot directly UPDATE financial records');
select ok(not has_table_privilege('authenticated','public.daily_cash_received','DELETE'), 'authenticated cannot delete cash records');
select ok(not has_table_privilege('authenticated','public.sync_operations','SELECT'), 'idempotency ledger not exposed to app');
select ok(to_regclass('public.registered_devices_one_enabled_writer_idx') is not null, 'V1 one-enabled-writer uniqueness index exists');
select ok((select count(*) from pg_constraint where conrelid = 'public.items'::regclass and contype = 'f') >= 2, 'item foreign keys enforce workspace/category association');

-- Owner A: sees ONLY its workspace's data.
set local role authenticated;
set local request.jwt.claim.sub = '10000000-0000-4000-8000-000000000001';
select is((select count(*) from public.workspaces), 1::bigint, 'owner sees own workspace only');
select is((select count(*) from public.workspace_members), 1::bigint, 'owner sees own membership row only');
select is((select count(*) from public.items), 1::bigint, 'owner sees own catalog only');
select is((select count(*) from public.purchases), 1::bigint, 'owner sees own purchase only');
select is((select count(*) from public.daily_cash_received), 1::bigint, 'owner sees own cash only');
select is((select count(*) from public.registered_devices), 1::bigint, 'owner sees own device');

-- Viewer A: reads business data but CANNOT see another user's registered device.
set local request.jwt.claim.sub = '10000000-0000-4000-8000-000000000002';
select is((select count(*) from public.items), 1::bigint, 'viewer sees workspace A catalog');
select is((select count(*) from public.purchases), 1::bigint, 'viewer sees workspace A purchases');
select is((select count(*) from public.registered_devices), 0::bigint, 'viewer cannot inspect owner device');
select is((select count(*) from public.workspace_members), 1::bigint, 'viewer sees own membership only');

-- Another owner B: same item names are allowed in another workspace; A stays hidden.
set local request.jwt.claim.sub = '10000000-0000-4000-8000-000000000003';
select is((select count(*) from public.items), 1::bigint, 'other workspace sees only its own matching-name item');
select is((select count(*) from public.items where workspace_id = '20000000-0000-4000-8000-000000000001'), 0::bigint, 'workspace B cannot inspect A items');
select is((select count(*) from public.workspaces), 1::bigint, 'workspace B sees only B workspace');
select is((select count(*) from public.workspace_members where workspace_id = '20000000-0000-4000-8000-000000000001'), 0::bigint, 'workspace B cannot inspect A membership');
select is((select count(*) from public.daily_cash_received), 0::bigint, 'workspace B cannot see A cash');

select * from finish();
rollback;
