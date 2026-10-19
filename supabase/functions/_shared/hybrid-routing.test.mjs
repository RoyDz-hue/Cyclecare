import test from 'node:test';
import assert from 'node:assert/strict';
import { executeHybridPlan, hybridReadiness, HYBRID_PROTOCOL_REVISION, validateHybridPlan } from './hybrid-routing.js';

const plan = (overrides = {}) => ({
  routing_revision: HYBRID_PROTOCOL_REVISION,
  consent_revision: HYBRID_PROTOCOL_REVISION,
  chatproviders: ['COHERE', 'GEMINI'],
  consents: {
    COHERE: { provider: 'COHERE', granted: true, revoked: false, revision: HYBRID_PROTOCOL_REVISION },
    GEMINI: { provider: 'GEMINI', granted: true, revoked: false, revision: HYBRID_PROTOCOL_REVISION }
  },
  deadline_ms: 1000,
  deadline_at: Date.now() + 1000,
  ...overrides
});
const consent = async (provider) => ({ provider, granted: true, revoked: false, revision: HYBRID_PROTOCOL_REVISION });

test('Hybrid is disabled by default and cannot be enabled by a client-shaped plan', () => {
  assert.equal(hybridReadiness().available, false);
  assert.equal(validateHybridPlan(plan(), { enabled: false }).code, 'hybrid_disabled');
});

test('Hybrid requires matching revisions and explicit per-provider consent', () => {
  assert.equal(validateHybridPlan(plan({ consent_revision: 'old' }), { enabled: true }).code, 'revision_mismatch');
  assert.equal(validateHybridPlan(plan({ consents: { COHERE: { provider: 'COHERE', granted: true, revision: HYBRID_PROTOCOL_REVISION } } }), { enabled: true }).code, 'provider_consent_missing');
  assert.equal(validateHybridPlan(plan({ consents: { COHERE: { provider: 'COHERE', granted: true, revoked: true, revision: HYBRID_PROTOCOL_REVISION }, GEMINI: { provider: 'GEMINI', granted: true, revision: HYBRID_PROTOCOL_REVISION } } }), { enabled: true }).code, 'provider_consent_missing');
});

test('Hybrid runs an ordered allowlist sequentially and accounts quota per attempt', async () => {
  const seen = [];
  const result = await executeHybridPlan(plan(), { enabled: true, liveConsent: consent, runProvider: async (provider) => {
    seen.push(provider);
    return provider === 'COHERE' ? { ok: false, code: 'provider_unavailable' } : { ok: true, output: 'safe' };
  }, consumeQuota: async (provider, attempt) => ({ allowed: seen.length < 2 || provider === 'GEMINI', provider, attempt }) });
  assert.deepEqual(seen, ['COHERE', 'GEMINI']);
  assert.equal(result.ok, true);
  assert.equal(result.provider, 'GEMINI');
  assert.equal(result.attempts, 2);
});

test('Hybrid never falls back after safety refusal or malformed output', async () => {
  let calls = 0;
  const result = await executeHybridPlan(plan(), { enabled: true, liveConsent: consent, runProvider: async () => { calls += 1; return { ok: false, code: 'malformed_output' }; } });
  assert.equal(result.code, 'malformed_output');
  assert.equal(calls, 1);
});

test('Hybrid quota denial stops before any provider call', async () => {
  let calls = 0;
  const result = await executeHybridPlan(plan(), { enabled: true, liveConsent: consent, consumeQuota: async () => ({ allowed: false }) , runProvider: async () => { calls += 1; return { ok: true, output: 'nope' }; } });
  assert.equal(result.code, 'quota_denied');
  assert.equal(calls, 0);
});


test('Hybrid stops when live consent is revoked between attempts', async () => {
  let calls = 0;
  const result = await executeHybridPlan(plan(), { enabled: true, liveConsent: async (provider, context) => ({ provider, granted: context.attempt === 1, revision: HYBRID_PROTOCOL_REVISION }), runProvider: async () => { calls += 1; return { ok: false, code: 'provider_unavailable' }; } });
  assert.equal(result.code, 'provider_consent_missing');
  assert.equal(calls, 1);
});
