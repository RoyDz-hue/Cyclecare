import test from 'node:test';import assert from 'node:assert/strict';import{asksMemberCount}from './command-intents.js';
test('plain member-count questions are recognized',()=>{
 for(const t of ['How many users do we have','how many members','How many users are there?','What is the total number of users','total member count','user count','How many users'])assert.ok(asksMemberCount(t),t);
});
test('qualified or unrelated requests are left to the planner',()=>{
 for(const t of ['how many users joined this week','how many premium members','how many coins does roy have','gift all users 2 coins each','how many users do we have and ban the inactive ones','show me the member count breakdown'])assert.equal(asksMemberCount(t),false,t);
});
