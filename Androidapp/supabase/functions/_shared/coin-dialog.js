// Explicit coin slots. A confirmation never executes a pending plan.
export function parseCoinRequest(text) {
  const t=text.trim().replace(/^debit\s+amount\s+of\s+/i,'deduct ').replace(/^debit\s+/i,'deduct ');
  let m=t.match(/^(deduct|remove|take)\s+(\d+(?:\.\d+)?)\s*(?:coins?\s*)?from\s+(?:all\s+(?:users?|members?)|everyone|everybody|every\s+(?:user|member))(?:[.,]?\s+(?:reason|because)\s*:?\s*(.+))?$/i);
  if(m)return {scope:'all',delta:-Number(m[2]),query:null,reason:m[3]?.trim()||null};
  m=t.match(/^(deduct|remove|take)\s+(\d+(?:\.\d+)?)\s*(?:coins?\s*)?from\s+(.+?)(?:[.,]?\s+(?:reason|because)\s*:?\s*(.+))?$/i);
  if(m){
    const q=m[3].trim().replace(/(?:['’]s)?\s+(?:ledger|wallet|account)\s*$/i,'');
    // A scope phrase is never a recipient name; let the fallback classify it.
    if(!/^(?:all\b|every\b|each\b|everyone\b|everybody\b|per[-\s]|the\s+whole\b)/i.test(q))
      return {delta:-Number(m[2]),query:q,reason:m[4]?.trim()||null};
  }
  m=t.match(/^(give|grant|gift|git|credit|award|send)\s+(?:(\d+(?:\.\d+)?)\s+coins?\s+to\s+(?:all\s+(?:our\s+)?(?:users?|members?)|everyone|everybody|every\s+(?:user|member)|each\s+(?:user|member)|the\s+whole\s+(?:community|team))\s*(?:each)?|(?:all\s+(?:our\s+)?(?:users?|members?)|everyone|everybody|every\s+(?:user|member)|each\s+(?:user|member)|the\s+whole\s+(?:community|team))\s+(\d+(?:\.\d+)?)\s*coins?(?:\s+each)?)(?:[.,]?\s+(?:reason|because)\s*:?\s*(.+))?$/i);
  if(m)return {scope:'all',delta:Number(m[2]||m[3]),query:null,reason:m[4]?.trim()||null};
  m=t.match(/^(give|grant|gift|git|credit|award|send)\s+(?:(\d+(?:\.\d+)?)\s+coins?\s+to\s+(?:any\s+|a\s+)?random\s+(?:user|member|person)|(?:any\s+|a\s+)?random\s+(?:user|member|person)\s+(\d+(?:\.\d+)?)\s*coins?)(?:[.,]?\s+(?:reason|because)\s*:?\s*(.+))?$/i);
  if(m)return {delta:Number(m[2]||m[3]),query:null,random:true,reason:m[4]?.trim()||null};
  m=t.match(/^(give|grant|gift|git|credit|award|send)\s+(?:(\d+(?:\.\d+)?)\s+coins?\s+to\s+(.+?)|(.+?)\s+(\d+(?:\.\d+)?)\s*(?:coins?)?)(?:[.,]?\s+(?:reason|because)\s*:?\s*(.+))?$/i);
  if(m){
    const q=(m[3]||m[4]).trim();
    // A scope phrase is never a recipient name; let the fallback classify it.
    if(!/^(?:all\b|every\b|each\b|everyone\b|everybody\b|per[-\s]|the\s+whole\b)/i.test(q))
      return {delta:Number(m[2]||m[5]),query:q,reason:m[6]?.trim()||null};
  }
  // Natural-language fallback: a coin amount plus a clear scope or verb anywhere
  // in the sentence, however it is phrased. Decimals stay exact; the flow
  // enforces the 3-decimal limit without rounding.
  // Questions are never directives: "did you give all users 5 coins?" asks
  // about the past, it does not order a gift. Skip the fallback for them.
  if(/^(?:who|what|what's|how|how's|when|why|which|did|do|does|is|are|was|were|has|have|had|am)\b/i.test(t))return null;
  if(/^(?:can|could|will|would|should|shall)\s+(?:i|we)\b/i.test(t))return null; // advice-seeking ("should I gift...?"), not a directive. "Can you gift...?" IS a directive and falls through.
  const amt=t.match(/(\d+(?:\.\d+)?)[\s-]*coins?\b/i);
  // Audience scope. Bare forms mean every member. Qualified forms ("each paying
  // member", "per extant eligible user") name a SUBSET: adjectives are audience
  // filters, never decoration, and stripping them would silently widen the gift.
  // A qualified scope only counts as all-members when the SAME message explicitly
  // claims totality ("universally scoped", "cardinality-complete", "the entirety",
  // "per-capita", "every single user"). Otherwise it is a subset we cannot filter
  // safely: return scope 'subset' so the flow clarifies instead of guessing.
  const bareScope=/\b(?:all\s+(?:our\s+)?(?:users?|members?)|everyone|everybody|every\s+(?:user|member)|each\s+(?:user|member)|per[-\s]user|per[-\s]member|per[-\s]capita|the\s+whole\s+(?:community|team)|across\s+all\s+(?:our\s+)?(?:users?|members?))\b/i.test(t);
  const qualifiedScope=/\b(?:all\s+(?:[\w-]+\s+){1,3}(?:users?|members?)|every\s+(?:[\w-]+\s+){1,2}(?:user|member)|each\s+(?:[\w-]+\s+){1,2}(?:user|member)|per[-\s](?:[\w-]+[-\s]+){1,3}(?:user|member)|across\s+(?:[\w-]+\s+){1,4}(?:users?|members?))\b/i.test(t);
  const totality=/\b(?:everyone|everybody|every\s+single\s+(?:user|member)|entirety|whole\s+(?:community|team)|universally|cardinality|per[-\s]capita)\b/i.test(t);
  const allScope=bareScope||(qualifiedScope&&totality);
  const deduct=/\b(?:deduct|deduction|debit|remove|take\s+back|take|claw\s*back)\b/i.test(t);
  const giveVerb=/\b(?:give|gives|giving|grant|grants|granting|gift|gifts|gifting|git|credit|credits|crediting|award|awards|awarding|send|sends|sending|disburse|disburses|disbursing|disbursement|distribute|distributes|distributing|distribution|pay|pays|drop|drops|allocate|allocates|allocating|allocation|entitle|entitlement|deposit|deposits)\b/i.test(t);
  if(amt&&allScope&&(giveVerb||deduct||/\beach\b/i.test(t)||/\bper[-\s](?:user|member)\b/i.test(t))){
    return {scope:'all',delta:(deduct&&!giveVerb?-1:1)*Number(amt[1]),query:null,reason:null};
  }
  if(amt&&qualifiedScope&&(giveVerb||deduct||/\beach\b/i.test(t)||/\bper[-\s]/i.test(t))){
    return {scope:'subset',delta:(deduct&&!giveVerb?-1:1)*Number(amt[1]),query:null,reason:null};
  }
  if(amt&&giveVerb&&/\b(?:any\s+|a\s+)?random\s+(?:user|member|person)\b/i.test(t)){
    return {delta:Number(amt[1]),query:null,random:true,reason:null};
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
