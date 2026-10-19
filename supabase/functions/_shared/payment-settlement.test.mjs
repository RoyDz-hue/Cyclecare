import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { validateSettlement, replayDecision } from './settlement.ts';
const source = name => readFileSync(new URL(`../../functions/${name}/index.ts`, import.meta.url), 'utf8');

test('PayPal handlers are default-off and use bounded/deadline helpers', () => {
  for (const name of ['paypal-create-order', 'paypal-capture']) {
    const text = source(name);
    assert.match(text, /verifiedProviderGate\("paypal"\)/);
    assert.match(text, /boundedJson/);
    assert.match(text, /fetchDeadline/);
    assert.doesNotMatch(text, /fetch\(/); // provider calls must be deadline-wrapped
  }
});

test('create persists local intent before any provider call and old schema fails closed', () => {
  const text = source('paypal-create-order');
  assert.ok(text.indexOf('from("payment_intents")') < text.indexOf('fetchDeadline(`${base}/v1/oauth2/token`'));
  assert.match(text, /no provider call was made/);
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

test('forward SQL is proposal-only and has immutable unique event key', () => {
  const sql = readFileSync(new URL('../../proposals/20261019000100_payment_settlement_protocol.sql', import.meta.url), 'utf8');
  assert.match(sql, /PROPOSAL ONLY/);
  assert.match(sql, /UNIQUE\(provider, provider_reference, event_type\)/);
  assert.match(sql, /RAISE EXCEPTION 'payment event ambiguous'/);
});
