import test from "node:test";
import assert from "node:assert/strict";
import { exactCents, lkr, kg, lotCostCents, dateIsValid, normalizeDailyRow, summarizeDays, groupKitchens, requirementsByItem, dailyPriceTrends, recentBusinessDays, completedPurchaseReport, calendarMonthDays, groupRowsByCatalogCategory, csvDocument, csvCell, centsAsDecimal } from "../marketpro-dashboard/core.mjs";

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


test("PDF report groups completed items by category and reconciles all cash purchases", () => {
  const requisitions = [
    {kitchen:"Indian Kitchen",item_id:"apple",quantity_grams:5000},
    {kitchen:"Main Kitchen",item_id:"apple",quantity_grams:5000},
    {kitchen:"Main Kitchen",item_id:"carrot",quantity_grams:20000}
  ];
  const purchases = [
    {item_id:"apple",quantity_grams:8000,price_cents_per_kg:12000},
    {item_id:"carrot",quantity_grams:10000,price_cents_per_kg:3000}
  ];
  const additions = [{item_id:"apple",quantity_grams:4000,price_cents_per_kg:13000}];
  const items = new Map([
    ["apple",{id:"apple",name:"ඇපල්",category_id:"fruit"}],
    ["carrot",{id:"carrot",name:"කැරට්",category_id:"veg"}]
  ]);
  const categories = new Map([
    ["fruit",{id:"fruit",name:"Fruits"}],
    ["veg",{id:"veg",name:"Vegetables"}]
  ]);
  const report = completedPurchaseReport(requisitions,purchases,additions,items,categories);
  assert.equal(report.completedCount,1);
  assert.equal(report.groups.length,1);
  assert.equal(report.groups[0].name,"Fruits");
  assert.equal(report.groups[0].rows[0].requiredGrams,10000);
  assert.equal(report.groups[0].rows[0].purchasedGrams,12000);
  assert.equal(report.groups[0].subtotalCents,148000n);
  assert.equal(report.completedTotalCents,148000n);
  assert.equal(report.totalSpentCents,178000n,"Incomplete carrot purchase still reduces cash");
});

test("PDF report sums each lot with Android half-up rounding", () => {
  const items = new Map([["i",{id:"i",name:"Item",category_id:"c"}]]);
  const categories = new Map([["c",{id:"c",name:"Category"}]]);
  const report = completedPurchaseReport([],[
    {item_id:"i",quantity_grams:1500,price_cents_per_kg:101}
  ],[{item_id:"i",quantity_grams:1500,price_cents_per_kg:101}],items,categories);
  assert.equal(report.totalSpentCents,304n);
  assert.equal(report.completedTotalCents,304n);
  assert.equal(report.groups[0].rows[0].purchasedGrams,3000);
});

test("calendar highlights only actual saved dates and supports leap-year February", () => {
  const dates = ["2028-02-01","2028-02-29","2028-03-01"];
  const m = calendarMonthDays(2028,1,dates);
  assert.equal(m.days.length,29);
  assert.equal(m.days[0].recorded,true);
  assert.equal(m.days[28].recorded,true);
  assert.equal(m.days[1].recorded,false);
  assert.equal(m.firstWeekday,2);
  assert.equal(calendarMonthDays(2027,1,[]).days.length,28);
  assert.throws(()=>calendarMonthDays(2027,12,[]),/Invalid/);
});


