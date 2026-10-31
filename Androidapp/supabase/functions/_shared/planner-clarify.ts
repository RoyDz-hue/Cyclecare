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
