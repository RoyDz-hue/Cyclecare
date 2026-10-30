import test from 'node:test';
import assert from 'node:assert/strict';
import {formatCommandOutcome, formatTopBalanceReply, formatJoinedListReply} from './command-results.js';
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

test('joined-list reply names members with join dates',()=>{
 const out=formatJoinedListReply({total:2,members:[{display_name:'Neema',email:'neema@example.com',created_at:'2026-10-30T07:12:00Z',status:'ACTIVE'},{display_name:'',email:'tab@example.com',created_at:'2026-10-30T06:00:00Z',status:'ACTIVE'}]},'today');
 assert.match(out,/2 new members joined today/);
 assert.match(out,/Neema \(neema@example\.com\): joined 2026-10-30/);
 assert.doesNotMatch(out,/Unnamed member/);
});
test('joined-list reply is honest when empty or truncated',()=>{
 assert.match(formatJoinedListReply({total:0,members:[]},'today'),/No new members joined today/);
 const out=formatJoinedListReply({total:55,members:[{display_name:'A',created_at:'2026-10-01T00:00:00Z'}]},'this month');
 assert.match(out,/55 new members joined this month/);
 assert.match(out,/and 54 more/);
 assert.match(formatJoinedListReply(null,'today'),/unavailable right now/);
});

test('joined list renders real names, email fallback, empty case, and no rankings note', () => {
  const named = formatJoinedListReply({ total: 2, members: [
    { display_name: 'Wanjiru Kamau', email: 'wanjiru@example.com', created_at: '2026-10-30T08:15:00Z', status: 'active' },
    { display_name: null, email: 'otieno.m@example.com', created_at: '2026-10-30T06:02:00Z', status: 'active' },
  ] }, 'today');
  assert.match(named, /^2 new members joined today:/);
  assert.match(named, /Wanjiru Kamau \(wanjiru@example\.com\)/);
  assert.match(named, /otieno\.m \(otieno\.m@example\.com\)/, 'email prefix stands in when no name');
  assert.ok(!/unnamed/i.test(named), 'no Unnamed member rows');
  assert.ok(!/rank|top|leaderboard/i.test(named), 'no unrelated rankings note');

  const empty = formatJoinedListReply({ total: 0, members: [] }, 'today');
  assert.equal(empty, 'No new members joined today.');

  const capped = formatJoinedListReply({ total: 54, members: new Array(50).fill({ display_name: 'Member', email: 'm@example.com', created_at: '2026-10-30T00:00:00Z' }) }, 'this week');
  assert.match(capped, /and 4 more\./);
});
