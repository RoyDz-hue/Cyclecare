import test from 'node:test';
import assert from 'node:assert/strict';
import { paymentRoutingBlockResponse } from './payment-routing.ts';

// Gate contract: fails closed (never initiates a payment) on missing
// credentials or RPC failure; maps registry verdicts to the right statuses.
function withEnv(fn) {
  return async () => {
    const saved = { url: Deno.env.get('SUPABASE_URL'), key: Deno.env.get('SUPABASE_SERVICE_ROLE_KEY'), keys: Deno.env.get('SUPABASE_SECRET_KEYS') };
    const realFetch = globalThis.fetch;
    try { await fn(); } finally {
      for (const [k, v] of [['SUPABASE_URL', saved.url], ['SUPABASE_SERVICE_ROLE_KEY', saved.key], ['SUPABASE_SECRET_KEYS', saved.keys]]) {
        if (v == null) Deno.env.delete(k); else Deno.env.set(k, v);
      }
      globalThis.fetch = realFetch;
    }
  };
}
function stubRpc(body, status = 200) {
  globalThis.fetch = async () => new Response(JSON.stringify(body), { status, headers: { 'content-type': 'application/json' } });
}

test('missing credentials fail closed with 503', withEnv(async () => {
  Deno.env.delete('SUPABASE_URL'); Deno.env.delete('SUPABASE_SERVICE_ROLE_KEY'); Deno.env.delete('SUPABASE_SECRET_KEYS');
  const blocked = await paymentRoutingBlockResponse('paypal', 'KE', 'USD');
  assert.equal(blocked.status, 503);
}));

test('RPC failure fails closed with 503', withEnv(async () => {
  Deno.env.set('SUPABASE_URL', 'https://routing.invalid'); Deno.env.set('SUPABASE_SERVICE_ROLE_KEY', 'k');
  globalThis.fetch = async () => { throw new Error('network'); };
  const blocked = await paymentRoutingBlockResponse('paypal', 'KE', 'USD');
  assert.equal(blocked.status, 503);
}));

test('allowed verdict lets the checkout proceed (null)', withEnv(async () => {
  Deno.env.set('SUPABASE_URL', 'https://routing.invalid'); Deno.env.set('SUPABASE_SERVICE_ROLE_KEY', 'k');
  stubRpc({ allowed: true });
  assert.equal(await paymentRoutingBlockResponse('paypal', 'KE', 'USD'), null);
}));

test('region and currency denials are 403; other blocks are 503', withEnv(async () => {
  Deno.env.set('SUPABASE_URL', 'https://routing.invalid'); Deno.env.set('SUPABASE_SERVICE_ROLE_KEY', 'k');
  stubRpc({ allowed: false, reason: 'region_not_allowed' });
  assert.equal((await paymentRoutingBlockResponse('paypal', 'US', 'USD')).status, 403);
  stubRpc({ allowed: false, reason: 'currency_not_allowed' });
  assert.equal((await paymentRoutingBlockResponse('paypal', 'KE', 'EUR')).status, 403);
  stubRpc({ allowed: false, reason: 'option_disabled' });
  assert.equal((await paymentRoutingBlockResponse('paypal', 'KE', 'USD')).status, 503);
}));
