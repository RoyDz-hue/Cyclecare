import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { validateSettlement, replayDecision } from './settlement.ts';
const source = name => readFileSync(new URL(`../${name}/index.ts`, import.meta.url), 'utf8');
const shared = name => readFileSync(new URL(`./${name}`, import.meta.url), 'utf8');

test('real PayPal entrypoints use the guarded shared controller without direct provider fetches', () => {
  for (const [name, action] of [['paypal-create-order', 'create'], ['paypal-capture', 'capture'], ['paypal-reconcile', 'reconcile'], ['paypal-webhook', 'webhook']]) {
    assert.ok(source(name).includes(`createPayPalHandler("${action}", payPalRuntimeDependencies())`));
    assert.doesNotMatch(source(name), /\bfetch\(/);
  }
  assert.match(shared('paypal-runtime.ts'), /verifiedProviderGate\("paypal"\)/);
  assert.match(shared('paypal-handler.ts'), /boundedJson/);
  assert.match(shared('paypal-handler.ts'), /fetchDeadline/);
});

test('real persistence and provider-action claims precede order submission', () => {
  const text = shared('paypal-handler.ts');
  assert.match(shared('paypal-runtime.ts'), /create_payment_intent/);
  assert.match(shared('paypal-runtime.ts'), /claim_payment_provider_action/);
  const submit = text.indexOf('fetchDeadline(`${api.base}/v2/checkout/orders`');
  assert.ok(submit > 0);
  assert.ok(text.indexOf('await store.create(') < submit);
  assert.ok(text.indexOf('await store.claim(') < submit);
  assert.match(shared('paypal-runtime.ts'), /get_payment_protocol_readiness/);
});

test('wrong owner, currency, amount, and missing reference reject settlement', () => {
  const base = { expectedReference: 'local', expectedOwner: 'u1', expectedAmount: '10.00', expectedCurrency: 'USD', providerReference: 'cap-1', owner: 'u1', amount: '10.00', currency: 'USD' };
  assert.equal(validateSettlement(base), 'settle');
  assert.equal(validateSettlement({ ...base, owner: 'u2' }), 'reject');
  assert.equal(validateSettlement({ ...base, currency: 'KES' }), 'reject');
  assert.equal(validateSettlement({ ...base, amount: '9.00' }), 'reject');
  assert.equal(validateSettlement({ ...base, providerReference: undefined }), 'ambiguous');
});

test('capture/reversal/refund replays are idempotent and conflicts are ambiguous', () => {
  assert.equal(replayDecision(null, 'capture', 'cap-1'), 'settle');
  assert.equal(replayDecision({ eventType: 'capture', providerReference: 'cap-1' }, 'capture', 'cap-1'), 'duplicate');
  assert.equal(replayDecision({ eventType: 'capture', providerReference: 'cap-1' }, 'refund', 'cap-1'), 'ambiguous');
  assert.equal(replayDecision({ eventType: 'refund', providerReference: 'refund-1' }, 'refund', 'refund-1'), 'duplicate');
  assert.equal(replayDecision({ eventType: 'reversal', providerReference: 'rev-1' }, 'reversal', 'rev-1'), 'duplicate');
});

test('forward SQL is proposal-only and has an immutable unique event key', () => {
  const sql = readFileSync(new URL('../../proposals/20261019000100_payment_settlement_protocol.sql', import.meta.url), 'utf8');
  assert.match(sql, /PROPOSAL ONLY/);
  assert.match(sql, /UNIQUE\s*\(\s*provider,\s*provider_reference,\s*event_type\s*\)/);
  assert.match(sql, /RAISE EXCEPTION 'payment event ambiguous'/);
});
