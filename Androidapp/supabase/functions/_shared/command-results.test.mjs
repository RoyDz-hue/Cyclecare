import test from 'node:test';
import assert from 'node:assert/strict';
import {formatCommandOutcome} from './command-results.js';
test('expired cards say to ask again',()=>assert.match(formatCommandOutcome({status:'expired'}),/expired.*Ask again/));
test('unconfirmed writes never claim completed',()=>{const text=formatCommandOutcome({status:'unconfirmed',steps:[{tool:'set_user_status',kind:'WRITE',ok:true,verified:false}]});assert.match(text,/accepted but not confirmed/);assert.doesNotMatch(text,/completed successfully/);assert.match(text,/Do not repeat/);});
