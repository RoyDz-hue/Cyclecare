// Admin Control Center client: typed wrappers over the role-checked RPCs.
// Server-side validation is authoritative; these mirrors give fast feedback
// and keep obviously-invalid writes (negative prices, bad ids) off the wire.
import { adminJson } from './supabaseAdmin.js';

export const EDITABLE_CONFIG_KEYS = ['coin_packages', 'economy_multipliers', 'payment_gateway'];

export function validateCoinPackages(packages) {
  if (!Array.isArray(packages) || packages.length < 1 || packages.length > 20) {
    return 'Coin packages must be a list of 1 to 20 offers.';
  }
  const seen = new Set();
  for (const pkg of packages) {
    if (!pkg || typeof pkg !== 'object') return 'Every package must be an object.';
    if (!/^[a-z0-9_]{3,40}$/.test(pkg.id || '')) return 'Every package needs a lowercase id like pkg_starter.';
    if (seen.has(pkg.id)) return `Package id "${pkg.id}" is used twice.`;
    seen.add(pkg.id);
    if (!pkg.title || String(pkg.title).length > 60) return 'Every package needs a title (max 60 chars).';
    const coins = Number(pkg.coins);
    if (!Number.isInteger(coins) || coins < 1 || coins > 1000000) return `Package ${pkg.id} must grant 1-1,000,000 whole coins.`;
    const bonus = Number(pkg.bonusCoins || 0);
    if (!Number.isFinite(bonus) || bonus < 0 || bonus > 1000000) return `Package ${pkg.id} bonus must be 0-1,000,000.`;
    const kes = Number(pkg.priceKes || 0);
    const usd = Number(pkg.priceUsd || 0);
    if (!Number.isFinite(kes) || kes < 0 || kes > 1000000) return `Package ${pkg.id} KES price cannot be negative.`;
    if (!Number.isFinite(usd) || usd < 0 || usd > 10000) return `Package ${pkg.id} USD price cannot be negative.`;
    if (kes === 0 && usd === 0) return `Package ${pkg.id} needs a price in KES or USD.`;
  }
  return null;
}

export function validateMultipliers(multipliers) {
  if (!multipliers || typeof multipliers !== 'object' || Array.isArray(multipliers)) return 'Multipliers must be an object.';
  for (const [key, value] of Object.entries(multipliers)) {
    const num = Number(value);
    if (!Number.isFinite(num) || num < 0 || num > 100) return `Multiplier "${key}" must be a number between 0 and 100.`;
  }
  return null;
}

export function validateCosmetic(item) {
  if (!/^[a-z0-9_]{3,60}$/.test(item.item_id || '')) return 'Item id must be lowercase letters, numbers, underscores.';
  if (!['ARTIFACT', 'FRAME', 'THEME', 'BADGE'].includes(String(item.kind || '').toUpperCase())) return 'Kind must be ARTIFACT, FRAME, THEME or BADGE.';
  if (!item.name || String(item.name).length > 80) return 'Name is required (max 80 chars).';
  const price = Number(item.price_coins);
  if (!Number.isInteger(price) || price < 0 || price > 1000000) return 'Price must be a whole number of coins (0-1,000,000).';
  if (!['CLASSIC', 'RARE', 'EPIC', 'LEGENDARY'].includes(String(item.rarity || '').toUpperCase())) return 'Rarity must be CLASSIC, RARE, EPIC or LEGENDARY.';
  return null;
}

export function validateConfigValue(key, value) {
  if (key === 'coin_packages') return validateCoinPackages(value);
  if (key === 'economy_multipliers') return validateMultipliers(value);
  if (!EDITABLE_CONFIG_KEYS.includes(key)) return 'This configuration key is not editable from the admin panel.';
  return null;
}

const rpc = (config, name, body, onSessionChange) =>
  adminJson(config, `rest/v1/rpc/${name}`, { method: 'POST', body: JSON.stringify(body ?? {}) }, onSessionChange);

export const fetchControlData = (config, onSessionChange) => rpc(config, 'admin_get_control_data', {}, onSessionChange);
export const updateAppConfig = (config, key, value, onSessionChange) => rpc(config, 'admin_update_app_config', { p_key: key, p_value: value }, onSessionChange);
export const setPersonasEnabled = (config, enabled, onSessionChange) => rpc(config, 'admin_set_personas_enabled', { p_enabled: enabled }, onSessionChange);
export const updatePersona = (config, persona, onSessionChange) => rpc(config, 'admin_update_persona', {
  p_persona_id: persona.persona_id,
  p_daily_post_cap: persona.daily_post_cap ?? null,
  p_activity_weight: persona.activity_weight ?? null,
  p_is_active: persona.is_active ?? null
}, onSessionChange);
export const upsertCosmetic = (config, item, onSessionChange) => rpc(config, 'admin_upsert_cosmetic', {
  p_item_id: item.item_id,
  p_kind: String(item.kind || '').toUpperCase(),
  p_name: item.name,
  p_description: item.description || '',
  p_price_coins: Number(item.price_coins),
  p_rarity: String(item.rarity || 'CLASSIC').toUpperCase(),
  p_is_active: Boolean(item.is_active),
  p_sort_order: Number(item.sort_order || 0),
  p_premium_included: Boolean(item.premium_included)
}, onSessionChange);
export const moderateMessage = (config, messageId, action, onSessionChange) =>
  rpc(config, 'admin_moderate_message', { p_message_id: messageId, p_action: action }, onSessionChange);
export const applyUserAction = (config, action, targetUserId, payload, onSessionChange) =>
  rpc(config, 'admin_apply_action', { p_action: action, p_target_user_id: targetUserId, p_payload: payload }, onSessionChange);
