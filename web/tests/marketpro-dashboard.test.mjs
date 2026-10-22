import test from "node:test";
import assert from "node:assert/strict";
import { exactCents, lkr, kg, lotCostCents, dateIsValid, normalizeDailyRow, summarizeDays, groupKitchens } from "../marketpro-dashboard/core.mjs";

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
