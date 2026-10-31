import test from 'node:test';import assert from 'node:assert/strict';import{parseCoinRequest,applyCoinReply,isConfirmation,selectCandidate}from './coin-dialog.js';
test('amount-first debit does not treat amount as name',()=>assert.deepEqual(parseCoinRequest('Deduct 1000 coins from roy'),{delta:-1000,query:'roy',reason:null}));
test('credit layouts',()=>{assert.equal(parseCoinRequest('Give Kevin 200').query,'Kevin');assert.equal(parseCoinRequest('Give 200 coins to Kevin').delta,200)});
test('suggested reason preserves recipient amount',()=>{const s=applyCoinReply({delta:-1000,recipient:{email:'roy@example.com'},awaiting:'reason'},'Help me put a random reason');assert.equal(s.reason,'Admin adjustment');assert.equal(s.delta,-1000);assert.equal(s.recipient.email,'roy@example.com')});
test('confirmations preserve filled slots',()=>{for(const text of ['Yes','Yes him','that one']){assert.ok(isConfirmation(text));assert.equal(applyCoinReply({reason:'Grant',awaiting:'reason'},text).reason,'Grant')}});

test('candidate choices use saved list, not a new lookup',()=>{
 const rows=[{display_name:'Kevin Kamau',email:'kamau@example.com'},{display_name:'Kevin Smith',email:'smith@example.com'},{display_name:'Rod Kevin',email:'rod@example.com'}];
 for(const t of ['2','number 2','the second one','Smith','smith@example.com'])assert.equal(selectCandidate(rows,t),rows[1]);
 assert.equal(selectCandidate(rows,'Kevin'),null);assert.equal(selectCandidate(rows,'9'),null);
});

test('debit amount-of replaces stale slots and strips ledger suffix',()=>{assert.deepEqual(parseCoinRequest('Debit amount of 651 coins from deburgs ledger'),{delta:-651,query:'deburgs',reason:null});assert.equal(parseCoinRequest('Debit 651 coins from Kevin wallet').delta,-651)});

test('group gift parses as all-member scope, not a person',()=>{
 assert.deepEqual(parseCoinRequest('Gift all users 2 coins each'),{scope:'all',delta:2,query:null,reason:null});
 assert.deepEqual(parseCoinRequest('Give everyone 5 coins'),{scope:'all',delta:5,query:null,reason:null});
 assert.deepEqual(parseCoinRequest('Gift 10 coins to all members'),{scope:'all',delta:10,query:null,reason:null});
 assert.deepEqual(parseCoinRequest('Take 3 coins from everybody'),{scope:'all',delta:-3,query:null,reason:null});
});
test('named gifts stay single-member',()=>{
 assert.deepEqual(parseCoinRequest('Give Kevin 200'),{delta:200,query:'Kevin',reason:null});
 assert.deepEqual(parseCoinRequest('Gift 2 coins to Sarah'),{delta:2,query:'Sarah',reason:null});
 assert.deepEqual(parseCoinRequest('Give 200 coins to Kevin'),{delta:200,query:'Kevin',reason:null});
});

test('send and whole-community group phrasings parse as all-member scope',()=>{
 assert.deepEqual(parseCoinRequest('Send everyone 2 coins'),{scope:'all',delta:2,query:null,reason:null});
 assert.deepEqual(parseCoinRequest('Give the whole community 5 coins each'),{scope:'all',delta:5,query:null,reason:null});
});

test('gift typo Git still parses as an all-member grant',()=>{
 assert.deepEqual(parseCoinRequest('Git all users 3 coins each'),{scope:'all',delta:3,query:null,reason:null});
});
test('bare Yes while awaiting a reason defaults to Admin adjustment, keeps it a single gift',()=>{
 const s=applyCoinReply({scope:'all',delta:3,awaiting:'reason'},'Yes');
 assert.equal(s.reason,'Admin adjustment');
 assert.equal(s.scope,'all');
 assert.equal(s.delta,3);
});

test('parseCoinRequest recognizes random-member gifts', () => {
  assert.deepEqual(parseCoinRequest('Give any random user 2 coins'), { delta: 2, query: null, random: true, reason: null });
  assert.deepEqual(parseCoinRequest('Gift a random member 5 coins'), { delta: 5, query: null, random: true, reason: null });
  assert.deepEqual(parseCoinRequest('Send 3 coins to random person, reason welcome bonus'), { delta: 3, query: null, random: true, reason: 'welcome bonus' });
  assert.deepEqual(parseCoinRequest('Give Kevin 200'), { delta: 200, query: 'Kevin', reason: null }, 'named member unchanged');
  const all = parseCoinRequest('Gift all users 2 coins each');
  assert.equal(all.scope, 'all', 'all-scope unchanged');
  assert.equal(all.random, undefined);
});

