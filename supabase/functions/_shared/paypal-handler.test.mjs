import test from 'node:test';
import assert from 'node:assert/strict';
import { createPayPalHandler } from './paypal-handler.ts';
import { COIN_PACKAGES } from './payments.ts';

const owner = '11111111-1111-4111-8111-111111111111';
const intent = '22222222-2222-4222-8222-222222222222';
const config = { mode: 'sandbox', clientId: 'client', clientSecret: 'secret', url: 'https://db.invalid', serviceKey: 'service', returnUrl: 'https://app.invalid/paypal/return', cancelUrl: 'https://app.invalid/paypal/cancel', webhookId: 'hook' };
const order = (status = 'CREATED') => ({ id: 'ORDER1', status, links: [{ rel: 'approve', href: 'https://www.sandbox.paypal.com/checkoutnow?token=ORDER1' }], purchase_units: [{ reference_id: `CC_${owner}_stable`, custom_id: owner, amount: { currency_code: 'USD', value: '2.99' }, ...(status === 'COMPLETED' ? { payments: { captures: [{ id: 'CAP1' }] } } : {}) }] });
const tx = (extra = {}) => ({ id: 'row', user_id: owner, external_reference: `CC_${owner}_stable`, provider: 'paypal', amount: '2.99', amount_minor: 299, currency: 'USD', status: 'pending', payment_intent_id: intent, metadata: { provider_order_id: 'ORDER1' }, ...extra });
function fixture({ released = true, store = {}, api = {}, restricted = false, auth = owner } = {}) {
  const calls = []; const db = { readiness: async () => ({ schema_ready: true, version: 3 }), create: async (...a) => { calls.push(['create', ...a]); return { success: true, status: 'pending', payment_intent_id: intent }; }, claim: async (...a) => { calls.push(['claim', ...a]); return { may_submit: true }; }, attach: async (...a) => { calls.push(['attach', ...a]); return { success: true }; }, findOwn: async () => tx(), findOrder: async () => tx(), batch: async (...a) => { calls.push(['batch', ...a]); return { success: true, status: 'success' }; }, ...store };
  const provider = { accessToken: async () => 'token', getOrder: async () => order('COMPLETED'), getCapture: async () => ({ id: 'CAP1', status: 'COMPLETED', amount: { currency_code: 'USD', value: '2.99' }, supplementary_data: { related_ids: { order_id: 'ORDER1' } } }), verifyWebhook: async () => ({ id: 'evt', event_type: 'PAYMENT.CAPTURE.COMPLETED', resource: { id: 'CAP1', amount: { currency_code: 'USD', value: '2.99' } } }), base: 'https://api-m.sandbox.paypal.com', ...api };
  return { calls, deps: { released: () => released, authenticate: async () => auth, config: () => config, restricted: async () => restricted, store: () => db, api: () => provider } };
}
async function body(response) { return await response.json(); }
function req(value, method = 'POST') { return new Request('https://offline.invalid', { method, body: method === 'POST' ? JSON.stringify(value) : undefined }); }

test('server coin catalog conforms to the three SQL package IDs, prices, and rewards', () => {
  assert.deepEqual(COIN_PACKAGES, {
    pkg_seed: { id: 'pkg_seed', title: 'Bloom Seed', priceUsd: 2.99, priceKes: 389, coins: 250 },
    pkg_sanctuary: { id: 'pkg_sanctuary', title: 'Sanctuary Vault', priceUsd: 6.99, priceKes: 909, coins: 850 },
    pkg_celestial: { id: 'pkg_celestial', title: 'Celestial Treasury', priceUsd: 14.99, priceKes: 1949, coins: 2400 },
  });
});

test('source gate false returns 503 before persistence/provider contact for every endpoint', async () => {
  for (const kind of ['create', 'capture', 'reconcile', 'webhook']) {
    let touched = 0; const f = fixture({ released: false, store: { readiness: async () => { touched++; throw Error('persistence'); } }, api: { getOrder: async () => { touched++; throw Error('provider'); } } });
    const response = await createPayPalHandler(kind, f.deps)(req(kind === 'create' ? { package_id: 'pkg_seed', checkout_request_id: '11111111-1111-4111-8111-111111111111' } : { external_reference: 'x' }));
    assert.equal(response.status, 503); assert.equal(touched, 0);
  }
});

test('create requires exact stable client ID, rejects inherited package and direct card', async () => {
  const f = fixture(); const h = createPayPalHandler('create', f.deps);
  assert.equal((await h(req({ package_id: 'pkg_seed' }))).status, 400);
  assert.equal((await h(req({ package_id: 'toString', checkout_request_id: '11111111-1111-4111-8111-111111111111' }))).status, 400);
  assert.equal((await h(req({ package_id: 'pkg_seed', checkout_request_id: '11111111-1111-4111-8111-111111111111', is_acdc_card: true }))).status, 400);
  assert.equal((await h(req({ package_id: 'pkg_seed', checkout_request_id: '11111111-1111-4111-8111-111111111111', extra: 1 }))).status, 400);
});

