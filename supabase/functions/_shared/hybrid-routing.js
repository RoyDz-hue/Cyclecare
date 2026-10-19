export const HYBRID_PROTOCOL_REVISION = 'hybrid-v1';
export const HYBRID_DEFAULT_ENABLED = false;
export const HYBRID_MAX_ATTEMPTS = 3;
export const HYBRID_MAX_DEADLINE_MS = 30_000;

const SUPPORTED_CHAT_PROVIDERS = Object.freeze(['COHERE', 'GEMINI', 'OPENROUTER']);
const TERMINAL_FAILURES = new Set(['safety_refusal', 'malformed_output', 'untrusted_output']);

export function hybridReadiness({ enabled = false, runtimeReady = false, contractRevision, consentProtocolRevision } = {}) {
  if (!enabled) return { configured: false, validated: false, available: false, reason: 'Disabled by the server default gate; client requests cannot enable Hybrid.' };
  if (!runtimeReady) return { configured: false, validated: false, available: false, reason: 'Unavailable: server runtime readiness has not been reviewed.' };
  if (contractRevision !== HYBRID_PROTOCOL_REVISION || consentProtocolRevision !== HYBRID_PROTOCOL_REVISION) {
    return { configured: false, validated: false, available: false, reason: 'Unavailable: routing and consent revisions do not match the reviewed Hybrid protocol.' };
  }
  return { configured: true, validated: true, available: true, reason: 'Reviewed server-side Hybrid contract is ready.' };
}

function providerId(value) {
  const id = typeof value === 'string' ? value.trim().toUpperCase() : '';
  return SUPPORTED_CHAT_PROVIDERS.includes(id) ? id : null;
}

function consentFor(consents, provider) {
  if (!consents || typeof consents !== 'object' || Array.isArray(consents)) return undefined;
  const value = consents[provider];
  if (!value || typeof value !== 'object') return undefined;
  return value;
}

/** Validate a server-created plan. No request field can create or widen this plan. */
export function validateHybridPlan(plan, { now = Date.now(), enabled = HYBRID_DEFAULT_ENABLED } = {}) {
  if (!enabled) return { ok: false, code: 'hybrid_disabled' };
  if (!plan || typeof plan !== 'object' || Array.isArray(plan)) return { ok: false, code: 'plan_required' };
  if (plan.routing_revision !== HYBRID_PROTOCOL_REVISION || plan.consent_revision !== HYBRID_PROTOCOL_REVISION) return { ok: false, code: 'revision_mismatch' };
  if (!Array.isArray(plan.chatproviders) || plan.chatproviders.length < 1 || plan.chatproviders.length > HYBRID_MAX_ATTEMPTS) return { ok: false, code: 'allowlist_invalid' };
  const providers = plan.chatproviders.map(providerId);
  if (providers.some((id) => !id) || new Set(providers).size !== providers.length) return { ok: false, code: 'provider_allowlist_invalid' };
  if (!plan.consents || typeof plan.consents !== 'object' || Array.isArray(plan.consents)) return { ok: false, code: 'consent_map_required' };
  for (const provider of providers) {
    const consent = consentFor(plan.consents, provider);
    if (consent?.granted !== true || consent.provider !== provider || consent.revision !== HYBRID_PROTOCOL_REVISION || consent.revoked === true) {
      return { ok: false, code: 'provider_consent_missing', provider };
    }
  }
  const deadlineMs = Number(plan.deadline_ms);
  if (!Number.isInteger(deadlineMs) || deadlineMs < 1 || deadlineMs > HYBRID_MAX_DEADLINE_MS) return { ok: false, code: 'deadline_invalid' };
  const deadlineAt = Number(plan.deadline_at ?? (now + deadlineMs));
  if (!Number.isFinite(deadlineAt) || deadlineAt <= now || deadlineAt > now + deadlineMs) return { ok: false, code: 'deadline_expired' };
  return { ok: true, providers, deadlineAt, maxAttempts: providers.length };
}

