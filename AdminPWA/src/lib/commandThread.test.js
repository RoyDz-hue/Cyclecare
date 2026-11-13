import test from 'node:test';
import assert from 'node:assert/strict';
import { applyRealtimeInsert } from './commandThread.js';

test('ignores an exact duplicate by id', () => {
  const cur = [{ id: 'u1', role: 'assistant', kind: 'chat', content: 'Hi' }];
  const row = { id: 'u1', role: 'assistant', kind: 'chat', content: 'Hi' };
  assert.equal(applyRealtimeInsert(cur, row), cur);
});

test('replaces a local chat echo with the server row instead of duplicating it', () => {
  const cur = [
    { id: 'm1', role: 'user', kind: 'chat', content: 'Good morning' },
    { id: 'a-1', echo: true, role: 'assistant', kind: 'chat', content: 'Good morning! How can I assist you today?' },
  ];
  const row = { id: 'srv-1', role: 'assistant', kind: 'chat', content: 'Good morning! How can I assist you today?' };
  const next = applyRealtimeInsert(cur, row);
  assert.equal(next.length, 2);
  assert.equal(next.filter((m) => m.content.includes('assist you')).length, 1);
  assert.equal(next[1].id, 'srv-1');
});

test('keeps a cached non-echo message that merely shares content', () => {
  const cur = [{ id: 'old', role: 'assistant', kind: 'chat', content: 'Same words' }];
  const row = { id: 'srv-2', role: 'assistant', kind: 'chat', content: 'Same words' };
  const next = applyRealtimeInsert(cur, row);
  assert.equal(next.length, 2);
});

test('replaces plan and result echoes by run_id even when content differs', () => {
  const cur = [
    { id: 'p-1', echo: true, role: 'assistant', kind: 'plan', content: 'Plan summary', run_id: 'run-1' },
    { id: 'r-1', echo: true, role: 'assistant', kind: 'result', content: 'formatted result', run_id: 'run-1' },
  ];
  const planRow = { id: 'srv-p', role: 'assistant', kind: 'plan', content: 'Plan summary. Reason: x', run_id: 'run-1' };
  const resultRow = { id: 'srv-r', role: 'assistant', kind: 'result', content: 'Coin change confirmed.', run_id: 'run-1' };
  const afterPlan = applyRealtimeInsert(cur, planRow);
  assert.equal(afterPlan.filter((m) => m.run_id === 'run-1').length, 2);
  assert.ok(afterPlan.some((m) => m.id === 'srv-p'));
  const afterResult = applyRealtimeInsert(afterPlan, resultRow);
  assert.equal(afterResult.filter((m) => m.run_id === 'run-1').length, 2);
  assert.ok(afterResult.some((m) => m.id === 'srv-r'));
  assert.ok(!afterResult.some((m) => m.echo));
});

test('replaces the pending optimistic user bubble when the real user row arrives', () => {
  const cur = [{ id: 'local-1', pending: true, role: 'user', kind: 'chat', content: 'Good morning' }];
  const row = { id: 'srv-u', role: 'user', kind: 'chat', content: 'Good morning' };
  const next = applyRealtimeInsert(cur, row);
  assert.equal(next.length, 1);
  assert.equal(next[0].id, 'srv-u');
});

test('two identical rapid replies each keep their server row', () => {
  const cur = [
    { id: 'a-1', echo: true, role: 'assistant', kind: 'chat', content: 'Hello' },
    { id: 'a-2', echo: true, role: 'assistant', kind: 'chat', content: 'Hello' },
  ];
  const first = applyRealtimeInsert(cur, { id: 'srv-1', role: 'assistant', kind: 'chat', content: 'Hello' });
  const second = applyRealtimeInsert(first, { id: 'srv-2', role: 'assistant', kind: 'chat', content: 'Hello' });
  assert.equal(second.filter((m) => m.content === 'Hello').length, 2);
  assert.deepEqual(second.map((m) => m.id), ['srv-1', 'srv-2']);
});

test('appends unrelated rows in order and leaves others untouched', () => {
  const cur = [{ id: 'm1', role: 'user', kind: 'chat', content: 'A' }];
  const next = applyRealtimeInsert(cur, { id: 'm2', role: 'assistant', kind: 'chat', content: 'B' });
  assert.deepEqual(next.map((m) => m.id), ['m1', 'm2']);
});

test('realtime copy of a user message replaces its settled local echo, no duplicate bubble', () => {
  const local = [{ id: 'local-1', echo: true, role: 'user', kind: 'chat', content: 'Give any random user 2 coins', created_at: '2026-10-31T04:18:00Z', pending: false }];
  const row = { id: 'srv-9', role: 'user', kind: 'chat', content: 'Give any random user 2 coins', created_at: '2026-10-31T04:18:01Z' };
  const next = applyRealtimeInsert(local, row);
  assert.equal(next.length, 1, 'one bubble, not two');
  assert.equal(next[0].id, 'srv-9');
});
