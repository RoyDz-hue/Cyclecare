// Mocked end-to-end conversation tests: no network, no model calls, no real member rows.
import { assertEquals, assertStringIncludes } from "jsr:@std/assert";
import { coinDialog, executeStep, planHasMultipleWrites } from "./admin-command-flow.ts";

const PROFILES = [{ user_id: "u-1" }, { user_id: "u-2" }, { user_id: "u-3" }];

function makeClients(profiles: any[] = PROFILES) {
  const state: { row: any } = { row: null };
  const sent: any[] = [];
  const rpcCalls: any[] = [];
  let giftCalls = 0;
  const okChain: any = { eq: () => okChain, then: (res: any) => Promise.resolve({ error: null }).then(res) };
  const admin: any = {
    from(table: string) {
      if (table === "admin_coin_dialog_state") return {
        select: () => ({ eq: () => ({ maybeSingle: async () => ({ data: state.row, error: null }) }) }),
        upsert: async (r: any) => { state.row = { state: r.state, updated_at: r.updated_at }; return { error: null }; },
        delete: () => ({ eq: async () => { state.row = null; return { error: null }; } }),
      };
      if (table === "profiles") return { select: async (_c?: string, opts?: any) => opts?.head ? { count: profiles.length, error: null } : { data: profiles, error: null } };
      if (table === "admin_command_messages") return { insert: async (r: any) => { sent.push(r); return { error: null }; } };
      if (table === "admin_command_runs") return { update: () => okChain };
      throw new Error("unexpected table " + table);
    },
  };
  const userClient: any = {
    rpc: async (name: string, params: any) => {
      rpcCalls.push({ name, params });
      if (name === "admin_command_propose") return { data: { run_id: "run-1" }, error: null };
      if (name === "admin_gift_all_coins") {
        giftCalls++;
        const n = params.p_recipients?.length ?? 0;
        return { data: { success: true, duplicate: giftCalls > 1, recipients: n, total_coins: n * params.p_delta }, error: null };
      }
      if (name === "admin_find_recipients") {
        const q = String(params?.p_query ?? "").toLowerCase();
        const data = q.includes("kevin") ? [{ user_id: "u-kevin", display_name: "Kevin", email: "kevin@example.com" }] : [];
        return { data, error: null };
      }
      if (name === "admin_resolve_recipient") {
        const email = String(params?.p_email ?? "kevin@example.com");
        const id = email === "amani@example.com" ? "u-amani" : "u-kevin";
        return { data: { user_id: id, email }, error: null };
      }
      if (name === "admin_random_member") return { data: { user_id: "u-amani", display_name: "Amani", email: "amani@example.com" }, error: null };
      if (name === "admin_apply_action") return { data: { success: true, action: params.p_action, details: { delta: params.p_payload?.delta } }, error: null };
      throw new Error("unexpected rpc " + name);
    },
  };
  return { admin, userClient, state, sent, rpcCalls };
}

Deno.test("group gift conversation: request -> reason -> plan carries the exact snapshot", async () => {
  const { admin, userClient, state, sent, rpcCalls } = makeClients();

  const r2 = await coinDialog(admin, userClient, "admin-1", "Gift all users 2 coins each");
  assertEquals(r2.type, "plan", "clear plan proposed immediately, no typed confirmation");
  assertEquals(r2.requires_approval, true);
  const step = r2.plan.steps[0];
  assertEquals(step.tool, "gift_all_coins");
  assertEquals(step.args.reason, "Admin adjustment", "missing reason defaults");
  assertEquals(step.args.recipients, ["u-1", "u-2", "u-3"]);
  assertStringIncludes(step.label, "3 members");
  assertStringIncludes(step.label, "6 coins in total");
  assertEquals(state.row, null, "dialog state cleared after proposal");
  assertEquals(sent.some((m) => m.kind === "plan" && m.run_id === "run-1"), true);
  assertEquals(rpcCalls.filter((c) => c.name === "admin_gift_all_coins").length, 0, "proposal does not execute");

  // approval executes once with the snapshot; retry returns the stored result
  const ex1 = await executeStep(userClient, "gift_all_coins", step.args);
  assertEquals(ex1.ok, true);
  const gift = rpcCalls.find((c) => c.name === "admin_gift_all_coins");
  assertEquals(gift.params.p_recipients, ["u-1", "u-2", "u-3"]);
  assertEquals(gift.params.p_delta, 2);
  const ex2 = await executeStep(userClient, "gift_all_coins", step.args);
  assertEquals((ex2.result as any).duplicate, true, "retry sees the stored result, no double gift");
});

