import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import vm from 'node:vm';
import { stripTypeScriptTypes } from 'node:module';
import { manifestWithFingerprint, MANIFEST_REVISION } from './provider-manifests.js';
import { fetchJsonBounded } from './bounded-json.js';
import { boundedJson } from './payment-request.ts';

const owner = '11111111-1111-4111-8111-111111111111';
const canonical = await manifestWithFingerprint('COHERE');
const original = readFileSync(new URL('../ai-provider-consent/index.ts', import.meta.url), 'utf8');
const executable = stripTypeScriptTypes(original.split('\n').filter(line => !line.trim().startsWith('import ')).join('\n'));
function load(options = {}) {
  let handler;
  vm.runInNewContext(executable, { serve(fn) { handler = fn; }, Request, Response, Headers, URL, JSON, Number, String, Object, Array, Error,
    corsHeaders: {}, authenticatedUserId: async () => options.auth === false ? null : owner,
    supabaseSecretKey: () => 'fixture-service-key', supabasePublishableKey: () => 'fixture-public-key',
    manifestWithFingerprint, MANIFEST_REVISION, fetchJsonBounded, boundedJson,
    Deno: { env: { get: name => name === 'SUPABASE_URL' ? 'https://fixture.invalid' : undefined } } });
  return handler;
}
const get = () => new Request('https://app.invalid/consent', { headers: { Authorization: 'Bearer fixture-user-token' } });
const body = (extra = {}) => ({ provider_id: 'COHERE', enabled: true, expected_revision: 0, manifest_fingerprint: canonical.manifest_fingerprint, ...extra });
const post = value => new Request('https://app.invalid/consent', { method: 'POST', headers: { Authorization: 'Bearer fixture-user-token', 'Content-Type': 'application/json' }, body: JSON.stringify(value) });
function tables({ approved = false, clock = [], consent = [] } = {}) {
  return {
    cyclecare_ai_processor_consents: consent,
    cyclecare_ai_processor_consent_clocks: clock,
    cyclecare_ai_provider_policy_readiness: [{ processor: 'COHERE', reviewed: approved, eligible: approved, policy_revision: canonical.policy_revision, manifest_fingerprint: canonical.manifest_fingerprint }],
    cyclecare_ai_provider_manifests: [{ processor: 'COHERE', eligible: approved, account_reviewed: approved, use_case_reviewed: approved, audience_reviewed: approved, manifest_revision: MANIFEST_REVISION, manifest_fingerprint: canonical.manifest_fingerprint }],
  };
}
async function offline(fetcher, work) {
  const before = globalThis.fetch; globalThis.fetch = fetcher;
  try { return await work(); } finally { globalThis.fetch = before; }
}
function tableFetch(rows) {
  return async url => {
    const u = new URL(String(url)); assert.equal(u.origin, 'https://fixture.invalid');
    const table = u.pathname.split('/').at(-1);
    assert.ok(Object.hasOwn(rows, table), `unexpected endpoint ${u.pathname}`);
    if (table.includes('consent')) assert.equal(u.searchParams.get('user_id'), `eq.${owner}`);
    return new Response(JSON.stringify(rows[table]), { status: 200 });
  };
}

