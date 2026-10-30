import test from 'node:test';import assert from 'node:assert/strict';import{asksMemberCount,mixesCoinWithOtherAction,asksJoinedCount,asksTierCount,asksTopBalance}from './command-intents.js';
test('plain member-count questions are recognized',()=>{
 for(const t of ['How many users do we have','how many members','How many users are there?','What is the total number of users','total member count','user count','How many users'])assert.ok(asksMemberCount(t),t);
});
test('qualified or unrelated requests are left to the planner',()=>{
 for(const t of ['how many users joined this week','how many premium members','how many coins does roy have','gift all users 2 coins each','how many users do we have and ban the inactive ones','show me the member count breakdown'])assert.equal(asksMemberCount(t),false,t);
});

test('mixed coin + other action asks for a split',()=>{
 assert.ok(mixesCoinWithOtherAction('Gift Kevin 5 coins and ban Sarah'));
 assert.ok(mixesCoinWithOtherAction('Send everyone 2 coins and then announce it'));
 assert.equal(mixesCoinWithOtherAction('Gift all users 2 coins each'),false);
 assert.equal(mixesCoinWithOtherAction('Ban Sarah'),false);
});
test('filtered count questions are recognized with their filter',()=>{
 assert.equal(asksJoinedCount('How many members joined this week'),'this week');
 assert.equal(asksJoinedCount('how many users signed up today?'),'today');
 assert.equal(asksJoinedCount('How many members joined'),null);
 assert.equal(asksTierCount('How many premium members'),'premium');
 assert.equal(asksTierCount('free member count'),'free');
 assert.equal(asksTierCount('How many members'),null);
});
test('top-balance questions are recognized',()=>{
 assert.ok(asksTopBalance('Who has the most coins'));
 assert.ok(asksTopBalance("who's the richest member?"));
 assert.equal(asksTopBalance('Show me the coin leaderboard'),false);
});