Deno.test("after a group proposal there is no typed-confirm state; approval is only the plan card", async () => {
  const { admin, userClient, state, rpcCalls } = makeClients();
  const r = await coinDialog(admin, userClient, "admin-1", "Gift all users 2 coins each");
  assertEquals(r.type, "plan");
  assertEquals(state.row, null, "dialog state cleared after proposal");
  const again = await coinDialog(admin, userClient, "admin-1", "Yes");
  assertEquals(again, null, "no pending dialog: execution only via the plan card Confirm button");
  assertEquals(rpcCalls.filter((c) => c.name === "admin_gift_all_coins").length, 0, "nothing executed");
});

Deno.test("mass deduction is refused with a clear boundary", async () => {
  const { admin, userClient, state } = makeClients();
  const r = await coinDialog(admin, userClient, "admin-1", "Take 3 coins from everybody");
  assertStringIncludes(r.message, "deduction has to name each member");
  assertEquals(state.row, null);
});

Deno.test("single-member flow resolves the recipient and proposes one plan with default reason", async () => {
  const { admin, userClient, state } = makeClients();
  const r2 = await coinDialog(admin, userClient, "admin-1", "Give Kevin 200");
  assertEquals(r2.type, "plan");
  assertEquals(r2.plan.steps.length, 1);
  assertEquals(r2.plan.steps[0].tool, "adjust_coins");
  assertEquals(r2.plan.steps[0].args.targets, ["u-kevin"]);
  assertEquals(r2.plan.steps[0].args.delta, 200);
  assertEquals(r2.plan.steps[0].args.reason, "Admin adjustment");
  assertEquals(state.row?.state?.awaiting, "approval");
});

Deno.test("a new group gift after a proposal proposes a fresh plan", async () => {
  const { admin, userClient } = makeClients();
  const plan = await coinDialog(admin, userClient, "admin-1", "Gift all users 2 coins each");
  assertEquals(plan.type, "plan");
  const r = await coinDialog(admin, userClient, "admin-1", "Gift everyone 5 coins");
  assertEquals(r.type, "plan");
  assertStringIncludes(r.plan.steps[0].label, "15 coins in total");
});

// Planner-level multi-write guard, with the registry lookup mocked. Fail-closed:
// unknown tools arrive as RISKY (the real lookup's default) and count as writes.
const KINDS: Record<string, string> = {
  get_user_insights: "READ",
  get_member_count: "READ",
  adjust_coins: "WRITE",
  set_user_status: "WRITE",
  send_notice: "WRITE",
};
const kindOf = async (tool: string) => KINDS[tool] ?? "RISKY";

Deno.test("guard clarifies two same-category writes", async () => {
  const steps = [{ tool: "set_user_status" }, { tool: "set_user_status" }];
  assertEquals(await planHasMultipleWrites(steps, kindOf), true);
});

Deno.test("guard clarifies cross-category writes", async () => {
  const steps = [{ tool: "adjust_coins" }, { tool: "set_user_status" }];
  assertEquals(await planHasMultipleWrites(steps, kindOf), true);
});

Deno.test("guard fails closed on unknown tools", async () => {
  assertEquals(await planHasMultipleWrites([{ tool: "drop_everything" }, { tool: "adjust_coins" }], kindOf), true);
  assertEquals(await planHasMultipleWrites([{ tool: "drop_everything" }, { tool: "get_user_insights" }], kindOf), false);
});

