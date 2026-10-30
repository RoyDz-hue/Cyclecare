// Small deterministic intents answered directly, without a plan card or the model.
export const asksMemberCount = (text) =>
  /^(?:how many (?:users?|members?)(?:\s+do we have|\s+are there)?|what(?:'s| is) the (?:total )?(?:number|count) of (?:users?|members?)|(?:total |current )?(?:user|member) count)\s*[?]?$/i.test(String(text ?? '').trim());

// A message that starts as a coin action but bundles another change: split, never half-do.
export const mixesCoinWithOtherAction = (text) => {
  const t = String(text ?? '').trim();
  const coin = /^(?:deduct|debit|remove|take|give|grant|gift|credit|award|send)\b/i.test(t);
  const other = /\b(?:ban|suspend|notice|announce|tier|upgrade|downgrade|artifact|moderate|price|pricing)\b/i.test(t);
  return coin && other && /\b(?:and|then|also|after that)\b/i.test(t);
};

// Filtered member counts: joined window or membership tier.
export const asksJoinedCount = (text) => {
  const m = String(text ?? '').trim().match(/^how many (?:users?|members?)(?:\s+do we have)?\s+(?:joined|signed up|are new)\s+(today|this week|this month)\s*[?]?$/i);
  return m ? m[1].toLowerCase() : null;
};
export const asksTierCount = (text) => {
  const m = String(text ?? '').trim().match(/^(?:how many (free|supporter|premium) members(?:\s+do we have)?|(free|supporter|premium) member count)\s*[?]?$/i);
  return m ? (m[1] || m[2]).toLowerCase() : null;
};

// A single proportional answer for the top-balance question.
export const asksTopBalance = (text) =>
  /^(?:who has the most coins|who(?:'s| is) (?:the )?richest(?: member)?|(?:top|highest) (?:coin )?balance)\s*[?]?$/i.test(String(text ?? '').trim());
