/* Pure helpers: all money is handled in integer cents to match Android. */
export function exactCents(value) {
  if (value === null || value === undefined || value === "") return 0n;
  const raw = String(value).trim();
  if (!/^-?\d+(?:\.0+)?$/.test(raw)) throw new Error("Invalid integer cents");
  return BigInt(raw.split(".")[0]);
}

export function lkr(value) {
  const cents = exactCents(value);
  const negative = cents < 0n;
  const n = negative ? -cents : cents;
  const whole = n / 100n;
  const frac = (n % 100n).toString().padStart(2, "0");
  const thousands = whole.toString().replace(/\B(?=(\d{3})+(?!\d))/g, ",");
  return (negative ? "-" : "") + "Rs. " + thousands + "." + frac;
}

export function kg(grams) {
  const n = Number(grams);
  if (!Number.isFinite(n) || n < 0) throw new Error("Invalid grams");
  return (n / 1000).toLocaleString("en-LK", { maximumFractionDigits: 3 }) + " kg";
}

export function lotCostCents(quantityGrams, priceCentsPerKg) {
  const g = exactCents(quantityGrams), c = exactCents(priceCentsPerKg);
  if (g < 0n || c < 0n) throw new Error("Negative lot inputs");
  return (g * c + 500n) / 1000n; // Match Kotlin BigDecimal HALF_UP, positive lot.
}

export function dateIsValid(input) {
  if (!/^\d{4}-\d{2}-\d{2}$/.test(input)) return false;
  const date = new Date(input + "T00:00:00Z");
  return Number.isFinite(date.getTime()) && date.toISOString().slice(0,10) === input;
}

export function normalizeDailyRow(row) {
  return {
    business_date: row.business_date,
    required_grams: Number(row.required_grams || 0),
    total_expenses_cents: exactCents(row.total_expenses_cents),
    initial_purchase_expenses_cents: exactCents(row.initial_purchase_expenses_cents),
    additional_purchase_expenses_cents: exactCents(row.additional_purchase_expenses_cents),
    cash_received_cents: exactCents(row.cash_received_cents),
    cash_balance_cents: exactCents(row.cash_balance_cents)
  };
}

export function summarizeDays(days) {
  const sum = (name) => days.reduce((a, row) => a + exactCents(row[name]), 0n);
  return {
    totalExpenses: sum("total_expenses_cents"),
    totalCashReceived: sum("cash_received_cents"),
    netBalance: sum("cash_balance_cents")
  };
}

export function groupKitchens(rows) {
  const groups = new Map();
  for (const r of rows) {
    const prev = groups.get(r.kitchen) || { kitchen: r.kitchen, itemCount: 0, grams: 0 };
    prev.itemCount++;
    prev.grams += Number(r.quantity_grams);
    groups.set(r.kitchen, prev);
  }
  const order = ["Main Kitchen","Indian Kitchen","Pastry Kitchen"];
  return [...groups.values()].sort((a,b) => order.indexOf(a.kitchen) - order.indexOf(b.kitchen));
}


/**
 * Item demand is daily and may come from several kitchens. Keep one unique
 * requirement per item, never add it again for each purchase lot.
 */
export function requirementsByItem(rows) {
  const result = new Map();
  for (const row of rows) {
    const grams = Number(row.quantity_grams);
    if (!Number.isSafeInteger(grams) || grams < 0) throw Error("Invalid requisition grams");
    const current = result.get(row.item_id) || { grams: 0, kitchens: new Map() };
    current.grams += grams;
    current.kitchens.set(row.kitchen, (current.kitchens.get(row.kitchen) || 0) + grams);
    result.set(row.item_id, current);
  }
  return new Map([...result].map(([id, item]) => [id, {
    grams: item.grams,
    kitchens: [...item.kitchens].sort(([a], [b]) => a.localeCompare(b))
      .map(([kitchen, quantity_grams]) => ({ kitchen, quantity_grams }))
  }]));
}

/**
 * Same business rule as Android PriceTrends.kt:
 * each date combines initial + additional lots into a quantity-weighted
 * unit price (HALF_UP to integer cents); dates without lots are not filled.
 */
export function dailyPriceTrends(lots) {
  const byDay = new Map();
  for (const row of lots) {
    const grams = exactCents(row.quantity_grams);
    const price = exactCents(row.price_cents_per_kg);
    if (grams <= 0n || price < 0n) continue;
    if (!dateIsValid(row.business_date)) throw Error("Invalid price history date");
    const current = byDay.get(row.business_date) ||
      { date: row.business_date, grams: 0n, weighted: 0n, purchaseLots: 0 };
    current.grams += grams;
    current.weighted += grams * price;
    current.purchaseLots++;
    byDay.set(row.business_date, current);
  }
  return [...byDay.values()].sort((a, b) => a.date.localeCompare(b.date))
    .map(day => ({
      date: day.date,
      quantityGrams: Number(day.grams),
      weightedPriceCentsPerKg: (day.weighted + day.grams / 2n) / day.grams,
      purchaseLots: day.purchaseLots
    }));
}


/** Newest business-date summaries for compact overview charts and tables.
 * Sorting on YYYY-MM-DD keeps selection stable regardless of API order.
 * A visible limit does not delete or change saved financial records.
 */
