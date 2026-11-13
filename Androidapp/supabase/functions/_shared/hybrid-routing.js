export const HYBRID_PROTOCOL_REVISION = 'hybrid-v3';
export const HYBRID_DEFAULT_ENABLED = false;
export const HYBRID_SOURCE_RELEASED = true;
export const HYBRID_MAX_ATTEMPTS = 3;
export const HYBRID_MAX_DEADLINE_MS = 30_000;

const SUPPORTED_CHAT_PROVIDERS = Object.freeze(['COHERE', 'GEMINI', 'OPENROUTER']);
const TERMINAL_FAILURES = new Set(['safety_refusal', 'malformed_output', 'untrusted_output', 'empty_output', 'provider_auth', 'provider_client_error']);
const TRANSIENT_FAILURES = new Set(['provider_unavailable', 'provider_timeout', 'provider_rate_limited', 'provider_server_error']);

export function hybridReadiness({ enabled = false, runtimeReady = false, sourceReleased = HYBRID_SOURCE_RELEASED, contractRevision, consentProtocolRevision } = {}) {
  if (!sourceReleased) return { configured: false, validated: false, available: false, reason: 'Disabled by the server source release gate; database settings cannot enable Hybrid.' };
  if (!enabled) return { configured: false, validated: false, available: false, reason: 'Disabled by the server default gate; client requests cannot enable Hybrid.' };
  if (!runtimeReady) return { configured: false, validated: false, available: false, reason: 'Unavailable: server runtime readiness has not been reviewed.' };
  if (contractRevision !== HYBRID_PROTOCOL_REVISION || consentProtocolRevision !== HYBRID_PROTOCOL_REVISION) return { configured: false, validated: false, available: false, reason: 'Unavailable: routing and consent revisions do not match the reviewed Hybrid protocol.' };
  return { configured: true, validated: true, available: true, reason: 'Reviewed server-side Hybrid contract is ready.' };
}
function providerId(value) { const id = typeof value === 'string' ? value.trim().toUpperCase() : ''; return SUPPORTED_CHAT_PROVIDERS.includes(id) ? id : null; }
function consentFor(consents, provider) { if (!consents || typeof consents !== 'object' || Array.isArray(consents)) return undefined; const value = consents[provider]; return value && typeof value === 'object' ? value : undefined; }

export function validateHybridPlan(plan, { now = Date.now(), enabled = HYBRID_DEFAULT_ENABLED, sourceReleased = HYBRID_SOURCE_RELEASED } = {}) {
  if (!sourceReleased || !enabled) return { ok: false, code: 'hybrid_disabled' };
  if (!plan || typeof plan !== 'object' || Array.isArray(plan)) return { ok: false, code: 'plan_required' };
  if (plan.routing_revision !== HYBRID_PROTOCOL_REVISION || plan.consent_revision !== HYBRID_PROTOCOL_REVISION) return { ok: false, code: 'revision_mismatch' };
  if (!Array.isArray(plan.chatproviders) || plan.chatproviders.length < 1 || plan.chatproviders.length > HYBRID_MAX_ATTEMPTS) return { ok: false, code: 'allowlist_invalid' };
  const providers = plan.chatproviders.map(providerId);
  if (providers.some((id) => !id) || new Set(providers).size !== providers.length) return { ok: false, code: 'provider_allowlist_invalid' };
  if (!Array.isArray(plan.processor_receipts) || plan.processor_receipts.length !== providers.length) return { ok: false, code: 'processor_receipts_required' };
  for (const receipt of plan.processor_receipts) if (!receipt || !providerId(receipt.provider_id) || !Number.isInteger(receipt.revision) || receipt.revision < 1 || typeof receipt.manifest_fingerprint !== 'string' || !/^[a-f0-9]{64}$/.test(receipt.manifest_fingerprint)) return { ok: false, code: 'processor_receipt_invalid' };
  if (!plan.consents || typeof plan.consents !== 'object' || Array.isArray(plan.consents)) return { ok: false, code: 'consent_map_required' };
  for (const provider of providers) { const consent = consentFor(plan.consents, provider); if (consent?.granted !== true || consent.provider !== provider || consent.revision !== HYBRID_PROTOCOL_REVISION || consent.revoked === true) return { ok: false, code: 'provider_consent_missing', provider }; }
  const deadlineMs = Number(plan.deadline_ms); if (!Number.isInteger(deadlineMs) || deadlineMs < 1 || deadlineMs > HYBRID_MAX_DEADLINE_MS) return { ok: false, code: 'deadline_invalid' };
  const deadlineAt = Number(plan.deadline_at ?? (now + deadlineMs)); if (!Number.isFinite(deadlineAt) || deadlineAt <= now || deadlineAt > now + deadlineMs) return { ok: false, code: 'deadline_expired' };
  return { ok: true, providers, deadlineAt, maxAttempts: providers.length };
}
function remainingSignal(signal, deadlineAt) { const remaining = deadlineAt - Date.now(); if (remaining <= 0) return null; const controller = new AbortController(); const timer = setTimeout(() => controller.abort(), remaining); if (signal) signal.addEventListener('abort', () => controller.abort(), { once: true }); return { signal: controller.signal, stop: () => clearTimeout(timer) }; }

