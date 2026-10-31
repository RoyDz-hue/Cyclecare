// Explicit coin slots. A confirmation never executes a pending plan.
export function parseCoinRequest(text) {
  const t=text.trim().replace(/^debit\s+amount\s+of\s+/i,'deduct ').replace(/^debit\s+/i,'deduct ');
  let m=t.match(/^(deduct|remove|take)\s+(\d+(?:\.\d+)?)\s*(?:coins?\s*)?from\s+(?:all\s+(?:users?|members?)|everyone|everybody|every\s+(?:user|member))(?:[.,]?\s+(?:reason|because)\s*:?\s*(.+))?$/i);
  if(m)return {scope:'all',delta:-Number(m[2]),query:null,reason:m[3]?.trim()||null};
  m=t.match(/^(deduct|remove|take)\s+(\d+(?:\.\d+)?)\s*(?:coins?\s*)?from\s+(.+?)(?:[.,]?\s+(?:reason|because)\s*:?\s*(.+))?$/i);
  if(m){
    const q=m[3].trim().replace(/(?:['’]s)?\s+(?:ledger|wallet|account)\s*$/i,'');
    // A scope phrase is never a recipient name; let the fallback classify it.
    if(!/^(?:all\b|every\b|each\b|everyone\b|everybody\b|per[-\s]|the\s+whole\b|any\s+(?:user|member|person)\b)|\bcoins?\b/i.test(q))
      return {delta:-Number(m[2]),query:q,reason:m[4]?.trim()||null};
  }
  m=t.match(/^(give|grant|gift|git|credit|award|send)\s+(?:(\d+(?:\.\d+)?)\s+coins?\s+to\s+(?:all\s+(?:our\s+)?(?:users?|members?)|everyone|everybody|every\s+(?:user|member)|each\s+(?:user|member)|the\s+whole\s+(?:community|team))\s*(?:each)?|(?:all\s+(?:our\s+)?(?:users?|members?)|everyone|everybody|every\s+(?:user|member)|each\s+(?:user|member)|the\s+whole\s+(?:community|team))\s+(\d+(?:\.\d+)?)\s*coins?(?:\s+each)?)(?:[.,]?\s+(?:reason|because)\s*:?\s*(.+))?$/i);
  if(m)return {scope:'all',delta:Number(m[2]||m[3]),query:null,reason:m[4]?.trim()||null};
  m=t.match(/^(give|grant|gift|git|credit|award|send)\s+(?:(-?\d+(?:\.\d+)?)\s+coins?\s+to\s+(?:(?:any\s+|a\s+)?random\s+(?:user|member|person)|any\s+(?:user|member|person))|(?:(?:any\s+|a\s+)?random\s+(?:user|member|person)|any\s+(?:user|member|person))\s+(-?\d+(?:\.\d+)?)\s*coins?)(?:[.,]?\s+(?:reason|because)\s*:?\s*(.+))?$/i);
  if(m)return {delta:Number(m[2]||m[3]),query:null,random:true,reason:m[4]?.trim()||null};
  m=t.match(/^(give|grant|gift|git|credit|award|send)\s+(?:(\d+(?:\.\d+)?)\s+coins?\s+to\s+(.+?)|(.+?)\s+(\d+(?:\.\d+)?)\s*(?:coins?)?)(?:[.,]?\s+(?:reason|because)\s*:?\s*(.+))?$/i);
  if(m){
    const q=(m[3]||m[4]).trim();
    // A scope phrase is never a recipient name; let the fallback classify it.
    if(!/^(?:all\b|every\b|each\b|everyone\b|everybody\b|per[-\s]|the\s+whole\b|any\s+(?:user|member|person)\b)|\bcoins?\b/i.test(q))
      return {delta:Number(m[2]||m[5]),query:q,reason:m[6]?.trim()||null};
  }
  // Natural-language fallback: a coin amount plus a clear scope or verb anywhere
  // in the sentence, however it is phrased. Decimals stay exact; the flow
  // enforces the 3-decimal limit without rounding.
  // Questions are never directives: "did you give all users 5 coins?" asks
  // about the past, it does not order a gift. Skip the fallback for them.
  if(/^(?:who|what|what's|how|how's|when|why|which|did|do|does|is|are|was|were|has|have|had|am)\b/i.test(t))return null;
  if(/^(?:can|could|will|would|should|shall)\s+(?:i|we)\b/i.test(t))return null; // advice-seeking ("should I gift...?"), not a directive. "Can you gift...?" IS a directive and falls through.
  // A negated or deferred instruction is never a gift. Lexical guard, not
  // semantic proof: phrasings outside this list fall to the planner, which can
  // clarify but never execute without the review card.
  if(/\b(?:don't|do\s+not|didn't|did\s+not|doesn't|does\s+not|never|no\s+longer|refrain|avoid|let's\s+not|lets\s+not)\b/i.test(t))return null;
  if(/^(?:cancel|abort|undo|reverse|revert|stop|forget|hold\s+off|hold\s+on|hang\s+on|wait)\b/i.test(t))return null; // cancellation/reversal/deferral intent, not a gift
  // "5 or 10 coins" is a choice between amounts: never pick one.
  if(/-?\d+(?:\.\d+)?\s+or\s+-?\d+(?:\.\d+)?\s*coins?\b/i.test(t)||/\bcoins?\s+of\s+-?\d+(?:\.\d+)?\s+or\s+-?\d+(?:\.\d+)?/i.test(t))return null;
  const WORDNUM={one:1,two:2,three:3,four:4,five:5,six:6,seven:7,eight:8,nine:9,ten:10};
  let amt=t.match(/(-?\d+(?:\.\d+)?)[\s-]*coins?\b/i);
  if(!amt){const w=t.match(/\b(one|two|three|four|five|six|seven|eight|nine|ten)\s+coins?\b/i);if(w)amt=[w[0],String(WORDNUM[w[1].toLowerCase()])];}
  // The amount can sit away from the word "coins" ("Valued at -4.1 each
  // donate to all users coins"): read the exact signed number as long as a
  // coin word appears somewhere. The amount is never asked for again when it
  // is already written; scope/verb branches below still decide what it means.
  if(!amt&&/\bcoins?\b/i.test(t)){
    // Scan on a copy with date shapes blanked, then keep only plausible coin
    // amounts: no years/long ids, no ordinals ("31st"), no fiat ("500 KES"),
    // no letter-glued identifiers ("INV-2342"). Zero or several survivors is
    // ambiguous: better to clarify than to gift a date or a count.
    const masked=t.replace(/\b\d{4}-\d{1,2}-\d{1,2}\b/g,' ').replace(/\b\d{1,2}[/.]\d{1,2}[/.]\d{2,4}\b/g,' ');
    const cands=[];
    for(const n of masked.matchAll(/-?\d+(?:\.\d+)?/g)){
      const num=n[0],i=n.index;
      if(!num.includes('.')&&num.replace('-','').length>=4)continue; // year / long id
      if(/^(?:st|nd|rd|th)\b/i.test(masked.slice(i+num.length).trimStart()))continue; // ordinal day
      if(/^\s*(?:KES|USD|Ksh|shillings?)\b/i.test(masked.slice(i+num.length)))continue; // fiat, not coins
      if(/[A-Za-z]$/.test(masked.slice(0,i)))continue; // glued to a letter (INV-2342)
      cands.push(num);
    }
    if(cands.length===1)amt=[cands[0],cands[0]];
  }
  // Audience scope. Bare forms mean every member (grounded: the member registry
  // is exactly the all-users population). ANY adjective qualifier ("eligible",
  // "active", "paying", "extant", "new") names a SUBSET: qualifiers are audience
  // filters, never decoration, and no grandiose totality language ("universally
  // scoped", "the entirety of the ACTIVE-user ecosystem") cancels them. There is
  // no grounded filter for such qualifiers, so a qualified scope always returns
  // 'subset' and the flow clarifies instead of guessing or widening the audience.
  const allScope=/\b(?:all\s+(?:our\s+)?(?:users?|members?)|everyone|everybody|every\s+(?:user|member)|each\s+(?:user|member)|per[-\s]user|per[-\s]member|per[-\s]capita|the\s+whole\s+(?:community|team)|across\s+all\s+(?:our\s+)?(?:users?|members?))\b/i.test(t);
  const qualifiedScope=/\b(?:all\s+(?:our\s+)?(?:(?!our\b)[\w-]+\s+){1,3}(?:users?|members?)|every\s+(?:[\w-]+\s+){1,2}(?:user|member)|each\s+(?:[\w-]+\s+){1,2}(?:user|member)|per[-\s](?:[\w-]+[-\s]+){1,3}(?:user|member)|across\s+(?:(?!all\b|our\b)[\w-]+\s+){1,4}(?:users?|members?))\b/i.test(t);
  const deduct=/\b(?:deduct|deduction|debit|remove|take\s+back|take|claw\s*back)\b/i.test(t);
  const giveVerb=/\b(?:give|gives|giving|grant|grants|granting|gift|gifts|gifting|git|credit|credits|crediting|award|awards|awarding|send|sends|sending|disburse|disburses|disbursing|disbursement|distribute|distributes|distributing|distribution|pay|pays|drop|drops|allocate|allocates|allocating|allocation|entitle|entitlement|deposit|deposits|add|adds|adding|top\s*up|bless|blesses|shower|showers|receive|receives|receiving|get|gets|getting|donate|donates|donating|donation)\b/i.test(t);
  // A qualifier anywhere on the audience wins over any bare/totality wording in
  // the same message: "per extant eligible user ... per-capita" is still a
  // qualified audience, so subset is checked first.
  if(amt&&qualifiedScope&&(giveVerb||deduct||/\beach\b/i.test(t)||/\bper[-\s]/i.test(t))){
    return {scope:'subset',delta:(deduct&&!giveVerb?-1:1)*Number(amt[1]),query:null,reason:null};
  }
  if(amt&&allScope&&(giveVerb||deduct||/\beach\b/i.test(t)||/\bper[-\s](?:user|member)\b/i.test(t))){
    return {scope:'all',delta:(deduct&&!giveVerb?-1:1)*Number(amt[1]),query:null,reason:null};
  }
  if(amt&&(giveVerb||deduct)&&/\b(?:(?:any\s+|a\s+)?random\s+(?:user|member|person)|any\s+(?:user|member|person))\b/i.test(t)){
    // "any user" delegates the recipient choice: a random single member. A
    // signed amount is preserved (give -5 = deduct 5); a deduct verb forces a
    // deduction. Single-recipient and all-member deductions both go through
    // the same review card as gifts (bulk deductions added Oct 31).
    const d=Number(amt[1]);
    return {delta:(deduct&&!giveVerb)?-Math.abs(d):d,query:null,random:true,reason:null};
  }
  return null;
}
export const isConfirmation=t=>/^(?:yes|yes him|yes her|that one|correct|him|her|yep)[.!]?$/i.test(t.trim());
export const wantsSuggestedReason=t=>/\b(?:random reason|find a reason|help.*reason|you decide|choose.*reason|put.*reason)\b/i.test(t);
export function applyCoinReply(state,text) {
 const s={...state};
 if(wantsSuggestedReason(text)||(s.awaiting==='reason'&&!s.reason&&isConfirmation(text)))s.reason='Admin adjustment';
 else if(s.awaiting==='reason'&&!isConfirmation(text))s.reason=text.trim().replace(/^reason\s*:\s*/i,'');
 if(s.awaiting==='recipient'&&!isConfirmation(text)&&!wantsSuggestedReason(text))s.query=text.trim();
 return s;
}

export function selectCandidate(candidates,text) {
 const rows=Array.isArray(candidates)?candidates:[];
 const t=text.trim().toLowerCase().replace(/[.!?]$/,'');
 const ordinal={first:1,second:2,third:3,fourth:4,fifth:5};
 const m=t.match(/^(?:number\s+)?(\d+)$/);
 const o=t.match(/^(?:the\s+)?(first|second|third|fourth|fifth)(?:\s+one)?$/);
 const index=m?Number(m[1]):o?ordinal[o[1]]:null;
 if(index!==null)return rows[index-1]||null;
 if(t.length<2)return null;
 const matches=rows.filter(r=>String(r.display_name||'').toLowerCase().includes(t)||String(r.email||'').toLowerCase().includes(t));
 return matches.length===1?matches[0]:null;
}

export const startsCoinCommand=text=>/^(?:deduct|debit|remove|take|give|grant|gift|git|credit|award|send|disburse|distribute)\b/i.test(text.trim());
