import { adminJson } from './supabaseAdmin.js';

// FX client + pure helpers (mirrors of the server-side rules, for preview/tests).
const rpc = (config, name, body, onSessionChange) =>
  adminJson(config, `rest/v1/rpc/${name}`, { method: 'POST', body: JSON.stringify(body ?? {}) }, onSessionChange);

export const getFxRates = (config, onSessionChange) => rpc(config, 'admin_get_fx_rates', {}, onSessionChange);
export const getFxStatus = (config, onSessionChange) => rpc(config, 'fx_status', {}, onSessionChange);
export const setFxRate = (config, args, onSessionChange) =>
  rpc(config, 'admin_set_fx_rate', {
    p_quote_currency: args.quote, p_rate_per_usd: args.rate ?? null,
    p_admin_override: args.override ?? null, p_clear_override: args.clearOverride === true,
    p_is_active: typeof args.isActive === 'boolean' ? args.isActive : null,
  }, onSessionChange);
export const refreshFx = (config, onSessionChange) =>
  adminJson(config, 'functions/v1/fx-refresh', { method: 'POST', body: '{}' }, onSessionChange);

// Mirror of public.psych_round for previews.
export function psychRound(value) {
  const v = Number(value);
  if (!Number.isFinite(v) || v <= 0) return null;
  if (v >= 1000) return Math.floor(v / 100) * 100 + 99;
  if (v >= 100) return Math.floor(v / 10) * 10 + 9;
  if (v >= 10) return Math.floor(v) + 0.9;
  return Math.floor(v * 10) / 10 + 0.09;
}

export function formatFxAmount(currency, amount) {
  if (!Number.isFinite(Number(amount))) return '';
  const whole = Number(amount) === Math.trunc(Number(amount));
  const body = Number(amount) >= 100 || whole
    ? Number(amount).toLocaleString('en-US', { maximumFractionDigits: 0 })
    : Number(amount).toFixed(2);
  return `${currency} ${body}`;
}

// Effective rate and preview for one quote currency.
export function fxPreview(row, usdPrice) {
  const rate = Number(row?.admin_override ?? row?.rate_per_usd);
  if (!Number.isFinite(rate) || rate <= 0 || !Number.isFinite(Number(usdPrice))) return null;
  return { rate, display: psychRound(Number(usdPrice) * rate) };
}

export function describeRefresh(row) {
  if (!row) return 'No refresh has run yet.';
  const when = row.created_at ? new Date(row.created_at).toLocaleString() : 'unknown time';
  const who = row.triggered_by === 'admin' ? 'admin ping' : 'app (lazy daily)';
  const status = row.status === 'ok' ? 'succeeded' : row.status === 'partial' ? 'partially succeeded' : 'failed';
  return `${when} - ${status} via ${row.source || 'unknown source'} (${who}, ${row.quotes_updated ?? 0} currencies)`;
}
