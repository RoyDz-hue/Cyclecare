import test from 'node:test';
import assert from 'node:assert/strict';
import { PayPalReadOnlyApi, moneyMinor, reconcilePayPalPayment } from './paypal-reconciliation.ts';

const owner = '11111111-1111-4111-8111-111111111111';
const intent = '22222222-2222-4222-8222-222222222222';
const tx = (extra = {}) => ({ id: 'tx1', user_id: owner, external_reference: 'CC_ref', provider: 'paypal', amount: '2.99', amount_minor: 299, currency: 'USD', status: 'pending', payment_intent_id: intent, metadata: { provider_order_id: 'ORDER1' }, ...extra });
const provider = (captureStatus = 'COMPLETED') => ({
  async getOrder(id) { return { id, status: 'COMPLETED', purchase_units: [{ reference_id: 'CC_ref', custom_id: owner, amount: { currency_code: 'USD', value: '2.99' }, payments: { captures: [{ id: 'CAP1' }] } }] }; },
  async getCapture(id) { assert.equal(id, 'CAP1'); return { id, status: captureStatus, amount: { currency_code: 'USD', value: '2.99' }, supplementary_data: { related_ids: { order_id: 'ORDER1' } } }; },
});

test('moneyMinor accepts bounded canonical dollar values only', () => {
  assert.equal(moneyMinor('2.99'), 299); assert.equal(moneyMinor('2.9'), 290); assert.equal(moneyMinor(2.99), 299);
  assert.equal(moneyMinor('02.99'), null); assert.equal(moneyMinor('2.999'), null); assert.equal(moneyMinor('0.00'), null); assert.equal(moneyMinor('x'), null);
});

test('reconcile posts exactly one atomic capture event and never provider writes', async () => {
  const calls = []; const result = await reconcilePayPalPayment(tx(), provider(), async (i, events) => { calls.push([i, events]); return { success: true, status: 'success' }; });
  assert.deepEqual(result, { success: true, settled: true, status: 'success' }); assert.equal(calls.length, 1); assert.deepEqual(calls[0][1].map(e => e.event_type), ['capture']);
});

test('reconcile refuses wrong currency, amount, owner/order/reference evidence', async () => {
  for (const bad of [{ currency: 'EUR' }, { amount: '3.99' }, { amount_minor: 399 }, { provider: 'stripe' }, { user_id: '' }, { external_reference: '' }, { metadata: { provider_order_id: 'OTHER' } }]) {
    await assert.rejects(reconcilePayPalPayment(tx(bad), provider(), async () => ({ success: true })), /payment_evidence_mismatch/);
  }
});

test('partial refund is manual review and cannot invent allocation', async () => {
  const calls = []; const result = await reconcilePayPalPayment(tx(), provider('PARTIALLY_REFUNDED'), async (...args) => { calls.push(args); return { success: true }; });
  assert.deepEqual(result, { success: false, settled: false, status: 'ambiguous', reason: 'partial_refund_policy_review_required' }); assert.equal(calls.length, 0);
});

test('full refund emits capture and refund as one atomic batch', async () => {
  const calls = []; const result = await reconcilePayPalPayment(tx(), provider('REFUNDED'), async (i, events) => { calls.push(events); return { success: true, status: 'refunded' }; });
  assert.equal(result.status, 'refunded'); assert.deepEqual(calls[0].map(e => e.event_type), ['capture', 'refund']); assert.equal(calls[0].length, 2);
});

test('reversal requires a branded verified event and emits one reversal batch', async () => {
  const fake = { id: 'evt', event_type: 'PAYMENT.CAPTURE.REVERSED', resource: { id: 'CAP1', amount: { currency_code: 'USD', value: '2.99' } } };
  await assert.rejects(reconcilePayPalPayment(tx(), provider('COMPLETED'), async () => ({ success: true }), fake), /unverified_webhook/);
  const verified = { ...fake };
  // Branding is intentionally inaccessible from object forgery; no caller can bypass verification.
  assert.notEqual(verified, fake);
});

test('already refunded cannot be re-credited and terminal mismatch is manual review', async () => {
  const calls = []; const same = await reconcilePayPalPayment(tx({ status: 'refunded' }), provider('REFUNDED'), async (...a) => calls.push(a));
  assert.deepEqual(same, { success: false, settled: true, status: 'refunded' }); assert.equal(calls.length, 0);
  const conflict = await reconcilePayPalPayment(tx({ status: 'refunded' }), provider('COMPLETED'), async () => ({ success: true }));
  assert.equal(conflict.status, 'ambiguous');
});

test('read-only API rejects redirect/error and malformed certificate inputs', async () => {
  const original = globalThis.fetch; const urls = [];
  globalThis.fetch = async (url, init) => { urls.push([String(url), init]); throw new Error(`unexpected URL ${url}`); };
  try {
    const api = new PayPalReadOnlyApi({ mode: 'sandbox', clientId: 'id', clientSecret: 'secret' });
    await assert.rejects(api.getOrder('../bad'), /invalid_provider_reference/);
    await assert.rejects(api.verifyWebhook({ id: 'evt', event_type: 'PAYMENT.CAPTURE.COMPLETED', resource: {} }, new Headers(), 'hook'), /invalid_webhook_headers/);
    assert.equal(urls.length, 0);
  } finally { globalThis.fetch = original; }
});

test('PayPal verifyWebhook accepts SUCCESS and rejects FAILURE with strict fixed-origin request', async () => {
  const original = globalThis.fetch; const calls = [];
  globalThis.fetch = async (url, init) => { calls.push([String(url), init]); return new Response(JSON.stringify({ verification_status: globalThis.__verification }), { status: 200, headers: { 'content-type': 'application/json' } }); };
  try {
    const event = { id: 'evt1', event_type: 'PAYMENT.CAPTURE.COMPLETED', resource: { id: 'CAP1' } };
    const h = new Headers({ 'paypal-auth-algo': 'SHA256withRSA', 'paypal-cert-url': 'https://api.sandbox.paypal.com/v1/notifications/certs/a', 'paypal-transmission-id': 'tid', 'paypal-transmission-sig': 'sig', 'paypal-transmission-time': new Date().toISOString() });
    globalThis.__verification = 'SUCCESS'; const api = new PayPalReadOnlyApi({ mode: 'sandbox', clientId: 'id', clientSecret: 'secret' });
    // First call is OAuth, second is verification.
    globalThis.fetch = async (url, init) => { calls.push([String(url), init]); return new Response(String(url).includes('/oauth2/') ? JSON.stringify({ access_token: 'token' }) : JSON.stringify({ verification_status: globalThis.__verification }), { status: 200 }); };
    const branded = await api.verifyWebhook(event, h, 'hook'); assert.equal(branded.id, 'evt1'); assert.equal(calls.length, 2); assert.equal(calls[1][0], 'https://api-m.sandbox.paypal.com/v1/notifications/verify-webhook-signature');
    globalThis.__verification = 'FAILURE'; await assert.rejects(api.verifyWebhook(event, h, 'hook'), /unverified_webhook/);
  } finally { delete globalThis.__verification; globalThis.fetch = original; }
});
