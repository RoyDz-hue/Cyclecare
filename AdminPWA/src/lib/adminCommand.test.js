import test from 'node:test';
import assert from 'node:assert/strict';
import { buildLocalSuggestions, describeStep, stepContentLines, planReviewBlocker, planRiskLevel, stepKind, planCardState, PLAN_OUTCOME } from './adminCommand.js';
import SPEC from './adminToolSpec.json' with { type: 'json' };

test('stepKind classifies tools', () => {
  assert.equal(stepKind('get_user_insights'), 'READ');
  assert.equal(stepKind('send_notice'), 'WRITE');
  assert.equal(stepKind('adjust_coins'), 'RISKY');
  assert.equal(stepKind('set_user_status'), 'RISKY');
  assert.equal(stepKind('unknown_future_tool'), 'UNKNOWN');
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

test('stepContentLines shows exact notice content from the bound args', () => {
  const lines = stepContentLines({ tool: 'send_notice', label: 'Send a good morning message to all users', args: { title: 'Good Morning from CycleCare!', body: 'Habari ya asubuhi!', audience: 'ALL' } });
  assert.deepEqual(lines, [
    { k: 'Title', v: 'Good Morning from CycleCare!' },
    { k: 'Message', v: 'Habari ya asubuhi!' },
    { k: 'Audience', v: 'All members except banned/suspended' },
    { k: 'Channel', v: 'In-app notice + push notification' },
  ]);
});

test('stepContentLines shows recipient names for custom lists and ignores other tools', () => {
  const named = stepContentLines({ tool: 'send_notice', args: { body: 'Hi', targets: ['u1', 'u2'], target_names: ['Amina', 'Brian'] } });
  assert.equal(named.find((l) => l.k === 'Audience').v, 'Amina, Brian');
  const lines = stepContentLines({ tool: 'send_notice', args: { body: 'Hi', targets: ['u1', 'u2'] } });
  assert.equal(lines.find((l) => l.k === 'Audience').v, '2 selected');
  assert.equal(lines.find((l) => l.k === 'Title').v, 'CycleCare Notice');
  assert.deepEqual(stepContentLines({ tool: 'adjust_coins', args: {} }), []);
});

test('PWA spec mirror is identical to the edge function spec data', async () => {
  const { readFileSync } = await import('node:fs');
  const edge = JSON.parse(readFileSync(new URL('../../../Androidapp/supabase/functions/admin-tool-spec-data.json', import.meta.url), 'utf8'));
  const pwa = JSON.parse(readFileSync(new URL('./adminToolSpec.json', import.meta.url), 'utf8'));
  assert.deepEqual(pwa, edge, 'PWA and edge capability specs drifted');
});

test('every WRITE/RISKY tool gets real review content on the card, not just a label', () => {
  const fullArgs = { targets: ['u1'], target_names: ['Amina'], delta: -4.1, reason: 'r', title: 't', body: 'b', audience: 'ALL', status: 'ACTIVE', tier: 'FREE', enabled: true, persona_id: 'p1', item_id: 'i1', artifact_name: 'Rose Obelisk', action: 'REMOVE', packages: [{ id: 'p1' }], price_coins: 10, is_active: true, daily_post_cap: 3, focus: 'all' };
  for (const spec of SPEC.tools) {
    if (spec.kind === 'READ') continue;
    const lines = stepContentLines({ tool: spec.name, args: fullArgs });
    assert.ok(lines.length > 0, `${spec.name}: review card would show only a label`);
  }
});

test('review derives direction from the bound amount, never the label', () => {
  const deduct = stepContentLines({ tool: 'adjust_coins', args: { delta: -50, targets: ['u1'], target_names: ['Amina'] } });
  assert.equal(deduct.find((l) => l.k === 'Amount per member').v, 'Deduct 50 coins');
  const gift = stepContentLines({ tool: 'adjust_coins', args: { delta: 50, targets: ['u1'], target_names: ['Amina'] } });
  assert.equal(gift.find((l) => l.k === 'Amount per member').v, 'Gift 50 coins');
});

test('bulk coin review shows member count and total from the bound snapshot', () => {
  const lines = stepContentLines({ tool: 'deduct_all_coins', args: { delta: 4.1, reason: 'r', recipients: ['a', 'b', 'c'] } });
  assert.equal(lines.find((l) => l.k === 'Members').v, '3 members');
  assert.equal(lines.find((l) => l.k === 'Total').v, '12.3 coins in total');
});

test('unknown or contentless writes fail closed: no Confirm path', () => {
  assert.ok(planReviewBlocker([{ tool: 'made_up_write', label: 'Do something', args: {} }]));
  assert.ok(planReviewBlocker([{ tool: 'adjust_coins', label: 'Deduct coins', args: {} }]));
  assert.equal(planReviewBlocker([{ tool: 'get_user_insights', args: {} }]), null); // reads never block
  assert.equal(planReviewBlocker([{ tool: 'adjust_coins', args: { delta: -5, targets: ['u1'], target_names: ['Amina'], reason: 'r' } }]), null);
});
