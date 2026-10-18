import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { aiProviderStatus, normalizeAiProvider, validateAiRequest, validateImageRequest, paymentSuccessCanCredit } from './request-contract.js';

test('AI provider selection is a strict single-provider allowlist', () => {
  assert.equal(normalizeAiProvider(' gemini '), 'GEMINI');
  assert.equal(normalizeAiProvider('HYBRID'), null);
  assert.equal(normalizeAiProvider(undefined), null);
  assert.deepEqual(aiProviderStatus('COHERE', false), {
    configured: false,
    validated: false,
    reason: 'The provider Edge secret is missing or still a placeholder.',
  });
  assert.equal(aiProviderStatus('OPENROUTER', true).validated, false);
  assert.match(aiProviderStatus('HYBRID', true).reason, /Unsupported provider/);
});

test('admin provider status documents Hybrid as disabled without a multi-provider contract', () => {
  const source = readFileSync(new URL('../admin-provider-status/index.ts', import.meta.url), 'utf8');
  assert.match(source, /HYBRID/);
  assert.match(source, /consent-safe multi-provider contract/);
  assert.match(source, /validated: false/);
});

test('AI v2 minimal requires request consent and has no health context', () => {
  assert.equal(validateAiRequest({ schema_version: 2, user_question: 'hello', context_mode: 'minimal', ai_consent: true }).ok, true);
  assert.equal(validateAiRequest({ schema_version: 1, user_question: 'hello', context_mode: 'minimal', ai_consent: true }).ok, false);
  assert.equal(validateAiRequest({ schema_version: 2, user_question: 'hello', context_mode: 'minimal', ai_consent: true, context: { summary: 'raw' } }).ok, false);
  assert.equal(validateAiRequest({ schema_version: 2, user_question: 'hello', context_mode: 'minimal', ai_consent: false }).ok, false);
  assert.equal(validateAiRequest({ schema_version: 2, user_question: 'hello', context_mode: 'minimal', ai_consent: true, conversation_history: [{ role: 'user', content: 'already shared' }] }).ok, true);
  assert.equal(validateAiRequest({ schema_version: 2, user_question: 'hello', context_mode: 'minimal', ai_consent: true, conversation_history: [{ role: 'system', content: 'unsafe' }] }).ok, false);
  assert.equal(validateAiRequest({ schema_version: 2, user_question: 'hello', context_mode: 'minimal', ai_consent: true, conversation_history: Array.from({ length: 7 }, () => ({ role: 'user', content: 'x' })) }).ok, false);
});
test('expanded context is bounded and summary-only', () => {
  assert.equal(validateAiRequest({ schema_version: 2, user_question: 'q', context_mode: 'expanded', ai_consent: true, context: { summary: 'bounded visible summary' } }).ok, true);
  assert.equal(validateAiRequest({ schema_version: 2, user_question: 'q', context_mode: 'expanded', ai_consent: true, context: { summary: 'x'.repeat(2401) } }).ok, false);
  assert.equal(validateAiRequest({ schema_version: 2, user_question: 'q'.repeat(1201), context_mode: 'minimal', ai_consent: true }).ok, false);
});
test('image requires consent and caps custom prompt', () => {
  assert.equal(validateImageRequest({ style: 'botanical', custom_prompt: 'calm', image_consent: true }).ok, true);
  assert.equal(validateImageRequest({ style: 'botanical', custom_prompt: 'x'.repeat(501), image_consent: true }).ok, false);
  assert.equal(validateImageRequest({ style: 'botanical', custom_prompt: 'calm', image_consent: false }).ok, false);
});
test('payment credit requires independent verification, exact amount, and callback token', () => {
  assert.equal(paymentSuccessCanCredit({ independentlyVerified: false, amountMatches: true, callbackTokenValid: true }), false);
  assert.equal(paymentSuccessCanCredit({ independentlyVerified: true, amountMatches: false, callbackTokenValid: true }), false);
  assert.equal(paymentSuccessCanCredit({ independentlyVerified: true, amountMatches: true, callbackTokenValid: false }), false);
  assert.equal(paymentSuccessCanCredit({ independentlyVerified: true, amountMatches: true, callbackTokenValid: true }), true);
});
test('tier/admin policy is not encoded as a consent bypass', () => {
  // Contract tests document the invariant enforced by gateway: admin bypass is tier only;
  // persisted consent remains required for every remote request.
  assert.equal(validateAiRequest({ schema_version: 2, user_question: 'q', context_mode: 'minimal', ai_consent: false }).ok, false);
});
test('schema readiness is v2-only', () => {
  const migration = readFileSync(new URL('../../migrations/20261018110000_cyclecare_backend_contract_v2.sql', import.meta.url), 'utf8');
  assert.match(migration, /schema_version INTEGER NOT NULL DEFAULT 2/);
  assert.match(migration, /daily_log_schema_version/);
  assert.doesNotMatch(migration, /period_end_date_source/);
});

test('AI safety contract asks for uncertainty and escalation without diagnosis claims', () => {
  const gateway = readFileSync(new URL('../ai-gateway/index.ts', import.meta.url), 'utf8');
  assert.match(gateway, /not a diagnosis or a substitute for medical care/);
  assert.match(gateway, /avoid claiming certainty/);
  assert.match(gateway, /sudden severe pain, fainting, or heavy bleeding/);
});
