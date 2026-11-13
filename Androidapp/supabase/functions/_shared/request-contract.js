export const MAX_QUESTION = 1200;
export const MAX_SUMMARY = 2400;
export const MAX_PROMPT = 500;
export const SUPPORTED_AI_PROVIDERS = Object.freeze(["COHERE", "GEMINI", "OPENROUTER", "LAJIWA"]);

const CONTROL = /[\u0000-\u0008\u000B\u000C\u000E-\u001F]/g;
export function normalizeAiProvider(value) {
  const normalized = typeof value === "string" ? value.trim().toUpperCase() : "";
  return SUPPORTED_AI_PROVIDERS.includes(normalized) ? normalized : null;
}
export function aiProviderStatus(provider, configured) {
  const normalized = normalizeAiProvider(provider);
  if (!normalized) return { configured: false, validated: false, reason: "Unsupported provider; no provider was selected." };
  if (!configured) return { configured: false, validated: false, reason: "The provider Edge secret is missing or still a placeholder." };
  return { configured: true, validated: false, reason: "Configured, but not validated by a live provider call." };
}
export function cleanText(value, max) {
  if (typeof value !== "string") return null;
  const clean = value.replace(CONTROL, "").trim();
  return clean.length > 0 && clean.length <= max ? clean : null;
}
export function validateAiRequest(body) {
  if (!body || typeof body !== "object" || Array.isArray(body)) return { ok: false, error: "object_required" };
  const allowed = new Set(["schema_version", "user_question", "context_mode", "ai_consent", "context", "conversation_history", "processor_receipts"]);
  if (Object.keys(body).some((key) => !allowed.has(key))) return { ok: false, error: "unknown_field" };
  if (body.schema_version === 2) return { ok: false, error: "structure_update_required" };
  if (body.schema_version !== 3) return { ok: false, error: "schema_version_3_required" };
  if (!Array.isArray(body.processor_receipts) || body.processor_receipts.length < 1 || body.processor_receipts.length > 3) return { ok: false, error: "processor_receipts_required" };
  for (const receipt of body.processor_receipts) {
    if (!receipt || typeof receipt !== "object" || typeof receipt.provider_id !== "string" || !Number.isInteger(receipt.revision) || receipt.revision < 1 || typeof receipt.manifest_fingerprint !== "string" || !/^[a-f0-9]{64}$/.test(receipt.manifest_fingerprint)) return { ok: false, error: "processor_receipt_invalid" };
  }
  const question = cleanText(body.user_question, MAX_QUESTION);
  if (!question) return { ok: false, error: "question_invalid" };
  if (body.ai_consent !== true) return { ok: false, error: "request_consent_required" };
  if (body.context_mode !== "minimal" && body.context_mode !== "expanded") return { ok: false, error: "context_mode_invalid" };
  if (body.context_mode === "minimal" && body.context !== undefined) return { ok: false, error: "minimal_context_forbidden" };
  let summary = null;
  if (body.context_mode === "expanded") {
    if (!body.context || typeof body.context !== "object" || Array.isArray(body.context)) return { ok: false, error: "context_required" };
    if (Object.keys(body.context).some((key) => key !== "summary")) return { ok: false, error: "unknown_context_field" };
    summary = cleanText(body.context.summary, MAX_SUMMARY);
    if (!summary) return { ok: false, error: "summary_invalid" };
  }
  let conversationHistory = [];
  if (body.conversation_history !== undefined) {
    if (!Array.isArray(body.conversation_history) || body.conversation_history.length > 6) return { ok: false, error: "conversation_history_invalid" };
    conversationHistory = body.conversation_history.map((turn) => {
      if (!turn || typeof turn !== "object" || (turn.role !== "user" && turn.role !== "assistant") || typeof turn.content !== "string" || turn.content.trim().length === 0 || turn.content.length > 600) return null;
      if (Object.keys(turn).some((key) => key !== "role" && key !== "content")) return null;
      const content = cleanText(turn.content, 600);
      return content ? { role: turn.role, content } : null;
    });
    if (conversationHistory.some((turn) => turn === null)) return { ok: false, error: "conversation_history_invalid" };
  }
  return { ok: true, question, contextMode: body.context_mode, summary, conversationHistory, processorReceipts: body.processor_receipts };
}
export function validateImageRequest(body) {
  if (!body || typeof body !== "object" || Array.isArray(body)) return { ok: false, error: "object_required" };
  const allowed = new Set(["style", "custom_prompt", "image_consent"]);
  if (Object.keys(body).some((key) => !allowed.has(key))) return { ok: false, error: "unknown_field" };
  if (body.image_consent !== true) return { ok: false, error: "request_consent_required" };
  const prompt = body.custom_prompt === undefined || body.custom_prompt === "" ? "" : cleanText(body.custom_prompt, MAX_PROMPT);
  if (body.custom_prompt !== undefined && prompt === null) return { ok: false, error: "prompt_invalid" };
  return { ok: true, prompt: prompt || "" };
}
export function paymentSuccessCanCredit({ independentlyVerified, amountMatches, callbackTokenValid }) {
  return independentlyVerified === true && amountMatches === true && callbackTokenValid === true;
}
