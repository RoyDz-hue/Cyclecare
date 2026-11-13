import { serve } from "https://deno.land/std@0.224.0/http/server.ts";
import { createClient } from "https://esm.sh/@supabase/supabase-js@2.118.0";
import { authenticatedUserId, isAccountRestricted, jsonResponse, supabaseSecretKey } from "../_shared/payments.ts";
import { validateImageRequest } from "../_shared/request-contract.js";

const IMAGE_PRESETS: Record<string, string> = {
  botanical: "A serene botanical garden after gentle rain, soft sage leaves, delicate wildflowers, warm natural light, refined editorial illustration, soothing palette, no people, no text, no symbols, no medical imagery.",
  moonlit: "Quiet moonlit water with a luminous crescent reflected on gentle ripples, deep indigo and muted lavender, elegant dreamy painterly illustration, no people, no text, no symbols, no medical imagery.",
  sunrise: "A soft sunrise over a peaceful horizon, warm peach and champagne light, airy watercolor texture, elegant minimal composition, no people, no text, no symbols, no medical imagery.",
  ocean: "A tranquil ocean cove at dawn with smooth waves and a pale rose sky, calm teal and sand colors, refined painterly illustration, no people, no text, no symbols, no medical imagery.",
};

const MAX_IMAGE_BYTES = 8 * 1024 * 1024;
const CORS_HEADERS = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Headers": "authorization, x-client-info, apikey, content-type",
  "Access-Control-Allow-Methods": "POST, OPTIONS",
};

function usableSecret(value: string | undefined): value is string {
  const secret = value?.trim() ?? "";
  if (secret.length <= 8 || (secret.startsWith("__") && secret.endsWith("__"))) return false;
  return !/^(?:<[^>]+>|placeholder.*|change(?:[_ -]?me)?.*|replace.*|your(?:[_ -].*)?|todo.*|0+)$/i.test(secret);
}

function imageError(message: string, status: number): Response {
  return new Response(JSON.stringify({ success: false, error: message }), {
    status,
    headers: { ...CORS_HEADERS, "Content-Type": "application/json", "Cache-Control": "no-store" },
  });
}

async function readSmallJson(req: Request): Promise<unknown> {
  const reader = req.body?.getReader();
  if (!reader) throw new Error("missing-body");
  const chunks: Uint8Array[] = [];
  let size = 0;
  while (true) {
    const { done, value } = await reader.read();
    if (done) break;
    size += value.byteLength;
    if (size > 4096) {
      await reader.cancel().catch(() => {});
      throw new Error("body-too-large");
    }
    chunks.push(value);
  }
  const bytes = new Uint8Array(size);
  let offset = 0;
  for (const chunk of chunks) {
    bytes.set(chunk, offset);
    offset += chunk.byteLength;
  }
  return JSON.parse(new TextDecoder().decode(bytes));
}


function imageResponse(bytes: Uint8Array, contentType: string): Response {
  return new Response(bytes, {
    status: 200,
    headers: {
      ...CORS_HEADERS,
      "Content-Type": contentType,
      "Content-Length": String(bytes.byteLength),
      "Cache-Control": "private, no-store, max-age=0",
      "X-Content-Type-Options": "nosniff",
    },
  });
}

function remainingSignal(deadline: number, capMs: number): { signal: AbortSignal; stop: () => void } | null {
  const remaining = deadline - Date.now();
  if (remaining <= 1_000) return null;
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), Math.min(capMs, remaining));
  return { signal: controller.signal, stop: () => clearTimeout(timer) };
}

