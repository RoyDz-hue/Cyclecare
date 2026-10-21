import test from 'node:test';
import assert from 'node:assert/strict';
import { psychRound, formatFxAmount, fxPreview, describeRefresh } from './fx.js';

test('psychRound applies charm endings by magnitude', () => {
  assert.equal(psychRound(3643.2), 3699);   // UGX starter pack
  assert.equal(psychRound(462.0), 469);
  assert.equal(psychRound(45.2), 45.9);
  assert.equal(psychRound(0.91), 0.99);
  assert.equal(psychRound(0), null);
  assert.equal(psychRound(-5), null);
});

test('formatFxAmount matches the Android store formatting', () => {
  assert.equal(formatFxAmount('UGX', 3699), 'UGX 3,699');
  assert.equal(formatFxAmount('KES', 50), 'KES 50');
  assert.equal(formatFxAmount('EUR', 0.99), 'EUR 0.99');
});

test('fxPreview uses the admin override when present', () => {
  const row = { rate_per_usd: 4000, admin_override: 3700 };
  assert.deepEqual(fxPreview(row, 0.99), { rate: 3700, display: 3699 });
  assert.deepEqual(fxPreview({ rate_per_usd: 4000 }, 0.99), { rate: 4000, display: 3999 });
  assert.equal(fxPreview({ rate_per_usd: 0 }, 1), null);
});

test('describeRefresh is human readable', () => {
  assert.equal(describeRefresh(null), 'No refresh has run yet.');
  const s = describeRefresh({ created_at: '2026-10-21T13:59:00Z', triggered_by: 'lazy', status: 'ok', source: 'open.er-api.com', quotes_updated: 6 });
  assert.ok(s.includes('succeeded') && s.includes('open.er-api.com') && s.includes('lazy'));
});
