import test from 'node:test';
import assert from 'node:assert/strict';
import { buildLocalSuggestions, describeStep, planRiskLevel, stepKind, planCardState, PLAN_OUTCOME } from './adminCommand.js';

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

test('plan card closes on saved run status even without a decided flag', () => {
  // Roy 08:41: card looked active but the run was already closed.
  assert.equal(planCardState({ run_status: 'done' }).decided, true);
  assert.equal(planCardState({ run_status: 'done' }).outcome.label, 'Completed');
  assert.equal(planCardState({ run_status: 'rejected' }).decided, true);
  assert.equal(planCardState({ run_status: 'rejected' }).outcome.label, 'Cancelled - no change was made');
  assert.equal(planCardState({ run_status: 'failed' }).outcome.tone, 'bad');
  assert.ok(!planCardState({ run_status: 'failed' }).outcome.label.includes('no change'), 'failed never claims zero writes');
  assert.ok(planCardState({ run_status: 'rejected' }).outcome.label.includes('no change'), 'rejected proves zero writes');
  assert.equal(planCardState({ run_status: 'unconfirmed' }).outcome.label, 'Accepted but not confirmed - do not repeat; review the current state');
  assert.equal(planCardState({ run_status: 'executing' }).decided, true, 'in-flight run is not clickable');
  assert.equal(planCardState({ run_status: 'unknown' }).outcome.label, 'Outcome unclear - ask me to check before trying again');
  assert.equal(planCardState({ run_status: 'something-new' }).outcome, PLAN_OUTCOME.unknown, 'unrecognized status never looks active');
});

test('plan card stays active only while the run is still proposed', () => {
  assert.equal(planCardState({ run_status: 'proposed' }).decided, false);
  assert.equal(planCardState({ run_status: 'proposed' }).outcome, null);
  assert.equal(planCardState({}).decided, false, 'fresh local echo has no run status yet');
  assert.equal(planCardState({ decided: true }).decided, true, 'decided meta still closes without run status');
});
