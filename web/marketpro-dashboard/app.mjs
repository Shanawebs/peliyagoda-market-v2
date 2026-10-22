import { DashboardApi } from "./auth-client.mjs";
import { REFRESH_INTERVAL_MS } from "./config.mjs";
import { exactCents, lkr, kg, lotCostCents, dateIsValid, normalizeDailyRow, groupKitchens } from "./core.mjs";

const $ = id => document.getElementById(id);
const api = new DashboardApi();
const state = {
  user: null, membership: null, summary: [], categoryById: new Map(), itemById: new Map(),
  data: { requisitions: [], purchases: [], additions: [] }, date: "",
  busy: false, ready: false, tab: "overview", autoTimer: null
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
  for (const row of state.summary) {
    const tr = elem("tr"), first = elem("td");
    const button = elem("button", formattedDate(row.business_date), "table-button");
    button.type = "button";
    button.addEventListener("click", () => {
      state.date = row.business_date;
      $("date-filter").value = state.date;
      refresh();
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
  const days = [...state.summary].reverse().slice(-7);
  if (!days.length) { target.append(elem("p", "No dated records yet.", "subtle")); return; }
  const max = Math.max(1, ...days.flatMap(x => [
    Math.abs(Number(x.total_expenses_cents)), Math.abs(Number(x.cash_received_cents))
  ]));
  for (const row of days) {
    const group = elem("div", "", "trend-group");
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
  if (!lots.length) return emptyRow(body, 5, "No purchases for selected date.");
  let subtotal = 0n;
  for (const lot of lots) {
    const cents = lotCostCents(lot.quantity_grams, lot.price_cents_per_kg);
    subtotal += cents;
    const tr = elem("tr"), first = elem("td");
    first.append(elem("span", lot.type, lot.type === "Additional" ? "tag tag-lot" : "tag"));
    tr.append(first, td(itemName(lot.item_id)), td(kg(lot.quantity_grams), "right"),
      td(lkr(lot.price_cents_per_kg), "right"), td(lkr(cents), "right"));
    body.append(tr);
  }
  const total = elem("tr");
  total.append(td("Total lots"), td(""), td(""), td(""), td(lkr(subtotal), "right"));
  body.append(total);
}
function renderAll() {
  renderMetrics();
  renderHistory();
  renderChart();
  renderKitchens();
  renderPurchases();
}
function switchTab(which) {
  if (!["overview","kitchens","purchases","cash"].includes(which)) return;
  state.tab = which;
  document.querySelectorAll(".tab").forEach(button => {
    button.classList.toggle("active", button.dataset.tab === which);
    button.setAttribute("aria-selected", String(button.dataset.tab === which));
  });
  for (const k of ["overview", "kitchens", "purchases", "cash"]) show("panel-" + k, k === which);
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
    await readDate();
    renderAll();
    setConnection("Updated " + new Date().toLocaleTimeString("en-GB", {
      timeZone: "Asia/Colombo", hour: "2-digit", minute: "2-digit", second: "2-digit"
    }) + " (Sri Lanka)");
  } catch (error) {
    message(error);
    setConnection("Refresh failed — showing last successful data");
  } finally {
    state.busy = false;
    $("refresh-button").disabled = false;
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
  state.categoryById.clear();
  state.itemById.clear();
  state.data = { requisitions: [], purchases: [], additions: [] };
  for (const id of ["history-body","requisitions-body","purchases-body","trend-chart","kitchen-summary"]) clear(id);
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
    state.date = event.target.value;
    void refresh();
  });
  document.querySelectorAll(".tab").forEach(button => {
    button.addEventListener("click", () => switchTab(button.dataset.tab));
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
