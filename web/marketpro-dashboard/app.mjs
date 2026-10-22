import { DashboardApi } from "./auth-client.mjs";
import { REFRESH_INTERVAL_MS } from "./config.mjs";
import { exactCents, lkr, kg, lotCostCents, dateIsValid, normalizeDailyRow, groupKitchens, requirementsByItem, dailyPriceTrends, recentBusinessDays, completedPurchaseReport, calendarMonthDays } from "./core.mjs";

const $ = id => document.getElementById(id);
const api = new DashboardApi();
const state = {
  user: null, membership: null, summary: [], categoryById: new Map(), itemById: new Map(),
  data: { requisitions: [], purchases: [], additions: [] }, date: "",
  busy: false, ready: false, tab: "overview", autoTimer: null,
  chartCategory: null, chartItemId: null, chartHistory: [], chartLoadedFor: null, chartSerial: 0,
  calendarOpen: false, calendarYear: 0, calendarMonth: 0, calendarSerial: 0,
  calendarMarkers: new Set(), pdfOpen: false, pendingDateRefresh: false
};
const text = (id, value) => { $(id).textContent = String(value); };
const show = (id, visible) => $(id).classList.toggle("hidden", !visible);
const clear = id => $(id).replaceChildren();
function elem(tag, content = "", cls = "") {
  const node = document.createElement(tag);
  node.textContent = String(content);
  if (cls) node.className = cls;
  return node;
}
function td(content, cls = "") { return elem("td", content, cls); }
function message(error, target = "dashboard-error") {
  $(target).textContent = typeof error === "string" ? error : (error?.message || "Unknown error");
  show(target, true);
}
function clearMessage(target = "dashboard-error") { show(target, false); }
function emptyRow(body, colSpan, value) {
  const tr = elem("tr"), column = td(value, "empty");
  column.colSpan = colSpan; tr.append(column); body.append(tr);
}
function formattedDate(input) {
  if (!dateIsValid(input)) return input;
  return new Intl.DateTimeFormat("en-GB", {
    timeZone: "Asia/Colombo", day: "2-digit", month: "short", year: "numeric"
  }).format(new Date(input + "T10:00:00Z"));
}
function todayColombo() {
  const parts = new Intl.DateTimeFormat("en-GB", {
    timeZone: "Asia/Colombo", year: "numeric", month: "2-digit", day: "2-digit"
  }).formatToParts(new Date());
  const p = Object.fromEntries(parts.map(part => [part.type, part.value]));
  return p.year + "-" + p.month + "-" + p.day;
}
function itemName(id) { return state.itemById.get(id)?.name || "Unknown item"; }
function chooseDailyRow() {
  return state.summary.find(x => x.business_date === state.date) ||
    normalizeDailyRow({
      business_date: state.date, required_grams: 0, initial_purchase_expenses_cents: 0,
      additional_purchase_expenses_cents: 0, total_expenses_cents: 0,
      cash_received_cents: 0, cash_balance_cents: 0
    });
}
function money(id, cents) {
  text(id, lkr(cents));
  $(id).classList.toggle("negative", exactCents(cents) < 0n);
}
function renderMetrics() {
  const day = chooseDailyRow(), { requisitions, purchases, additions } = state.data;
  text("kpi-kitchen", kg(day.required_grams));
  money("kpi-expenses", day.total_expenses_cents);
  money("kpi-cash", day.cash_received_cents);
  money("kpi-balance", day.cash_balance_cents);
  text("kpi-kitchen-sub", requisitions.length + " requisition entries");
  text("kpi-expenses-sub", purchases.length + " purchases · " + additions.length + " extra lots");
  text("count-purchases", purchases.length);
  text("count-additions", additions.length);
  text("count-requisitions", requisitions.length);
  text("count-items", state.itemById.size);
  text("count-categories", state.categoryById.size);
  text("activity-date", formattedDate(state.date));
  for (const [id, value] of [
    ["detail-cash", day.cash_received_cents],
    ["detail-initial", day.initial_purchase_expenses_cents],
    ["detail-additional", day.additional_purchase_expenses_cents],
    ["detail-expenses", day.total_expenses_cents],
    ["detail-balance", day.cash_balance_cents]
  ]) money(id, value);
}
function renderHistory() {
  const body = $("history-body"); body.replaceChildren();
  if (state.summary.length === 0) return emptyRow(body, 4, "No cloud financial history yet.");
  for (const row of recentBusinessDays(state.summary, 10)) {
    const tr = elem("tr"), first = elem("td");
    const button = elem("button", formattedDate(row.business_date), "table-button");
    button.type = "button";
    button.addEventListener("click", () => {
      state.date = row.business_date;
      $("date-filter").value = state.date;
      syncDateButton();
      closeCalendar();
      void refresh();
      window.scrollTo({ top: 0, behavior: "smooth" });
    });
    first.append(button);
    tr.append(first, td(lkr(row.total_expenses_cents), "right"),
      td(lkr(row.cash_received_cents), "right"),
      td(lkr(row.cash_balance_cents), "right " + (row.cash_balance_cents < 0n ? "negative" : "")));
    body.append(tr);
  }
}
function renderChart() {
  const target = $("trend-chart"); target.replaceChildren();
  const days = recentBusinessDays(state.summary, 10).reverse();
  if (!days.length) { target.append(elem("p", "No dated records yet.", "subtle")); return; }
  const max = Math.max(1, ...days.flatMap(x => [
    Math.abs(Number(x.total_expenses_cents)), Math.abs(Number(x.cash_received_cents))
  ]));
  for (const row of days) {
    const group = elem("div", "", "trend-group");
    group.setAttribute("aria-label", formattedDate(row.business_date) + ": " +
      lkr(row.total_expenses_cents) + " spent, " + lkr(row.cash_received_cents) + " received");
    const expense = elem("div", "", "trend-bar trend-expenses");
    const cash = elem("div", "", "trend-bar trend-cash");
    expense.style.height = Math.max(2, Math.abs(Number(row.total_expenses_cents)) / max * 100) + "%";
    cash.style.height = Math.max(2, Math.abs(Number(row.cash_received_cents)) / max * 100) + "%";
    expense.title = formattedDate(row.business_date) + ": " + lkr(row.total_expenses_cents) + " spent";
    cash.title = formattedDate(row.business_date) + ": " + lkr(row.cash_received_cents) + " received";
    group.append(expense, cash, elem("span", row.business_date.slice(5), "trend-day"));
    target.append(group);
  }
}
function renderKitchens() {
  const summary = $("kitchen-summary"); summary.replaceChildren();
  const groups = groupKitchens(state.data.requisitions);
  if (!groups.length) summary.append(elem("p", "No kitchen requisitions for this date.", "subtle"));
  for (const group of groups) {
    const card = elem("div", "", "small-metric");
    card.append(elem("p", group.kitchen), elem("strong", kg(group.grams)),
      elem("small", group.itemCount + " requisition items"));
    summary.append(card);
  }
  const body = $("requisitions-body"); body.replaceChildren();
  const rows = [...state.data.requisitions].sort((a, b) =>
    a.kitchen.localeCompare(b.kitchen) || itemName(a.item_id).localeCompare(itemName(b.item_id)));
  if (!rows.length) return emptyRow(body, 3, "No requisitions for selected date.");
  for (const r of rows) {
    const tr = elem("tr");
    tr.append(td(r.kitchen), td(itemName(r.item_id)), td(kg(r.quantity_grams), "right"));
    body.append(tr);
  }
}
function renderPurchases() {
  const body = $("purchases-body"); body.replaceChildren();
  const lots = [
    ...state.data.purchases.map(r => ({ ...r, type: "Initial" })),
    ...state.data.additions.map(r => ({ ...r, type: "Additional" }))
  ].sort((a, b) => itemName(a.item_id).localeCompare(itemName(b.item_id)));
  if (!lots.length) return emptyRow(body, 6, "No purchases for selected date.");
  const demandByItem = requirementsByItem(state.data.requisitions);
  let subtotal = 0n;
  for (const lot of lots) {
    const cents = lotCostCents(lot.quantity_grams, lot.price_cents_per_kg);
    subtotal += cents;
    const tr = elem("tr"), first = elem("td");
    first.append(elem("span", lot.type, lot.type === "Additional" ? "tag tag-lot" : "tag"));
    const need = elem("td");
    const requirement = demandByItem.get(lot.item_id);
    if (requirement) {
      need.append(elem("strong", kg(requirement.grams), "need-total"));
      for (const kitchen of requirement.kitchens) {
        need.append(elem("span", kitchen.kitchen + " · " + kg(kitchen.quantity_grams), "need-breakdown"));
      }
    } else {
      need.append(elem("span", "Not requested", "need-absent"));
    }
    tr.append(first, td(itemName(lot.item_id)), need, td(kg(lot.quantity_grams), "right"),
      td(lkr(lot.price_cents_per_kg), "right"), td(lkr(cents), "right"));
    body.append(tr);
  }
  const total = elem("tr", "", "purchase-total");
  total.append(td("Total lots"), td(""), td(""), td(""), td(""), td(lkr(subtotal), "right"));
  body.append(total);
}


