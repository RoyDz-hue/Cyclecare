import { adminJson, getAdminSession } from './supabaseAdmin.js';

// Admin Command client: prompt -> plan card -> approval -> executed steps.
const call = (config, body, onSessionChange) =>
  adminJson(config, 'functions/v1/admin-command', { method: 'POST', body: JSON.stringify(body) }, onSessionChange);

export const commandPlan = (config, prompt, onSessionChange) => call(config, { action: 'plan', prompt }, onSessionChange);
export const commandDecide = (config, runId, approve, execute, onSessionChange) =>
  call(config, { action: 'decide', run_id: runId, approve, execute }, onSessionChange);
export const commandExecute = (config, runId, onSessionChange) => call(config, { action: 'execute', run_id: runId }, onSessionChange);
export const commandSuggestions = (config, onSessionChange) => call(config, { action: 'suggest' }, onSessionChange);
export const commandHistory = (config, onSessionChange) => call(config, { action: 'list' }, onSessionChange);
export const commandMessage = (config, prompt, onSessionChange) => call(config, { action: 'message', prompt }, onSessionChange);
export const commandHistoryList = (config, onSessionChange) => call(config, { action: 'history' }, onSessionChange);

// The admin's user id comes from their own session JWT; it keys the local
// per-admin thread cache. Never trusted for authorization - the backend
// re-derives identity on every call.
export function adminIdFromConfig(config) {
  try {
    const token = String(config?.accessToken || getAdminSession()?.access_token || '');
    const payload = JSON.parse(atob(token.split('.')[1].replace(/-/g, '+').replace(/_/g, '/')));
    return String(payload?.sub || 'unknown');
  } catch { return 'unknown'; }
}

// Pure helpers (unit tested).

export function stepKind(tool) {
  const spec = SPEC_BY_NAME.get(tool || '');
  if (!spec) return 'UNKNOWN';
  return spec.kind;
}

export function planRiskLevel(steps) {
  if (!Array.isArray(steps) || steps.length === 0) return 'empty';
  if (steps.some((s) => stepKind(s?.tool) === 'RISKY')) return 'risky';
  if (steps.some((s) => stepKind(s?.tool) === 'WRITE')) return 'write';
  return 'read';
}

export function describeStep(step) {
  const tool = step?.tool || '';
  const args = step?.args || {};
  if (step?.label) return step.label;
  const targets = Array.isArray(args.targets) ? `${args.targets.length} target${args.targets.length === 1 ? '' : 's'}` : '';
  switch (tool) {
    case 'get_user_insights': return `Member insights (${args.focus || 'all'})`;
    case 'adjust_coins': return `${args.delta >= 0 ? 'Gift' : 'Deduct'} ${Math.abs(args.delta || 0)} coins ${targets}`.trim();
    case 'set_user_status': return `Set status ${args.status || ''} ${targets}`.trim();
    case 'set_user_tier': return `Set tier ${args.tier || ''} ${targets}`.trim();
    case 'set_coin_packages': return `Replace price list (${(args.packages || []).length} packages)`;
    case 'send_notice': return `Send notice: ${args.title || 'CycleCare Notice'}`;
    default: return tool.replace(/_/g, ' ');
  }
}

// Exact content a step will send/write, driven by the shared capability spec
// (adminToolSpec.json, mirrored from the edge function) so the review card
// shows the bound args for ANY tool, not a hardcoded few.
import SPEC_DATA from './adminToolSpec.json' with { type: 'json' };
const SPEC_BY_NAME = new Map(SPEC_DATA.tools.map((t) => [t.name, t]));

function formatReviewValue(key, value, args) {
  // Derived fields are computed from the bound snapshot, not read from args.
  if (key === '__member_count') return Array.isArray(args.recipients) ? `${args.recipients.length} members` : null;
  if (key === '__total') {
    const n = Number(args.delta);
    return Array.isArray(args.recipients) && Number.isFinite(n) ? `${Math.round(Math.abs(n) * args.recipients.length * 1000) / 1000} coins in total` : null;
  }
  if (value === undefined || value === null || value === '') return null;
  if (key === 'audience') {
    const aud = String(value).toUpperCase();
    if (aud === 'ALL') return 'All members except banned/suspended';
    if (Array.isArray(args.targets) && args.targets.length) return null; // names line covers it
    return aud;
  }
  if (key === 'target_names') return Array.isArray(value) && value.length ? value.join(', ') : null;
  if (key === 'targets') {
    if (Array.isArray(args.target_names) && args.target_names.length) return null; // names shown instead
    return Array.isArray(value) ? `${value.length} selected` : null;
  }
  if (key === 'delta') {
    const n = Number(value);
    if (!Number.isFinite(n)) return null;
    if (Array.isArray(args.targets)) return `${n < 0 ? 'Deduct' : 'Gift'} ${Math.abs(n)} coins`;
    return `${Math.abs(n)} coins`; // gift_all/deduct_all direction is fixed by the bound tool name
  }
  if (key === 'packages') return Array.isArray(value) ? `${value.length} packages` : null;
  if (key === 'enabled' || key === 'is_active') return value ? 'Yes' : 'No';
  if (Array.isArray(value)) return value.join(', ');
  return String(value);
}

