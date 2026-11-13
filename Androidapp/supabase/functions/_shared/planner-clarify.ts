// Planner free-text clarifications bypass the spec's structured problems, so
// this gate enforces the one rule that must hold regardless of what the model
// wrote: no backend machinery ever reaches the admin. If a clarify text leaks
// schema/JSON/provider/tooling language, it is replaced with a safe generic
// ask. Chat text is not touched (normal conversation may use these words
// innocently); clarify is the planner asking for structured action input.
const MACHINERY = /\b(?:schema|json|payload|uuid|regex|enum|arg(?:ument)?s?|parameter|provider|openrouter|gemini|cohere|prompt|token|validation rule|rpc|sql|column|tool call|planner|spec)\b/i;

const SAFE_FALLBACK =
  "I am missing one detail to do that safely. Please restate what you need in plain words - nothing was changed.";

export function sanitizeClarifyText(text: string): { text: string; sanitized: boolean } {
  const trimmed = (text || "").trim();
  if (!trimmed) return { text: SAFE_FALLBACK, sanitized: true };
  if (MACHINERY.test(trimmed)) return { text: SAFE_FALLBACK, sanitized: true };
  return { text: trimmed, sanitized: false };
}

import { amountCandidates } from "./amount-hygiene.js";

// Structured clarify: the planner reports WHICH essentials are missing using
// plain field names; the response is composed here from canonical asks, never
// from the model's free text. This is the resolution mechanism - the sanitizer
// above is only the leak guard for the unstructured fallback.
const FIELD_ASKS: Record<string, string> = {
  amount: "I need the exact coin amount.",
  recipient: "Which member do you mean? A full name or email works.",
  audience: "Who should receive it: everyone, one tier, or named members?",
  title: "What title should the notice have?",
  message: "What should the message say?",
  body: "What should the message say?",
  tier: "Which tier: FREE, SUPPORTER or PREMIUM?",
  status: "What status should they have: ACTIVE, SUSPENDED or BANNED?",
  artifact: "Which artifact should I gift?",
  reason: "What reason should I record?",
  packages: "I need the full new price list to work from.",
  scope: "Should that apply to everyone or to named members?",
  item: "Which item do you mean?",
  guide: "Which guide do you mean?",
};

export const CLARIFIABLE_FIELDS: string[] = Object.keys(FIELD_ASKS);

// Compose the clarify message from validated field names. Unknown names are
// dropped; null when nothing known remains (caller falls back to sanitized
// free text). At most three asks so the admin is never buried.
export function composeMissingClarify(missing: unknown): string | null {
  if (!Array.isArray(missing)) return null;
  const asks: string[] = [];
  for (const m of missing) {
    const ask = FIELD_ASKS[String(m).toLowerCase().trim()];
    if (ask && !asks.includes(ask)) asks.push(ask);
    if (asks.length === 3) break;
  }
  if (asks.length === 0) return null;
  return asks.join(" ") + " Nothing was changed.";
}

// Server-side truth check on the planner's missing list: a field the admin
// ALREADY supplied is never asked for again, whatever the model claimed. Only
// verifiable fields are filtered (amount, recipient); the rest pass through.
export function filterActuallyMissing(missing: unknown, prompt: string, hasResolvedRecipient: boolean): unknown {
  if (!Array.isArray(missing)) return missing;
  return missing.filter((m) => {
    const f = String(m).toLowerCase().trim();
    if (f === "amount") {
      if (/\d+(?:\.\d+)?\s*coins?\b/i.test(prompt)) return false; // "5 coins" written
      if (amountCandidates(prompt).length === 1) return false; // one plausible amount present
    }
    if (f === "recipient" && hasResolvedRecipient) return false;
    return true;
  });
}