Deno.test("guard permits a single write and read-plus-write", async () => {
  assertEquals(await planHasMultipleWrites([{ tool: "adjust_coins" }], kindOf), false);
  assertEquals(await planHasMultipleWrites([{ tool: "get_user_insights" }, { tool: "set_user_status" }], kindOf), false);
  assertEquals(await planHasMultipleWrites([], kindOf), false);
});

Deno.test("gift typo then bare Yes: one approval plan, nothing executes", async () => {
  const { admin, userClient, state, rpcCalls } = makeClients();

  const r2 = await coinDialog(admin, userClient, "admin-1", "Git all users 3 coins each");
  assertEquals(r2.type, "plan", "typo gift still enters the deterministic coin dialog and proposes one plan");
  assertEquals(r2.requires_approval, true);
  assertEquals(r2.plan.steps.length, 1, "single write step only");
  const step = r2.plan.steps[0];
  assertEquals(step.tool, "gift_all_coins");
  assertEquals(step.args.delta, 3);
  assertEquals(step.args.reason, "Admin adjustment");
  assertEquals(step.args.recipients, ["u-1", "u-2", "u-3"]);
  assertStringIncludes(step.label, "9 coins in total");
  assertEquals(rpcCalls.filter((c: any) => c.name === "admin_gift_all_coins").length, 0, "proposal never executes");
});

Deno.test("random-member gift: picks a member, names the pick, proposes one plan, nothing executes", async () => {
  const { admin, userClient, rpcCalls } = makeClients();
  const r = await coinDialog(admin, userClient, "admin-1", "Give any random user 2 coins");
  assertEquals(r.type, "plan");
  assertEquals(r.plan.steps.length, 1);
  assertEquals(r.plan.steps[0].tool, "adjust_coins");
  assertEquals(r.plan.steps[0].args.targets, ["u-amani"]);
  assertEquals(r.plan.steps[0].args.delta, 2);
  assertEquals(r.plan.steps[0].args.reason, "Admin adjustment");
  assertStringIncludes(r.plan.steps[0].label, "amani@example.com");
  assertStringIncludes(r.plan.steps[0].label, "random pick");
  assertEquals(rpcCalls.filter((c) => c.name === "admin_random_member").length, 1);
  assertEquals(rpcCalls.filter((c) => c.name === "admin_gift_all_coins").length, 0, "nothing executed");
  assertEquals(rpcCalls.filter((c) => c.name === "admin_command_propose").length, 1);
});

Deno.test("decimal all-scope gift proposes one plan with the exact amount", async () => {
  const { admin, userClient, state, rpcCalls } = makeClients();
  const r = await coinDialog(admin, userClient, "admin-1", "Broo I therefore direct you acutely to categorically disburse 3.5 coins each to all our users on the go");
  assertEquals(r.type, "plan");
  assertEquals(r.plan.steps[0].args.delta, 3.5, "decimal amount preserved exactly");
  assertStringIncludes(r.plan.steps[0].label, "Gift 3.5 coins to each of the 3 members (10.5 coins in total)");
  assertEquals(rpcCalls.filter((c) => c.name === "admin_command_propose").length, 1);
  assertEquals(rpcCalls.filter((c) => c.name === "admin_gift_all_coins").length, 0, "nothing executed");
  assertEquals(state.row, null, "no stuck dialog state");
});

Deno.test("more than 3 decimals is rejected with the exact amount echoed, nothing proposed", async () => {
  const { admin, userClient, rpcCalls, state } = makeClients();
  const r = await coinDialog(admin, userClient, "admin-1", "Disburse 3.4567 coins each to all our users");
  assertEquals(r.type, "chat");
  assertStringIncludes(r.message, "3 decimal places");
  assertStringIncludes(r.message, "3.4567");
  assertStringIncludes(r.message, "3.457");
  assertEquals(rpcCalls.filter((c) => c.name === "admin_command_propose").length, 0);
  assertEquals(state.row, null, "no stuck dialog state");
});

