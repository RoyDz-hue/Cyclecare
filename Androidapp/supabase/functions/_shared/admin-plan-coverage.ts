// Deterministic requested-part vs planned-step coverage for admin READ plans.
// The planner can under-plan a compound question (answer one half, silently
// drop the other). This check never trusts step labels: coverage comes only
// from each step's tool and args. If the administrator's message asks for an
// insights facet no planned step can return, the flow clarifies honestly
// instead of answering half the question as if it were the whole.
export type InsightFacet = "tiers" | "richest" | "newest" | "most_active" | "inactive";

const FACET_PATTERNS: [InsightFacet, RegExp][] = [
  ["tiers", /\b(tiers?|tier\s+split|split\b|breakdown|free\s+vs|premium|supporter)\b/i],
  ["richest", /\b(top\s+(?:\d+\s+)?(?:coin\s+)?holders?|richest|wealthiest|most\s+coins|highest\s+balances?|top\s+\d+\s+(?:members?|users?|balances?)|coin\s+holders?)\b/i],
  ["newest", /\b(newest|latest\s+(?:members?|users?)|recently\s+joined|just\s+joined)\b/i],
  ["most_active", /\b(most\s+active|active\s+(?:members?|users?)|most\s+engaged)\b/i],
  ["inactive", /\b(inactive|dormant|lapsed|quiet\s+members?)\b/i],
];

const FOCUS_COVERS: Record<string, InsightFacet[]> = {
  tier_split: ["tiers"],
  richest: ["richest"],
  newest: ["newest"],
  most_active: ["most_active"],
  inactive: ["inactive"],
  all: ["tiers", "richest", "newest", "most_active", "inactive"],
};

export const FACET_LABEL: Record<InsightFacet, string> = {
  tiers: "the tier split",
  richest: "the top coin holders",
  newest: "the newest members",
  most_active: "the most active members",
  inactive: "the inactive members",
};

export function requestedFacets(prompt: string): InsightFacet[] {
  return FACET_PATTERNS.filter(([, re]) => re.test(prompt)).map(([f]) => f);
}

export function coveredFacets(steps: { tool?: unknown; args?: unknown }[]): Set<InsightFacet> {
  const covered = new Set<InsightFacet>();
  for (const st of steps ?? []) {
    if (st?.tool !== "get_user_insights") continue;
    const focus = String((st?.args as any)?.focus ?? "all");
    for (const f of FOCUS_COVERS[focus] ?? []) covered.add(f);
  }
  return covered;
}

// Facets the admin asked for that no planned insights step can return.
// Only meaningful when the prompt requests at least one insights facet.
export function missingFacets(prompt: string, steps: { tool?: unknown; args?: unknown }[]): InsightFacet[] {
  const requested = requestedFacets(prompt);
  if (requested.length === 0) return [];
  const covered = coveredFacets(steps);
  return requested.filter((f) => !covered.has(f));
}