test('parseCoinRequest understands natural sentences and preserves decimals exactly', () => {
  assert.deepEqual(parseCoinRequest('Broo I therefore direct you acutely to categorically disburse 3.5 coins each to all our users on the go'),
    { scope: 'all', delta: 3.5, query: null, reason: null }, 'Roy exact phrasing, decimal preserved');
  assert.deepEqual(parseCoinRequest('Disburse 2 coins each to all our users'), { scope: 'all', delta: 2, query: null, reason: null });
  assert.deepEqual(parseCoinRequest('Give Kevin 3.5 coins'), { delta: 3.5, query: 'Kevin', reason: null });
  assert.deepEqual(parseCoinRequest('Send Kevin 50 coins'), { delta: 50, query: 'Kevin', reason: null });
  assert.equal(parseCoinRequest('How many coins does Kevin have?'), null, 'no amount-verb coin question stays null');
  assert.equal(parseCoinRequest('What is the price of 100 coins?'), null, 'pricing question stays null');
});

test('decimals beyond 3 places are preserved exactly for the flow to judge', () => {
  const r = parseCoinRequest('Disburse 3.4567 coins each to all our users');
  assert.equal(r.scope, 'all');
  assert.equal(r.delta, 3.4567);
});

test('whole-number phrasing still parses after decimal support', () => {
  const r = parseCoinRequest('Give all users 4 coins');
  assert.equal(r.scope, 'all');
  assert.equal(r.delta, 4);
});

test('"any user" delegates the pick; signed amounts are preserved (Roy 08:25 defect)', () => {
  // Roy's exact screenshot message: must become a random single-recipient
  // DEDUCTION card, never a clarify that changes the amount.
  assert.deepEqual(parseCoinRequest('Give any user -5 coins'), { delta: -5, query: null, random: true, reason: null });
  assert.deepEqual(parseCoinRequest('Give any user 5 coins'), { delta: 5, query: null, random: true, reason: null });
  assert.deepEqual(parseCoinRequest('Send 10 coins to any member'), { delta: 10, query: null, random: true, reason: null });
  assert.deepEqual(parseCoinRequest('Deduct 5 coins from any user'), { delta: -5, query: null, random: true, reason: null });
  assert.deepEqual(parseCoinRequest('Gift any person 3 coins, reason surprise'), { delta: 3, query: null, random: true, reason: 'surprise' });
  // Existing random phrasings unchanged.
  assert.deepEqual(parseCoinRequest('Give any random user 2 coins'), { delta: 2, query: null, random: true, reason: null });
  // "any user" is never a recipient NAME to search for.
  const named = parseCoinRequest('Give any user 5 coins');
  assert.equal(named.query, null);
});

test('amount written away from the word "coins" is read exactly (Roy 08:49 defect)', () => {
  // Roy's exact message: amount AND all-users scope are both given, so ACI
  // must never ask for the amount again. Scope all + negative = the existing
  // mass-deduction refusal (policy unchanged).
  assert.deepEqual(parseCoinRequest('Valued at -4.1 each donate to all users coins'),
    { scope: 'all', delta: -4.1, query: null, reason: null });
  assert.deepEqual(parseCoinRequest('Donate 5 coins to all users'), { scope: 'all', delta: 5, query: null, reason: null });
  // No amount written still means no amount: never invent one.
  assert.equal(parseCoinRequest('Gift all users coins'), null);
  assert.equal(parseCoinRequest('Donate coins to everyone'), null);
});

