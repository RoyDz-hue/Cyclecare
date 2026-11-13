import assert from 'node:assert/strict';
import { test } from 'node:test';
import {
  adminAuthErrorMessage,
  adminJson,
  adminResultMessage,
  DEFAULT_SUPABASE_PUBLISHABLE_KEY,
  DEFAULT_SUPABASE_URL,
  resolveSupabaseConfig
} from './supabaseAdmin.js';

test('unconfigured and placeholder PWA values resolve to the valid CycleCare public config', () => {
  const missing = resolveSupabaseConfig();
  const placeholder = resolveSupabaseConfig({
    url: 'https://your-project.supabase.co',
    publishableKey: 'sb_publishable_REPLACE_WITH_CYCLECARE_PUBLISHABLE_KEY'
  });
  for (const config of [missing, placeholder]) {
    assert.equal(config.url, DEFAULT_SUPABASE_URL);
    assert.equal(config.anonKey, DEFAULT_SUPABASE_PUBLISHABLE_KEY);
    assert.equal(config.projectRef, 'apjqcocxtylsofgxhetb');
  }
});

test('a valid configured publishable key and local development URL remain supported', () => {
  const config = resolveSupabaseConfig({
    url: 'http://127.0.0.1:54321/',
    publishableKey: 'sb_publishable_test_public_key_1234567890'
  });
  assert.equal(config.url, 'http://127.0.0.1:54321');
  assert.equal(config.anonKey, 'sb_publishable_test_public_key_1234567890');
  assert.equal(config.projectRef, '');
});

test('a URL for a different or placeholder project cannot be paired with the CycleCare default key', () => {
  for (const url of ['https://other-project.supabase.co', 'https://your-project.supabase.co']) {
    const config = resolveSupabaseConfig({ url });
    assert.equal(config.url, DEFAULT_SUPABASE_URL);
    assert.equal(config.anonKey, DEFAULT_SUPABASE_PUBLISHABLE_KEY);
  }
  const invalidPair = resolveSupabaseConfig({
    url: 'https://other-project.supabase.co',
    publishableKey: DEFAULT_SUPABASE_PUBLISHABLE_KEY
  });
  assert.equal(invalidPair.url, DEFAULT_SUPABASE_URL);
  assert.equal(invalidPair.anonKey, DEFAULT_SUPABASE_PUBLISHABLE_KEY);
});

test('auth failures map to activity-specific text and do not echo invalid API details', () => {
  const message = adminAuthErrorMessage('signIn', { message: 'Invalid API key: sb_secret_value' });
  assert.equal(message, "We couldn't sign you in right now. Please try again later.");
  assert.doesNotMatch(message, /api key|supabase|backend|secret/i);
  assert.equal(
    adminAuthErrorMessage('signIn', { message: 'Invalid login credentials' }),
    "That email and password don't match. Check them and try again."
  );
});

test('RPC result messages are sanitized before UI display', () => {
  assert.equal(
    adminResultMessage('This account is suspended.', 'The action could not be completed.'),
    'This account is suspended.'
  );
  assert.equal(
    adminResultMessage('Postgres database error: invalid API key', 'The action could not be completed.'),
    'The action could not be completed.'
  );
});

test('admin API error responses are replaced with user-facing action messages', async () => {
  const originalFetch = globalThis.fetch;
  try {
    globalThis.fetch = async () => new Response(JSON.stringify({ message: 'Invalid API key (HTTP 401)' }), {
      status: 401,
      headers: { 'Content-Type': 'application/json' }
    });
    await assert.rejects(
      adminJson({ url: DEFAULT_SUPABASE_URL, anonKey: DEFAULT_SUPABASE_PUBLISHABLE_KEY }, 'rest/v1/rpc/admin_get_data'),
      /Your session has expired\. Sign in again to continue\./
    );
  } finally {
    globalThis.fetch = originalFetch;
  }
});

test('failed runtime provider saves remain failures and do not expose backend details', async () => {
  const originalFetch = globalThis.fetch;
  try {
    globalThis.fetch = async () => new Response(JSON.stringify({ message: 'Postgres constraint violation' }), {
      status: 500,
      headers: { 'Content-Type': 'application/json' }
    });
    await assert.rejects(
      adminJson({ url: DEFAULT_SUPABASE_URL, anonKey: DEFAULT_SUPABASE_PUBLISHABLE_KEY }, 'rest/v1/rpc/admin_update_runtime_config'),
      /That CycleCare action couldn't be completed\. Please try again later\./
    );
  } finally {
    globalThis.fetch = originalFetch;
  }
});
