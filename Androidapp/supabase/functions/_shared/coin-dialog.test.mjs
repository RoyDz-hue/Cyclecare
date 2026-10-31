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
