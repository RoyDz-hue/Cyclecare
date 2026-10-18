import test from 'node:test';
import assert from 'node:assert/strict';
import vm from 'node:vm';
import { readFileSync } from 'node:fs';

function callbackFixture({ transactionStatus = 'pending', validToken = true } = {}) {
  let writes = 0;
  const tx = { id: 'transaction-fixture', user_id: 'owner', provider: 'payhero', amount: 100, currency: 'KES', status: transactionStatus,
    metadata: { callback_token_hash: 'expected' } };
  const chain = { select() { return this; }, eq() { return this; }, async maybeSingle() { return { data: tx, error: null }; },
    update() { writes++; throw new Error('Unverified callback must not write'); } };
  const sandbox = { Request, Response, URL, console: { error() {}, warn() {} },
    corsHeaders: {}, Deno: { env: { get() { return 'fixture'; } } },
    supabaseSecretKey() { return 'fixture'; },
    async sha256Hex() { return validToken ? 'expected' : 'invalid'; },
    constantTimeHexEquals(a, b) { return a === b; },
    jsonResponse(body, status = 200) { return new Response(JSON.stringify(body), { status }); },
    createClient() { return { from() { return chain; }, async rpc() { writes++; throw new Error('No credit permitted'); } }; }
  };
  const raw = readFileSync(new URL('../payhero-callback/index.ts', import.meta.url), 'utf8');
  const executable = raw.replace(/^import .*;\s*$/gm, '').replace('serve(', 'globalThis.handler = (').replace('req: Request', 'req');
  vm.createContext(sandbox);
  vm.runInContext(executable, sandbox);
  return { handler: sandbox.handler, writes: () => writes };
}

for (const payload of [
  { ResultCode: 0 }, { ResultCode: '0' }, { success: true }, { status: 'SUCCESS' },
  { status: 'COMPLETED', ResultCode: 1 }, { status: 'FAILED', ResultCode: 0 },
  { status: 'SUCCESS', amount: 999999, MpesaReceiptNumber: 'forged' },
  { status: 'FAILED' },
]) {
  test(`callback ${JSON.stringify(payload)} cannot settle a pending payment`, async () => {
    const fixture = callbackFixture();
    const request = new Request(`https://callback.example/?token=${'x'.repeat(32)}`, { method: 'POST',
      body: JSON.stringify({ external_reference: 'fixture', ...payload }) });
    assert.equal((await fixture.handler(request)).status, 503);
    assert.equal(fixture.writes(), 0);
  });
}

test('invalid transaction capability is rejected before writes', async () => {
  const fixture = callbackFixture({ validToken: false });
  const request = new Request(`https://callback.example/?token=${'x'.repeat(32)}`, { method: 'POST', body: JSON.stringify({ external_reference: 'fixture', status: 'SUCCESS' }) });
  assert.equal((await fixture.handler(request)).status, 401);
  assert.equal(fixture.writes(), 0);
});

test('an already recorded success is idempotent without issuing another credit', async () => {
  const fixture = callbackFixture({ transactionStatus: 'success' });
  const request = new Request(`https://callback.example/?token=${'x'.repeat(32)}`, { method: 'POST', body: JSON.stringify({ external_reference: 'fixture', status: 'SUCCESS' }) });
  const response = await fixture.handler(request);
  assert.equal(response.status, 200);
  assert.equal((await response.json()).duplicate, true);
  assert.equal(fixture.writes(), 0);
});

test('new PayHero initiation is disabled before a provider fetch can occur', () => {
  const source = readFileSync(new URL('../payhero-stk/index.ts', import.meta.url), 'utf8');
  const disabled = source.indexOf('independent');
  assert.ok(disabled >= 0);
  assert.doesNotMatch(source, /await fetch\(|\.insert\(/);
});
