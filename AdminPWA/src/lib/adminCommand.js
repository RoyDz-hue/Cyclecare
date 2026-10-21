import { adminJson } from './supabaseAdmin.js';

// Admin Command client: prompt -> plan card -> approval -> executed steps.
const call = (config, body, onSessionChange) =>
  adminJson(config, 'functions/v1/admin-command', { method: 'POST', body: JSON.stringify(body) }, onSessionChange);

export const commandPlan = (config, prompt, onSessionChange) => call(config, { action: 'plan', prompt }, onSessionChange);
export const commandDecide = (config, runId, approve, execute, onSessionChange) =>
  call(config, { action: 'decide', run_id: runId, approve, execute }, onSessionChange);
export const commandExecute = (config, runId, onSessionChange) => call(config, { action: 'execute', run_id: runId }, onSessionChange);
export const commandSuggestions = (config, onSessionChange) => call(config, { action: 'suggest' }, onSessionChange);
export const commandHistory = (config, onSessionChange) => call(config, { action: 'list' }, onSessionChange);

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
