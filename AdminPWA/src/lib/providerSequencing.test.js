import assert from 'node:assert/strict';
import { test } from 'node:test';
import {
  createProviderSequencingState,
  establishProviderSession,
  observeProviderSession,
  beginProviderMutation,
  invalidateProviderMutation,
  isProviderMutationCurrent,
  deferProviderRefresh,
  consumeDeferredProviderRefresh
} from './providerSequencing.js';

const session = (access_token, id = 'admin') => ({ access_token, refresh_token: `${access_token}-refresh`, user: { id } });

test('old initial refresh is ignored after a provider save begins', () => {
  const state = createProviderSequencingState(session('initial'));
  const initialRefreshEpoch = state.sessionEpoch;
  const mutation = beginProviderMutation(state, session('initial'));
  observeProviderSession(state, session('rotated'));
  assert.equal(state.sessionEpoch, initialRefreshEpoch);
  assert.equal(isProviderMutationCurrent(state, mutation), true);
  invalidateProviderMutation(state);
  assert.equal(isProviderMutationCurrent(state, mutation), false);
});

test('token rotation during the save keeps the authoritative mutation current', () => {
  const state = createProviderSequencingState(session('initial'));
  const mutation = beginProviderMutation(state, session('initial'));
  observeProviderSession(state, session('rotated'));
  assert.equal(isProviderMutationCurrent(state, mutation), true);
});

test('manual refresh during mutation is deferred, not started concurrently', () => {
  const state = createProviderSequencingState(session('initial'));
  beginProviderMutation(state, session('initial'));
  assert.equal(deferProviderRefresh(state, true), true);
  assert.equal(consumeDeferredProviderRefresh(state), true);
  assert.equal(consumeDeferredProviderRefresh(state), false);
});

test('signout/relogin invalidates a previous provider save even for the same user', () => {
  const state = createProviderSequencingState(session('initial'));
  const mutation = beginProviderMutation(state, session('initial'));
  establishProviderSession(state, null);
  establishProviderSession(state, session('new'));
  assert.equal(isProviderMutationCurrent(state, mutation), false);
});
