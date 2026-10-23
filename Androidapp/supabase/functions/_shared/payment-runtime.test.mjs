import test from 'node:test';
import assert from 'node:assert/strict';
import vm from 'node:vm';
import { stripTypeScriptTypes } from 'node:module';
import { readFileSync } from 'node:fs';
import { boundedJson, boundedResponseJson, fetchDeadline, verifiedProviderGate, PAYMENT_PROTOCOL_RELEASED } from './payment-request.ts';
import { COIN_PACKAGES, jsonResponse } from './payments.ts';
import { createPayPalHandler } from './paypal-handler.ts';


// The payment routing registry (added with the AI core + payment routing
// framework) consults a live RPC before these handlers run. Stub it allowed so
// these tests exercise their own contracts; the gate itself is covered by
// payment-routing.test.mjs.
Deno.env.set("SUPABASE_URL", "https://routing.invalid");
Deno.env.set("SUPABASE_SERVICE_ROLE_KEY", "routing-test-key");
const realFetch = globalThis.fetch;
const routingAllowResponse = () => new Response(JSON.stringify({ allowed: true }), { status: 200, headers: { 'content-type': 'application/json' } });
const isRoutingCall = (input) => String(input).includes('/rest/v1/rpc/payment_option_allowed');
globalThis.fetch = async (input, init) => isRoutingCall(input) ? routingAllowResponse() : realFetch(input, init);

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
  const source = readFileSync(new URL(`../${name}/index.ts`, import.meta.url), 'utf8');
  assert.match(source, /createPayPalHandler\("(?:create|capture)"/);
  let accesses = 0;
  const forbidden = async () => { accesses++; throw new Error('Unreleased payment must not access persistence or a provider'); };
  const deps = {
    released: () => false,
    authenticate: async () => 'fixture-owner',
    config: () => { accesses++; throw new Error('provider config must not be read'); },
    restricted: forbidden,
    store: () => { accesses++; throw new Error('persistence must not be read'); },
    api: () => { accesses++; throw new Error('provider must not be contacted'); },
  };
  return { handler: createPayPalHandler(name === 'paypal-create-order' ? 'create' : 'capture', deps), accesses: () => accesses };
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