// Prompt polish: the chat-provider chain (Gemini -> Cohere -> OpenRouter)
// refines the concept once; any failure returns the original prompt unchanged.
async function polishPrompt(basePrompt: string, deadline: number): Promise<string> {
  const instruction = "You optimize prompts for an image generator. Rewrite the concept below as one vivid, refined image prompt of at most 80 words. Preserve the mood and intent. Keep every existing safety restriction (no people, no text, no symbols, no medical imagery). Reply with the prompt text only.\n\nConcept: ";
  const candidates: Array<{ provider: string; key: string | undefined }> = [
    { provider: "GEMINI", key: Deno.env.get("GEMINI_API_KEY") },
    { provider: "COHERE", key: Deno.env.get("COHERE_API_KEY") },
    { provider: "OPENROUTER", key: Deno.env.get("OPENROUTER_API_KEY") },
  ];
  for (const { provider, key } of candidates) {
    if (!usableSecret(key)) continue;
    const timed = remainingSignal(deadline, 20_000);
    if (!timed) return basePrompt;
    const signal = timed.signal;
    try {
      let text: string | undefined;
      if (provider === "GEMINI") {
        const model = Deno.env.get("GEMINI_MODEL") || "gemini-3.6-flash";
        const r = await fetch(`https://generativelanguage.googleapis.com/v1beta/models/${encodeURIComponent(model)}:generateContent`, {
          method: "POST", headers: { "Content-Type": "application/json", "x-goog-api-key": key! }, signal,
          body: JSON.stringify({ contents: [{ role: "user", parts: [{ text: instruction + basePrompt }] }], generationConfig: { temperature: 0.7, maxOutputTokens: 1000 } }),
        });
        if (!r.ok) continue;
        const d = await r.json();
        text = d?.candidates?.[0]?.content?.parts?.map((p: { text?: unknown }) => typeof p?.text === "string" ? p.text : "").join("");
      } else {
        const endpoint = provider === "COHERE" ? "https://api.cohere.com/v2/chat" : "https://openrouter.ai/api/v1/chat/completions";
        const r = await fetch(endpoint, {
          method: "POST", headers: { "Content-Type": "application/json", Authorization: `Bearer ${key}` }, signal,
          body: JSON.stringify({
            model: provider === "COHERE" ? "command-a-03-2025" : "openai/gpt-4.1-mini",
            messages: [{ role: "user", content: instruction + basePrompt }], temperature: 0.7, max_tokens: 220,
            ...(provider === "OPENROUTER" ? { provider: { only: ["openai"], allow_fallbacks: false, data_collection: "deny", zdr: true } } : {}),
          }),
        });
        if (!r.ok) continue;
        const d = await r.json();
        text = provider === "COHERE"
          ? (Array.isArray(d?.message?.content) ? d.message.content.map((p: { text?: unknown }) => typeof p?.text === "string" ? p.text : "").join("") : undefined)
          : d?.choices?.[0]?.message?.content;
      }
      const clean = typeof text === "string" ? text.replace(/[\u0000-\u001F]/g, " ").trim() : "";
      timed.stop();
      if (clean.length >= 40 && clean.length <= 600) return clean;
    } catch { /* fall through to next provider */ } finally { timed.stop(); }
  }
  return basePrompt;
}

async function readImageBytes(response: Response): Promise<Uint8Array | null> {
  const reader = response.body?.getReader();
  if (!reader) return null;
  const chunks: Uint8Array[] = [];
  let total = 0;
  while (true) {
    const { done, value } = await reader.read();
    if (done) break;
    total += value.byteLength;
    if (total > MAX_IMAGE_BYTES) { await reader.cancel().catch(() => {}); return null; }
    chunks.push(value);
  }
  const image = new Uint8Array(total);
  let offset = 0;
  for (const chunk of chunks) { image.set(chunk, offset); offset += chunk.byteLength; }
  return image.byteLength > 0 ? image : null;
}

async function fetchPollinationsImage(prompt: string, apiKey: string, deadline: number): Promise<{ bytes: Uint8Array; contentType: string } | null> {
  const timed = remainingSignal(deadline, 110_000);
  if (!timed) return null;
  const signal = timed.signal;
  try {
    const imageUrl = new URL(`https://gen.pollinations.ai/image/${encodeURIComponent(prompt)}`);
    imageUrl.searchParams.set("model", "tongyi-mai/z-image-turbo");
    imageUrl.searchParams.set("width", "1024");
    imageUrl.searchParams.set("height", "1024");
    imageUrl.searchParams.set("safe", "true");
    const r = await fetch(imageUrl, { method: "GET", headers: { Authorization: `Bearer ${apiKey}`, Accept: "image/*" }, signal });
    if (!r.ok) { console.error("Pollinations status", r.status); return null; }
    const contentType = r.headers.get("content-type")?.split(";")[0].trim().toLowerCase() || "";
    if (!/^image\/(?:png|jpeg|webp|avif)$/.test(contentType)) return null;
    const bytes = await readImageBytes(r);
    return bytes ? { bytes, contentType } : null;
  } catch { return null; } finally { timed.stop(); }
}