function remainingSignal(signal, deadlineAt) {
  const remaining = deadlineAt - Date.now();
  if (remaining <= 0) return null;
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), remaining);
  if (signal) signal.addEventListener('abort', () => controller.abort(), { once: true });
  return { signal: controller.signal, stop: () => clearTimeout(timer) };
}

/** Sequentially invoke providers. Safety/malformed results are terminal and never fall back. */
export async function executeHybridPlan(plan, { enabled = HYBRID_DEFAULT_ENABLED, now = Date.now(), runProvider, consumeQuota, liveConsent } = {}) {
  const checked = validateHybridPlan(plan, { enabled, now });
  if (!checked.ok) return { ok: false, code: checked.code, provider: checked.provider };
  if (typeof runProvider !== 'function') return { ok: false, code: 'runner_missing' };
  // A static plan is not proof that consent is still valid. Enabled execution
  // therefore requires a server-owned live check; the default remains closed.
  if (typeof liveConsent !== 'function') return { ok: false, code: 'live_consent_check_missing' };
  let attempts = 0;
  for (const provider of checked.providers) {
    if (Date.now() >= checked.deadlineAt || attempts >= checked.maxAttempts) return { ok: false, code: 'deadline_exceeded' };
    attempts += 1;
    const consentTimed = remainingSignal(undefined, checked.deadlineAt);
    if (!consentTimed) return { ok: false, code: 'deadline_exceeded', provider, attempts };
    try {
      const consentAbort = new Promise((_, reject) => consentTimed.signal.addEventListener('abort', () => reject(Object.assign(new Error('deadline'), { name: 'AbortError' })), { once: true }));
      const consent = await Promise.race([liveConsent(provider, { signal: consentTimed.signal, attempt: attempts }), consentAbort]);
      consentTimed.stop();
      if (consent?.granted !== true || consent.provider !== provider || consent.revision !== HYBRID_PROTOCOL_REVISION || consent.revoked === true) return { ok: false, code: 'provider_consent_missing', provider, attempts };
    } catch (error) {
      consentTimed.stop();
      return { ok: false, code: error?.name === 'AbortError' ? 'deadline_exceeded' : 'provider_consent_missing', provider, attempts };
    }
    if (typeof consumeQuota === 'function') {
      const quotaTimed = remainingSignal(undefined, checked.deadlineAt);
      if (!quotaTimed) return { ok: false, code: 'deadline_exceeded', provider, attempts };
      try {
        const quotaAbort = new Promise((_, reject) => quotaTimed.signal.addEventListener('abort', () => reject(Object.assign(new Error('deadline'), { name: 'AbortError' })), { once: true }));
        const quota = await Promise.race([consumeQuota(provider, attempts, { signal: quotaTimed.signal }), quotaAbort]);
        quotaTimed.stop();
        if (!quota || quota.allowed !== true) return { ok: false, code: 'quota_denied', provider, attempts };
      } catch (error) {
        quotaTimed.stop();
        return { ok: false, code: error?.name === 'AbortError' ? 'deadline_exceeded' : 'quota_denied', provider, attempts };
      }
    }
    const timed = remainingSignal(undefined, checked.deadlineAt);
    if (!timed) return { ok: false, code: 'deadline_exceeded', provider, attempts };
    try {
      const abort = new Promise((_, reject) => timed.signal.addEventListener('abort', () => reject(Object.assign(new Error('deadline'), { name: 'AbortError' })), { once: true }));
      const result = await Promise.race([runProvider(provider, { signal: timed.signal, attempt: attempts }), abort]);
      timed.stop();
      if (result?.ok === true && result.output !== undefined) return { ok: true, provider, output: result.output, attempts };
      const code = result?.code || 'provider_unavailable';
      if (TERMINAL_FAILURES.has(code)) return { ok: false, code, provider, attempts };
    } catch (error) {
      timed.stop();
      if (error?.name === 'AbortError') return { ok: false, code: 'deadline_exceeded', provider, attempts };
    }
  }
  return { ok: false, code: Date.now() >= checked.deadlineAt ? 'deadline_exceeded' : 'providers_unavailable', attempts };
}