function renderChartControls() {
  const categories = [...state.categoryById.values()].sort((a, b) => a.name.localeCompare(b.name));
  const withItems = categories.filter(category =>
    [...state.itemById.values()].some(item => item.category_id === category.id));
  if (!withItems.some(category => category.id === state.chartCategory)) {
    state.chartCategory = withItems[0]?.id || null;
    state.chartSerial++;
    state.chartItemId = null;
    state.chartHistory = [];
    state.chartLoadedFor = null;
  }
  const strip = $("chart-categories"); strip.replaceChildren();
  for (const category of withItems) {
    const chip = elem("button", category.name, "category-chip");
    chip.type = "button";
    chip.setAttribute("aria-pressed", String(category.id === state.chartCategory));
    chip.addEventListener("click", () => {
      if (state.chartCategory === category.id) return;
      state.chartCategory = category.id;
      state.chartItemId = null;
      state.chartHistory = [];
      state.chartLoadedFor = null;
      state.chartSerial++;
      show("price-charts", false);
      text("chart-status", "Select an item to view its historical charts.");
      renderChartControls();
    });
    strip.append(chip);
  }
  const dropdown = $("chart-item");
  dropdown.replaceChildren();
  const prompt = elem("option", "Choose an item");
  prompt.value = "";
  dropdown.append(prompt);
  const eligible = [...state.itemById.values()]
    .filter(item => item.category_id === state.chartCategory)
    .sort((a,b) => a.name.localeCompare(b.name));
  for (const item of eligible) {
    const opt = elem("option", item.name); opt.value = item.id; dropdown.append(opt);
  }
  dropdown.disabled = eligible.length === 0;
  if (!eligible.some(item => item.id === state.chartItemId)) {
    state.chartItemId = null;
    state.chartHistory = [];
    state.chartLoadedFor = null;
    show("price-charts", false);
  }
  dropdown.value = state.chartItemId || "";
  if (!withItems.length) {
    text("chart-status", "No catalog categories or items are available.");
    show("price-charts", false);
  }
}

