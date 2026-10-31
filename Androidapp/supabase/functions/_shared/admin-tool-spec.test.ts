import { assert, assertEquals } from "https://deno.land/std@0.224.0/assert/mod.ts";
import { TOOL_SPECS, buildToolCatalog, matchWorkflow, specFor, validateSteps } from "./admin-tool-spec.ts";
import { GOLDEN_CATALOG } from "./tool-catalog.golden.ts";
import { executeStep } from "./admin-command-flow.ts";
import { VERIFIERS, runVerifier } from "./admin-verify.ts";

// Mirrors the live ai_tool_registry rows (17 active). If a tool is added to
// the registry without a spec - or removed without cleanup - this fails.
const LIVE_REGISTRY_ACTIVE = [
  "adjust_coins", "deduct_all_coins", "get_audit_log", "get_flagged_messages",
  "get_pricing", "get_safety_signals", "get_user_insights", "gift_all_coins",
  "gift_artifact", "moderate_message", "send_notice", "set_coin_packages",
  "set_cosmetic_price", "set_guides_enabled", "set_user_status",
  "set_user_tier", "update_guide",
];

Deno.test("every active registry tool has exactly one spec", () => {
  const names = TOOL_SPECS.map((s) => s.name);
  assertEquals(new Set(names).size, names.length, "duplicate spec names");
  for (const tool of LIVE_REGISTRY_ACTIVE) assert(specFor(tool), `missing spec for ${tool}`);
  for (const name of names) assert(LIVE_REGISTRY_ACTIVE.includes(name) || name === "get_member_count", `spec ${name} not in registry`);
});

Deno.test("every WRITE/RISKY tool declares review fields and a verifier", () => {
  for (const spec of TOOL_SPECS) {
    if (spec.kind === "READ") continue;
    assert(spec.reviewFields.length > 0, `${spec.name}: review card would be label-only`);
    assert(spec.requiresVerifier, `${spec.name}: write without read-back verification`);
  }
});

Deno.test("essential args appear in the catalog line so the planner can fill them", () => {
  for (const spec of TOOL_SPECS) {
    for (const arg of spec.essentialArgs) {
      assert(spec.catalogLine.includes(arg), `${spec.name}: essential arg ${arg} missing from catalog line`);
    }
  }
});

Deno.test("generated catalog lists every tool exactly once with the header", () => {
  const catalog = buildToolCatalog();
  assert(catalog.startsWith("Available tools (use only these, with only these args):"));
  for (const spec of TOOL_SPECS) {
    const hits = catalog.split(`- ${spec.name} `).length - 1;
    assertEquals(hits, 1, `${spec.name} should appear exactly once`);
  }
});

Deno.test("catalog text stays byte-identical to the planner wording in use", () => {
  const catalog = buildToolCatalog();
  for (const probe of [
    "- adjust_coins {targets: [user_id,...], delta: number (up to 3 decimal places), reason}",
    "All-or-nothing: aborts if any member cannot cover the full amount.",
    "- send_notice {targets: [user_id,...] or audience:",
  ]) assert(catalog.includes(probe), probe);
});

Deno.test("generated catalog matches the exact planner wording byte for byte", () => {
  assertEquals("\n" + buildToolCatalog(), GOLDEN_CATALOG);
});

// Coverage against the REAL handlers, not spec self-consistency: the executor
// is called with a mock backend for every WRITE/RISKY tool, and the verifier
// registry must hold a real function for each. A spec cannot claim a
// capability without an implemented, explicitly allowlisted code path.
Deno.test("every WRITE/RISKY spec has a working executor handler", async () => {
  const rpc = () => Promise.resolve({ data: { message_id: "m1", success: true, details: { new_balance: 0 } }, error: null });
  const userClient = { rpc };
  for (const spec of TOOL_SPECS) {
    if (spec.kind === "READ") continue;
    const args: Record<string, unknown> = { targets: ["u1"], target: "u1", delta: 1, title: "t", body: "b", audience: "ALL", status: "ACTIVE", tier: "FREE", enabled: true, persona_id: "p1", item_id: "i1", artifact_name: "Rose Obelisk", action: "DISMISS", packages: [] };
    const out = await executeStep(userClient, spec.name, args);
    assert(out.error !== `unknown tool ${spec.name}`, `${spec.name}: executor does not know this tool`);
  }
});

Deno.test("every WRITE/RISKY spec has a registered real verifier", () => {
  for (const spec of TOOL_SPECS) {
    if (spec.kind === "READ") continue;
    assertEquals(typeof VERIFIERS[spec.name], "function", `${spec.name}: no verifier - a successful write would report unconfirmed`);
  }
});

Deno.test("unknown tools fail closed: no verifier, unknown-tool executor error", async () => {
  assertEquals(await runVerifier({}, "made_up_tool", {}, {}), null);
  const out = await executeStep({ rpc: () => Promise.resolve({ data: null, error: null }) }, "made_up_tool", {});
  assertEquals(out.ok, false);
  assertEquals(out.error, "unknown tool made_up_tool");
});

