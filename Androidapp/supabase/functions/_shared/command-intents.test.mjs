import test from 'node:test';import assert from 'node:assert/strict';import{asksMemberCount,mixesCoinWithOtherAction,asksJoinedCount,asksJoinedList,asksTierCount,asksTopBalance,mixesWriteActions,joinedWindowBoundary}from './command-intents.js';
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

test('mixed write categories ask for a split, single writes and reads pass',()=>{
 assert.ok(mixesWriteActions('Gift Kevin 5 coins and ban Sarah'));
 assert.ok(mixesWriteActions('Send everyone 2 coins and then announce it'));
 assert.ok(mixesWriteActions('Upgrade Kevin to premium and gift him 100 coins'));
 assert.ok(mixesWriteActions('Send a notice about the meeting and ban repeat offenders'));
 assert.equal(mixesWriteActions('Gift all users 2 coins each'),false);
 assert.equal(mixesWriteActions('Gift Kevin and Sarah 5 coins'),false);
 assert.equal(mixesWriteActions('Send a notice about the community call'),false);
 assert.equal(mixesWriteActions('How many coins does Kevin have and what is his tier?'),false);
 assert.equal(mixesWriteActions('Ban Sarah and suspend Kevin'),false);
});
test('joined windows are calendar boundaries in Nairobi time',()=>{
 // 2026-10-30T12:00:00Z is a Friday, 15:00 in Nairobi.
 const now = Date.UTC(2026,9,30,12,0,0);
 assert.deepEqual(joinedWindowBoundary('today',now),{since:'2026-10-29T21:00:00.000Z',span:'today'});
 assert.deepEqual(joinedWindowBoundary('this week',now),{since:'2026-10-25T21:00:00.000Z',span:'this week'});
 assert.deepEqual(joinedWindowBoundary('this month',now),{since:'2026-09-30T21:00:00.000Z',span:'this month'});
 // Sunday belongs to the week that started the previous Monday.
 const sun = Date.UTC(2026,10,1,8,0,0);
 assert.equal(joinedWindowBoundary('this week',sun).since,'2026-10-25T21:00:00.000Z');
});

test('joined-list questions are recognized with their window',()=>{
 assert.equal(asksJoinedList('Any person who has created an account today'),'today');
 assert.equal(asksJoinedList('Who joined today?'),'today');
 assert.equal(asksJoinedList('show me new members this week'),'this week');
 assert.equal(asksJoinedList('which users signed up this month'),'this month');
 assert.equal(asksJoinedList('list members who created an account today'),'today');
});
test('joined-list intent never steals counts or unrelated asks',()=>{
 assert.equal(asksJoinedList('How many members joined today'),null);
 assert.equal(asksJoinedList('How many users signed up this week'),null);
 assert.equal(asksJoinedList('How many premium members'),null);
 assert.equal(asksJoinedList('show me the newest users and their tier split'),null);
 assert.equal(asksJoinedList('gift all users 2 coins each'),null);
});
