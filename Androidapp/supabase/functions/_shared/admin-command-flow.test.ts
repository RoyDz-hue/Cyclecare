// Mocked end-to-end conversation tests: no network, no model calls, no real member rows.
import { assertEquals, assertStringIncludes } from "jsr:@std/assert";
import { coinDialog, executeStep } from "./admin-command-flow.ts";

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
      if (name === "admin_find_recipients") return { data: [{ user_id: "u-kevin", display_name: "Kevin", email: "kevin@example.com" }], error: null };
      if (name === "admin_resolve_recipient") return { data: { user_id: "u-kevin", email: "kevin@example.com" }, error: null };
      throw new Error("unexpected rpc " + name);
    },
  };
  return { admin, userClient, state, sent, rpcCalls };
}

Deno.test("group gift conversation: request -> reason -> plan carries the exact snapshot", async () => {
  const { admin, userClient, state, sent, rpcCalls } = makeClients();

  const r1 = await coinDialog(admin, userClient, "admin-1", "Gift all users 2 coins each");
  assertEquals(r1.type, "clarify");
  assertStringIncludes(r1.message, "3 members");
  assertStringIncludes(r1.message, "6 coins in total");
  assertEquals(state.row?.state?.scope, "all");
  assertEquals(state.row?.state?.awaiting, "reason");
  assertEquals(rpcCalls.length, 0, "no execution during preview");

  const r2 = await coinDialog(admin, userClient, "admin-1", "Admin adjustment");
  assertEquals(r2.type, "plan");
  assertEquals(r2.requires_approval, true);
  const step = r2.plan.steps[0];
  assertEquals(step.tool, "gift_all_coins");
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

Deno.test("cancel during the reason question aborts with no execution", async () => {
  const { admin, userClient, rpcCalls } = makeClients();
  await coinDialog(admin, userClient, "admin-1", "Gift all users 2 coins each");
  const r = await coinDialog(admin, userClient, "admin-1", "cancel");
  assertStringIncludes(r.message, "Cancelled");
  assertEquals(rpcCalls.filter((c) => c.name === "admin_command_propose").length, 0);
});

Deno.test("mass deduction is refused with a clear boundary", async () => {
  const { admin, userClient, state } = makeClients();
  const r = await coinDialog(admin, userClient, "admin-1", "Take 3 coins from everybody");
  assertStringIncludes(r.message, "deduction has to name each member");
  assertEquals(state.row, null);
});

Deno.test("single-member flow still resolves recipient then reason then plan", async () => {
  const { admin, userClient, state } = makeClients();
  const r1 = await coinDialog(admin, userClient, "admin-1", "Give Kevin 200");
  assertStringIncludes(r1.message, "Kevin");
  assertStringIncludes(r1.message, "reason");
  const r2 = await coinDialog(admin, userClient, "admin-1", "Admin adjustment");
  assertEquals(r2.type, "plan");
  assertEquals(r2.plan.steps[0].tool, "adjust_coins");
  assertEquals(r2.plan.steps[0].args.targets, ["u-kevin"]);
  assertEquals(r2.plan.steps[0].args.delta, 200);
  assertEquals(state.row?.state?.awaiting, "approval");
});

Deno.test("a new group gift after a proposal starts a fresh dialog", async () => {
  const { admin, userClient } = makeClients();
  await coinDialog(admin, userClient, "admin-1", "Gift all users 2 coins each");
  const plan = await coinDialog(admin, userClient, "admin-1", "Admin adjustment");
  assertEquals(plan.type, "plan");
  const r = await coinDialog(admin, userClient, "admin-1", "Gift everyone 5 coins");
  assertEquals(r.type, "clarify");
  assertStringIncludes(r.message, "15 coins in total");
});
