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

// Distinct write categories bundled in one message: clarify order, never half-do.
const WRITE_CATEGORIES = [
  /\b(?:gift|give|grant|award|credit|deduct|debit)\b|\b(?:send|remove|take)\b[^.!?;]*\bcoins?\b/i,
  /\b(?:ban|suspend|restrict|unban|unsuspend)\b/i,
  /\b(?:announce|announcement|broadcast|notice|notify)\b/i,
  /\b(?:upgrade|downgrade)\b|\btier\b/i,
  /\b(?:artifact|pricing|price)\b/i,
];
export const mixesWriteActions = (text) => {
  const t = String(text ?? '').trim();
  if (!/\b(?:and|then|also|after that)\b/i.test(t)) return false;
  let hits = 0;
  for (const re of WRITE_CATEGORIES) if (re.test(t)) hits++;
  return hits >= 2;
};

// Calendar boundaries in Africa/Nairobi (fixed +03:00): today, this week (Monday), this month.
export const joinedWindowBoundary = (win, nowMs = Date.now()) => {
  const EAT = 3 * 3600_000;
  const shifted = new Date(nowMs + EAT);
  const y = shifted.getUTCFullYear(), m = shifted.getUTCMonth(), d = shifted.getUTCDate();
  let start;
  if (win === 'today') start = Date.UTC(y, m, d);
  else if (win === 'this week') start = Date.UTC(y, m, d - ((shifted.getUTCDay() + 6) % 7));
  else start = Date.UTC(y, m, 1);
  return { since: new Date(start - EAT).toISOString(), span: win };
};