// A WRITE/RISKY step is reviewable only when its bound args produce every
// material fact. Unknown tool or any missing essential content: the card must
// not offer Confirm. Plain wording, no internals, no blind retry advice.
export function stepReviewBlocker(step) {
  const spec = SPEC_BY_NAME.get(step?.tool || '');
  if (!spec) return 'I could not prepare a clear review of this change, so nothing was done and it cannot be approved.';
  if (spec.kind === 'READ') return null;
  const lines = stepContentLines(step);
  const rendered = new Set(lines.map((l) => l.k));
  // Every label group covering an essential arg must render a real value.
  for (const arg of spec.essentialArgs) {
    const fields = spec.reviewFields.filter((f) => f.key === arg || (arg === 'targets' && f.key === 'target_names'));
    if (fields.length === 0) continue;
    const covered = fields.some((f) => rendered.has(f.label));
    if (!covered) {
      const gap = fields[0].label.toLowerCase();
      return `I could not prepare a clear review of this change - ${gap} is missing - so nothing was done and it cannot be approved.`;
    }
  }
  return null;
}

export function planReviewBlocker(steps) {
  if (!Array.isArray(steps)) return 'This plan is empty.';
  for (const step of steps) {
    const blocked = stepReviewBlocker(step);
    if (blocked) return blocked;
  }
  return null;
}

export function stepContentLines(step) {
  const spec = SPEC_BY_NAME.get(step?.tool || '');
  if (!spec) return [];
  const args = { ...(step?.args || {}) };
  if (step?.tool === 'send_notice' && !args.title) args.title = 'CycleCare Notice'; // executor default - show what will actually send
  const lines = [];
  const seen = new Set();
  for (const field of spec.reviewFields) {
    if (seen.has(field.label)) continue;
    const v = formatReviewValue(field.key, args[field.key], args);
    if (v === null) continue;
    seen.add(field.label);
    lines.push({ k: field.label, v });
  }
  if (step?.tool === 'send_notice') lines.push({ k: 'Channel', v: 'In-app notice + push notification' });
  return lines;
}

export function buildLocalSuggestions(insights) {
  const chips = [];
  const active = insights?.most_active?.[0];
  const inactive = insights?.inactive?.[0];
  if (active?.user_id) chips.push({ label: 'Reward the most committed member', prompt: `Check the most active member and gift ${active.user_id} 500 coins with a thank-you notice` });
  if (inactive?.user_id) chips.push({ label: 'Win back inactive members', prompt: 'Show me the most inactive members and send them a warm CycleCare team notice inviting them back' });
  chips.push({ label: 'Richest members', prompt: 'Show me the richest users by coin balance' });
  chips.push({ label: 'Newest members', prompt: 'Show me the newest users' });
  chips.push({ label: 'Pricing check', prompt: 'Show me the current coin packages and cosmetic prices' });
  chips.push({ label: 'Moderation queue', prompt: 'Show me the flagged messages waiting for review' });
  chips.push({ label: 'Premium vs free', prompt: 'Compare premium vs free members and show the tier split' });
  return chips.slice(0, 6);
}

export async function preloadCommandThread(config) {
  const id = adminIdFromConfig(config);
  if (id === 'unknown') return;
  const res = await commandHistoryList(config);
  if (Array.isArray(res?.messages)) {
    try { localStorage.setItem(`admin-command-thread-${id}`, JSON.stringify(res.messages)); } catch { /* best-effort */ }
  }
}

// Plan card lifecycle: a card is closed when the admin decided it OR when the
// saved run is no longer waiting for a decision. The saved run status wins
// over the message meta, so a card can never look actionable when the backend
// would refuse the click (expired, already decided, or decided out-of-band).
// Executor semantics (admin-command executeRun): 'rejected' never executed, so
// "no change was made" is proven. 'failed' means at least one step failed but
// earlier steps may have applied - never claim zero writes. 'unconfirmed'
// means accepted but not verified; repeating could double-apply.
export const PLAN_OUTCOME = {
  done: { label: 'Completed', tone: 'ok' },
  rejected: { label: 'Cancelled - no change was made', tone: 'muted' },
  expired: { label: 'Expired - no change was made', tone: 'muted' },
  failed: { label: 'Failed - check the result message before trying again; earlier steps may have applied', tone: 'bad' },
  unconfirmed: { label: 'Accepted but not confirmed - do not repeat; review the current state', tone: 'bad' },
  approved: { label: 'Approved - working on it...', tone: 'muted' },
  executing: { label: 'Approved - working on it...', tone: 'muted' },
  already_started: { label: 'Already in progress', tone: 'muted' },
  unknown: { label: 'Outcome unclear - ask me to check before trying again', tone: 'bad' },
};

export function planCardState(meta) {
  const runStatus = meta?.run_status;
  const closedByRun = runStatus != null && runStatus !== 'proposed';
  const decided = meta?.decided === true || closedByRun;
  const outcome = closedByRun ? (PLAN_OUTCOME[runStatus] || PLAN_OUTCOME.unknown) : null;
  return { decided, outcome };
}
