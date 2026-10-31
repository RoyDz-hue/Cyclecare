import { assertEquals, assert } from "https://deno.land/std@0.224.0/assert/mod.ts";
import { capConversation, extractUsage } from "./ai-cost.ts";

Deno.test("capConversation keeps the newest lines within the char budget", () => {
  const lines = Array.from({ length: 24 }, (_, i) => `Admin: message ${i} ` + "x".repeat(700));
  const capped = capConversation(lines, 4000);
  assert(capped.length < 24 && capped.length > 0);
  assert(capped.join("\n").length <= 4000 + 800); // one line may straddle the boundary
  assertEquals(capped[capped.length - 1], lines[23], "newest line always kept");
  assertEquals(capped[0], lines[24 - capped.length], "kept suffix is contiguous");
});

Deno.test("capConversation never invents or reorders content", () => {
  const lines = ["a", "bb", "ccc"];
  assertEquals(capConversation(lines, 10), ["a", "bb", "ccc"]);
  assertEquals(capConversation(lines, 2)[0], "ccc");
});

Deno.test("extractUsage reads each provider shape and never invents numbers", () => {
  assertEquals(extractUsage("GEMINI", { usageMetadata: { promptTokenCount: 100, candidatesTokenCount: 40 } }), { input: 100, output: 40 });
  assertEquals(extractUsage("OPENROUTER", { usage: { prompt_tokens: 90, completion_tokens: 30 } }), { input: 90, output: 30 });
  assertEquals(extractUsage("COHERE", { usage: { tokens: { input_tokens: 80, output_tokens: 25 } } }), { input: 80, output: 25 });
  assertEquals(extractUsage("GEMINI", {}), { input: null, output: null });
  assertEquals(extractUsage("OPENROUTER", { usage: {} }), { input: null, output: null });
});