export async function executeHybridPlan(plan, { enabled = HYBRID_DEFAULT_ENABLED, sourceReleased = HYBRID_SOURCE_RELEASED, now = Date.now(), runProvider, consumeQuota, liveConsent } = {}) {
  const checked = validateHybridPlan(plan, { enabled, sourceReleased, now });
  if (!checked.ok) return { ok: false, code: checked.code, provider: checked.provider };
  if (typeof runProvider !== 'function') return { ok: false, code: 'runner_missing' };
  if (typeof liveConsent !== 'function') return { ok: false, code: 'live_consent_check_missing' };
  let attempts = 0; const receipts = new Map(plan.processor_receipts.map((r) => [providerId(r.provider_id), r]));
  for (const provider of checked.providers) {
    if (Date.now() >= checked.deadlineAt || attempts >= checked.maxAttempts) return { ok: false, code: 'deadline_exceeded' };
    attempts += 1;
    const consentTimed = remainingSignal(undefined, checked.deadlineAt); if (!consentTimed) return { ok: false, code: 'deadline_exceeded', provider, attempts };
    try {
      const abort = new Promise((_, reject) => consentTimed.signal.addEventListener('abort', () => reject(Object.assign(new Error('deadline'), { name: 'AbortError' })), { once: true }));
      const consent = await Promise.race([liveConsent(provider, { signal: consentTimed.signal, attempt: attempts }), abort]); consentTimed.stop();
      const receipt = receipts.get(provider); if (consent?.granted !== true || consent.provider !== provider || consent.revision !== HYBRID_PROTOCOL_REVISION || consent.revoked === true || consent.revision_number !== receipt?.revision || consent.manifest_fingerprint !== receipt?.manifest_fingerprint) return { ok: false, code: 'provider_consent_missing', provider, attempts };
    } catch (error) { consentTimed.stop(); return { ok: false, code: error?.name === 'AbortError' ? 'deadline_exceeded' : 'provider_consent_missing', provider, attempts }; }
    if (typeof consumeQuota === 'function') {
      const quotaTimed = remainingSignal(undefined, checked.deadlineAt); if (!quotaTimed) return { ok: false, code: 'deadline_exceeded', provider, attempts };
      try { const abort = new Promise((_, reject) => quotaTimed.signal.addEventListener('abort', () => reject(Object.assign(new Error('deadline'), { name: 'AbortError' })), { once: true })); const quota = await Promise.race([consumeQuota(provider, attempts, { signal: quotaTimed.signal }), abort]); quotaTimed.stop(); if (!quota || quota.allowed !== true) return { ok: false, code: 'quota_denied', provider, attempts }; }
      catch (error) { quotaTimed.stop(); return { ok: false, code: error?.name === 'AbortError' ? 'deadline_exceeded' : 'quota_denied', provider, attempts }; }
    }
    // Quota reservation can race revocation/restriction; recheck all live consent gates immediately before I/O.
    const beforeDispatch = remainingSignal(undefined, checked.deadlineAt); if (!beforeDispatch) return { ok: false, code: 'deadline_exceeded', provider, attempts };
    try {
      const abort = new Promise((_, reject) => beforeDispatch.signal.addEventListener('abort', () => reject(Object.assign(new Error('deadline'), { name: 'AbortError' })), { once: true }));
      const consent = await Promise.race([liveConsent(provider, { signal: beforeDispatch.signal, attempt: attempts, afterQuota: true }), abort]); beforeDispatch.stop();
      const receipt = receipts.get(provider); if (consent?.granted !== true || consent.provider !== provider || consent.revision !== HYBRID_PROTOCOL_REVISION || consent.revoked === true || consent.revision_number !== receipt?.revision || consent.manifest_fingerprint !== receipt?.manifest_fingerprint) return { ok: false, code: 'provider_consent_missing', provider, attempts };
    } catch (error) { beforeDispatch.stop(); return { ok: false, code: error?.name === 'AbortError' ? 'deadline_exceeded' : 'provider_consent_missing', provider, attempts }; }
    const timed = remainingSignal(undefined, checked.deadlineAt); if (!timed) return { ok: false, code: 'deadline_exceeded', provider, attempts };
    try {
      const abort = new Promise((_, reject) => timed.signal.addEventListener('abort', () => reject(Object.assign(new Error('deadline'), { name: 'AbortError' })), { once: true }));
      const result = await Promise.race([runProvider(provider, { signal: timed.signal, attempt: attempts }), abort]); timed.stop();
      if (result?.ok === true && result.output !== undefined) return { ok: true, provider, output: result.output, attempts };
      const code = result?.code || 'provider_unavailable';
      if (TERMINAL_FAILURES.has(code) || !TRANSIENT_FAILURES.has(code)) return { ok: false, code, provider, attempts };
    } catch (error) { timed.stop(); if (error?.name === 'AbortError') return { ok: false, code: 'provider_timeout', provider, attempts }; }
  }
  return { ok: false, code: Date.now() >= checked.deadlineAt ? 'deadline_exceeded' : 'providers_unavailable', attempts };
}
