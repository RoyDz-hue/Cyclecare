export const AI_PROVIDER_CATALOG = Object.freeze([
  Object.freeze({ id: 'OPENROUTER', name: 'OpenRouter', purpose: 'Model routing for mobile AI' }),
  Object.freeze({ id: 'GEMINI', name: 'Gemini', purpose: 'Google Gemini guidance' }),
  Object.freeze({ id: 'COHERE', name: 'Cohere', purpose: 'Cohere Command guidance' }),
  Object.freeze({ id: 'LAJIWA', name: 'Lajiwa', purpose: 'Lajiwa self-hosted guidance' })
]);

export const AI_PROVIDER_IDS = Object.freeze(AI_PROVIDER_CATALOG.map(({ id }) => id));

// HYBRID is live: the server balances consented providers with failover.
// The edge layer enforces consent, review and runtime gates on every save.
export const HYBRID_PROVIDER_OPTION = Object.freeze({
  id: 'HYBRID',
  name: 'Hybrid Bridge',
  purpose: 'Balanced multi-provider routing with automatic failover',
  disabledReason: 'Hybrid needs at least one provider key saved on the server.'
});

export function validateAiProviderSelection(provider, { configuredIds } = {}) {
  const candidate = typeof provider === 'string' ? provider.trim().toUpperCase() : '';
  if (!candidate) return { ok: false, code: 'MISSING_PROVIDER', message: 'Choose a configured AI provider.' };
  if (candidate === HYBRID_PROVIDER_OPTION.id) {
    if (configuredIds && configuredIds.size === 0) {
      return { ok: false, code: 'HYBRID_UNCONFIGURED', message: HYBRID_PROVIDER_OPTION.disabledReason };
    }
    return { ok: true, provider: candidate };
  }
  if (candidate === 'POLLINATIONS') {
    return { ok: false, code: 'IMAGE_ONLY_PROVIDER', message: 'Pollinations is available for image generation only, not mobile AI guidance.' };
  }
  if (!AI_PROVIDER_IDS.includes(candidate)) {
    return { ok: false, code: 'INVALID_PROVIDER', message: 'Choose one of the supported mobile AI providers.' };
  }
  if (configuredIds && !configuredIds.has(candidate)) {
    return { ok: false, code: 'UNCONFIGURED_PROVIDER', message: `${candidate} is not configured. Choose a provider with a recorded server credential.` };
  }
  return { ok: true, provider: candidate };
}