Deno.test("decimal gift total in the plan label is exact, not float dust", async () => {
  const { admin, userClient } = makeClients();
  const r = await coinDialog(admin, userClient, "admin-1", "Disburse 2.3 coins each to all our users");
  assertEquals(r.type, "plan");
  assertStringIncludes(r.plan.steps[0].label, "Gift 2.3 coins to each of the 3 members (6.9 coins in total)");
});

Deno.test("natural-phrased whole all-scope gift proposes one plan", async () => {
  const { admin, userClient, rpcCalls } = makeClients();
  const r = await coinDialog(admin, userClient, "admin-1", "Disburse 2 coins each to all our users");
  assertEquals(r.type, "plan");
  assertStringIncludes(r.plan.steps[0].label, "Gift 2 coins to each of the 3 members (6 coins in total)");
  assertEquals(rpcCalls.filter((c) => c.name === "admin_gift_all_coins").length, 0, "nothing executed");
});

Deno.test("a new full gift instruction is not swallowed as a pending recipient name", async () => {
  const { admin, userClient, state } = makeClients();
  const stuck = await coinDialog(admin, userClient, "admin-1", "Give Zznotaperson 2 coins");
  assertStringIncludes(stuck.message, "No member matches");
  const r = await coinDialog(admin, userClient, "admin-1", "Broo I therefore direct you acutely to categorically disburse 3.5 coins each to all our users on the go");
  assertEquals(r.type, "plan", "parsed as a new gift, not a recipient name");
  assertEquals(r.plan.steps[0].args.delta, 3.5);
  assertEquals(state.row, null, "stuck recipient state cleared");
});

Deno.test("decimal single-recipient gift proposes one plan with the exact amount", async () => {
  const { admin, userClient, rpcCalls } = makeClients();
  const r = await coinDialog(admin, userClient, "admin-1", "Give Kevin 3.5 coins");
  assertEquals(r.type, "plan");
  assertEquals(r.plan.steps[0].args.delta, 3.5, "decimal amount preserved exactly");
  assertStringIncludes(r.plan.steps[0].label, "Grant 3.5 coins to kevin@example.com");
  assertEquals(rpcCalls.filter((c) => c.name === "admin_command_propose").length, 1);
});

Deno.test("single-recipient gift with more than 3 decimals is rejected, nothing proposed", async () => {
  const { admin, userClient, rpcCalls } = makeClients();
  const r = await coinDialog(admin, userClient, "admin-1", "Give Kevin 3.4567 coins");
  assertEquals(r.type, "chat");
  assertStringIncludes(r.message, "3 decimal places");
  assertStringIncludes(r.message, "3.4567");
  assertEquals(rpcCalls.filter((c) => c.name === "admin_command_propose").length, 0);
});

Deno.test("giftAllVerified accepts exact decimal totals and float dust", async () => {
  const { giftAllVerified } = await import("./admin-command-flow.ts");
  assertEquals(giftAllVerified({ recipients: 3, total_coins: 10.5 }, { delta: 3.5, recipients: ["a", "b", "c"] }), true);
  assertEquals(giftAllVerified({ recipients: 3, total_coins: 6.8999999999999995 }, { delta: 2.3, recipients: ["a", "b", "c"] }), true, "JS float product dust tolerated");
  assertEquals(giftAllVerified({ recipients: 3, total_coins: 10.6 }, { delta: 3.5, recipients: ["a", "b", "c"] }), false);
  assertEquals(giftAllVerified({ recipients: 3, total_coins: 3.003 }, { delta: 1.001, recipients: ["a", "b", "c"] }), true, "thousandths compare exactly");
});

Deno.test("executeStep passes the exact decimal delta through, never truncates", async () => {
  const { userClient, rpcCalls } = makeClients();
  const ok = await executeStep(userClient, "adjust_coins", { targets: ["u-kevin"], delta: 2.5, reason: "Test" });
  assertEquals(ok.ok, true);
  const call = rpcCalls.find((c) => c.name === "admin_apply_action");
  assertEquals(call.params.p_payload.delta, 2.5, "2.5 reaches the RPC exactly");
  const gift = await executeStep(userClient, "gift_all_coins", { delta: 3.5, reason: "Test", run_key: "rk-1", recipients: ["u-1", "u-2"] });
  assertEquals(gift.ok, true);
  const giftCall = rpcCalls.find((c) => c.name === "admin_gift_all_coins");
  assertEquals(giftCall.params.p_delta, 3.5, "3.5 reaches the RPC exactly");
});