test('create uses server package amount/reward and mode-bound approval URL', async () => {
  const f = fixture({ api: { getOrder: async () => ({ ...order('CREATED'), links: [{ rel: 'approve', href: 'https://www.sandbox.paypal.com/checkoutnow?token=ORDER1' }] }) } });
  const original = globalThis.fetch; globalThis.fetch = async (url) => { assert.equal(String(url), 'https://api-m.sandbox.paypal.com/v2/checkout/orders'); return new Response(JSON.stringify({ ...order('CREATED'), purchase_units: [{ reference_id: `CC_${owner}_11111111-1111-4111-8111-111111111111`, custom_id: owner, amount: { currency_code: 'USD', value: '2.99' } }] }), { status: 201 }); };
  let result; try { result = await body(await createPayPalHandler('create', f.deps)(req({ package_id: 'pkg_seed', checkout_request_id: '11111111-1111-4111-8111-111111111111' }))); } finally { globalThis.fetch = original; }
  assert.equal(result.success, true); assert.equal(result.amount_usd, COIN_PACKAGES.pkg_seed.priceUsd); assert.equal(result.coins_to_credit, COIN_PACKAGES.pkg_seed.coins);
  assert.equal(result.approval_url.startsWith('https://www.sandbox.paypal.com/'), true);
});

test('duplicate order is GET-only and ambiguous claims deny a second POST', async () => {
  let posts = 0; const f = fixture({ store: { create: async () => ({ success: true, status: 'pending', payment_intent_id: intent, provider_order_id: 'ORDER1' }), claim: async () => ({ may_submit: false }) }, api: { getOrder: async () => { posts++; return { ...order('CREATED'), purchase_units: [{ reference_id: `CC_${owner}_11111111-1111-4111-8111-111111111111`, custom_id: owner, amount: { currency_code: 'USD', value: '2.99' } }] }; } } });
  const result = await body(await createPayPalHandler('create', f.deps)(req({ package_id: 'pkg_seed', checkout_request_id: '11111111-1111-4111-8111-111111111111' })));
  assert.equal(result.success, true); assert.equal(posts, 1); assert.equal(f.calls.filter(c => c[0] === 'claim').length, 0);
  const f2 = fixture({ store: { create: async () => ({ success: true, status: 'pending', payment_intent_id: intent }), claim: async () => ({ may_submit: false }) }, api: { getOrder: async () => { throw Error('must not GET before claim'); } } });
  assert.equal((await createPayPalHandler('create', f2.deps)(req({ package_id: 'pkg_seed', checkout_request_id: '11111111-1111-4111-8111-111111111111' }))).status, 409);
});

test('capture APPROVED submits once, then GET verifies and batches settlement', async () => {
  let postCount = 0; let reads = 0; const f = fixture({ api: { getOrder: async () => (++reads === 1 ? { ...order('APPROVED') } : { ...order('COMPLETED') }) } });
  const original = globalThis.fetch; globalThis.fetch = async (url, init) => { if (String(url).includes('/capture')) { postCount++; return new Response('{}', { status: 201 }); } throw Error(`unexpected URL ${url}`); };
  try { const result = await body(await createPayPalHandler('capture', f.deps)(req({ external_reference: `CC_${owner}_stable` }))); assert.equal(result.settled, true); assert.equal(postCount, 1); assert.equal(f.calls.filter(c => c[0] === 'batch').length, 1); } finally { globalThis.fetch = original; }
});

test('completed capture is GET-only; wrong owner and missing records are 404', async () => {
  let postCount = 0; const original = globalThis.fetch; globalThis.fetch = async () => { postCount++; throw Error('unexpected write'); };
  try { const f = fixture(); const result = await createPayPalHandler('capture', f.deps)(req({ external_reference: `CC_${owner}_stable` })); assert.equal(result.status, 200); assert.equal(postCount, 0); const missing = fixture({ store: { findOwn: async () => null } }); assert.equal((await createPayPalHandler('capture', missing.deps)(req({ external_reference: 'nope' }))).status, 404); } finally { globalThis.fetch = original; }
});

test('webhook requires provider verification branding and never accepts raw event objects', async () => {
  const f = fixture({ api: { verifyWebhook: async () => { throw Error('unverified_webhook'); } } });
  const response = await createPayPalHandler('webhook', f.deps)(req({ id: 'evt', event_type: 'PAYMENT.CAPTURE.COMPLETED', resource: { id: 'CAP1' } }));
  assert.equal(response.status, 401);
});

test('readiness mismatch returns 503 and authenticated capture rejects malformed reference', async () => {
  const f = fixture({ store: { readiness: async () => ({ schema_ready: true, version: 2 }) } }); assert.equal((await createPayPalHandler('capture', f.deps)(req({ external_reference: 'x' }))).status, 503);
  const g = fixture(); assert.equal((await createPayPalHandler('capture', g.deps)(req({ external_reference: 123 }))).status, 400);
});
