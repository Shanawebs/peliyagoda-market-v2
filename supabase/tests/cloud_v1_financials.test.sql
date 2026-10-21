-- pgTAP finance tests run against a disposable local DB only; all fixtures rolled back.
begin;
create extension if not exists pgtap with schema extensions;
select plan(18);
insert into auth.users(id,email) values
 ('b0000000-0000-4000-8000-000000000001','owner@example.invalid'),
 ('b0000000-0000-4000-8000-000000000002','viewer@example.invalid'),
 ('b0000000-0000-4000-8000-000000000003','other@example.invalid');
insert into public.workspaces(id,name) values
 ('b1000000-0000-4000-8000-000000000001','A'),
 ('b1000000-0000-4000-8000-000000000002','B');
insert into public.workspace_members(workspace_id,user_id,role) values
 ('b1000000-0000-4000-8000-000000000001','b0000000-0000-4000-8000-000000000001','owner'),
 ('b1000000-0000-4000-8000-000000000001','b0000000-0000-4000-8000-000000000002','viewer'),
 ('b1000000-0000-4000-8000-000000000002','b0000000-0000-4000-8000-000000000003','owner');
insert into public.categories(id,workspace_id,name) values
 ('b2000000-0000-4000-8000-000000000001','b1000000-0000-4000-8000-000000000001','Veg');
insert into public.items(id,workspace_id,category_id,name) values
 ('b3000000-0000-4000-8000-000000000001','b1000000-0000-4000-8000-000000000001',
 'b2000000-0000-4000-8000-000000000001','Carrot');
-- Each lot: 1500 grams times 101 cents per kg = 151.5 cents, HALF_UP => 152 cents.
insert into public.purchases(id,workspace_id,business_date,item_id,quantity_grams,price_cents_per_kg) values
 ('b4000000-0000-4000-8000-000000000001','b1000000-0000-4000-8000-000000000001',
 '2026-10-21','b3000000-0000-4000-8000-000000000001',1500,101),
 ('b4000000-0000-4000-8000-000000000002','b1000000-0000-4000-8000-000000000001',
 '2026-10-23','b3000000-0000-4000-8000-000000000001',1000,200);
insert into public.purchase_additions
 (id,workspace_id,business_date,item_id,quantity_grams,price_cents_per_kg) values
 ('b5000000-0000-4000-8000-000000000001','b1000000-0000-4000-8000-000000000001',
 '2026-10-21','b3000000-0000-4000-8000-000000000001',1500,101);
insert into public.requisitions(id,workspace_id,business_date,kitchen,item_id,quantity_grams) values
 ('b6000000-0000-4000-8000-000000000001','b1000000-0000-4000-8000-000000000001',
 '2026-10-21','Main Kitchen','b3000000-0000-4000-8000-000000000001',8000),
 ('b6000000-0000-4000-8000-000000000002','b1000000-0000-4000-8000-000000000001',
 '2026-10-21','Indian Kitchen','b3000000-0000-4000-8000-000000000001',4000),
 ('b6000000-0000-4000-8000-000000000003','b1000000-0000-4000-8000-000000000001',
 '2026-10-21','Pastry Kitchen','b3000000-0000-4000-8000-000000000001',1000);
insert into public.daily_cash_received(id,workspace_id,business_date,amount_cents) values
 ('b7000000-0000-4000-8000-000000000001','b1000000-0000-4000-8000-000000000001','2026-10-21',1000),
 ('b7000000-0000-4000-8000-000000000002','b1000000-0000-4000-8000-000000000001','2026-10-22',500);

set local role authenticated;
set local request.jwt.claim.sub='b0000000-0000-4000-8000-000000000001';
select is((select count(*) from public.daily_financial_summary),3::bigint,'three business dates');
select is((select required_grams from public.daily_financial_summary where business_date='2026-10-21'),13000::numeric,'three kitchens total 13kg');
select is((select initial_purchase_expenses_cents from public.daily_financial_summary where business_date='2026-10-21'),152::numeric,'initial lot HALF_UP');
select is((select additional_purchase_expenses_cents from public.daily_financial_summary where business_date='2026-10-21'),152::numeric,'additional lot HALF_UP');
select is((select total_expenses_cents from public.daily_financial_summary where business_date='2026-10-21'),304::numeric,'round each purchase lot independently');
select is((select cash_received_cents from public.daily_financial_summary where business_date='2026-10-21'),1000::numeric,'cash received');
select is((select cash_balance_cents from public.daily_financial_summary where business_date='2026-10-21'),696::numeric,'balance is cash less expenses');
select is((select total_expenses_cents from public.daily_financial_summary where business_date='2026-10-22'),0::numeric,'cash-only day expense zero');
select is((select cash_balance_cents from public.daily_financial_summary where business_date='2026-10-22'),500::numeric,'cash-only day balance');
select is((select cash_balance_cents from public.daily_financial_summary where business_date='2026-10-23'),-200::numeric,'negative balance if no cash received');
select is((select count(*) from public.requisitions),3::bigint,'three kitchen requisitions');
select is((select count(*) from public.daily_cash_received),2::bigint,'two cash days');
select ok(not has_table_privilege('authenticated','public.requisitions','INSERT'),'no direct requisition writes');
select ok(not has_table_privilege('authenticated','public.daily_cash_received','UPDATE'),'no direct cash edits');
set local request.jwt.claim.sub='b0000000-0000-4000-8000-000000000002';
select is((select count(*) from public.daily_financial_summary),3::bigint,'viewer reads own workspace finance');
set local request.jwt.claim.sub='b0000000-0000-4000-8000-000000000003';
select is((select count(*) from public.daily_financial_summary),0::bigint,'foreign workspace cannot see finance');
select is((select count(*) from public.requisitions),0::bigint,'foreign workspace cannot see requisitions');
select is((select count(*) from public.daily_cash_received),0::bigint,'foreign workspace cannot see cash');
select * from finish();
rollback;
