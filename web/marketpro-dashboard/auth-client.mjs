import { SUPABASE_URL, PUBLISHABLE_KEY } from "./config.mjs";

const SESSION_KEY = "marketpro_dashboard_auth_session_v1";
const MAX_ROWS = 1000;
const allowedTables = new Set([
  "workspace_members", "workspaces", "categories", "items", "requisitions",
  "purchases", "purchase_additions", "daily_cash_received", "daily_financial_summary"
]);

export class DashboardApi {
  constructor() {
    this.session = null;
    this.refreshPromise = null;
    try {
      const saved = sessionStorage.getItem(SESSION_KEY);
      this.session = saved ? JSON.parse(saved) : null;
    } catch (_) {
      this.session = null;
    }
  }

  clear() {
    this.session = null;
    sessionStorage.removeItem(SESSION_KEY);
  }

  save(response) {
    if (!response?.access_token || !response?.refresh_token) throw Error("Invalid Auth response");
    this.session = {
      access_token: response.access_token,
      refresh_token: response.refresh_token,
      expiry_ms: Date.now() + Math.max(20, Number(response.expires_in || 3600)) * 1000
    };
    sessionStorage.setItem(SESSION_KEY, JSON.stringify(this.session));
  }

  async call(path, { method = "GET", data, token, retry = false } = {}) {
    const url = new URL(path, SUPABASE_URL);
    if (url.origin !== new URL(SUPABASE_URL).origin) throw Error("Unexpected API origin");
    const headers = {
      apikey: PUBLISHABLE_KEY,
      Accept: "application/json"
    };
    if (token) headers.Authorization = "Bearer " + token;
    if (data !== undefined) headers["Content-Type"] = "application/json";
    let response;
    try {
      response = await fetch(url, {
        method, headers, body: data === undefined ? undefined : JSON.stringify(data),
        cache: "no-store", redirect: "error"
      });
    } catch (_) {
      throw Error("Cannot reach Supabase. Check your internet connection.");
    }
    if (!response.ok) {
      if (response.status === 401 && retry && this.session?.refresh_token) {
        await this.refresh();
        return this.call(path, { method, data, token: this.session.access_token });
      }
      if (response.status === 401 || response.status === 403) {
        throw Error("Session expired or access denied. Please sign in again.");
      }
      throw Error("Cloud request failed (HTTP " + response.status + ").");
    }
    if (response.status === 204) return null;
    return response.json();
  }

  async login(email, password) {
    if (!email.includes("@") || !password) throw Error("Email and password are required");
    const data = await this.call("/auth/v1/token?grant_type=password", {
      method: "POST", data: { email: email.trim(), password }
    });
    this.save(data);
    return this.user();
  }

  async refresh() {
    if (this.refreshPromise) return this.refreshPromise;
    this.refreshPromise = (async () => {
      const token = this.session?.refresh_token;
      if (!token) throw Error("Please sign in to continue.");
      try {
        const response = await this.call("/auth/v1/token?grant_type=refresh_token", {
          method: "POST", data: { refresh_token: token }
        });
        this.save(response);
      } catch (error) {
        this.clear();
        throw error;
      }
    })();
    try { await this.refreshPromise; } finally { this.refreshPromise = null; }
  }

  async accessToken() {
    if (!this.session) throw Error("Please sign in to continue.");
    if (Date.now() > this.session.expiry_ms - 45000) await this.refresh();
    return this.session.access_token;
  }

  async user() {
    return this.call("/auth/v1/user", { token: await this.accessToken(), retry: true });
  }

  async membership(userId) {
    const rows = await this.select("workspace_members", {
      select: "workspace_id,role", user_id: "eq." + userId, limit: "2"
    });
    if (rows.length !== 1) throw Error("No authorized Market Pro workspace found.");
    return rows[0];
  }

  async select(table, params = {}) {
    if (!allowedTables.has(table)) throw Error("Unexpected API table");
    const url = new URL("/rest/v1/" + table, SUPABASE_URL);
    Object.entries(params).forEach(([key, value]) => url.searchParams.set(key, String(value)));
    if (!url.searchParams.has("limit")) url.searchParams.set("limit", String(MAX_ROWS));
    return this.call(url.pathname + url.search, {
      token: await this.accessToken(), retry: true
    });
  }

  async logout() {
    try {
      if (this.session) await this.call("/auth/v1/logout", {
        method: "POST", token: await this.accessToken()
      });
    } catch (_) {
      // Always clear local session, including when offline.
    } finally { this.clear(); }
  }
}
