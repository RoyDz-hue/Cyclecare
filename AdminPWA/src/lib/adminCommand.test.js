import test from 'node:test';
import assert from 'node:assert/strict';
import { buildLocalSuggestions, describeStep, planRiskLevel, stepKind } from './adminCommand.js';

test('stepKind classifies tools', () => {
  assert.equal(stepKind('get_user_insights'), 'READ');
  assert.equal(stepKind('send_notice'), 'WRITE');
  assert.equal(stepKind('adjust_coins'), 'RISKY');
  assert.equal(stepKind('set_user_status'), 'RISKY');
  assert.equal(stepKind('unknown_future_tool'), 'READ');
});

test('planRiskLevel marks risky and write plans for approval', () => {
  assert.equal(planRiskLevel([{ tool: 'get_user_insights' }]), 'read');
  assert.equal(planRiskLevel([{ tool: 'send_notice' }]), 'write');
  assert.equal(planRiskLevel([{ tool: 'get_pricing' }, { tool: 'set_user_status' }]), 'risky');
  assert.equal(planRiskLevel([]), 'empty');
  assert.equal(planRiskLevel(null), 'empty');
});

test('describeStep falls back sensibly', () => {
  assert.equal(describeStep({ tool: 'adjust_coins', args: { targets: ['a', 'b'], delta: 500 } }), 'Gift 500 coins 2 targets');
  assert.equal(describeStep({ tool: 'get_user_insights', args: { focus: 'richest' } }), 'Member insights (richest)');
  assert.equal(describeStep({ tool: 'x', label: 'Use the label' }), 'Use the label');
});

test('buildLocalSuggestions seeds chips from live insights', () => {
  const chips = buildLocalSuggestions({ most_active: [{ user_id: 'u1' }], inactive: [{ user_id: 'u2' }] });
  assert.ok(chips[0].prompt.includes('u1'));
  assert.ok(chips.some((c) => c.label === 'Moderation queue'));
  assert.ok(chips.length <= 6);
  const empty = buildLocalSuggestions({});
  assert.ok(empty.length >= 4);
});
