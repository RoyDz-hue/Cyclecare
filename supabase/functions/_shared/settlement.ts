export type SettlementDecision = "settle" | "duplicate" | "reject" | "ambiguous";

export function validateSettlement(input: {
  expectedReference: string; expectedOwner: string; expectedAmount: string; expectedCurrency: string;
  providerReference?: unknown; owner?: unknown; amount?: unknown; currency?: unknown;
}): SettlementDecision {
  if (typeof input.providerReference !== "string" || !input.providerReference) return "ambiguous";
  if (input.owner !== input.expectedOwner || input.currency !== input.expectedCurrency || String(input.amount) !== input.expectedAmount) return "reject";
  if (input.providerReference === input.expectedReference) return "reject";
  return "settle";
}

/** Provider references are globally unique evidence keys; replay is harmless, conflict is never auto-charged. */
export function replayDecision(existing: { providerReference?: string | null; eventType?: string } | null, eventType: string, providerReference: string): SettlementDecision {
  if (!existing) return "settle";
  if (existing.providerReference === providerReference && existing.eventType === eventType) return "duplicate";
  return "ambiguous";
}

export const AMBIGUOUS_PAYMENT_MESSAGE = "Payment status is being reconciled; no duplicate charge or account credit was issued.";