Deno.test("validation: presence alone is not enough - values, enums, ranges, cross-field", () => {
  const P = (tool: string, args: unknown) => validateSteps([{ tool, args }]).map((p) => p.problem);
  assert(P("adjust_coins", { targets: ["u1"] }).includes("I need the exact amount"));
  assert(P("adjust_coins", { targets: ["u1"], delta: 0 }).some((p) => p.includes("non-zero")));
  assert(P("adjust_coins", { targets: ["u1"], delta: 1.2345 }).some((p) => p.includes("decimal")));
  assert(P("adjust_coins", { targets: ["u1", "u2", "u3", "u4", "u5", "u6"], delta: 5 }).some((p) => p.includes("at most 5")));
  assert(P("set_user_tier", { targets: ["u1"], tier: "GOLD" }).some((p) => p.includes("FREE, SUPPORTER, PREMIUM")));
  assert(P("send_notice", { title: "t", body: "b", audience: "ALL", targets: ["u1"] }).some((p) => p.includes("not both")));
  assert(P("gift_all_coins", { delta: 0.5 }).some((p) => p.includes("between 1 and 10,000 coins")));
  assert(P("made_up_tool", {}).some((p) => p.includes("unknown tool")));
  assertEquals(validateSteps([{ tool: "adjust_coins", args: { targets: ["u1"], delta: -50, reason: "r" } }]).length, 0);
  assertEquals(validateSteps([{ tool: "send_notice", args: { title: "t", body: "b", audience: "ALL" } }]).length, 0);
});

// Boundary matrix: the proposal stage and the executor MUST agree on every
// combination. The executor calls use a mock backend; an accepted invalid
// plan or a rejected valid one fails here.
Deno.test("proposal validation and executor enforcement agree on every boundary case", async () => {
  const rpc = () => Promise.resolve({ data: { message_id: "m1", success: true, details: { new_balance: 0 }, recipients: 1, total_coins: 1 }, error: null });
  const cases: [string, Record<string, unknown>][] = [
    ["adjust_coins", { targets: ["u1"], delta: -50, reason: "r" }],
    ["adjust_coins", { targets: ["u1"], delta: 0 }],
    ["adjust_coins", { targets: ["u1"], delta: 1.2345 }],
    ["adjust_coins", { targets: [], delta: 5 }],
    ["adjust_coins", { targets: ["u1", "u2", "u3", "u4", "u5", "u6"], delta: 5 }],
    ["adjust_coins", { targets: ["u1"], delta: 10001 }],
    ["gift_all_coins", { delta: 2, reason: "r" }],
    ["gift_all_coins", { delta: 0.5 }],
    ["gift_all_coins", { delta: -3 }],
    ["deduct_all_coins", { delta: 4.1, reason: "r" }],
    ["deduct_all_coins", { delta: 10001 }],
    ["set_user_tier", { targets: ["u1"], tier: "PREMIUM" }],
    ["set_user_tier", { targets: ["u1"], tier: "GOLD" }],
    ["set_user_status", { targets: ["u1"], status: "SUSPENDED", duration_hours: 24 }],
    ["set_user_status", { targets: ["u1"], status: "ASLEEP" }],
    ["send_notice", { title: "t", body: "b", audience: "ALL" }],
    ["send_notice", { title: "t", body: "b", audience: "ALL", targets: ["u1"] }],
    ["send_notice", { body: "b", audience: "ALL" }],
    ["moderate_message", { targets: ["m1"], action: "REMOVE" }],
    ["moderate_message", { targets: ["m1"], action: "SHRED" }],
    ["set_coin_packages", { packages: [{ id: "p1" }] }],
    ["set_coin_packages", { packages: [] }],
    ["set_guides_enabled", { enabled: true }],
    ["set_guides_enabled", { enabled: "yes" }],
    ["gift_artifact", { targets: ["u1"], artifact_name: "Rose Obelisk" }],
    ["gift_artifact", { targets: ["u1"], artifact_name: "Invisible Crown" }],
  ];
  for (const [tool, args] of cases) {
    const proposalOk = validateSteps([{ tool, args }]).length === 0;
    const out = await executeStep({ rpc }, tool, args);
    const executorOk = out.ok === true;
    assertEquals(executorOk, proposalOk, `${tool} ${JSON.stringify(args)}: proposal ${proposalOk} vs executor ${executorOk}`);
  }
});

Deno.test("matchWorkflow: exact registered sequence matches", () => {
  const steps = [
    { tool: "gift_all_coins", args: { delta: 5, reason: "thank-you" } },
    { tool: "send_notice", args: { audience: "ALL", title: "Gift", body: "We gifted you 5 coins." } },
  ];
  assertEquals(matchWorkflow(steps), "gift_all_with_notice");
});
Deno.test("matchWorkflow: wrong order, extra step, missing step never match", () => {
  const a = { tool: "gift_all_coins", args: { delta: 5 } };
  const n = { tool: "send_notice", args: { audience: "ALL", title: "t", body: "b" } };
  assertEquals(matchWorkflow([n, a]), null);
  assertEquals(matchWorkflow([a]), null);
  assertEquals(matchWorkflow([a, n, a]), null);
});
Deno.test("matchWorkflow: off-constraint audience or targets never match", () => {
  const a = { tool: "gift_all_coins", args: { delta: 5 } };
  assertEquals(matchWorkflow([a, { tool: "send_notice", args: { audience: "FREE", title: "t", body: "b" } }]), null);
  assertEquals(matchWorkflow([a, { tool: "send_notice", args: { targets: ["u1"], title: "t", body: "b" } }]), null);
});
Deno.test("matchWorkflow: no registered workflow for other write pairs", () => {
  assertEquals(matchWorkflow([
    { tool: "adjust_coins", args: { targets: ["u1"], delta: 5, reason: "r" } },
    { tool: "send_notice", args: { audience: "ALL", title: "t", body: "b" } },
  ]), null);
});