test("Kitchens and Purchases list catalog categories in requested Vegetable-Chinese-Fruits order", () => {
  const items = new Map([
    ["apple",{id:"apple",name:"ඇපල්",category_id:"cat-f"}],
    ["kale",{id:"kale",name:"කේල්",category_id:"cat-c"}],
    ["carrot",{id:"carrot",name:"කැරට්",category_id:"cat-v"}],
    ["cucumber",{id:"cucumber",name:"පිපිඤ්ඤා",category_id:"cat-v"}],
    ["other",{id:"other",name:"Other",category_id:"cat-o"}]
  ]);
  const cats = new Map([
    ["cat-f",{id:"cat-f",name:"Fruits"}],
    ["cat-c",{id:"cat-c",name:"Chinese Veg"}],
    ["cat-v",{id:"cat-v",name:"Vegetable"}],
    ["cat-o",{id:"cat-o",name:"Herbs"}]
  ]);
  const rows = [
    {id:1,item_id:"apple",quantity_grams:1000},
    {id:2,item_id:"kale",quantity_grams:0},
    {id:3,item_id:"carrot",quantity_grams:2000},
    {id:4,item_id:"cucumber",quantity_grams:3500},
    {id:5,item_id:"other",quantity_grams:200},
    {id:6,item_id:"apple",quantity_grams:500},
    {id:7,item_id:"missing",quantity_grams:0}
  ];
  const frozenOrder = rows.map(x => x.id);
  const grouped = groupRowsByCatalogCategory(rows,items,cats);
  assert.deepEqual(grouped.map(x=>x.name),
    ["Vegetables","Chinese Vegetables","Fruits","Herbs","Uncategorized"]);
  assert.deepEqual(grouped.map(x=>x.rows.length),[2,1,2,1,1]);
  assert.deepEqual(grouped.flatMap(x=>x.rows.map(row=>row.id)).sort((a,b)=>a-b),
    frozenOrder);
  assert.deepEqual(rows.map(x=>x.id),frozenOrder,"source rows must not change");
  assert.equal(grouped.flatMap(x=>x.rows).filter(row=>row.quantity_grams===0).length,2,
    "zero-quantity requisitions remain visible");
});

test("Multiple initial/additional lots survive category grouping without money duplication", () => {
  const cats=new Map([["fruit",{name:"Fruit"}],["veg",{name:"Vegetables"}]]);
  const items=new Map([
    ["a",{category_id:"fruit"}],
    ["b",{category_id:"veg"}]
  ]);
  const lots=[
    {id:"a1",item_id:"a",type:"Initial",quantity_grams:12000,price_cents_per_kg:25000},
    {id:"a2",item_id:"a",type:"Additional",quantity_grams:2000,price_cents_per_kg:12000},
    {id:"b1",item_id:"b",type:"Initial",quantity_grams:10000,price_cents_per_kg:20000}
  ];
  const groups=groupRowsByCatalogCategory(lots,items,cats);
  assert.deepEqual(groups.map(x=>x.name),["Vegetables","Fruits"]);
  const subtotals=groups.map(g=>g.rows.reduce((sum,lot)=>
    sum+lotCostCents(lot.quantity_grams,lot.price_cents_per_kg),0n));
  assert.deepEqual(subtotals,[200000n,324000n]);
  assert.equal(subtotals.reduce((a,b)=>a+b,0n),
    lots.reduce((sum,lot)=>sum+lotCostCents(lot.quantity_grams,lot.price_cents_per_kg),0n));
});

test("Excel CSV preserves Sinhala item names and authentic UTF-8 BOM", () => {
  const output = csvDocument(["Item","Quantity kg","Amount Rs"],[
    ["කොත්තමල්ලි කොළ","2.500","1500.25"],
    ["කැරට්","12.000","-15.50"]
  ]);
  assert.equal(output.charCodeAt(0),0xFEFF);
  assert.match(output, /කොත්තමල්ලි කොළ/);
  assert.match(output, /"කැරට්",12.000,-15.50/);
  assert.equal(output.split("\r\n").length,3);
});
test("CSV export protects against spreadsheet formula injection", () => {
  assert.equal(csvCell("=HYPERLINK(\"https://example.test\")"),
    "\"'=HYPERLINK(\"\"https://example.test\"\")\"");
  assert.equal(csvCell("@SUM(A1)"), "\"'@SUM(A1)\"");
  assert.equal(csvCell("-31.25"), "-31.25");
  assert.equal(csvCell("3.00"), "3.00");
  assert.throws(()=>csvDocument(["A","B"],[["one"]]),/row/);
});
test("CSV financial currency conversion remains precise above Number.MAX_SAFE_INTEGER", () => {
  assert.equal(centsAsDecimal("900719925474099199"),"9007199254740991.99");
  assert.equal(centsAsDecimal("-200"),"-2.00");
});
