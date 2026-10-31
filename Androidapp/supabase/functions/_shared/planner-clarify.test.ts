import { assertEquals } from "https://deno.land/std@0.224.0/assert/mod.ts";
import { composeMissingClarify, sanitizeClarifyText } from "./planner-clarify.ts";

Deno.test("plain clarify passes through untouched", () => {
  const r = sanitizeClarifyText("Which member should this go to?");
  assertEquals(r, { text: "Which member should this go to?", sanitized: false });
});
Deno.test("machinery leaks are replaced", () => {
  for (const leak of [
    "The amount field fails the schema.",
    "I need a valid JSON payload.",
    "Which provider should I use, Gemini or Cohere?",
    "Please supply the missing arguments.",
    "That tier is not in the enum.",
    "Your prompt is unclear.",
  ]) {
    const r = sanitizeClarifyText(leak);
    assertEquals(r.sanitized, true, leak);
    assertEquals(r.text.includes("plain words"), true, leak);
    assertEquals(MACHINERY_TEST_SAFE(r.text), true, leak);
  }
});
Deno.test("empty clarify becomes the safe ask", () => {
  assertEquals(sanitizeClarifyText("  ").sanitized, true);
});
Deno.test("coin/amount wording is not machinery", () => {
  assertEquals(sanitizeClarifyText("How many coins should each member receive?").sanitized, false);
});
function MACHINERY_TEST_SAFE(text: string): boolean {
  return !/\b(?:schema|json|payload|uuid|regex|enum|provider|openrouter|gemini|cohere|prompt|token|rpc|sql)\b/i.test(text);
}

Deno.test("composeMissingClarify: known fields become exact asks", () => {
  const msg = composeMissingClarify(["amount", "recipient"]);
  assertEquals(msg, "I need the exact coin amount. Which member do you mean? A full name or email works. Nothing was changed.");
});
Deno.test("composeMissingClarify: unknown fields dropped, null when none known", () => {
  assertEquals(composeMissingClarify(["flux_capacitor"]), null);
  assertEquals(composeMissingClarify("amount"), null);
  assertEquals(composeMissingClarify([]), null);
  const msg = composeMissingClarify(["nonsense", "tier"]);
  assertEquals(msg, "Which tier: FREE, SUPPORTER or PREMIUM? Nothing was changed.");
});
Deno.test("composeMissingClarify: at most three asks, deduped", () => {
  const msg = composeMissingClarify(["amount", "amount", "title", "body", "tier"])!;
  assertEquals(msg.split("Nothing was changed.").length, 2);
  assertEquals((msg.match(/\?/g) || []).length + (msg.match(/\./g) || []).length - 1 >= 3, true);
  assertEquals(msg.includes("Which tier"), false); // 4th ask dropped
});