async function readItemHistory(itemId) {
  async function pageThrough(table) {
    const rows = [];
    for (let offset = 0; offset < 10000; offset += 1000) {
      const page = await api.select(table, {
        select: "business_date,quantity_grams,price_cents_per_kg",
        workspace_id: "eq." + currentWorkspace(),
        item_id: "eq." + itemId, deleted_at: "is.null",
        order: "business_date.desc,id.desc", limit: "1000", offset: String(offset)
      });
      rows.push(...page);
      if (page.length < 1000) return rows;
    }
    throw Error("Over 10,000 historical lots in " + table + ". Chart omitted to avoid an incomplete trend.");
  }
  const [initial, additional] = await Promise.all([
    pageThrough("purchases"), pageThrough("purchase_additions")
  ]);
  return [...initial, ...additional];
}

function svgNode(tag, attrs = {}, label) {
  const node = document.createElementNS("http://www.w3.org/2000/svg", tag);
  for (const [key, value] of Object.entries(attrs)) node.setAttribute(key, String(value));
  if (label !== undefined) node.textContent = String(label);
  return node;
}

function drawPriceChart(id, days, metric, color, decimals) {
  const target = $(id); target.replaceChildren();
  const viewportHeight = 225, top = 28, bottom = 183, step = 86;
  const width = Math.max(550, 72 + (days.length - 1) * step);
  const values = days.map(metric);
  const min = Math.min(...values), max = Math.max(...values);
  const margin = Math.max(1, max - min) * .3;
  const low = Math.max(0, min - margin), high = max + margin;
  const range = Math.max(.0001, high - low);
  const y = val => bottom - (val - low) / range * (bottom - top);
  const axis = elem("div", "", "chart-axis");
  const scroller = elem("div", "", "chart-scroll");
  scroller.setAttribute("tabindex", "0");
  scroller.setAttribute("aria-label", "Scroll horizontally to view older and newer purchase dates");
  const svg = svgNode("svg", {
    width, height: viewportHeight, viewBox: "0 0 " + width + " " + viewportHeight,
    role: "img", "aria-label": id === "price-trend" ? "Historical weighted average price per kilogram" : "Historical purchased quantity"
  });
  for (let t = 0; t <= 3; t++) {
    const value = low + (high - low) * t / 3;
    const yy = y(value);
    const tick = elem("span", value.toFixed(decimals), "chart-axis-label");
    tick.style.top = yy + "px";
    axis.append(tick);
    svg.append(svgNode("line", { x1: 0, x2: width, y1: yy, y2: yy,
      stroke: "#dae6e1", "stroke-width": 1 }));
  }
  const points = values.map((value, index) => ({
    x: 36 + index * step, y: y(value), value, date: days[index].date
  }));
  if (points.length > 1) svg.append(svgNode("polyline", {
    points: points.map(p => p.x + "," + p.y).join(" "),
    fill: "none", stroke: color, "stroke-width": 2.8,
    "stroke-linecap": "round", "stroke-linejoin": "round"
  }));
  for (const p of points) {
    const group = svgNode("g");
    group.append(svgNode("circle", {
      cx: p.x, cy: p.y, r: 4.4, fill: color
    }));
    group.append(svgNode("text", {
      x: p.x, y: Math.max(14, p.y - 11),
      "text-anchor": "middle", fill: "#263d31", "font-size": 12, "font-weight": 700
    }, p.value.toFixed(2)));
    group.append(svgNode("text", {
      x: p.x, y: 214, "text-anchor": "middle", fill: "#61756a", "font-size": 12
    }, p.date.slice(5)));
    group.append(svgNode("title", {}, p.date + " · " + p.value.toFixed(2)));
    svg.append(group);
  }
  scroller.append(svg);
  target.append(axis, scroller);
  return scroller;
}

