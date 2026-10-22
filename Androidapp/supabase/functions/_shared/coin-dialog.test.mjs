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