Deno.test("executeStep refuses more than 3 decimals instead of rounding", async () => {
  const { userClient, rpcCalls } = makeClients();
  const before = rpcCalls.length;
  const gift = await executeStep(userClient, "gift_all_coins", { delta: 3.4567, reason: "Test", run_key: "rk-1", recipients: ["u-1"] });
  assertEquals(gift.ok, false);
  const adj = await executeStep(userClient, "adjust_coins", { targets: ["u-kevin"], delta: 1.2345, reason: "Test" });
  assertEquals(adj.ok, false);
  assertEquals(rpcCalls.filter((c) => c.name === "admin_gift_all_coins" || c.name === "admin_apply_action").length, 0, "no RPC left the edge");
  assertEquals(rpcCalls.length, before, "no RPC calls at all");
});

Deno.test("verbose all-users directive (hyphenated amount, per-user, noun verbs) proposes a plan, not a typed question", async () => {
  const { admin, userClient } = makeClients();
  const out = await coinDialog(admin, userClient, "admin-1",
    "Broo, I hereby issue an unequivocal directive for the deterministic, atomic, and globally scoped orchestration of a 4.6-coin per-user entitlement allocation, requiring immediate propagation across the complete eligible-user cardinality, with idempotent execution, transactional integrity, invariant preservation, and zero tolerance for partial, duplicated, or semantically divergent settlement states.");
  assertEquals(out?.type, "plan", "the whole directive becomes one review card");
  assertEquals(out?.plan?.steps?.[0]?.tool, "gift_all_coins");
  assertEquals(out?.plan?.steps?.[0]?.args?.delta, 4.6, "4.6 preserved exactly");
});

Deno.test("per-user / each user / hyphenated amount phrasings parse as all-scope gifts", async () => {
  const { parseCoinRequest } = await import("./coin-dialog.js");
  assertEquals(parseCoinRequest("Disburse 1.5 coins per user")?.scope, "all");
  assertEquals(parseCoinRequest("Disburse 1.5 coins per user")?.delta, 1.5);
  assertEquals(parseCoinRequest("Gift 2 coins to each user")?.scope, "all");
  assertEquals(parseCoinRequest("Allocation of a 3.25-coin grant to all eligible members")?.delta, 3.25);
  assertEquals(parseCoinRequest("Credit 10 coins across all our users")?.scope, "all");
});

Deno.test("questions about coins are never parsed as gift directives", async () => {
  const { parseCoinRequest } = await import("./coin-dialog.js");
  assertEquals(parseCoinRequest("Did you give all users 5 coins?"), null);
  assertEquals(parseCoinRequest("How many coins does Kevin have?"), null);
  assertEquals(parseCoinRequest("What is the price of 100 coins?"), null);
  assertEquals(parseCoinRequest("Can you gift everyone 2 coins?"), null, "a request phrased as a question is not a directive");
});

Deno.test("normalizeSteps fills the gift run key and lets SQL snapshot recipients", async () => {
  const { normalizeSteps } = await import("./admin-command-flow.ts");
  const [st] = normalizeSteps([{ tool: "gift_all_coins", label: "Gift 4.6 coins to every member", args: { delta: 4.6, reason: "Test" } }]);
  assertEquals(typeof st.args.run_key, "string");
  assertEquals(st.args.run_key.length > 0, true, "idempotency key is never empty");
  assertEquals("recipients" in st.args, false, "absent recipients lets SQL snapshot atomically");
  const other = normalizeSteps([{ tool: "adjust_coins", label: "x", args: { targets: ["u"], delta: 1 } }]);
  assertEquals(other[0].args.run_key, undefined, "other tools untouched");
});
