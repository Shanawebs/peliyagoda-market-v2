-- All test fixtures are synthetic and fully rolled back; NO photo data.
begin;
create extension if not exists pgtap with schema extensions;
select plan(14);
insert into auth.users(id,email) values
('c0000000-0000-4000-8000-000000000001','writer@example.invalid'),
('c0000000-0000-4000-8000-000000000002','other@example.invalid');
insert into public.workspaces(id,name) values
('c1000000-0000-4000-8000-000000000001','A');
insert into public.workspace_members(workspace_id,user_id,role) values
('c1000000-0000-4000-8000-000000000001','c0000000-0000-4000-8000-000000000001','owner');
insert into public.registered_devices(id,workspace_id,user_id,device_label,enabled,can_write) values
('c2000000-0000-4000-8000-000000000001','c1000000-0000-4000-8000-000000000001',
'c0000000-0000-4000-8000-000000000001','Primary',true,true);
select ok(not has_function_privilege('anon',
'public.marketpro_apply_change(uuid,uuid,uuid,text,uuid,text,jsonb)','EXECUTE'),'anon denied');
set local role authenticated;
set local request.jwt.claim.sub='c0000000-0000-4000-8000-000000000001';
select is((public.marketpro_apply_change(
'c1000000-0000-4000-8000-000000000001','c2000000-0000-4000-8000-000000000001',
'c3000000-0000-4000-8000-000000000001','category','c4000000-0000-4000-8000-000000000001',
'upsert','{"name":"Veg"}')->>'accepted')::boolean,true,'category added');
select is((public.marketpro_apply_change(
'c1000000-0000-4000-8000-000000000001','c2000000-0000-4000-8000-000000000001',
'c3000000-0000-4000-8000-000000000002','item','c5000000-0000-4000-8000-000000000001',
'upsert','{"name":"Carrot","category_id":"c4000000-0000-4000-8000-000000000001","is_active":true}')->>'accepted')::boolean,true,'item added');
select is((public.marketpro_apply_change(
'c1000000-0000-4000-8000-000000000001','c2000000-0000-4000-8000-000000000001',
'c3000000-0000-4000-8000-000000000003','purchase','c6000000-0000-4000-8000-000000000001',
'upsert','{"business_date":"2026-10-21","item_id":"c5000000-0000-4000-8000-000000000001","quantity_grams":1500,"price_cents_per_kg":101}')->>'replayed')::boolean,false,'purchase added');
select is((public.marketpro_apply_change(
'c1000000-0000-4000-8000-000000000001','c2000000-0000-4000-8000-000000000001',
'c3000000-0000-4000-8000-000000000003','purchase','c6000000-0000-4000-8000-000000000001',
'upsert','{"business_date":"2026-10-21","item_id":"c5000000-0000-4000-8000-000000000001","quantity_grams":1500,"price_cents_per_kg":101}')->>'replayed')::boolean,true,'replayed');
select is((select revision from public.purchases),1::bigint,'replay does not duplicate revisions');
select is((public.marketpro_apply_change(
'c1000000-0000-4000-8000-000000000001','c2000000-0000-4000-8000-000000000001',
'c3000000-0000-4000-8000-000000000004','daily_cash_received','c7000000-0000-4000-8000-000000000001',
'upsert','{"business_date":"2026-10-21","amount_cents":200}')->>'accepted')::boolean,true,'cash added');
select is((select cash_balance_cents from public.daily_financial_summary),48::numeric,'200 cents cash minus 152 cents spent');
select throws_ok($$select public.marketpro_apply_change(
'c1000000-0000-4000-8000-000000000001','c2000000-0000-4000-8000-000000000001',
'c3000000-0000-4000-8000-000000000005','purchase','c6000000-0000-4000-8000-000000000002',
'upsert','{"invoice_photo":"base64"}');$$,
'22023','Cloud payload property is not allowed','reject photos');
select throws_ok($$select public.marketpro_apply_change(
'c1000000-0000-4000-8000-000000000001','c2000000-0000-4000-8000-000000000099',
'c3000000-0000-4000-8000-000000000006','purchase','c6000000-0000-4000-8000-000000000003',
'delete','{}');$$,'42501','Writer device is not authorized for this workspace','reject unregistered device');
select is((public.marketpro_apply_change(
'c1000000-0000-4000-8000-000000000001','c2000000-0000-4000-8000-000000000001',
'c3000000-0000-4000-8000-000000000007','purchase','c6000000-0000-4000-8000-000000000001',
'delete','{}')->>'accepted')::boolean,true,'delete as tombstone');
select is((select total_expenses_cents from public.daily_financial_summary),0::numeric,'deleted purchase excluded from finance');
set local request.jwt.claim.sub='c0000000-0000-4000-8000-000000000002';
select is((select count(*) from public.purchases),0::bigint,'other user cannot see purchase');
select throws_ok($$select public.marketpro_apply_change(
'c1000000-0000-4000-8000-000000000001','c2000000-0000-4000-8000-000000000001',
'c3000000-0000-4000-8000-000000000008','purchase','c6000000-0000-4000-8000-000000000001',
'delete','{}');$$,'42501','Writer device is not authorized for this workspace','cannot impersonate owner');
select * from finish();
rollback;