test('an unrelated number is never treated as the coin amount (offline ambiguity checks)', () => {
  // Dates, years, ordinals, fiat, identifiers, and either/or amounts must
  // clarify (null), never become a gift of 2026 / 500 / 31 / -2342 coins.
  assert.equal(parseCoinRequest('Gift all users coins on 2026-11-01'), null, 'ISO date');
  assert.equal(parseCoinRequest('Credit coins to all users today 2026'), null, 'bare year');
  assert.equal(parseCoinRequest('Send coins worth 500 KES to all users'), null, 'fiat amount');
  assert.equal(parseCoinRequest('Gift all users 5 or 10 coins'), null, 'either/or amount');
  assert.equal(parseCoinRequest('Gift all users coins, invoice INV-2342'), null, 'letter-glued identifier');
  assert.equal(parseCoinRequest('Gift coins to all users on the 31st'), null, 'ordinal day');
  // The amount written as the value still reads exactly.
  assert.deepEqual(parseCoinRequest('Valued at -4.1 each donate to all users coins'), { scope: 'all', delta: -4.1, query: null, reason: null });
  assert.deepEqual(parseCoinRequest('Gift coins worth 2.5 to all users'), { scope: 'all', delta: 2.5, query: null, reason: null });
  // A proper coin amount next to "coins" still wins over a date in the same message.
  assert.deepEqual(parseCoinRequest('Gift all users 1000 coins on 2026-11-01'), { scope: 'all', delta: 1000, query: null, reason: null });
  // Instruction prose containing "coins" is never a recipient name.
  assert.equal(parseCoinRequest('Credit coins to all users today 2026'), null);
});

test('sign is semantics: negative/minus wording is a deduction, never a gift (Roy 11:56 defect)', () => {
  // Roy's exact message: the buggy run proposed Gift +3. It must parse as -3.
  assert.deepEqual(parseCoinRequest('Gift all users a surprise bal of negative three coins each'),
    { scope: 'all', delta: -3, query: null, reason: null });
  assert.deepEqual(parseCoinRequest('Gift all users minus 3 coins'), { scope: 'all', delta: -3, query: null, reason: null });
  assert.deepEqual(parseCoinRequest('Give everyone negative two coins'), { scope: 'all', delta: -2, query: null, reason: null });
  // Positive wording stays positive - word and digit forms, both distinct.
  assert.deepEqual(parseCoinRequest('Gift all users three coins'), { scope: 'all', delta: 3, query: null, reason: null });
  assert.deepEqual(parseCoinRequest('Gift all users 3 coins'), { scope: 'all', delta: 3, query: null, reason: null });
});

test('negative zero and decimal words fail closed (no silent round or change)', () => {
  // "negative 0" parses to 0; the flow rejects it (amount < 1) with a clarify.
  const zero = parseCoinRequest('Gift all users negative 0 coins');
  assert.equal(zero && zero.delta, 0);
  // Decimal words are not word-numbers: never round "two point five" to 2.
  assert.equal(parseCoinRequest('Gift all users negative two point five coins'), null);
  assert.equal(parseCoinRequest('Gift all users two point five coins'), null);
});

test('contradictory amounts fail closed; a repeated amount is fine', () => {
  assert.equal(parseCoinRequest('Gift all users three coins, actually five coins'), null);
  assert.equal(parseCoinRequest('Gift 3 coins to all users, make it 5 coins'), null);
  assert.equal(parseCoinRequest('Gift -3 coins to all users, yes 3 coins'), null);
  assert.deepEqual(parseCoinRequest('Gift 3 coins to all users, exactly 3 coins'), { scope: 'all', delta: 3, query: null, reason: null });
});

test('amount-attached direction: the cue bound to the amount decides (Roy 12:14, corrected)', () => {
  // "allocated" attaches the amount to the audience; "diminishing" directs it.
  assert.deepEqual(parseCoinRequest('Directive therefore, allocated to all users a diminishing balance of 2 coins each'),
    { scope: 'all', delta: -2, query: null, reason: null });
  // An audience verb with no amount cue keeps the positive default.
  assert.deepEqual(parseCoinRequest('Allocated to all users a balance of 2 coins each'),
    { scope: 'all', delta: 2, query: null, reason: null });
  // A reduction noun attached to the amount is a deduction.
  assert.deepEqual(parseCoinRequest('Apply a reduction of 2 coins each to all users'),
    { scope: 'all', delta: -2, query: null, reason: null });
});

test('ungrounded direction is never a positive default (Roy 12:30 "Liquidate" defect)', () => {
  // Unknown effect words are not the parser's to guess: null -> semantics planner.
  assert.equal(parseCoinRequest('Liquidate all users with 4 coins each'), null);
  assert.equal(parseCoinRequest('Tokenize each person with 2 coins'), null);
  assert.equal(parseCoinRequest('2 coins each to all users'), null);
  assert.equal(parseCoinRequest('From all user freeze all their balnces'), null);
  // Grounded positives and negatives still parse deterministically.
  assert.deepEqual(parseCoinRequest('Gift 4 coins to all users'), { scope: 'all', delta: 4, query: null, reason: null });
  assert.deepEqual(parseCoinRequest('Directive therefore, allocated to all users a diminishing balance of 2 coins each'),
    { scope: 'all', delta: -2, query: null, reason: null });
});