function renderItemCharts(preserveScroll = false) {
  if (!state.chartItemId || state.chartLoadedFor !== state.chartItemId) {
    show("price-charts", false); return;
  }
  const full = dailyPriceTrends(state.chartHistory);
  if (!full.length) {
    text("chart-status", "No recorded purchases for this item yet.");
    show("price-charts", false);
    return;
  }
  const days = full.slice(-180);
  const prev = preserveScroll ? $("price-trend").querySelector(".chart-scroll")?.scrollLeft : null;
  const totalLots = full.reduce((sum, day) => sum + day.purchaseLots, 0);
  text("chart-status", full.length + " recorded day(s) · " + totalLots +
    " purchase lot(s)" + (full.length > 180 ? " · Most recent 180 dates shown" : ""));
  show("price-charts", true);
  const price = drawPriceChart("price-trend", days,
    day => Number(day.weightedPriceCentsPerKg) / 100, "#0b9870", 0);
  const qty = drawPriceChart("quantity-trend", days,
    day => day.quantityGrams / 1000, "#806ac4", 1);
  let syncing = false;
  function sync(from, to) {
    if (syncing) return;
    syncing = true; to.scrollLeft = from.scrollLeft; syncing = false;
  }
  price.addEventListener("scroll", () => sync(price, qty), { passive: true });
  qty.addEventListener("scroll", () => sync(qty, price), { passive: true });
  price.scrollLeft = prev === null || prev === undefined ? price.scrollWidth : prev;
  qty.scrollLeft = price.scrollLeft;
}

