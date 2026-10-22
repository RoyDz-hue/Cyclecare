// Explicit coin slots. A confirmation never executes a pending plan.
export function parseCoinRequest(text) {
  const t=text.trim();
  let m=t.match(/^(deduct|remove|take)\s+(\d+)\s*(?:coins?\s*)?from\s+(.+?)(?:[.,]?\s+(?:reason|because)\s*:?\s*(.+))?$/i);
  if(m)return {delta:-Number(m[2]),query:m[3].trim(),reason:m[4]?.trim()||null};
  m=t.match(/^(give|grant|gift|credit|award)\s+(?:(\d+)\s+coins?\s+to\s+(.+?)|(.+?)\s+(\d+)\s*(?:coins?)?)(?:[.,]?\s+(?:reason|because)\s*:?\s*(.+))?$/i);
  if(m)return {delta:Number(m[2]||m[5]),query:(m[3]||m[4]).trim(),reason:m[6]?.trim()||null};
  return null;
}
export const isConfirmation=t=>/^(?:yes|yes him|yes her|that one|correct|him|her|yep)[.!]?$/i.test(t.trim());
export const wantsSuggestedReason=t=>/\b(?:random reason|find a reason|help.*reason|you decide|choose.*reason|put.*reason)\b/i.test(t);
export function applyCoinReply(state,text) {
 const s={...state};
 if(wantsSuggestedReason(text))s.reason='Admin adjustment';
 else if(s.awaiting==='reason'&&!isConfirmation(text))s.reason=text.trim().replace(/^reason\s*:\s*/i,'');
 if(s.awaiting==='recipient'&&!isConfirmation(text)&&!wantsSuggestedReason(text))s.query=text.trim();
 return s;
}
