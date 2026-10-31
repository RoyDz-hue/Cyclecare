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
export const RISKY_TOOLS = new Set(['set_coin_packages', 'adjust_coins', 'set_user_status', 'set_user_tier', 'set_guides_enabled', 'moderate_message']);
export const WRITE_TOOLS = new Set(['set_cosmetic_price', 'gift_artifact', 'send_notice', 'update_guide']);

export function stepKind(tool) {
  if (RISKY_TOOLS.has(tool)) return 'RISKY';
  if (WRITE_TOOLS.has(tool)) return 'WRITE';
  return 'READ';
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

// Exact content a step will send/write, shown on the review card so approval
// is informed by the bound args (what executes), not just a summary label.
export function stepContentLines(step) {
  const tool = step?.tool || '';
  const args = step?.args || {};
  if (tool === 'send_notice') {
    const lines = [];
    lines.push({ k: 'Title', v: String(args.title || 'CycleCare Notice') });
    lines.push({ k: 'Message', v: String(args.body || '') });
    if (args.audience) {
      const aud = String(args.audience).toUpperCase();
      lines.push({ k: 'Audience', v: aud === 'ALL' ? 'All members except banned/suspended' : aud });
    } else if (Array.isArray(args.targets) && args.targets.length) {
      const names = Array.isArray(args.target_names) && args.target_names.length === args.targets.length ? args.target_names : null;
      lines.push({ k: 'Audience', v: names ? names.join(', ') : `${args.targets.length} selected member${args.targets.length === 1 ? '' : 's'}` });
    } else {
      lines.push({ k: 'Audience', v: 'ALL' });
    }
    lines.push({ k: 'Channel', v: 'In-app notice + push notification' });
    return lines;
  }
  return [];
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
