import assert from 'node:assert/strict';
import test from 'node:test';
import { adminRequest, clearAdminSession, getAdminSession, saveAdminSession } from '../src/lib/supabaseAdmin.js';

const config = { url: 'https://offline.invalid', anonKey: 'fake-public-key' };
const session = (access = 'fake-old-access', refresh = 'fake-old-refresh') => ({ access_token: access, refresh_token: refresh, user: { id: 'same-owner' } });
const response = (status, body = {}) => new Response(JSON.stringify(body), { status });
function deferred() {
  let resolve;
  const promise = new Promise(r => { resolve = r; });
  return { promise, resolve };
}
function setup(t) {
  const oldFetch = globalThis.fetch;
  const oldWindow = globalThis.window;
  const store = new Map();
  const events = [];
  globalThis.window = {
    sessionStorage: { getItem: k => store.get(k) || null, setItem: (k, v) => store.set(k, v), removeItem: k => store.delete(k) },
    dispatchEvent: e => events.push(e.type)
  };
  clearAdminSession();
  saveAdminSession(session());
  t.after(() => {
    clearAdminSession();
    globalThis.fetch = oldFetch;
    if (oldWindow === undefined) delete globalThis.window;
    else globalThis.window = oldWindow;
  });
  return events;
}

test('concurrent 401s share one refresh and both retain the rotated session', async t => {
  const events = setup(t);
  const started = deferred(), refreshed = deferred();
  let refreshCalls = 0;
  globalThis.fetch = async (url, init) => {
    if (url.includes('grant_type=refresh_token')) {
      refreshCalls++;
      started.resolve();
      return refreshed.promise;
    }
    return response(init.headers.get('Authorization') === 'Bearer fake-new-access' ? 200 : 401);
  };
  const requests = [adminRequest(config, 'data'), adminRequest(config, 'status')];
  await started.promise;
  refreshed.resolve(response(200, session('fake-new-access', 'fake-new-refresh')));
  assert.deepEqual((await Promise.all(requests)).map(r => r.status), [200, 200]);
  assert.equal(refreshCalls, 1);
  assert.equal(getAdminSession().access_token, 'fake-new-access');
  assert.deepEqual(events, []);
});

test('a late 401 retries the current rotated token without reusing its old refresh token', async t => {
  setup(t);
  const late = deferred();
  let refreshCalls = 0;
  globalThis.fetch = async (url, init) => {
    if (url.includes('grant_type=refresh_token')) {
      refreshCalls++;
      return response(200, session('fake-new-access', 'fake-new-refresh'));
    }
    if (init.headers.get('Authorization') === 'Bearer fake-new-access') return response(200);
    if (url.endsWith('/late')) return late.promise;
    return response(401);
  };
  const delayedRequest = adminRequest(config, 'late');
  assert.equal((await adminRequest(config, 'first')).status, 200);
  late.resolve(response(401));
  assert.equal((await delayedRequest).status, 200);
  assert.equal(refreshCalls, 1);
});

test('refresh completion cannot replace a newer login, including the same owner', async t => {
  setup(t);
  const started = deferred(), refreshed = deferred();
  let sessionCallbacks = 0;
  globalThis.fetch = async url => {
    if (url.includes('grant_type=refresh_token')) { started.resolve(); return refreshed.promise; }
    return response(401);
  };
  const pending = adminRequest(config, 'data', {}, () => sessionCallbacks++);
  await started.promise;
  clearAdminSession();
  saveAdminSession(session('fake-relogin-access', 'fake-relogin-refresh'));
  refreshed.resolve(response(200, session('fake-stale-access', 'fake-stale-refresh')));
  assert.equal((await pending).status, 401);
  assert.equal(getAdminSession().access_token, 'fake-relogin-access');
  assert.equal(sessionCallbacks, 0);
});

test('an old request cannot retry under a new same-owner login after logout', async t => {
  setup(t);
  const late = deferred();
  let calls = 0;
  globalThis.fetch = async () => { calls++; return late.promise; };
  const pending = adminRequest(config, 'mutation', { method: 'POST', body: '{}' });
  clearAdminSession();
  saveAdminSession(session('fake-relogin-access', 'fake-relogin-refresh'));
  late.resolve(response(401));
  assert.equal((await pending).status, 401);
  assert.equal(calls, 1, 'must not replay the old mutation with the new login');
  assert.equal(getAdminSession().access_token, 'fake-relogin-access');
});

test('shared refresh rejection expires the old session once, without duplicate refreshes', async t => {
  const events = setup(t);
  const started = deferred(), rejected = deferred();
  let refreshCalls = 0;
  globalThis.fetch = async url => {
    if (url.includes('grant_type=refresh_token')) { refreshCalls++; started.resolve(); return rejected.promise; }
    return response(401);
  };
  const pending = [adminRequest(config, 'data'), adminRequest(config, 'status')];
  await started.promise;
  rejected.resolve(response(400, { error: 'fake refresh rejected' }));
  await Promise.all(pending);
  assert.equal(refreshCalls, 1);
  assert.equal(getAdminSession(), null);
  assert.deepEqual(events, ['cyclecare:session-expired']);
});

test('a temporary refresh network failure preserves the session and sanitizes errors', async t => {
  const events = setup(t);
  globalThis.fetch = async url => {
    if (url.includes('grant_type=refresh_token')) throw new Error('fake-private-provider-detail');
    return response(401);
  };
  await assert.rejects(adminRequest(config, 'data'), error => !error.message.includes('fake-private-provider-detail'));
  assert.equal(getAdminSession().access_token, 'fake-old-access');
  assert.deepEqual(events, []);
});
