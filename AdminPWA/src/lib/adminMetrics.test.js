import assert from 'node:assert/strict';
import { test } from 'node:test';
import { ADMIN_RESPONSE_LIMITS, limitedResponseLabel, providerEvidenceLabel } from './adminMetrics.js';

test('limited response labels never present capped rows as population totals', () => {
  assert.equal(ADMIN_RESPONSE_LIMITS.profiles, 50);
  assert.equal(limitedResponseLabel('profiles', 50), '50 accounts returned (latest 50; response cap)');
  assert.equal(limitedResponseLabel('transactions', 3), '3 transactions returned');
  assert.equal(limitedResponseLabel('profiles', 2, { noun: 'accounts shown' }), '2 accounts shown');
  assert.match(limitedResponseLabel('economy', 100), /response cap/);
});

test('provider evidence distinguishes recorded credentials from tested availability', () => {
  assert.equal(providerEvidenceLabel(), 'Availability not checked');
  assert.equal(providerEvidenceLabel({ statusLoaded: true, keyPresent: true }), 'Credential recorded; connection not tested');
  assert.equal(providerEvidenceLabel({ statusLoaded: true, keyPresent: false }), 'No credential recorded');
});
