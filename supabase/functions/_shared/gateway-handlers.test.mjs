import test from 'node:test';
import assert from 'node:assert/strict';
import { readFile, writeFile } from 'node:fs/promises';
import { stripTypeScriptTypes } from 'node:module';
import { hybridReadiness } from './hybrid-routing.js';
import { fileURLToPath } from 'node:url';

const ROOT = fileURLToPath(new URL('..', import.meta.url));
const UUID = '11111111-1111-4111-8111-111111111111';
const OTHER = '22222222-2222-4222-8222-222222222222';
const validAnswer = { summary:'s', what:'w', understand:'u', care:'c', watch:'watch', escalate:'e' };

const sharedSource = await readFile(`${ROOT}/_shared/request-contract.js`, 'utf8');
const shared = await import(`data:text/javascript;base64,${Buffer.from(sharedSource).toString('base64')}`);
const paymentsSource = await readFile(`${ROOT}/_shared/payments.ts`, 'utf8');
const validatorSource = paymentsSource.match(/export function isConfiguredSecret[\s\S]*?\n\}/)[0].replace('export ', '');
const validatorJs = stripTypeScriptTypes(validatorSource);
const isConfiguredSecret = new Function(`${validatorJs}; return isConfiguredSecret;`)();

async function loadHandler(file, state) {
  let handler;
  const source = await readFile(file, 'utf8');
  // Imports are deliberately stripped and replaced with test-controlled dependencies.
  const stripped = source.split('\n').filter(line => !line.startsWith('import ')).join('\n');
  const js = stripTypeScriptTypes(stripped);
  const context = {
    console: { log(){}, error(){} }, Response, Request, Headers, URL, URLSearchParams,
    TextDecoder, Uint8Array, ArrayBuffer, AbortController, DOMException, JSON, Object, String, Number, Boolean,
    setTimeout, clearTimeout,
    Deno: { env: { get(name) { return state.env[name]; } } },
    corsHeaders: { 'Access-Control-Allow-Origin': '*' },
    CORS_HEADERS: { 'Access-Control-Allow-Origin': '*' },
    validateAiRequest: shared.validateAiRequest,
    normalizeAiProvider: shared.normalizeAiProvider,
    SUPPORTED_AI_PROVIDERS: shared.SUPPORTED_AI_PROVIDERS,
    isConfiguredSecret, hybridReadiness,
    validateImageRequest: shared.validateImageRequest,
    isAccountRestricted: async () => false,
    authenticatedUserId: async () => state.authenticatedId ?? UUID,
    supabaseSecretKey: () => state.env.SUPABASE_SERVICE_ROLE_KEY,
    createClient: () => makeClient(state),
    serve: fn => { handler = fn; },
    fetch: async (url, options) => fakeFetch(String(url), options, state),
  };
  // vm is used without allowing imports, filesystem access, or network access from handler code.
  const vm = await import('node:vm');
  const boundedCode = (await readFile(`${ROOT}/_shared/bounded-json.js`, 'utf8')).replace('export async function', 'async function');
  vm.runInNewContext(boundedCode, context);
  vm.runInNewContext(js, context, { filename: file });
  assert.equal(typeof handler, 'function', `handler loaded: ${file}`);
  return handler;
}

function makeClient(state) {
  const chain = table => ({
    select() { return this; }, eq() { return this; },
    async maybeSingle() {
      state.tables.push(table);
      if (table === 'cyclecare_admin_roles') return { data: state.admin ? { user_id: UUID } : null, error: null };
      if (table === 'economy_profiles') return { data: { tier: state.tier ?? 'FREE' }, error: null };
      if (table === 'cyclecare_privacy_consents') return { data: state.consent ? { ai_context_enabled: true, image_generation_enabled: true } : { ai_context_enabled: false, image_generation_enabled: false }, error: null };
      if (table === 'cyclecare_runtime_config') return { data: { active_ai_provider: state.provider ?? 'OPENROUTER' }, error: null };
      return { data: null, error: null };
    },
  });
  return { from(table) { return chain(table); }, async rpc(name, args) { state.rpc.push({ name, args }); return state.quota ?? { data: { allowed: true }, error: null }; } };
}

async function fakeFetch(url, options, state) {
  state.fetches.push({ url, options });
  if (url.endsWith('/auth/v1/user')) return new Response(JSON.stringify({ id: state.authenticatedId ?? UUID }), { status: 200, headers: {'content-type':'application/json'} });
  assert.ok(!url.includes('supabase.co'), 'only auth endpoint may be fetched from Supabase');
  if (state.providerShape === 'image') return new Response(state.imageBytes ?? new Uint8Array([137,80,78,71]), { status: 200, headers: {'content-type':'image/png'} });
  if (state.providerShape === 'bad-image-type') return new Response('not image', { status: 200, headers: {'content-type':'application/json'} });
  if (state.providerShape === 'bad-ai') return new Response(JSON.stringify({ choices: [{ message: { content: JSON.stringify({ summary:'only' }) } }] }), { status: 200, headers: {'content-type':'application/json'} });
  const payload = state.providerShape === 'minimal' ? validAnswer : validAnswer;
  return new Response(JSON.stringify({ choices: [{ message: { content: JSON.stringify(payload) } }] }), { status: 200, headers: {'content-type':'application/json'} });
}

