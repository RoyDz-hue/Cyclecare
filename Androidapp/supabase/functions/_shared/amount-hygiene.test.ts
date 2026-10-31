import { assertEquals } from "https://deno.land/std@0.224.0/assert/mod.ts";
import { amountCandidates, amountSourceFlags, amountSourceProblem } from "./amount-hygiene.js";

Deno.test("clean amount passes", () => {
  assertEquals(amountSourceFlags("give everyone 50 coins", 50), []);
});
Deno.test("clean occurrence wins over a date-shaped twin", () => {
  assertEquals(amountSourceFlags("on 2026-10-31 give 31 coins", 31), []);
  assertEquals(amountSourceFlags("scheduled 2026-10-31, gift 2026 coins to all", 2026), []);
});
Deno.test("year-like amount is fine when glued to coins, flagged when bare", () => {
  assertEquals(amountSourceFlags("give all users 2026 coins", 2026), []);
  assertEquals(amountSourceFlags("for 2026 give everyone a bonus", 2026), ["year_like"]);
});
Deno.test("date-only occurrence flagged", () => {
  assertEquals(amountSourceProblem(amountSourceFlags("on 2026-10-31 gift coins", 2026)), "that number reads as a date, not a coin amount");
});
Deno.test("ordinal, fiat, identifier flagged", () => {
  assertEquals(amountSourceFlags("the 3rd user gets coins", 3), ["ordinal"]);
  assertEquals(amountSourceFlags("worth 500 KES, give coins", 500), ["fiat"]);
  assertEquals(amountSourceFlags("INV-2342 issued, give coins", 2342), ["identifier"]);
});
Deno.test("unwritten amount (word form) is not flagged", () => {
  assertEquals(amountSourceFlags("give five coins to everyone", 5), []);
});
Deno.test("amountCandidates matches parser guard behavior", () => {
  assertEquals(amountCandidates("Valued at -4.1 each donate to all users coins"), ["-4.1"]);
  assertEquals(amountCandidates("on 2026-10-31 give 5 coins"), ["5"]);
  assertEquals(amountCandidates("give 500 KES or 10 coins"), ["10"]);
  assertEquals(amountCandidates("INV-2342 and 3rd of May, give coins"), []);
  assertEquals(amountCandidates("give 5 or 10 coins"), ["5", "10"]);
});
