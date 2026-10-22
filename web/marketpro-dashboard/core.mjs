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
