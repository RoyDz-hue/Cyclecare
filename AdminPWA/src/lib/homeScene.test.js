import test from 'node:test';
import assert from 'node:assert/strict';
import { deriveTiers, createWorld, stepWorld, layoutLabels, SPEED_CAP, hitBody } from './homeScene.js';

test('tier counts come from the partition and add up to the total', () => {
  const t = deriveTiers({ counts: { profiles: 20, suspended: 1, banned: 0 }, member_insights: { tier_split: { FREE: 13, SUPPORTER: 0, PREMIUM: 7 } } });
  assert.equal(t.loaded, true);
  assert.equal(t.total, 20);
  assert.deepEqual(t.tiers.map((x) => [x.key, x.n]), [['PREMIUM', 7], ['FREE', 13], ['SUPPORTER', 0]]);
  assert.equal(t.unknown, 0);
  assert.equal(t.mismatch, false);
  assert.equal(t.states.suspended, 1);
});

test('unknown bucket is surfaced and a mismatch is flagged, never hidden', () => {
  const t = deriveTiers({ counts: { profiles: 10 }, member_insights: { tier_split: { FREE: 5, PREMIUM: 3, UNKNOWN: 1 } } });
  assert.equal(t.unknown, 1);
  assert.equal(t.mismatch, true);
});

test('missing data stays missing, no invented numbers', () => {
  const t = deriveTiers(null);
  assert.equal(t.loaded, false);
  assert.equal(t.total, null);
  assert.ok(t.tiers.every((x) => x.n === null));
  const partial = deriveTiers({ counts: { profiles: 4 } });
  assert.equal(partial.loaded, false);
  assert.ok(partial.tiers.every((x) => x.n === null));
});

test('ambient motion stays slow', () => {
  const w = createWorld();
  const env = { w: 390, h: 300, mode: 'home', drag: null };
  let maxV = 0;
  for (let i = 0; i < 1200; i++) { stepWorld(w, 1 / 60, env); if (i < 240) continue; for (const b of w.bodies) maxV = Math.max(maxV, Math.hypot(b.vx, b.vy)); }
  assert.ok(maxV <= SPEED_CAP.light + 1e-6, `max speed ${maxV}`);
  for (const b of w.bodies) { assert.ok(b.x >= 0 && b.x <= 390 && b.y >= 0 && b.y <= 300); }
});

test('labels never overlap in the home row, even with shapes stacked', () => {
  const w = createWorld();
  const env = { w: 390, h: 300, mode: 'home', drag: null };
  for (let i = 0; i < 300; i++) stepWorld(w, 1 / 60, env);
  w.bodies.forEach((b) => { b.x = 195; });
  const L = layoutLabels(w.bodies, 'home', 390, 300);
  const xs = L.map((l) => l.x).sort((a, b) => a - b);
  for (let i = 1; i < xs.length; i++) assert.ok(xs[i] - xs[i - 1] >= 95.9);
  assert.ok(xs[0] >= 0 && xs[2] <= 390);
});

test('strip mode docks shapes in a row and shapes do not overlap there', () => {
  const w = createWorld();
  for (let i = 0; i < 600; i++) stepWorld(w, 1 / 60, { w: 390, h: 300, mode: 'home', drag: null });
  for (let i = 0; i < 900; i++) stepWorld(w, 1 / 60, { w: 390, h: 72, mode: 'strip', drag: null });
  const b = w.bodies;
  assert.ok(b[0].x < b[1].x && b[1].x < b[2].x);
  for (let i = 1; i < b.length; i++) assert.ok(b[i].x - b[i - 1].x > b[i].rad + b[i - 1].rad);
  assert.equal(hitBody(b, b[1].x, b[1].y), 1);
});

test('scene grows back from the strip within about two seconds, then drifts slowly', () => {
  const w = createWorld();
  for (let i = 0; i < 300; i++) stepWorld(w, 1 / 60, { w: 390, h: 300, mode: 'home', drag: null });
  for (let i = 0; i < 300; i++) stepWorld(w, 1 / 60, { w: 390, h: 72, mode: 'strip', drag: null });
  for (let i = 0; i < 150; i++) stepWorld(w, 1 / 60, { w: 390, h: 300, mode: 'home', drag: null });
  const xs = w.bodies.map((b) => b.x);
  assert.ok(xs[0] < xs[1] && xs[1] < xs[2]);
  assert.ok(xs[2] - xs[0] > 120, `spread ${xs}`);
});