function baseState(extra={}) { return { env: { SUPABASE_URL:'https://local.invalid', SUPABASE_ANON_KEY:'anon-key-12345', SUPABASE_SERVICE_ROLE_KEY:'service-key-12345', OPENROUTER_API_KEY:'provider-key-12345', POLLINATIONS_API_KEY:'pollinations-key-12345' }, tier:'PREMIUM', consent:true, admin:false, provider:'OPENROUTER', tables:[], rpc:[], fetches:[], ...extra }; }
function aiBody(extra={}) { return { schema_version:2, user_question:'What is normal?', context_mode:'minimal', ai_consent:true, ...extra }; }
function imageBody(extra={}) { return { style:'botanical', image_consent:true, ...extra }; }
async function json(res) { return await res.json(); }

const ai = await loadHandler(`${ROOT}/ai-gateway/index.ts`, baseState());
const image = await loadHandler(`${ROOT}/image-generation/index.ts`, baseState());

for (const [name, make, body, expected] of [
  ['AI missing consent', s=>loadHandler(`${ROOT}/ai-gateway/index.ts`,s), aiBody({ai_consent:false}), 400],
  ['AI free tier', s=>loadHandler(`${ROOT}/ai-gateway/index.ts`,s), aiBody(), 403],
  ['AI admin still requires consent', s=>loadHandler(`${ROOT}/ai-gateway/index.ts`,s), aiBody({ai_consent:false}), 400],
]) test(name, async () => { const s=baseState({tier: name.includes('free')?'FREE':'PREMIUM', admin:name.includes('admin')}); const h=await make(s); const r=await h(new Request('https://local/ai',{method:'POST',headers:{authorization:'Bearer token', 'content-type':'application/json'},body:JSON.stringify(body)})); assert.equal(r.status,expected); assert.equal(s.fetches.filter(x=>!x.url.endsWith('/auth/v1/user')).length,0); });

test('AI quota denial prevents provider fetch and uses server-auth UUID', async () => { const s=baseState({authenticatedId:UUID, quota:{data:{allowed:false,reason:'daily_limit'},error:null}}); const h=await loadHandler(`${ROOT}/ai-gateway/index.ts`,s); const r=await h(new Request('https://local',{method:'POST',headers:{authorization:'Bearer token'},body:JSON.stringify(aiBody())})); assert.equal(r.status,429); assert.equal(s.rpc[0].name,'consume_cyclecare_ai_quota'); assert.equal(s.rpc[0].args.p_user_id,UUID); assert.equal(s.rpc[0].args.p_kind,'chat'); assert.equal(s.fetches.length,1); });

test('AI premium consented success is strict six-field response', async () => { const s=baseState(); const h=await loadHandler(`${ROOT}/ai-gateway/index.ts`,s); const r=await h(new Request('https://local',{method:'POST',headers:{authorization:'Bearer token'},body:JSON.stringify(aiBody())})); const b=await json(r); assert.equal(r.status,200); assert.deepEqual(Object.keys(b.data).sort(),['careText','escalateText','summary','understandText','watchText','whatText']); assert.equal(s.rpc[0].args.p_user_id,UUID); assert.equal(s.rpc[0].args.p_kind,'chat'); });

test('AI minimal excludes health fields; expanded sends bounded summary and history only', async () => { const s1=baseState({providerShape:'minimal'}); const h1=await loadHandler(`${ROOT}/ai-gateway/index.ts`,s1); await h1(new Request('https://local',{method:'POST',headers:{authorization:'Bearer token'},body:JSON.stringify(aiBody({context:{summary:'secret'}}))})); assert.equal(s1.fetches.length,1); const s2=baseState(); const h2=await loadHandler(`${ROOT}/ai-gateway/index.ts`,s2); await h2(new Request('https://local',{method:'POST',headers:{authorization:'Bearer token'},body:JSON.stringify(aiBody({context_mode:'expanded',context:{summary:'bounded summary'},conversation_history:[{role:'user',content:'prior'}]}))})); const sent=JSON.parse(s2.fetches[1].options.body); const msg=sent.messages[1].content; assert.match(msg,/bounded summary/); assert.match(msg,/user: prior/); assert.doesNotMatch(msg,/profiles|cycles|daily logs|health_field/); assert.deepEqual(s2.tables,['cyclecare_admin_roles','economy_profiles','cyclecare_privacy_consents','cyclecare_runtime_config']); });

test('AI legacy and unknown fields fail before provider', async () => { for (const body of [aiBody({schema_version:1}),aiBody({legacy_field:true})]) { const s=baseState(); const h=await loadHandler(`${ROOT}/ai-gateway/index.ts`,s); const r=await h(new Request('https://local',{method:'POST',headers:{authorization:'Bearer token'},body:JSON.stringify(body)})); assert.equal(r.status,400); assert.equal(s.fetches.length,1); } });

