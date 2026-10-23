// Deterministic Guide-message safety tests: no LLM calls, no network.
import { scanGuideMessage } from "../functions/_shared/ai-core.ts";
import { assertEquals } from "https://deno.land/std@0.224.0/assert/mod.ts";

const BLOCKED: [string, string][] = [
  ["Hey there, friends! Let's chat about rest today.", "template opener"],
  ["Hi everyone! I'm Imani, here to chat about cycles.", "self announcement"],
  ["Let's talk about sleep tonight", "chat-about opener"],
  ["I love the CycleCare app's reminders", "app plug"],
  ["You should try the app for this", "the-app plug"],
  ["I save my coins for the premium themes", "premium/coins plug"],
  ["The app's meditation timer is lovely", "app feature plug"],
  ["It's like a warm hug for your body", "brochure phrase"],
  ["I've been trying to wind down with stretches", "invented routine"],
  ["my cramps were bad last week", "invented body"],
  ["it helps me relax before bed", "claims a body"],
  ["I find stretching helps", "I-find claim"],
  ["that works for me too", "works-for-me claim"],
  ["when I was younger this happened", "invented past"],
  ["we're always here for you", "blanket availability"],
  ["here whenever you need us", "availability wording"],
  ["come back soon, we miss you", "cling"],
  ["talk to me anytime", "cling"],
  ["guaranteed to fix your cramps", "overclaim"],
];

const ALLOWED: [string, string][] = [
  ["Bright Star, sure! A warm drink and some gentle stretches often help folks relax before bed.", "general-truth tip"],
  ["Pole, that sounds heavy. Are you soaking through a pad every hour? Please get seen today.", "red-flag triage"],
  ["sawa, karibu! 😊", "greeting"],
  ["😂 Haha, fair", "short reaction"],
  ["we don't really know yet - everyone is different", "honest uncertainty"],
  ["Hmmm not sure about that one", "hedged answer"],
  ["Niaje Wanjiru, uko aje?", "sheng greeting"],
];

Deno.test("scanGuideMessage blocks unsafe or off-voice output", () => {
  for (const [text, label] of BLOCKED) {
    const v = scanGuideMessage(text);
    assertEquals(v.ok, false, `should block (${label}): ${text} -> ${v.reasons.join(",")}`);
  }
});

Deno.test("scanGuideMessage allows good human replies", () => {
  for (const [text, label] of ALLOWED) {
    const v = scanGuideMessage(text);
    assertEquals(v.ok, true, `should allow (${label}): ${text} -> ${v.reasons.join(",")}`);
  }
});
