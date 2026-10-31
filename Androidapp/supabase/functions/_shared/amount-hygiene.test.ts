import { assertEquals } from "https://deno.land/std@0.224.0/assert/mod.ts";
import { amountCandidates, amountSourceFlags, amountSourceProblem, expressedNegative, expressedDeduction, signMismatchProblem, coinWriteGuardProblem } from "./amount-hygiene.js";

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

Deno.test("expressedNegative detects written sign in word and digit forms", () => {
  assertEquals(expressedNegative("Gift all users a surprise bal of negative three coins each", 3), true);
  assertEquals(expressedNegative("gift everyone minus 3 coins", 3), true);
  assertEquals(expressedNegative("give -3 coins to all", 3), true);
  assertEquals(expressedNegative("reduce by negative 2.5 coins", 2.5), true);
  assertEquals(expressedNegative("gift everyone three coins", 3), false);
  assertEquals(expressedNegative("gift everyone 3 coins", 3), false);
});

Deno.test("signMismatchProblem binds plan sign to the admin's words", () => {
  // The 11:56 defect class: negative wording, positive gift plan.
  assertEquals(
    signMismatchProblem("Gift all users a surprise bal of negative three coins each", "gift_all_coins", 3),
    "you wrote a negative amount - that would take coins away, but this plan adds them");
  // The same wording correctly routed to a deduction is NOT flagged.
  assertEquals(signMismatchProblem("Gift all users a surprise bal of negative three coins each", "deduct_all_coins", 3), null);
  // A negative adjust plan needs written grounding: "negative" or a deduction verb.
  assertEquals(signMismatchProblem("take 3 coins from ann@x.com", "adjust_coins", -3), null);
  assertEquals(signMismatchProblem("give 3 coins to ann@x.com", "adjust_coins", -3),
    "your message adds or allocates coins, but this plan takes them away");
  assertEquals(signMismatchProblem("add negative 3 coins to ann@x.com", "adjust_coins", 3),
    "you wrote a negative amount - that would take coins away, but this plan adds them");
  // Consistent plans pass.
  assertEquals(signMismatchProblem("gift everyone 3 coins", "gift_all_coins", 3), null);
  assertEquals(expressedDeduction("deduct 5 coins from each member"), true);
  assertEquals(expressedDeduction("gift 5 coins to each member"), false);
});

Deno.test("direction is amount-attached, not a bag-of-words vote (Roy 12:14 + correction)", () => {
  // Roy's exact message, with his correction "diminishing means deduct":
  // "allocated" only attaches the amount to the audience; "diminishing"
  // directs the amount itself. A deduction is CONSISTENT - never clarify.
  assertEquals(
    signMismatchProblem("Directive therefore, allocated to all users a diminishing balance of 2 coins each", "deduct_all_coins", 2), null);
  // A positive gift plan for the same words is contradicted and blocked.
  assertEquals(
    signMismatchProblem("Directive therefore, allocated to all users a diminishing balance of 2 coins each", "gift_all_coins", 2),
    "your message says the balance is reduced, but this plan adds coins");
  // The contradiction is also caught at execution time through the backstop.
  assertEquals(
    coinWriteGuardProblem("Directive therefore, allocated to all users a diminishing balance of 2 coins each", "gift_all_coins", 2),
    "This plan no longer passes safety validation: your message says the balance is reduced, but this plan adds coins. Please ask again - nothing was executed.");
  // A REAL contradiction - both directions attached to the amount - clarifies.
  assertEquals(
    signMismatchProblem("add 2 coins but deduct 2 coins", "gift_all_coins", 2),
    "your message mixes adding and reducing wording - say exactly whether to add or deduct");
  // Increase cue alone blocks a deduction plan.
  assertEquals(
    signMismatchProblem("gift 2 coins to all users", "deduct_all_coins", 2),
    "your message adds or allocates coins, but this plan takes them away");
  // Unambiguous directions still pass: plain gift, plain deduction, explicit sign.
  assertEquals(signMismatchProblem("diminish all users by 2 coins", "deduct_all_coins", 2), null);
  assertEquals(signMismatchProblem("gift everyone 2 coins", "gift_all_coins", 2), null);
  assertEquals(signMismatchProblem("Gift all users a surprise bal of negative three coins each", "deduct_all_coins", 3), null);
  assertEquals(signMismatchProblem("take 3 coins from ann@x.com", "adjust_coins", -3), null);
});

Deno.test("coinWriteGuardProblem is the execute-time backstop", () => {
  // Legacy run: original words were never bound - fail closed, fresh review.
  assertEquals(coinWriteGuardProblem(null, "gift_all_coins", 3),
    "This plan predates amount-sign validation, so it cannot be re-checked safely. Please ask again - nothing was executed.");
  // Bound and consistent original passes.
  assertEquals(coinWriteGuardProblem("gift 3 coins to everyone", "gift_all_coins", 3), null);
  assertEquals(coinWriteGuardProblem("take 3 coins from ann@x.com", "adjust_coins", -3), null);
  // Bound but contradictory original fails closed.
  assertEquals(
    coinWriteGuardProblem("Gift all users a surprise bal of negative three coins each", "gift_all_coins", 3),
    "This plan no longer passes safety validation: you wrote a negative amount - that would take coins away, but this plan adds them. Please ask again - nothing was executed.");
  // Non-coin tools are untouched, even on legacy runs.
  assertEquals(coinWriteGuardProblem(null, "announce_all", NaN), null);
});
