export const AI_PROVIDER_CATALOG = Object.freeze([
  Object.freeze({ id: 'OPENROUTER', name: 'OpenRouter', purpose: 'Model routing for mobile AI' }),
  Object.freeze({ id: 'GEMINI', name: 'Gemini', purpose: 'Google Gemini guidance' }),
  Object.freeze({ id: 'COHERE', name: 'Cohere', purpose: 'Cohere Command guidance' })
]);

export const AI_PROVIDER_IDS = Object.freeze(AI_PROVIDER_CATALOG.map(({ id }) => id));

// HYBRID remains unavailable in this production-compatible Admin build.
// Candidate v3 routing and consent exist on the feature branch, not in production.
export const HYBRID_PROVIDER_OPTION = Object.freeze({
  id: 'HYBRID',
  name: 'Hybrid',
  purpose: 'Multi-provider routing (not available)',
  disabledReason: 'Unavailable: consent-safe Hybrid stays closed until the reviewed v3 backend, provider-policy approval, per-provider consent and staging acceptance are released.'
});

export function validateAiProviderSelection(provider, { configuredIds } = {}) {
  const candidate = typeof provider === 'string' ? provider.trim().toUpperCase() : '';
  if (!candidate) return { ok: false, code: 'MISSING_PROVIDER', message: 'Choose a configured AI provider.' };
  if (candidate === HYBRID_PROVIDER_OPTION.id) {
    return { ok: false, code: 'HYBRID_UNSUPPORTED', message: HYBRID_PROVIDER_OPTION.disabledReason };
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