async function fetchGeminiImage(prompt: string, apiKey: string, deadline: number): Promise<{ bytes: Uint8Array; contentType: string } | null> {
  const timed = remainingSignal(deadline, 60_000);
  if (!timed) return null;
  const signal = timed.signal;
  try {
    const model = Deno.env.get("GEMINI_IMAGE_MODEL") || "gemini-2.5-flash-image";
    const r = await fetch(`https://generativelanguage.googleapis.com/v1beta/models/${encodeURIComponent(model)}:generateContent`, {
      method: "POST", headers: { "Content-Type": "application/json", "x-goog-api-key": apiKey }, signal,
      body: JSON.stringify({ contents: [{ role: "user", parts: [{ text: `Generate an image: ${prompt}` }] }], generationConfig: { temperature: 0.6 } }),
    });
    if (!r.ok) { console.error("Gemini image status", r.status); return null; }
    const d = await r.json();
    const parts = d?.candidates?.[0]?.content?.parts;
    if (!Array.isArray(parts)) return null;
    for (const part of parts) {
      const inline = part?.inlineData ?? part?.inline_data;
      const mime = typeof inline?.mimeType === "string" ? inline.mimeType : (typeof inline?.mime_type === "string" ? inline.mime_type : "");
      const data = typeof inline?.data === "string" ? inline.data : "";
      if (!data || !/^image\/(?:png|jpeg|webp|avif)$/.test(mime)) continue;
      const bin = atob(data);
      const bytes = new Uint8Array(bin.length);
      for (let i = 0; i < bin.length; i++) bytes[i] = bin.charCodeAt(i);
      if (bytes.byteLength > 0 && bytes.byteLength <= MAX_IMAGE_BYTES) return { bytes, contentType: mime };
    }
    return null;
  } catch { return null; } finally { timed.stop(); }
}

