import test from 'node:test';
import assert from 'node:assert/strict';
import {formatCommandStep,formatCommandOutcome} from './commandResults.js';
const read=(tool,result)=>formatCommandStep({tool,result,ok:true});
test('richest uses sourced names, balances and ties',()=>{const s=read('get_user_insights',{richest:[{display_name:'Neema',coin_balance:500},{display_name:'Tabitha',coin_balance:500}]});assert.match(s,/shared by Neema, Tabitha/);assert.match(s,/500 coins/);assert.doesNotMatch(s,/user_id|\{|\}/);});
test('missing names never invent one or leak identifiers',()=>{const s=read('get_user_insights',{richest:[{user_id:'private-uuid',coin_balance:30}]});assert.match(s,/Unnamed member/);assert.doesNotMatch(s,/private-uuid/);});
test('tier split and empty reads are plain language',()=>{assert.match(read('get_user_insights',{tier_split:{FREE:18,PREMIUM:9}}),/premium: 9 members/);assert.match(read('get_flagged_messages',[]),/no flagged messages/);assert.match(read('get_safety_signals',[]),/No safety signals/);});
test('prices, flags and safety render actual values',()=>{assert.match(read('get_pricing',{coin_packages:[{title:'Starter',coins:100,price_kes:98,price_usd:0.98}]}),/Starter: 100 coins, KES 98 \/ US\$0.98/);assert.match(read('get_flagged_messages',[{sender_alias:'Mia',content:'Hi'}]),/Mia: "Hi"/);assert.match(read('get_safety_signals',[{display_name:'Mia',level:'HIGH',trigger_key:'heavy_bleeding'}]),/Mia: high signal, heavy bleeding/);});
test('group gift result names the gift and its confirmation state',()=>{
  assert.match(formatCommandStep({tool:'gift_all_coins',kind:'WRITE',ok:true,verified:true,result:{recipients:20,total_coins:40}}),/The gift to every member completed successfully/);
  assert.match(formatCommandStep({tool:'gift_all_coins',kind:'WRITE',ok:true,verified:true,result:{recipients:20,total_coins:40}}),/confirmed/);
  assert.match(formatCommandStep({tool:'gift_all_coins',kind:'WRITE',ok:false}),/could not be completed/);
});
test('unconfirmed writes never claim completed success',()=>{
  for (const verified of [false, null, undefined]) {
    const text = formatCommandStep({tool:'gift_all_coins',kind:'WRITE',ok:true,verified,result:{recipients:20,total_coins:40}});
    assert.match(text,/accepted but not confirmed/);
    assert.match(text,/Do not repeat/);
    assert.doesNotMatch(text,/completed successfully/);
  }
});
test('writes distinguish verified, accepted and failed outcomes',()=>{assert.match(formatCommandStep({tool:'set_user_tier',ok:true,verified:true}),/confirmed/);assert.match(formatCommandStep({tool:'send_notice',ok:true}),/separate follow-up check was not available/);assert.match(formatCommandStep({tool:'send_notice',ok:false}),/could not be completed/);assert.doesNotMatch(formatCommandOutcome({status:'failed',steps:[{ok:false,error:'raw sql code'}]}),/raw sql/);});

test('coin grants require read-back before success',()=>{
 assert.match(formatCommandStep({tool:'adjust_coins',ok:true,verified:null}),/accepted but not confirmed/);
 assert.match(formatCommandStep({tool:'adjust_coins',ok:true,verified:false}),/Do not repeat/);
 const text=formatCommandStep({tool:'adjust_coins',ok:true,verified:true,balances:[{balance:3180}]});
 assert.match(text,/confirmed/);assert.match(text,/3,180 coins/);
});
