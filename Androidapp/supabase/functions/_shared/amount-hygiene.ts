// Amount-source hygiene shared by the deterministic parser and the planner
// gate: a number written as a date, an ordinal, a fiat amount or part of an
// identifier is never a coin amount, and two surviving candidates are
// ambiguous. The planner path sees only the chosen number, so the proposal
// stage re-checks that number against the admin's own words before a plan is
// shown for approval.
export function amountSourceFlags(text: string, amount: number): string[] {
  const flags: string[] = [];
  if (!Number.isFinite(amount)) return flags;
  const raw = String(amount);
  // Find every occurrence of the exact token in the text.
  const escaped = raw.replace(".", "\\.");
  const re = new RegExp(`(^|[^\\d.])${escaped.replace("-", "-?")}([^\\d.]|$)`, "g");
  const hits: { index: number; token: string }[] = [];
  let m: RegExpExecArray | null;
  while ((m = re.exec(text)) !== null) hits.push({ index: m.index + m[1].length, token: raw });
  if (hits.length === 0) {
    // The amount is not literally written (e.g. "five coins"): no source
    // suspicion from textual guards.
  }
  for (const hit of hits) {
    const after = text.slice(hit.index + raw.length);
    const before = text.slice(0, hit.index);
    if (/^(?:st|nd|rd|th)\b/i.test(after.trimStart())) flags.push("ordinal");
    if (/^\s*(?:KES|USD|Ksh|shillings?)\b/i.test(after)) flags.push("fiat");
    if (/[A-Za-z]-?$/.test(before)) flags.push("identifier");
    // Date shapes: YYYY-MM-DD, DD/MM/YYYY, DD.MM.YY around this token.
    const window = text.slice(Math.max(0, hit.index - 12), hit.index + raw.length + 12);
    if (/\d{4}-\d{1,2}-\d{1,2}/.test(window) || /\d{1,2}[/.]\d{1,2}[/.]\d{2,4}/.test(window)) flags.push("date");
  }
  if (!raw.includes(".") && amount >= 1900 && amount <= 2100 && flags.length === 0) flags.push("year_like");
  return [...new Set(flags)];
}

export function amountSourceProblem(flags: string[]): string | null {
  if (flags.includes("date") || flags.includes("year_like")) return "that number reads as a date, not a coin amount";
  if (flags.includes("ordinal")) return "that number reads as an ordinal (1st, 2nd, 3rd...), not a coin amount";
  if (flags.includes("fiat")) return "that number reads as a money amount (KES/USD), not coins";
  if (flags.includes("identifier")) return "that number reads as part of an identifier, not a coin amount";
  return null;
}