export function recentBusinessDays(rows, limit = 10) {
  if (!Number.isInteger(limit) || limit < 1) throw Error("Invalid history display limit");
  return [...rows].sort((a, b) =>
    String(b.business_date).localeCompare(String(a.business_date))).slice(0, limit);
}


/**
 * Matches Android MarketPdf.completedCategoryGroups: consolidate daily initial
 * and additional purchase lots per item, include purchased > 0 and >= required,
 * sort into categories, and sum per-lot HALF_UP costs in integer cents.
 * Incomplete purchases still contribute to total cash spent.
 */
export function completedPurchaseReport(requisitions, purchases, additions, items, categories) {
  const required = requirementsByItem(requisitions);
  const aggregate = new Map();
  for (const lot of [...purchases, ...additions]) {
    const grams = Number(lot.quantity_grams);
    if (!Number.isSafeInteger(grams) || grams < 0) throw Error("Invalid purchased quantity");
    const cents = lotCostCents(lot.quantity_grams, lot.price_cents_per_kg);
    const current = aggregate.get(lot.item_id) ||
      { item_id: lot.item_id, purchasedGrams: 0, lineCents: 0n };
    current.purchasedGrams += grams;
    current.lineCents += cents;
    aggregate.set(lot.item_id, current);
  }
  let totalSpentCents = 0n;
  const grouped = new Map();
  for (const row of aggregate.values()) {
    totalSpentCents += row.lineCents;
    const neededGrams = required.get(row.item_id)?.grams || 0;
    if (row.purchasedGrams <= 0 || row.purchasedGrams < neededGrams) continue;
    const item = items.get(row.item_id);
    if (!item) throw Error("Cannot report a purchased item missing from the catalog");
    const category = categories.get(item.category_id)?.name || "Uncategorized";
    const previous = grouped.get(category) || [];
    previous.push({
      id: row.item_id, name: item.name,
      requiredGrams: neededGrams, purchasedGrams: row.purchasedGrams,
      lineCents: row.lineCents
    });
    grouped.set(category, previous);
  }
  const groups = [...grouped.entries()]
    .sort(([a],[b]) => a.localeCompare(b))
    .map(([name, rows]) => {
      rows.sort((a,b) => a.name.localeCompare(b.name));
      return {
        name, rows,
        subtotalCents: rows.reduce((sum,row)=>sum+row.lineCents,0n)
      };
    });
  return {
    groups,
    completedCount: groups.reduce((sum,g)=>sum+g.rows.length,0),
    completedTotalCents: groups.reduce((sum,g)=>sum+g.subtotalCents,0n),
    totalSpentCents
  };
}

/** Day markers are fetched from the authenticated workspace's daily summary. */
export function calendarMonthDays(year, monthIndex, dates) {
  if (!Number.isInteger(year) || year < 1 || year > 9999 ||
    !Number.isInteger(monthIndex) || monthIndex < 0 || monthIndex > 11) {
    throw Error("Invalid calendar month");
  }
  const count = new Date(Date.UTC(year, monthIndex+1, 0)).getUTCDate();
  const firstWeekday = new Date(Date.UTC(year, monthIndex, 1)).getUTCDay();
  const saved = new Set(dates);
  return {
    firstWeekday,
    days: Array.from({length:count},(_,i)=>{
      const date = String(year).padStart(4,"0")+"-"+
        String(monthIndex+1).padStart(2,"0")+"-"+String(i+1).padStart(2,"0");
      return {date, day:i+1, recorded:saved.has(date)};
    })
  };
}


/**
 * Category sections for read-only Kitchens and Purchases reports.
 * The master catalog (item.category_id -> categories.id) is the sole source of
 * membership. Display common existing naming variants consistently and order:
 * Vegetables, Chinese Vegetables, Fruits, then any other catalog categories.
 * Preserves every original row, including zero-quantity requisitions and
 * separate initial/additional purchase lots.
 */
export function groupRowsByCatalogCategory(rows, itemsById, categoriesById) {
  function canonicalCategory(name) {
    const normalized = String(name || "").trim().toLowerCase()
      .replace(/[\s_-]+/g, "");
    if (normalized === "vegetable" || normalized === "vegetables" ||
        normalized === "veggie" || normalized === "veggies") {
      return { name: "Vegetables", rank: 0 };
    }
    if (["chineseveg", "chinesevegs", "chinesevegetable",
         "chinesevegetables"].includes(normalized)) {
      return { name: "Chinese Vegetables", rank: 1 };
    }
    if (normalized === "fruit" || normalized === "fruits") {
      return { name: "Fruits", rank: 2 };
    }
    return { name: String(name || "Uncategorized").trim() || "Uncategorized", rank: 3 };
  }
  const groups = new Map();
  for (const row of rows) {
    const item = itemsById.get(row.item_id);
    const categoryId = item?.category_id || null;
    const key = categoryId === null ? "unknown" : String(categoryId);
    let group = groups.get(key);
    if (!group) {
      const category = canonicalCategory(categoriesById.get(categoryId)?.name);
      group = { key, name: category.name, rank: category.rank, rows: [] };
      groups.set(key, group);
    }
    group.rows.push(row);
  }
  return [...groups.values()].sort((a, b) =>
    a.rank - b.rank || a.name.localeCompare(b.name, "en", { sensitivity: "base" }) ||
    a.key.localeCompare(b.key));
}