test('AI invalid provider shape returns 502', async () => { const s=baseState({providerShape:'bad-ai'}); const h=await loadHandler(`${ROOT}/ai-gateway/index.ts`,s); const r=await h(new Request('https://local',{method:'POST',headers:{authorization:'Bearer token'},body:JSON.stringify(aiBody())})); assert.equal(r.status,502); });

test('Image consent, tier, style, custom prompt and private keyword gates', async () => { for (const [extra,status] of [[{image_consent:false},400],[{style:'unknown'},400],[{custom_prompt:'period symptoms'},400],[{custom_prompt:'x'.repeat(501)},400]]) { const s=baseState(); const h=await loadHandler(`${ROOT}/image-generation/index.ts`,s); const r=await h(new Request('https://local',{method:'POST',headers:{authorization:'Bearer token'},body:JSON.stringify(imageBody(extra))})); assert.equal(r.status,status); assert.equal(s.fetches.length,0); } const s=baseState({tier:'FREE'}); const h=await loadHandler(`${ROOT}/image-generation/index.ts`,s); const r=await h(new Request('https://local',{method:'POST',headers:{authorization:'Bearer token'},body:JSON.stringify(imageBody())})); assert.equal(r.status,403); });

test('Image success returns provider bytes and image quota uses server-auth UUID', async () => { const s=baseState({authenticatedId:UUID,providerShape:'image',imageBytes:new Uint8Array([1,2,3,4])}); const h=await loadHandler(`${ROOT}/image-generation/index.ts`,s); const r=await h(new Request('https://local',{method:'POST',headers:{authorization:'Bearer token'},body:JSON.stringify(imageBody({custom_prompt:'calm light'}))})); assert.equal(r.status,200); assert.equal(r.headers.get('content-type'),'image/png'); assert.deepEqual([...new Uint8Array(await r.arrayBuffer())],[1,2,3,4]); assert.equal(s.rpc[0].args.p_user_id,UUID); assert.equal(s.rpc[0].args.p_kind,'image'); });

test('Image invalid provider content type errors', async () => { const s=baseState({providerShape:'bad-image-type'}); const h=await loadHandler(`${ROOT}/image-generation/index.ts`,s); const r=await h(new Request('https://local',{method:'POST',headers:{authorization:'Bearer token'},body:JSON.stringify(imageBody())})); assert.equal(r.status,502); });

process.on('exit', async () => { /* output is captured by the shell runner */ });

for (const provider of ['HYBRID', 'POLLINATIONS', 'UNKNOWN']) {
  test(`unsupported runtime ${provider} cannot consume quota or contact a provider`, async () => {
    const s = baseState({ provider });
    const h = await loadHandler(`${ROOT}/ai-gateway/index.ts`, s);
    const r = await h(new Request('https://local', { method: 'POST', headers: { authorization: 'Bearer token' }, body: JSON.stringify(aiBody()) }));
    assert.equal(r.status, 503);
    assert.equal(s.rpc.length, 0);
    assert.equal(s.fetches.length, 1);
  });
}
test('missing chat credential fails before quota reservation', async () => {
  const s = baseState(); delete s.env.OPENROUTER_API_KEY;
  const h = await loadHandler(`${ROOT}/ai-gateway/index.ts`, s);
  const r = await h(new Request('https://local', { method: 'POST', headers: { authorization: 'Bearer token' }, body: JSON.stringify(aiBody()) }));
  assert.equal(r.status, 503); assert.equal(s.rpc.length, 0); assert.equal(s.fetches.length, 1);
});
test('missing image credential fails before quota reservation', async () => {
  const s = baseState(); delete s.env.POLLINATIONS_API_KEY;
  const h = await loadHandler(`${ROOT}/image-generation/index.ts`, s);
  const r = await h(new Request('https://local', { method: 'POST', headers: { authorization: 'Bearer token' }, body: JSON.stringify(imageBody()) }));
  assert.equal(r.status, 503); assert.equal(s.rpc.length, 0); assert.equal(s.fetches.length, 0);
});
test('client provider override is rejected rather than routing to another processor', async () => {
  const s = baseState();
  const h = await loadHandler(`${ROOT}/ai-gateway/index.ts`, s);
  const r = await h(new Request('https://local', { method: 'POST', headers: { authorization: 'Bearer token' }, body: JSON.stringify(aiBody({ provider: 'COHERE' })) }));
  assert.equal(r.status, 400); assert.equal(s.rpc.length, 0); assert.equal(s.fetches.length, 1);
});


test('Hybrid cannot route under a manually changed runtime provider field', async () => {
  const s = baseState({ provider: 'HYBRID' });
  const h = await loadHandler(`${ROOT}/ai-gateway/index.ts`, s);
  const r = await h(new Request('https://local', { method: 'POST', headers: { authorization: 'Bearer token' }, body: JSON.stringify(aiBody()) }));
  assert.equal(r.status, 503);
  assert.equal(s.rpc.length, 0);
  assert.equal(s.fetches.length, 1);
});