async function loadItemHistory(preserveScroll = false) {
  const itemId = state.chartItemId;
  if (!itemId || !state.ready) return;
  const requestId = ++state.chartSerial;
  if (!preserveScroll || state.chartLoadedFor !== itemId) {
    show("price-charts", false);
    text("chart-status", "Loading purchase history…");
  }
  try {
    const rows = await readItemHistory(itemId);
    if (requestId !== state.chartSerial || itemId !== state.chartItemId || !state.ready) return;
    state.chartHistory = rows;
    state.chartLoadedFor = itemId;
    renderItemCharts(preserveScroll);
  } catch (error) {
    if (requestId !== state.chartSerial || itemId !== state.chartItemId) return;
    // Never show a partial chart after any failed or truncated source query.
    state.chartHistory = [];
    state.chartLoadedFor = null;
    show("price-charts", false);
    text("chart-status", "Could not load complete item history: " + (error?.message || "Unknown error"));
  }
}



function syncDateButton() {
  if (!dateIsValid(state.date)) {
    text("date-trigger", "Select a date ▦");
    return;
  }
  const [year, month, day] = state.date.split("-");
  const button = $("date-trigger");
  button.replaceChildren();
  button.append(document.createTextNode(day + "/" + month + "/" + year + " "));
  button.append(elem("span", "▦", "calendar-trigger-icon"));
}
function chooseBusinessDate(date) {
  if (!dateIsValid(date)) return;
  state.date = date;
  $("date-filter").value = date;
  syncDateButton();
  closeCalendar();
  if (state.busy) state.pendingDateRefresh = true;
  else void refresh();
}
function closeCalendar(restoreFocus = false) {
  state.calendarOpen = false;
  state.calendarSerial++;
  show("date-calendar", false);
  $("date-trigger").setAttribute("aria-expanded", "false");
  if (restoreFocus) $("date-trigger").focus();
}
function openCalendar() {
  if (!state.ready) return;
  const base = dateIsValid(state.date) ? state.date : todayColombo();
  state.calendarYear = Number(base.slice(0,4));
  state.calendarMonth = Number(base.slice(5,7)) - 1;
  state.calendarOpen = true;
  $("date-trigger").setAttribute("aria-expanded", "true");
  show("date-calendar", true);
  void loadCalendarMonth();
}
function shiftCalendar(delta) {
  const dt = new Date(Date.UTC(state.calendarYear, state.calendarMonth + delta, 1));
  const yr = dt.getUTCFullYear();
  if (yr < 1 || yr > 9999) return;
  state.calendarYear = yr;
  state.calendarMonth = dt.getUTCMonth();
  void loadCalendarMonth();
}
function renderCalendar() {
  if (!state.calendarOpen) return;
  const year = state.calendarYear, month = state.calendarMonth;
  text("calendar-month", new Intl.DateTimeFormat("en-GB",
    {month:"long",year:"numeric",timeZone:"UTC"}).format(new Date(Date.UTC(year,month,1))));
  const result = calendarMonthDays(year, month, state.calendarMarkers);
  const grid = $("calendar-grid"); grid.replaceChildren();
  for (let i=0; i<result.firstWeekday; i++) {
    const blank = elem("span","","calendar-blank");
    blank.setAttribute("aria-hidden","true");
    grid.append(blank);
  }
  for (const entry of result.days) {
    const button = elem("button", "", "calendar-day");
    button.type = "button";
    button.textContent = String(entry.day);
    button.setAttribute("aria-label", entry.date + (entry.recorded ? ", data recorded" : ", no recorded summary"));
    button.setAttribute("aria-pressed", String(entry.date === state.date));
    if (entry.recorded) {
      button.classList.add("has-records");
      button.append(elem("span","","calendar-record-dot"));
    }
    if (entry.date === todayColombo()) button.classList.add("is-today");
    button.addEventListener("click", () => chooseBusinessDate(entry.date));
    grid.append(button);
  }
}
async function loadCalendarMonth() {
  if (!state.calendarOpen) return;
  const year = state.calendarYear, month = state.calendarMonth;
  const monthStart = String(year).padStart(4,"0")+"-"+String(month+1).padStart(2,"0")+"-01";
  const next = new Date(Date.UTC(year, month + 1, 1));
  const monthEnd = String(next.getUTCFullYear()).padStart(4,"0")+"-"+
    String(next.getUTCMonth()+1).padStart(2,"0")+"-01";
  // Show known dots immediately, then reconcile the entire month against RLS.
  state.calendarMarkers = new Set(state.summary.map(x=>x.business_date)
    .filter(day=>day.startsWith(monthStart.slice(0,7))));
  renderCalendar();
  text("calendar-message", "Checking recorded dates…");
  const seq = ++state.calendarSerial;
  try {
    const rows = await api.select("daily_financial_summary", {
      select:"business_date", workspace_id:"eq."+currentWorkspace(),
      and:"(business_date.gte."+monthStart+",business_date.lt."+monthEnd+")",
      order:"business_date.asc",limit:"1000"
    });
    if (rows.length >= 1000) throw Error("Too many dates in the selected month");
    if (seq !== state.calendarSerial || !state.calendarOpen) return;
    state.calendarMarkers = new Set(rows.map(x=>x.business_date));
    text("calendar-message", state.calendarMarkers.size ?
      state.calendarMarkers.size + " recorded date(s) this month" :
      "No recorded dates in this month");
    renderCalendar();
  } catch (error) {
    if (seq !== state.calendarSerial || !state.calendarOpen) return;
    text("calendar-message", "Could not check every date. Showing dates already loaded.");
    renderCalendar();
  }
}

