import test from 'node:test';
import assert from 'node:assert/strict';
import {formatCommandOutcome, formatTopBalanceReply} from './command-results.js';
test('expired cards say to ask again',()=>assert.match(formatCommandOutcome({status:'expired'}),/expired.*Ask again/));
test('unconfirmed writes never claim completed',()=>{const text=formatCommandOutcome({status:'unconfirmed',steps:[{tool:'set_user_status',kind:'WRITE',ok:true,verified:false}]});assert.match(text,/accepted but not confirmed/);assert.doesNotMatch(text,/completed successfully/);assert.match(text,/Do not repeat/);});

test('top balance reply names a single winner',()=>{
 assert.equal(formatTopBalanceReply([{user_id:'ana',coin_balance:500},{user_id:'ben',coin_balance:300}]),'ana has the most coins: 500 coins.');
});
test('top balance reply names every tie inside the window',()=>{
 assert.equal(formatTopBalanceReply([{user_id:'ana',coin_balance:500},{user_id:'ben',coin_balance:500},{user_id:'cy',coin_balance:100}]),'2 members tie for the most coins at 500 each: ana, ben.');
});
test('top balance reply is honest when the window runs out on ties',()=>{
 const rows = Array.from({length:10},(_,i)=>({user_id:'u'+i,coin_balance:120}));
 assert.equal(formatTopBalanceReply(rows),'At least 10 members tie for the most coins at 120 each: u0, u1, u2, u3, u4 and 5 more.');
});
test('top balance reply fails safe on empty or malformed rows',()=>{
 assert.equal(formatTopBalanceReply([]),"I couldn't check the balances just now.");
 assert.equal(formatTopBalanceReply(null),"I couldn't check the balances just now.");
 assert.equal(formatTopBalanceReply([{user_id:'ana'}]),"I couldn't check the balances just now.");
});