test('actual manifest keeps all unreviewed processors and Hybrid ineligible', async () => {
  await offline(tableFetch(tables()), async () => {
    const response = await load()(get()); assert.equal(response.status, 200);
    const manifest = await response.json(); assert.equal(manifest.account_snapshot, owner);
    assert.deepEqual(manifest.providers.map(p => p.id), ['COHERE', 'GEMINI', 'OPENROUTER']);
    assert.ok(manifest.providers.every(p => !p.eligible && !p.consent.enabled)); assert.equal(manifest.hybrid.released, false);
  });
});
test('reviewed database eligibility is reflected without pretending source defaults were approved', async () => {
  await offline(tableFetch(tables({ approved: true })), async () => {
    const response = await load()(get()); const manifest = await response.json();
    assert.equal(response.status, 200); assert.equal(manifest.providers[0].eligible, true);
    assert.equal(manifest.providers[1].eligible, false); assert.equal(manifest.hybrid.released, false);
  });
});
test('erasure retains a revision clock without retaining an enabled receipt', async () => {
  await offline(tableFetch(tables({ approved: true, clock: [{ processor: 'COHERE', revision: 5 }] })), async () => {
    const p = (await (await load()(get())).json()).providers[0];
    assert.equal(p.consent.revision, 5); assert.equal(p.consent.enabled, false);
  });
});
test('broken clock or missing contract tables fail closed instead of inventing revision zero', async () => {
  const rows = tables({ clock: [{ processor: 'COHERE', revision: 2 }], consent: [{ processor: 'COHERE', consent_revision: `${MANIFEST_REVISION}-1`, granted: true }] });
  await offline(tableFetch(rows), async () => assert.equal((await load()(get())).status, 503));
  await offline(async () => new Response('{}', { status: 404 }), async () => assert.equal((await load()(get())).status, 503));
});
test('POST forwards the caller JWT with a public key, never service-impersonating auth.uid()', async () => {
  await offline(async (url, init) => {
    assert.equal(String(url), 'https://fixture.invalid/rest/v1/rpc/set_my_ai_processor_consent_v3');
    assert.equal(init.headers.Authorization, 'Bearer fixture-user-token'); assert.equal(init.headers.apikey, 'fixture-public-key');
    const input = JSON.parse(init.body); assert.equal(input.p_expected_revision, 0); assert.equal(input.p_manifest_revision, MANIFEST_REVISION);
    assert.equal(input.p_processor, 'COHERE'); assert.equal(Object.hasOwn(input, 'p_user_id'), false);
    return new Response(JSON.stringify({ account_snapshot: owner, provider_id: 'COHERE', enabled: true, revision: 1, manifest_fingerprint: canonical.manifest_fingerprint }));
  }, async () => {
    const response = await load()(post(body())); const result = await response.json();
    assert.equal(response.status, 200); assert.equal(result.receipt.revision, 1); assert.equal(result.account_snapshot, owner);
  });
});
test('malformed expected revisions, owners and obsolete fingerprints never reach SQL', async () => {
  await offline(async () => { throw new Error('persistence should not be reached'); }, async () => {
    assert.equal((await load()(post(body({ expected_revision: '0' })))).status, 400);
    assert.equal((await load()(post(body({ user_id: 'other-owner' })))).status, 400);
    assert.equal((await load()(post(body({ manifest_fingerprint: 'b'.repeat(64) })))).status, 409);
  });
});
test('wrong owner, skipped revision and incomplete acknowledgements fail closed', async () => {
  for (const acknowledgement of [{}, { account_snapshot: 'other-owner' }, { account_snapshot: owner, provider_id: 'COHERE', enabled: true, revision: 9, manifest_fingerprint: canonical.manifest_fingerprint }]) {
    await offline(async () => new Response(JSON.stringify(acknowledgement)), async () => assert.equal((await load()(post(body()))).status, 503));
  }
});
test('revocation works while eligibility is unavailable and acknowledges the next clock', async () => {
  await offline(async (url, init) => {
    const input = JSON.parse(init.body); assert.equal(input.p_enabled, false); assert.equal(input.p_expected_revision, 4);
    return new Response(JSON.stringify({ account_snapshot: owner, provider_id: 'COHERE', enabled: false, revision: 5, manifest_fingerprint: '' }));
  }, async () => {
    const response = await load()(post(body({ enabled: false, expected_revision: 4, manifest_fingerprint: '' })));
    assert.equal(response.status, 200); assert.equal((await response.json()).receipt.enabled, false);
  });
});
test('unauthenticated reads and unsupported methods do not touch the database', async () => {
  await offline(async () => { throw new Error('database must not be touched'); }, async () => {
    assert.equal((await load({ auth: false })(get())).status, 401);
    assert.equal((await load()(new Request('https://app.invalid', { method: 'DELETE' }))).status, 405);
  });
});
