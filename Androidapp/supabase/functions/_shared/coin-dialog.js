// Explicit coin slots. A confirmation never executes a pending plan.
export function parseCoinRequest(text) {
  const t=text.trim().replace(/^debit\s+amount\s+of\s+/i,'deduct ').replace(/^debit\s+/i,'deduct ');
  let m=t.match(/^(deduct|remove|take)\s+(\d+)\s*(?:coins?\s*)?from\s+(?:all\s+(?:users?|members?)|everyone|everybody|every\s+(?:user|member))(?:[.,]?\s+(?:reason|because)\s*:?\s*(.+))?$/i);
  if(m)return {scope:'all',delta:-Number(m[2]),query:null,reason:m[3]?.trim()||null};
  m=t.match(/^(deduct|remove|take)\s+(\d+)\s*(?:coins?\s*)?from\s+(.+?)(?:[.,]?\s+(?:reason|because)\s*:?\s*(.+))?$/i);
  if(m)return {delta:-Number(m[2]),query:m[3].trim().replace(/(?:['’]s)?\s+(?:ledger|wallet|account)\s*$/i,''),reason:m[4]?.trim()||null};
  m=t.match(/^(give|grant|gift|git|credit|award|send)\s+(?:(\d+)\s+coins?\s+to\s+(?:all\s+(?:users?|members?)|everyone|everybody|every\s+(?:user|member)|the\s+whole\s+(?:community|team))\s*(?:each)?|(?:all\s+(?:users?|members?)|everyone|everybody|every\s+(?:user|member)|the\s+whole\s+(?:community|team))\s+(\d+)\s*coins?(?:\s+each)?)(?:[.,]?\s+(?:reason|because)\s*:?\s*(.+))?$/i);
  if(m)return {scope:'all',delta:Number(m[2]||m[3]),query:null,reason:m[4]?.trim()||null};
  m=t.match(/^(give|grant|gift|git|credit|award)\s+(?:(\d+)\s+coins?\s+to\s+(.+?)|(.+?)\s+(\d+)\s*(?:coins?)?)(?:[.,]?\s+(?:reason|because)\s*:?\s*(.+))?$/i);
  if(m)return {delta:Number(m[2]||m[5]),query:(m[3]||m[4]).trim(),reason:m[6]?.trim()||null};
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

export const startsCoinCommand=text=>/^(?:deduct|debit|remove|take|give|grant|gift|git|credit|award)\b/i.test(text.trim());
