// Explicit coin slots. A confirmation never executes a pending plan.
export function parseCoinRequest(text) {
  const t=text.trim().replace(/^debit\s+amount\s+of\s+/i,'deduct ').replace(/^debit\s+/i,'deduct ');
  let m=t.match(/^(deduct|remove|take)\s+(\d+(?:\.\d+)?)\s*(?:coins?\s*)?from\s+(?:all\s+(?:users?|members?)|everyone|everybody|every\s+(?:user|member))(?:[.,]?\s+(?:reason|because)\s*:?\s*(.+))?$/i);
  if(m)return {scope:'all',delta:-Number(m[2]),query:null,reason:m[3]?.trim()||null};
  m=t.match(/^(deduct|remove|take)\s+(\d+(?:\.\d+)?)\s*(?:coins?\s*)?from\s+(.+?)(?:[.,]?\s+(?:reason|because)\s*:?\s*(.+))?$/i);
  if(m)return {delta:-Number(m[2]),query:m[3].trim().replace(/(?:['’]s)?\s+(?:ledger|wallet|account)\s*$/i,''),reason:m[4]?.trim()||null};
  m=t.match(/^(give|grant|gift|git|credit|award|send)\s+(?:(\d+(?:\.\d+)?)\s+coins?\s+to\s+(?:all\s+(?:our\s+)?(?:users?|members?)|everyone|everybody|every\s+(?:user|member)|the\s+whole\s+(?:community|team))\s*(?:each)?|(?:all\s+(?:our\s+)?(?:users?|members?)|everyone|everybody|every\s+(?:user|member)|the\s+whole\s+(?:community|team))\s+(\d+(?:\.\d+)?)\s*coins?(?:\s+each)?)(?:[.,]?\s+(?:reason|because)\s*:?\s*(.+))?$/i);
  if(m)return {scope:'all',delta:Number(m[2]||m[3]),query:null,reason:m[4]?.trim()||null};
  m=t.match(/^(give|grant|gift|git|credit|award|send)\s+(?:(\d+(?:\.\d+)?)\s+coins?\s+to\s+(?:any\s+|a\s+)?random\s+(?:user|member|person)|(?:any\s+|a\s+)?random\s+(?:user|member|person)\s+(\d+(?:\.\d+)?)\s*coins?)(?:[.,]?\s+(?:reason|because)\s*:?\s*(.+))?$/i);
  if(m)return {delta:Number(m[2]||m[3]),query:null,random:true,reason:m[4]?.trim()||null};
  m=t.match(/^(give|grant|gift|git|credit|award|send)\s+(?:(\d+(?:\.\d+)?)\s+coins?\s+to\s+(.+?)|(.+?)\s+(\d+(?:\.\d+)?)\s*(?:coins?)?)(?:[.,]?\s+(?:reason|because)\s*:?\s*(.+))?$/i);
  if(m)return {delta:Number(m[2]||m[5]),query:(m[3]||m[4]).trim(),reason:m[6]?.trim()||null};
  // Natural-language fallback: a coin amount plus a clear scope or verb anywhere
  // in the sentence, however it is phrased. Decimals stay exact; the flow
  // enforces the 3-decimal limit without rounding.
  const amt=t.match(/(\d+(?:\.\d+)?)\s*coins?\b/i);
  const allScope=/\b(?:all\s+(?:our\s+)?(?:users?|members?)|everyone|everybody|every\s+(?:user|member)|the\s+whole\s+(?:community|team))\b/i.test(t);
  const deduct=/\b(?:deduct|remove|take\s+back|take|claw\s*back)\b/i.test(t);
  const giveVerb=/\b(?:give|grant|gift|git|credit|award|send|disburse|distribute|pay|drop|allocate)\b/i.test(t);
  if(amt&&allScope&&(giveVerb||deduct||/\beach\b/i.test(t))){
    return {scope:'all',delta:(deduct&&!giveVerb?-1:1)*Number(amt[1]),query:null,reason:null};
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