function reportLine(label, value, extraClass = "") {
  const line = elem("div","","pdf-summary-line "+extraClass);
  line.append(elem("span",label), elem("strong",value));
  return line;
}
function reportCategory(name, rows, subtotal) {
  const wrapper = elem("section","","pdf-category");
  wrapper.append(elem("h3",name,"pdf-category-heading"));
  for (const [index,row] of rows.entries()) {
    const card = elem("div","","pdf-report-item");
    const left = elem("div","","pdf-report-item-info");
    left.append(elem("strong",(index+1)+". "+row.name,"pdf-item-name"),
      elem("span","Required: "+kg(row.requiredGrams)+"  |  Bought: "+
        kg(row.purchasedGrams),"pdf-item-quantity"));
    const right = elem("div","","pdf-item-cost");
    right.append(elem("small","Final cost"),elem("strong",lkr(row.lineCents)));
    card.append(left,right); wrapper.append(card);
  }
  wrapper.append(reportLine(name+" subtotal",lkr(subtotal),"pdf-subtotal"));
  return wrapper;
}
function openPdfPreview() {
  if (!state.ready || state.busy) {
    message("Please finish refreshing the selected business date before viewing the PDF.");
    return;
  }
  try {
    const report = completedPurchaseReport(state.data.requisitions,
      state.data.purchases, state.data.additions,
      state.itemById, state.categoryById);
    if (!report.completedCount) {
      message("No fully completed purchased items to include in the report for " + state.date + ".");
      return;
    }
    const matchingDay = state.summary.find(x => x.business_date === state.date);
    if (!matchingDay) throw Error("No financial summary for this day. Refresh and try again.");
    if (matchingDay.total_expenses_cents !== report.totalSpentCents) {
      throw Error("Purchase totals and cloud summary do not currently match. Refresh before generating the report.");
    }
    const cashReceived = matchingDay.cash_received_cents;
    const balance = cashReceived - report.totalSpentCents;
    const root = $("pdf-document"); root.replaceChildren();
    const heading = elem("header","","pdf-report-header");
    heading.append(elem("p","PELIYAGODA MARKET PRO","pdf-brand"),
      elem("h1","Completed purchases by category"),
      elem("p","Date: "+state.date));
    root.append(heading);
    for (const group of report.groups) {
      root.append(reportCategory(group.name,group.rows,group.subtotalCents));
    }
    const totals = elem("section","","pdf-summary");
    totals.append(
      reportLine("COMPLETED FINAL TOTAL ("+report.completedCount+")",
        lkr(report.completedTotalCents),"pdf-grand-total"),
      reportLine("CASH RECEIVED",lkr(cashReceived)),
      reportLine("TOTAL SPENT (ALL PURCHASES)",lkr(report.totalSpentCents)),
      reportLine("CASH BALANCE",lkr(balance),
        balance < 0n ? "pdf-balance-negative" : "pdf-balance-positive")
    );
    totals.append(elem("p",
      "Cash balance includes every purchased lot, even when an item is incomplete. "+
      "Completed final total includes only fully completed items.","pdf-report-note"));
    root.append(totals,elem("footer","Market Pro | "+state.date+" | Cloud read-only report","pdf-report-footer"));
    clearMessage();
    state.pdfOpen = true;
    document.body.classList.add("pdf-open");
    show("pdf-preview",true);
    $("pdf-preview").focus();
  } catch (error) { message(error); }
}
function closePdfPreview() {
  if (!state.pdfOpen) return;
  state.pdfOpen = false;
  document.body.classList.remove("pdf-open");
  show("pdf-preview",false);
  $("pdf-button").focus();
}

