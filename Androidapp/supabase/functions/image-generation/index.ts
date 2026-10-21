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
    const apiKey = Deno.env.get("POLLINATIONS_API_KEY");
    if (!usableSecret(apiKey)) return imageError("Image creation is temporarily unavailable.", 503);
    const quota = await adminClient.rpc("consume_cyclecare_ai_quota", { p_user_id: userId, p_kind: "image" });
    if (quota.error || quota.data?.allowed !== true) return imageError(quota.data?.reason === "daily_limit" ? "Daily image limit reached." : "Image creation is temporarily unavailable.", quota.data?.reason === "daily_limit" ? 429 : 503);

    const controller = new AbortController();
    const timeout = setTimeout(() => controller.abort(), 150_000);
    try {
      const providerPrompt = `${IMAGE_PRESETS[style]}${customPrompt.trim() ? ` Additional user-directed artistic detail: ${customPrompt.trim()}` : ""}`;
      const imageUrl = new URL(`https://gen.pollinations.ai/image/${encodeURIComponent(providerPrompt)}`);
      imageUrl.searchParams.set("model", "tongyi-mai/z-image-turbo");
      imageUrl.searchParams.set("width", "1024");
      imageUrl.searchParams.set("height", "1024");
      imageUrl.searchParams.set("safe", "true");

      const providerResponse = await fetch(imageUrl, {
        method: "GET",
        headers: { Authorization: `Bearer ${apiKey}`, Accept: "image/*" },
        signal: controller.signal,
      });
      if (!providerResponse.ok) {
        console.error("Pollinations image request failed", providerResponse.status);
        return imageError("Image creation could not be completed. Please try again later.", 502);
      }
      const contentType = providerResponse.headers.get("content-type")?.split(";")[0].trim().toLowerCase() || "";
      if (!/^image\/(?:png|jpeg|webp|avif)$/.test(contentType)) {
        console.error("Pollinations returned an unexpected content type");
        return imageError("Image creation could not be completed. Please try again later.", 502);
      }
      const reader = providerResponse.body?.getReader();
      if (!reader) return imageError("Image creation could not be completed. Please try again later.", 502);
      const chunks: Uint8Array[] = [];
      let total = 0;
      while (true) {
        const { done, value } = await reader.read();
        if (done) break;
        total += value.byteLength;
        if (total > MAX_IMAGE_BYTES) {
          await reader.cancel().catch(() => {});
          return imageError("The generated image was too large to display.", 502);
        }
        chunks.push(value);
      }
      const image = new Uint8Array(total);
      let offset = 0;
      for (const chunk of chunks) {
        image.set(chunk, offset);
        offset += chunk.byteLength;
      }
      if (image.byteLength === 0) return imageError("Image creation could not be completed. Please try again later.", 502);
      return new Response(image, {
        status: 200,
        headers: {
          ...CORS_HEADERS,
          "Content-Type": contentType,
          "Content-Length": String(image.byteLength),
          "Cache-Control": "private, no-store, max-age=0",
          "X-Content-Type-Options": "nosniff",
        },
      });
    } finally {
      clearTimeout(timeout);
    }
  } catch (error) {
    const timedOut = error instanceof DOMException && error.name === "AbortError";
    console.error("Image generation request failed", timedOut ? "timeout" : "unavailable");
    return imageError("Image creation is temporarily unavailable. Please try again later.", 503);
  }
});
