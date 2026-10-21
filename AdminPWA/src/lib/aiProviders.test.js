import assert from 'node:assert/strict';
import { test } from 'node:test';
import {
  AI_PROVIDER_CATALOG,
  AI_PROVIDER_IDS,
  HYBRID_PROVIDER_OPTION,
  validateAiProviderSelection
} from './aiProviders.js';

test('mobile AI provider choices match the CycleCare gateway contract', () => {
  assert.deepEqual(AI_PROVIDER_IDS, ['OPENROUTER', 'GEMINI', 'COHERE']);
  assert.deepEqual(AI_PROVIDER_CATALOG.map(({ name }) => name), ['OpenRouter', 'Gemini', 'Cohere']);
});

test('retired provider IDs are absent from the Admin PWA selector', () => {
  assert.equal(AI_PROVIDER_IDS.includes('DEEPINFRA'), false);
  assert.equal(AI_PROVIDER_IDS.includes('HUGGINGFACE'), false);
});

test('provider switch validation fails closed for missing, invalid and image-only providers', () => {
  for (const value of [undefined, '', 'NOT_A_PROVIDER']) {
    assert.equal(validateAiProviderSelection(value).ok, false);
  }
  assert.equal(validateAiProviderSelection('POLLINATIONS').code, 'IMAGE_ONLY_PROVIDER');
});

test('Hybrid Bridge is selectable once any chat provider key is saved', () => {
  assert.equal(validateAiProviderSelection('HYBRID', { configuredIds: new Set() }).code, 'HYBRID_UNCONFIGURED');
  assert.deepEqual(validateAiProviderSelection('hybrid', { configuredIds: new Set(['GEMINI']) }), { ok: true, provider: 'HYBRID' });
  assert.deepEqual(validateAiProviderSelection('HYBRID'), { ok: true, provider: 'HYBRID' });
});

test('provider switch validation requires a server-recorded credential', () => {
  assert.equal(validateAiProviderSelection('COHERE', { configuredIds: new Set() }).code, 'UNCONFIGURED_PROVIDER');
  assert.deepEqual(validateAiProviderSelection('cohere', { configuredIds: new Set(['COHERE']) }), { ok: true, provider: 'COHERE' });
});
