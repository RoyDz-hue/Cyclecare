// Bounded AI cost controls for ACI planner calls: context caps and token
// accounting. No silent truncation of the admin's actual request - caps apply
// to history and live-data, never to the latest message.

// Keep the NEWEST conversation lines within maxChars total. The caller passes
// lines oldest-first; the admin's latest message travels separately in full.
export function capConversation(lines: string[], maxChars = 4000): string[] {
  const kept: string[] = [];
  let total = 0;
  for (let i = lines.length - 1; i >= 0; i--) {
    const line = lines[i];
    if (total + line.length > maxChars && kept.length > 0) break;
    total += line.length + 1;
    kept.unshift(line);
    if (total > maxChars) break;
  }
  return kept;
}

export interface TokenUsage { input: number | null; output: number | null }

// Best-effort usage extraction per provider response shape. nulls mean the
// provider did not report; accounting must never invent numbers.
export function extractUsage(provider: string, data: any): TokenUsage {
  try {
    if (provider === "GEMINI") {
      const u = data?.usageMetadata;
      return { input: typeof u?.promptTokenCount === "number" ? u.promptTokenCount : null,
               output: typeof u?.candidatesTokenCount === "number" ? u.candidatesTokenCount : null };
    }
    if (provider === "COHERE") {
      const u = data?.usage?.tokens ?? data?.usage?.billed_units;
      return { input: typeof u?.input_tokens === "number" ? u.input_tokens : null,
               output: typeof u?.output_tokens === "number" ? u.output_tokens : null };
    }
    const u = data?.usage; // OpenRouter/OpenAI shape
    return { input: typeof u?.prompt_tokens === "number" ? u.prompt_tokens : null,
             output: typeof u?.completion_tokens === "number" ? u.completion_tokens : null };
  } catch { return { input: null, output: null }; }
}

// Request-wide budget across fallback attempts (Roy's "one prompt twerking
// 30 times" guard): one admin message can never reserve more than
// maxOutputTokensTotal output tokens or run longer than maxTotalMs across ALL
// provider attempts combined. Reserve-based because failed attempts report no
// usage: we cap what we ask for, and record what providers say they burned.
export const REQUEST_BUDGET = {
  perAttemptOutputTokens: 1500,
  maxOutputTokensTotal: 3000,
  maxTotalMs: 60_000,
  perAttemptTimeoutMs: 25_000,
} as const;

// The max_tokens grant for the next attempt, or null to stop rotating. The
// grant decays by attempt (1500, 1000, 500): a request that already burned a
// full attempt gets progressively less room, and the total reserve across
// attempts can never exceed maxOutputTokensTotal.
export function nextAttemptGrant(attemptIndex: number, reservedSoFar: number, elapsedMs: number): number | null {
  if (elapsedMs >= REQUEST_BUDGET.maxTotalMs) return null;
  const remaining = REQUEST_BUDGET.maxOutputTokensTotal - reservedSoFar;
  if (remaining <= 0) return null;
  const decayed = REQUEST_BUDGET.perAttemptOutputTokens - attemptIndex * 500;
  if (decayed <= 0) return null;
  return Math.min(decayed, remaining);
}
