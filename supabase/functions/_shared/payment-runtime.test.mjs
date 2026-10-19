import test from 'node:test';
import assert from 'node:assert/strict';
import vm from 'node:vm';
import { stripTypeScriptTypes } from 'node:module';
import { readFileSync } from 'node:fs';
import { boundedJson, boundedResponseJson, fetchDeadline, verifiedProviderGate, PAYMENT_PROTOCOL_RELEASED } from './payment-request.ts';
import { COIN_PACKAGES, jsonResponse } from './payments.ts';

function requestWithStream(stream) {
  return new Request('https://offline.invalid', { method: 'POST', body: stream, duplex: 'half' });
}

test('neither live nor sandbox payments can be enabled by environment flags alone', () => {
  assert.equal(PAYMENT_PROTOCOL_RELEASED, false);
  assert.equal(verifiedProviderGate('paypal'), false);
  assert.equal(verifiedProviderGate('payhero'), false);
  assert.equal(verifiedProviderGate('unknown'), false);
});

test('actual payment JSON helper rejects null, arrays and malformed JSON', async () => {
  for (const body of ['null', '[]', 'private-invalid-json']) {
    await assert.rejects(boundedJson(new Request('https://offline.invalid', { method: 'POST', body })), /invalid_request/);
  }
});

test('actual payment JSON helper bounds streams without content-length', async () => {
  const stream = new ReadableStream({ start(c) { c.enqueue(new TextEncoder().encode('x'.repeat(65))); c.close(); } });
  await assert.rejects(boundedJson(requestWithStream(stream), 64), /request_too_large/);
});

test('actual payment JSON helper bounds stalled request bodies', async () => {
  await assert.rejects(boundedJson(requestWithStream(new ReadableStream({ start() {} })), 64, 5), /io_deadline/);
});

test('actual payment provider response parser bounds stalled bodies', async () => {
  await assert.rejects(boundedResponseJson(new Response(new ReadableStream({ start() {} })), 64, 5), /io_deadline/);
});

test('actual payment provider fetch deadline handles non-cooperative adapters', async t => {
  const original = globalThis.fetch;
  globalThis.fetch = async () => new Promise(() => {});
  t.after(() => { globalThis.fetch = original; });
  await assert.rejects(fetchDeadline('https://offline.invalid', {}, 5), /io_deadline/);
});

function realHandler(name) {
  let accesses = 0;
  const code = stripTypeScriptTypes(readFileSync(new URL(`../${name}/index.ts`, import.meta.url), 'utf8').replace(/^import .*;\s*$/mg, '')).replace('serve(', 'globalThis.handler = (');
  const forbidden = () => { accesses++; throw new Error('Unreleased payment must not access persistence or a provider'); };
  const scope = { Request, Response, URL, console: { error() {} }, COIN_PACKAGES, boundedJson, boundedResponseJson,
    fetchDeadline: forbidden, verifiedProviderGate, jsonResponse, corsHeaders: {},
    createClient: forbidden, authenticatedUserId: async () => 'fixture-owner', isAccountRestricted: forbidden,
    isConfiguredSecret: () => true, supabaseSecretKey: forbidden, redactedProviderError: () => 'unavailable',
    Deno: { env: { get: () => 'true' } } };
  vm.runInNewContext(code, scope, { timeout: 1000 });
  return { handler: scope.handler, accesses: () => accesses };
}

for (const name of ['paypal-create-order', 'paypal-capture']) {
  test(`real ${name} handler stops before persistence/provider contact`, async () => {
    const fixture = realHandler(name);
    const body = name === 'paypal-create-order' ? { package_id: 'pkg_seed' } : { external_reference: 'fixture-ref' };
    const response = await fixture.handler(new Request('https://offline.invalid', { method: 'POST', body: JSON.stringify(body) }));
    assert.equal(response.status, 503);
    assert.equal(fixture.accesses(), 0);
  });
}

test('real order handler rejects inherited package IDs as malformed input', async () => {
  const fixture = realHandler('paypal-create-order');
  const response = await fixture.handler(new Request('https://offline.invalid', { method: 'POST', body: '{"package_id":"toString"}' }));
  assert.equal(response.status, 400);
  assert.equal(fixture.accesses(), 0);
});

test('real capture handler rejects non-string references rather than throwing a server error', async () => {
  const fixture = realHandler('paypal-capture');
  const response = await fixture.handler(new Request('https://offline.invalid', { method: 'POST', body: '{"external_reference":123}' }));
  assert.equal(response.status, 400);
  assert.equal(fixture.accesses(), 0);
});
