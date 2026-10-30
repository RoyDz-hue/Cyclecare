import { assertEquals } from "https://deno.land/std@0.224.0/assert/mod.ts";
import { giftAllVerified } from "./admin-command-flow.ts";

const args = { delta: 2, reason: "patience this week", run_key: "k1", recipients: ["u1","u2","u3"] };

Deno.test("gift verified when recipients match snapshot and total is consistent", () => {
  assertEquals(giftAllVerified({ success: true, duplicate: false, recipients: 3, total_coins: 6, reason: "x" }, args), true);
});
Deno.test("duplicate idempotent replay verifies when counts match", () => {
  assertEquals(giftAllVerified({ success: true, duplicate: true, recipients: 3, total_coins: 6, reason: "x" }, args), true);
});
Deno.test("rejects recipient count short of snapshot", () => {
  assertEquals(giftAllVerified({ success: true, duplicate: false, recipients: 2, total_coins: 4, reason: "x" }, args), false);
});
Deno.test("rejects inconsistent total_coins", () => {
  assertEquals(giftAllVerified({ success: true, duplicate: false, recipients: 3, total_coins: 5, reason: "x" }, args), false);
});
Deno.test("rejects missing counts", () => {
  assertEquals(giftAllVerified({ success: true }, args), false);
});
Deno.test("no snapshot falls back to positive consistent count", () => {
  assertEquals(giftAllVerified({ success: true, recipients: 2, total_coins: 4 }, { delta: 2 }), true);
  assertEquals(giftAllVerified({ success: true, recipients: 0, total_coins: 0 }, { delta: 2 }), false);
});
