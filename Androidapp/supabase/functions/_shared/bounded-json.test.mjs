import test from 'node:test';
import assert from 'node:assert/strict';
import { fetchJsonBounded } from './bounded-json.js';

function mockFetch(t, callback) {
  const original = globalThis.fetch;
  globalThis.fetch = callback;
  t.after(() => { globalThis.fetch = original; });
}

test('bounded JSON helper accepts small JSON and retains response status', async t => {
  mockFetch(t, async () => new Response('{"id":"owner"}', { status: 200 }));
  const { response, data } = await fetchJsonBounded('https://offline.invalid');
  assert.equal(response.status, 200);
  assert.deepEqual(data, { id: 'owner' });
});

test('bounded JSON helper rejects declared oversize before consuming content', async t => {
  mockFetch(t, async () => new Response('{}', { headers: { 'content-length': '4096' } }));
  await assert.rejects(fetchJsonBounded('https://offline.invalid', {}, { maxBytes: 32 }), /response-too-large/);
});

test('bounded JSON helper rejects streamed oversize without trusting content-length', async t => {
  mockFetch(t, async () => new Response(new ReadableStream({ start(c) { c.enqueue(new TextEncoder().encode('x'.repeat(65))); c.close(); } })));
  await assert.rejects(fetchJsonBounded('https://offline.invalid', {}, { maxBytes: 64 }), /response-too-large/);
});

test('bounded JSON helper times out even if fetch ignores AbortSignal', async t => {
  mockFetch(t, async () => new Promise(() => {}));
  await assert.rejects(fetchJsonBounded('https://offline.invalid', {}, { timeoutMs: 5 }), /request-deadline/);
});

test('bounded JSON helper deadline includes stalled response-body reads', async t => {
  mockFetch(t, async () => new Response(new ReadableStream({ start() {} })));
  await assert.rejects(fetchJsonBounded('https://offline.invalid', {}, { timeoutMs: 5 }), /request-deadline|request-aborted/);
});

test('bounded JSON helper never exposes malformed response content in its error', async t => {
  mockFetch(t, async () => new Response('fake-private-response-detail'));
  await assert.rejects(fetchJsonBounded('https://offline.invalid'), error => error.message === 'invalid-json-response');
});

test('bounded JSON helper rejects already revoked requests without fetching', async t => {
  let calls = 0;
  mockFetch(t, async () => { calls++; return new Response('{}'); });
  const controller = new AbortController(); controller.abort();
  await assert.rejects(fetchJsonBounded('https://offline.invalid', { signal: controller.signal }), /request-aborted/);
  assert.equal(calls, 0);
});
