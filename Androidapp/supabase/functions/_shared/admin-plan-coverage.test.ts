import { assertEquals } from "https://deno.land/std@0.224.0/assert/mod.ts";
import { missingFacets, requestedFacets } from "./admin-plan-coverage.ts";

Deno.test("trial-2 compound prompt: tier split + top three holders needs both facets", () => {
  const prompt = "Summarize how our members are split across tiers and who the top three coin holders are.";
  assertEquals(requestedFacets(prompt).sort(), ["richest", "tiers"]);
  // The actual trial-2 plan: one step, focus tier_split, label claiming both.
  const underplanned = [{ tool: "get_user_insights", label: "Show the tier split and top coin holders", args: { focus: "tier_split" } }];
  assertEquals(missingFacets(prompt, underplanned), ["richest"], "labels must not count as coverage");
  // A correct plan covers both.
  const full = [
    { tool: "get_user_insights", args: { focus: "tier_split" } },
    { tool: "get_user_insights", args: { focus: "richest" } },
  ];
  assertEquals(missingFacets(prompt, full), []);
  // focus 'all' also covers both.
  assertEquals(missingFacets(prompt, [{ tool: "get_user_insights", args: { focus: "all" } }]), []);
});

Deno.test("single-facet prompts pass with a matching step", () => {
  assertEquals(missingFacets("Who are the top three coin holders?", [{ tool: "get_user_insights", args: { focus: "richest" } }]), []);
  assertEquals(missingFacets("How are members split across tiers?", [{ tool: "get_user_insights", args: { focus: "tier_split" } }]), []);
});

Deno.test("no insights facet requested -> no opinion", () => {
  assertEquals(missingFacets("Show the moderation queue", [{ tool: "get_flagged_messages", args: {} }]), []);
});
