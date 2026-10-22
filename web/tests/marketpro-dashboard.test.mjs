import test from "node:test";
import assert from "node:assert/strict";
import { exactCents, lkr, kg, lotCostCents, dateIsValid, normalizeDailyRow, summarizeDays, groupKitchens, requirementsByItem, dailyPriceTrends, recentBusinessDays } from "../marketpro-dashboard/core.mjs";

test("Android half-up on each 1.5kg lot 101 cents/kg", () => {
  const lot = lotCostCents(1500,101);
  assert.equal(lot,152n);
  assert.equal(lot*2n,304n);
});
test("cash balance exact when purchases include extra lots", () => {
  const row = normalizeDailyRow({business_date:"2026-10-21",required_grams:"13000",total_expenses_cents:"304",cash_received_cents:"1000",cash_balance_cents:"696"});
  assert.equal(row.total_expenses_cents,304n);
  assert.equal(row.cash_balance_cents,696n);
  assert.equal(summarizeDays([row]).netBalance,696n);
});
test("money formatting never converts exact cents to floats", () => {
  assert.equal(lkr("53590250"),"Rs. 535,902.50");
  assert.equal(lkr("-200"),"-Rs. 2.00");
  assert.equal(lkr("900719925474099199"),"Rs. 9,007,199,254,740,991.99");
  assert.equal(exactCents("-3"),-3n);
  assert.throws(() => exactCents("1.42"),/Invalid/);
});
test("kitchen aggregation and item counts", () => {
  const kitchens = groupKitchens([{kitchen:"Pastry Kitchen",quantity_grams:5000},{kitchen:"Main Kitchen",quantity_grams:1500},{kitchen:"Main Kitchen",quantity_grams:1200}]);
  assert.equal(kitchens[0].kitchen,"Main Kitchen");
  assert.equal(kitchens[0].grams,2700);
  assert.equal(kitchens[0].itemCount,2);
  assert.equal(kg(1500),"1.5 kg");
});
test("reject malformed calendar days", () => {
  assert.equal(dateIsValid("2026-10-21"),true);
  assert.equal(dateIsValid("2026-02-30"),false);
  assert.equal(dateIsValid("2026-1-1"),false);
});


test("purchase demand aggregates kitchens uniquely, not for every purchase lot", () => {
  const demand = requirementsByItem([
    {item_id:"A",kitchen:"Main Kitchen",quantity_grams:20000},
    {item_id:"A",kitchen:"Indian Kitchen",quantity_grams:35000},
    {item_id:"A",kitchen:"Main Kitchen",quantity_grams:5000},
    {item_id:"B",kitchen:"Pastry Kitchen",quantity_grams:0}
  ]);
  assert.equal(demand.get("A").grams,60000);
  assert.deepEqual(demand.get("A").kitchens,[
    {kitchen:"Indian Kitchen",quantity_grams:35000},
    {kitchen:"Main Kitchen",quantity_grams:25000}
  ]);
  assert.equal(demand.get("B").grams,0);
  assert.equal(demand.has("C"),false);
  assert.throws(() => requirementsByItem([{item_id:"A",kitchen:"Main",quantity_grams:-100}]),/Invalid/);
});

test("weighted price equals Android dailyPriceTrends HALF_UP, not simple average", () => {
  const rows = [
    {business_date:"2026-10-20",quantity_grams:1000,price_cents_per_kg:10000},
    {business_date:"2026-10-20",quantity_grams:3000,price_cents_per_kg:20000},
    {business_date:"2026-10-21",quantity_grams:1500,price_cents_per_kg:101},
    {business_date:"2026-10-21",quantity_grams:500,price_cents_per_kg:102},
    {business_date:"2026-10-22",quantity_grams:0,price_cents_per_kg:9999}
  ];
  const days = dailyPriceTrends(rows);
  assert.equal(days.length,2);
  assert.equal(days[0].date,"2026-10-20");
  assert.equal(days[0].quantityGrams,4000);
  assert.equal(days[0].weightedPriceCentsPerKg,17500n);
  assert.equal(days[0].purchaseLots,2);
  assert.equal(days[1].quantityGrams,2000);
  assert.equal(days[1].weightedPriceCentsPerKg,101n);
  assert.equal(days[1].purchaseLots,2);
});

test("charts never invent missing purchase days and reject invalid calendar data", () => {
  const days = dailyPriceTrends([
    {business_date:"2026-10-01",quantity_grams:1000,price_cents_per_kg:30000},
    {business_date:"2026-10-05",quantity_grams:500,price_cents_per_kg:40000}
  ]);
  assert.deepEqual(days.map(day=>day.date),["2026-10-01","2026-10-05"]);
  assert.throws(()=>dailyPriceTrends([{
    business_date:"2026-02-30",quantity_grams:1000,price_cents_per_kg:100
  }]),/Invalid/);
});


test("overview financial table and trend show 10 latest dated records, not oldest", () => {
  const all = Array.from({length: 14}, (_, index) => ({
    business_date: "2026-10-" + String(index + 1).padStart(2, "0"),
    total_expenses_cents: BigInt(index + 1)
  }));
  const original = [...all];
  const recent = recentBusinessDays([...all].reverse().sort((a, b) =>
    a.business_date.localeCompare(b.business_date)), 10);
  assert.equal(recent.length, 10);
  assert.equal(recent[0].business_date, "2026-10-14");
  assert.equal(recent.at(-1).business_date, "2026-10-05");
  assert.deepEqual(all, original, "source daily financial records must not be mutated");
  assert.deepEqual(recentBusinessDays(all.slice(0, 3)).map(x=>x.business_date),
    ["2026-10-03","2026-10-02","2026-10-01"]);
  assert.deepEqual(recentBusinessDays([]), []);
  assert.throws(() => recentBusinessDays(all, 0), /Invalid/);
});