function renderAll() {
  renderMetrics();
  renderHistory();
  renderChart();
  renderKitchens();
  renderPurchases();
  renderChartControls();
}
function switchTab(which) {
  if (!["overview","kitchens","purchases","cash","charts"].includes(which)) return;
  state.tab = which;
  document.querySelectorAll(".tab").forEach(button => {
    button.classList.toggle("active", button.dataset.tab === which);
    button.setAttribute("aria-selected", String(button.dataset.tab === which));
  });
  for (const k of ["overview", "kitchens", "purchases", "cash", "charts"]) show("panel-" + k, k === which);
}
function setConnection(message) { text("last-updated", message); }
function stopPolling() {
  if (state.autoTimer !== null) clearInterval(state.autoTimer);
  state.autoTimer = null;
}
function startPolling() {
  stopPolling();
  state.autoTimer = setInterval(() => {
    if (document.visibilityState === "visible" && state.ready) void refresh();
  }, REFRESH_INTERVAL_MS);
}
function currentWorkspace() {
  if (!state.membership?.workspace_id) throw Error("Missing authorized workspace");
  return state.membership.workspace_id;
}
async function readCatalog() {
  const workspace = currentWorkspace();
  const [categories, items] = await Promise.all([
    api.select("categories", {
      select: "id,name", workspace_id: "eq." + workspace, deleted_at: "is.null", limit: "1000"
    }),
    api.select("items", {
      select: "id,name,category_id,is_active", workspace_id: "eq." + workspace,
      deleted_at: "is.null", limit: "1000"
    })
  ]);
  if (items.length === 1000 || categories.length === 1000) throw Error(
    "Catalog is larger than the report limit. Pagination is required before showing full totals."
  );
  state.categoryById = new Map(categories.map(x => [x.id, x]));
  state.itemById = new Map(items.map(x => [x.id, x]));
}
async function readSummary() {
  const rows = await api.select("daily_financial_summary", {
    select: "business_date,required_grams,initial_purchase_expenses_cents,additional_purchase_expenses_cents,total_expenses_cents,cash_received_cents,cash_balance_cents",
    workspace_id: "eq." + currentWorkspace(), order: "business_date.desc", limit: "365"
  });
  state.summary = rows.map(normalizeDailyRow);
}
async function readDate() {
  if (!dateIsValid(state.date)) throw Error("Invalid business date");
  const common = { business_date: "eq." + state.date,
    workspace_id: "eq." + currentWorkspace(), deleted_at: "is.null", limit: "1000" };
  const [requisitions, purchases, additions] = await Promise.all([
    api.select("requisitions", { ...common, select: "id,kitchen,item_id,quantity_grams" }),
    api.select("purchases", { ...common, select: "id,item_id,quantity_grams,price_cents_per_kg" }),
    api.select("purchase_additions", { ...common, select: "id,item_id,quantity_grams,price_cents_per_kg" })
  ]);
  if ([requisitions, purchases, additions].some(x => x.length >= 1000)) throw Error(
    "Day contains over 1000 records; full pagination needed. No partial totals shown."
  );
  state.data = { requisitions, purchases, additions };
}
async function refresh() {
  if (state.busy || !state.ready || document.visibilityState === "hidden") return;
  state.busy = true;
  $("refresh-button").disabled = true;
  clearMessage();
  try {
    await Promise.all([readCatalog(), readSummary()]);
    if (!state.date) state.date = state.summary[0]?.business_date || todayColombo();
    $("date-filter").value = state.date;
    syncDateButton();
    await readDate();
    renderAll();
    if (state.tab === "charts" && state.chartItemId) await loadItemHistory(true);
    setConnection("Updated " + new Date().toLocaleTimeString("en-GB", {
      timeZone: "Asia/Colombo", hour: "2-digit", minute: "2-digit", second: "2-digit"
    }) + " (Sri Lanka)");
  } catch (error) {
    message(error);
    setConnection("Refresh failed — showing last successful data");
  } finally {
    state.busy = false;
    $("refresh-button").disabled = false;
    if (state.pendingDateRefresh && state.ready) {
      state.pendingDateRefresh = false;
      void refresh();
    }
  }
}
async function enterDashboard() {
  const user = await api.user();
  if (!user?.id || !user?.email_confirmed_at) throw Error("Verified email required");
  state.user = user;
  state.membership = await api.membership(user.id);
  state.ready = true;
  state.date = "";
  text("workspace-label", "Signed in as " + user.email + " · " + state.membership.role);
  show("login-screen", false);
  show("dashboard-screen", true);
  show("logout-button", true);
  await refresh();
  startPolling();
}
async function signOut() {
  stopPolling();
  await api.logout();
  state.user = null;
  state.membership = null;
  state.ready = false;
  state.summary = [];
  state.calendarSerial++;
  state.pendingDateRefresh = false;
  state.calendarMarkers = new Set();
  closeCalendar();
  closePdfPreview();
  state.categoryById.clear();
  state.itemById.clear();
  state.data = { requisitions: [], purchases: [], additions: [] };
  state.chartSerial++;
  state.chartCategory = null;
  state.chartItemId = null;
  state.chartHistory = [];
  state.chartLoadedFor = null;
  for (const id of ["history-body","requisitions-body","purchases-body","trend-chart","kitchen-summary",
    "chart-categories","price-trend","quantity-trend"]) clear(id);
  show("price-charts", false);
  show("logout-button", false);
  show("dashboard-screen", false);
  show("login-screen", true);
  $("password").value = "";
}
async function init() {
  const form = $("login-form");
  form.addEventListener("submit", async event => {
    event.preventDefault();
    const submit = $("login-button");
    if (submit.disabled) return;
    submit.disabled = true;
    clearMessage("login-error");
    const email = $("email").value, password = $("password").value;
    try {
      await api.login(email, password);
      $("password").value = "";
      await enterDashboard();
    } catch (error) {
      await api.logout();
      message(error, "login-error");
    } finally { submit.disabled = false; }
  });
  $("logout-button").addEventListener("click", () => void signOut());
  $("refresh-button").addEventListener("click", () => void refresh());
  $("date-filter").addEventListener("change", event => {
    if (!dateIsValid(event.target.value)) return;
    chooseBusinessDate(event.target.value);
  });
  $("date-trigger").addEventListener("click", () =>
    state.calendarOpen ? closeCalendar() : openCalendar());
  $("calendar-prev").addEventListener("click", () => shiftCalendar(-1));
  $("calendar-next").addEventListener("click", () => shiftCalendar(1));
  $("calendar-today").addEventListener("click", () => chooseBusinessDate(todayColombo()));
  $("calendar-close").addEventListener("click", () => closeCalendar(true));
  document.addEventListener("pointerdown", event => {
    if (state.calendarOpen && !event.target.closest(".date-picker-field")) closeCalendar();
  });
  document.addEventListener("keydown", event => {
    if (event.key !== "Escape") return;
    if (state.pdfOpen) { closePdfPreview(); event.preventDefault(); }
    else if (state.calendarOpen) { closeCalendar(true); event.preventDefault(); }
  });
  $("pdf-button").addEventListener("click", openPdfPreview);
  $("pdf-close-button").addEventListener("click", closePdfPreview);
  $("pdf-print-button").addEventListener("click", () => window.print());
  document.querySelectorAll(".tab").forEach(button => {
    button.addEventListener("click", () => switchTab(button.dataset.tab));
  });
  $("chart-item").addEventListener("change", event => {
    const id = event.target.value;
    state.chartSerial++;
    state.chartItemId = id || null;
    state.chartHistory = [];
    state.chartLoadedFor = null;
    if (id) void loadItemHistory(false);
    else {
      show("price-charts", false);
      text("chart-status", "Select an item to view its historical charts.");
    }
  });
  document.addEventListener("visibilitychange", () => {
    if (document.visibilityState === "visible" && state.ready) void refresh();
  });
  if (api.session) {
    try { await enterDashboard(); }
    catch (_) { await signOut(); }
  }
}
void init();