serve(async (req: Request) => {
  if (req.method === "OPTIONS") return new Response("ok", { headers: CORS_HEADERS });
  if (req.method !== "POST") return imageError("Image creation could not be completed.", 405);
  if (Number(req.headers.get("content-length") || 0) > 4096) return imageError("Image creation could not be completed.", 413);

  const userId = await authenticatedUserId(req);
  if (!userId) return imageError("Please sign in again before creating an image.", 401);

  const supabaseUrl = Deno.env.get("SUPABASE_URL")?.replace(/\/$/, "");
  const serviceKey = supabaseSecretKey();
  if (!supabaseUrl || !serviceKey) return imageError("Image creation is temporarily unavailable.", 503);

  let body: unknown;
  try {
    body = await readSmallJson(req);
  } catch {
    return imageError("Choose an image style and try again.", 400);
  }
  if (!body || typeof body !== "object" || Array.isArray(body)) return imageError("Choose an image style and try again.", 400);
  const requestBody = body as Record<string, unknown>;
  const style = requestBody.style;
  if (typeof style !== "string" || !Object.hasOwn(IMAGE_PRESETS, style)) {
    return imageError("Choose one of the available image styles.", 400);
  }
  const contract = validateImageRequest(requestBody);
  if (!contract.ok) return imageError(contract.error === "request_consent_required" ? "Explicit image-generation consent is required." : "Custom image prompts must be 500 characters or fewer.", 400);
  const customPrompt = contract.prompt ?? "";
  // Do not send health, identity, or clinical content to an image provider.
  if (/(?:\b(?:periods?|menstr\w*|cycles?|ovulat\w*|pregnan\w*|symptoms?|pain\w*|bleed\w*|medic\w*|diagnos\w*|health\w*|patient|cramps?|urinary|vaginal|name|email|phone|address)\b|[\w.+-]+@[\w.-]+\.[a-z]{2,}|https?:\/\/|(?:\+?\d[\d ()-]{7,}\d))/i.test(customPrompt)) {
    return imageError("Custom prompts must not contain health or personal information.", 400);
  }

  try {
    if (await isAccountRestricted(supabaseUrl, serviceKey, userId)) {
      return imageError("This account cannot create images right now.", 403);
    }

    const adminClient = createClient(supabaseUrl, serviceKey, {
      auth: { autoRefreshToken: false, persistSession: false },
    });
    const [adminResult, economyResult, consentResult] = await Promise.all([
      adminClient.from("cyclecare_admin_roles").select("user_id").eq("user_id", userId).maybeSingle(),
      adminClient.from("economy_profiles").select("tier").eq("user_id", userId).maybeSingle(),
      adminClient.from("cyclecare_privacy_consents").select("image_generation_enabled").eq("user_id", userId).maybeSingle(),
    ]);
    if (adminResult.error || economyResult.error || consentResult.error) {
      console.error("Image entitlement lookup failed", adminResult.error?.code || economyResult.error?.code || "unknown");
      return imageError("Image creation is temporarily unavailable.", 503);
    }
    const isAdmin = Boolean(adminResult.data?.user_id);
    const isPremium = String(economyResult.data?.tier || "").toUpperCase() === "PREMIUM";
    if (!isAdmin && !isPremium) return imageError("Image creation is available with CycleCare Premium.", 403);
    if (consentResult.data?.image_generation_enabled !== true) return imageError("Image-generation consent is not enabled.", 403);
    // No usable image provider at all fails closed before any quota is consumed.
    const pollinationsConfigured = usableSecret(Deno.env.get("POLLINATIONS_API_KEY"));
    const geminiConfigured = usableSecret(Deno.env.get("GEMINI_API_KEY"));
    if (!pollinationsConfigured && !geminiConfigured) return imageError("Image creation is temporarily unavailable.", 503);

    const basePrompt = `${IMAGE_PRESETS[style]}${customPrompt.trim() ? ` Additional user-directed artistic detail: ${customPrompt.trim()}` : ""}`;
    const deadline = Date.now() + 140_000;
    // One quota unit per image request, never per failover attempt.
    const quota = await adminClient.rpc("consume_cyclecare_ai_quota", { p_user_id: userId, p_kind: "image" });
    if (quota.error || quota.data?.allowed !== true) return imageError(quota.data?.reason === "daily_limit" ? "Daily image limit reached." : "Image creation is temporarily unavailable.", quota.data?.reason === "daily_limit" ? 429 : 503);



    // Step 1 - prompt polish: a text provider refines the concept into an
    // optimized image prompt. Invisible on failure: the raw prompt is used.
    const polished = await polishPrompt(basePrompt, deadline);

    // Step 2 - image routing chain: Pollinations -> Gemini image -> graceful advice.
    if (pollinationsConfigured) {
      const image = await fetchPollinationsImage(polished, Deno.env.get("POLLINATIONS_API_KEY")!.trim(), deadline);
      if (image) return imageResponse(image.bytes, image.contentType);
      console.error("Pollinations image attempt failed; failing over");
    }
    if (geminiConfigured) {
      const image = await fetchGeminiImage(polished, Deno.env.get("GEMINI_API_KEY")!.trim(), deadline);
      if (image) return imageResponse(image.bytes, image.contentType);
      console.error("Gemini image attempt failed; failing over");
    }
    // Final fallback: graceful system advice, never a raw error screen.
    return imageError("The studio is resting for a moment. Your idea is safe - please try again in a little while.", 503);
  } catch (error) {
    const timedOut = error instanceof DOMException && error.name === "AbortError";
    console.error("Image generation request failed", timedOut ? "timeout" : "unavailable");
    return imageError("Image creation is temporarily unavailable. Please try again later.", 503);
  }
});
