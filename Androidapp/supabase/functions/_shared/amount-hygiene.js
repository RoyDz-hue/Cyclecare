// Amount-source hygiene, single shared implementation. Used by the
// deterministic parser (coin-dialog.js, plain node) and the planner proposal
// gate (admin-command/index.ts, Deno edge). A number written as a date, an
// ordinal, a fiat amount or part of an identifier is never a coin amount.
// Kept as plain ESM JavaScript so the node .mjs test suites import the exact
// same module the edge bundles.

// Blank date shapes so a date's digits are never scanned as amounts.
export function maskDateShapes(text) {
  return String(text).replace(/\b\d{4}-\d{1,2}-\d{1,2}\b/g,' ').replace(/\b\d{1,2}[/.]\d{1,2}[/.]\d{2,4}\b/g,' ');
}

// Why is the numeric token at `index` in `text` not a coin amount? Null when
// nothing disqualifies it at that position.
export function tokenDisqualifier(text, index, token) {
  const after = text.slice(index + token.length);
  const before = text.slice(0, index);
  if (/^(?:st|nd|rd|th)\b/i.test(after.trimStart())) return 'ordinal';
  if (/^\s*(?:KES|USD|Ksh|shillings?)\b/i.test(after)) return 'fiat';
  if (/[A-Za-z]-?$/.test(before)) return 'identifier';
  if (!token.includes('.') && token.replace('-','').length >= 4) return 'long_number';
  return null;
}

// All numeric tokens in the text that survive the hygiene guards. Zero or
// several survivors is ambiguous: clarify rather than guess.
export function amountCandidates(text) {
  const masked = maskDateShapes(text);
  const cands = [];
  for (const n of masked.matchAll(/-?\d+(?:\.\d+)?/g)) {
    if (tokenDisqualifier(masked, n.index, n[0])) continue;
    cands.push(n[0]);
  }
  return cands;
}

// Planner-gate view: the planner already picked `amount`; re-check that exact
// number against the admin's own words. Any CLEAN written occurrence clears
// the number entirely ("on 2026-10-31 give 31 coins": the second 31 is a real
// amount). A year-like value explicitly glued to the word "coins" is the
// admin's choice, not a misread.
export function amountSourceFlags(text, amount) {
  if (!Number.isFinite(amount)) return [];
  const raw = String(amount);
  const masked = maskDateShapes(text);
  const re = new RegExp(`(^|[^\\d.])${raw.replace('.', '\\.')}([^\\d.]|$)`, 'g');
  const flags = new Set();
  let sawWritten = false, sawClean = false, sawCoinsGlued = false;
  let m;
  while ((m = re.exec(text)) !== null) {
    const index = m.index + m[1].length;
    sawWritten = true;
    const after = text.slice(index + raw.length);
    if (/^\s*coins?\b/i.test(after)) { sawClean = true; sawCoinsGlued = true; continue; }
    if (masked.slice(index, index + raw.length) !== raw) { flags.add('date'); continue; }
    const d = tokenDisqualifier(text, index, raw);
    if (d === 'ordinal' || d === 'fiat' || d === 'identifier') flags.add(d);
    else if (d === null) sawClean = true;
    // 'long_number' is neutral here: not proof of a misread, not a clean hit.
  }
  if (sawClean) return [];
  if (flags.size > 0) return [...flags];
  if (!sawWritten) return [];
  if (!raw.includes('.') && amount >= 1900 && amount <= 2100 && !sawCoinsGlued) return ['year_like'];
  return [];
}

export function amountSourceProblem(flags) {
  if (flags.includes('date') || flags.includes('year_like')) return 'that number reads as a date, not a coin amount';
  if (flags.includes('ordinal')) return 'that number reads as an ordinal (1st, 2nd, 3rd...), not a coin amount';
  if (flags.includes('fiat')) return 'that number reads as a money amount (KES/USD), not coins';
  if (flags.includes('identifier')) return 'that number reads as part of an identifier, not a coin amount';
  return null;
}

// ---- Signed-amount semantics -------------------------------------------
// Sign is meaning: "negative three" is a deduction, "+3" is a gift. A plan
// whose signed coin effect contradicts the admin's own words is unsafe at
// ANY confidence; these guards bind the plan's sign to the original text.

const WORD_FOR_NUM = { 1: 'one', 2: 'two', 3: 'three', 4: 'four', 5: 'five', 6: 'six', 7: 'seven', 8: 'eight', 9: 'nine', 10: 'ten' };
const NEG_MARKER = /(?:negative|minus|neg)\s*$/i;

// Every written form of |amount| (digits and single-word numbers) and whether
// a negative marker directly precedes it.
export function expressedNegative(text, amount) {
  if (!Number.isFinite(amount)) return false;
  const abs = Math.abs(amount);
  const forms = [String(abs)];
  if (Number.isInteger(abs) && WORD_FOR_NUM[abs]) forms.push(WORD_FOR_NUM[abs]);
  const t = String(text);
  for (const form of forms) {
    const rx = new RegExp(`(^|[^\\d.])${form.replace('.', '\\.')}(?![\\d.])`, 'gi');
    let m;
    while ((m = rx.exec(t)) !== null) {
      const idx = m.index + m[1].length;
      const before = t.slice(0, idx);
      if (NEG_MARKER.test(before)) return true;
      if (form === String(abs) && /\-\s*$/.test(before)) return true; // typed "-3"
    }
  }
  return false;
}

// A negative coin plan is also grounded by a deduction verb ("take", "deduct",
// "remove", ...) - the word "negative" is not the only way to take coins away.
export function expressedDeduction(text) {
  return /\b(?:deduct|take|took|remove|removed|subtract|debit|charge|reduce|dock|deducting)\b/i.test(String(text));
}

// Proposal-gate check for tools whose delta SIGN is the operation direction
// (gift_all_coins must be positive; adjust_coins carries its own sign).
// deduct_all_coins takes a positive magnitude - a negative phrasing is
// CONSISTENT with it, so it is not checked here.
export function signMismatchProblem(text, tool, amount) {
  if (tool !== 'gift_all_coins' && tool !== 'adjust_coins') return null;
  if (!Number.isFinite(amount) || amount === 0) return null;
  const neg = expressedNegative(text, amount);
  if (amount > 0 && neg) return 'you wrote a negative amount - that would take coins away, but this plan adds them';
  if (amount < 0 && !neg && !expressedDeduction(text)) return 'the plan takes coins away, but you did not write a negative amount or a deduction';
  return null;
}

// Execute-time backstop: the run's ORIGINAL wording is re-checked before any
// coin moves. A run whose original words were never bound (legacy canonical
// prompts) cannot be grounded: refuse execution and require a fresh review
// rather than pretend the rewritten label protects it.
export function coinWriteGuardProblem(originalPrompt, tool, delta) {
  if (tool !== 'gift_all_coins' && tool !== 'deduct_all_coins' && tool !== 'adjust_coins') return null;
  if (typeof originalPrompt !== 'string' || !originalPrompt.trim()) {
    return 'This plan predates amount-sign validation, so it cannot be re-checked safely. Please ask again - nothing was executed.';
  }
  const mismatch = signMismatchProblem(originalPrompt, tool, delta);
  if (mismatch) return `This plan no longer passes safety validation: ${mismatch}. Please ask again - nothing was executed.`;
  return null;
}
