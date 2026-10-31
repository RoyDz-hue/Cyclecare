import { assertEquals } from "https://deno.land/std@0.224.0/assert/mod.ts";
import { amountSourceFlags, amountSourceProblem } from "./amount-hygiene.ts";

Deno.test("amountSourceFlags: clean amount passes", () => {
  assertEquals(amountSourceFlags("give everyone 50 coins", 50), []);
});
Deno.test("amountSourceFlags: date detected", () => {
  assertEquals(amountSourceFlags("on 2026-10-31 give 31 coins", 2026).includes("date"), true);
  assertEquals(amountSourceProblem(amountSourceFlags("gift 2026 coins on 2026-10-31", 2026)), "that number reads as a date, not a coin amount");
});
Deno.test("amountSourceFlags: year-like bare amount", () => {
  assertEquals(amountSourceFlags("give all users 2026 coins", 2026), ["year_like"]);
  assertEquals(amountSourceFlags("give all users 2027.5 coins", 2027.5), []);
});
Deno.test("amountSourceFlags: ordinal detected", () => {
  assertEquals(amountSourceFlags("the 3rd user gets 10 coins", 3), ["ordinal"]);
});
Deno.test("amountSourceFlags: fiat detected", () => {
  assertEquals(amountSourceFlags("worth 500 KES, give 10 coins", 500), ["fiat"]);
});
Deno.test("amountSourceFlags: identifier detected", () => {
  assertEquals(amountSourceFlags("INV-2342 issued, give 5 coins", 2342), ["identifier"]);
});
Deno.test("amountSourceFlags: amount not written in text is not flagged", () => {
  assertEquals(amountSourceFlags("give five coins to everyone", 5), []);
});
