import { serve } from "https://deno.land/std@0.224.0/http/server.ts";
import { createClient } from "https://esm.sh/@supabase/supabase-js@2.118.0";
import { authenticatedUserId, isConfiguredSecret, jsonResponse, supabaseSecretKey } from "../_shared/payments.ts";
import { aiProviderStatus, normalizeAiProvider } from "../_shared/request-contract.js";
import { hybridReadiness } from "../_shared/hybrid-routing.js";

serve(async (req: Request) => {
  if (req.method === "OPTIONS") return jsonResponse({ ok: true });
  if (req.method !== "POST") return jsonResponse({ error: "Method not allowed" }, 405);

  const userId = await authenticatedUserId(req);
  if (!userId) return jsonResponse({ error: "Authentication required" }, 401);

  const supabaseUrl = Deno.env.get("SUPABASE_URL")?.replace(/\/$/, "");
  const secretKey = supabaseSecretKey();
  if (!supabaseUrl || !secretKey) return jsonResponse({ error: "Backend is not configured" }, 503);

  try {
    const adminClient = createClient(supabaseUrl, secretKey, {
      auth: { autoRefreshToken: false, persistSession: false },
    });
    const { data: role, error: roleError } = await adminClient
      .from("cyclecare_admin_roles")
      .select("user_id")
      .eq("user_id", userId)
      .maybeSingle();
    if (roleError) return jsonResponse({ error: "Administrator access could not be verified" }, 503);
    if (!role) return jsonResponse({ error: "Administrator access required" }, 403);

    const { data: runtime, error: configError } = await adminClient
      .from("cyclecare_runtime_config")
      .select("active_ai_provider, active_mpesa_provider, updated_at, hybrid_enabled, hybrid_contract_revision, hybrid_consent_revision")
      .eq("id", true)
      .maybeSingle();
    if (configError || !runtime) return jsonResponse({ error: "Runtime configuration is not initialized" }, 503);
    const rawActiveAi = String(runtime.active_ai_provider || "").trim().toUpperCase();
    const activeAi = rawActiveAi === "HYBRID" ? "HYBRID" : normalizeAiProvider(rawActiveAi);
    if (!activeAi) return jsonResponse({ error: "Runtime AI provider is unsupported; no provider was selected" }, 503);

    const payheroReady = isConfiguredSecret(Deno.env.get("PAYHERO_API_USERNAME"))
      && isConfiguredSecret(Deno.env.get("PAYHERO_API_PASSWORD"))
      && Number(Deno.env.get("PAYHERO_CHANNEL_ID")) > 0;
    const paypalReady = isConfiguredSecret(Deno.env.get("PAYPAL_CLIENT_ID"))
      && isConfiguredSecret(Deno.env.get("PAYPAL_CLIENT_SECRET"));
    const providerStatus = (provider: string, configured: boolean) => ({
      ...aiProviderStatus(provider, configured),
      key_present: configured,
    });
    const ai = {
      COHERE: providerStatus("COHERE", isConfiguredSecret(Deno.env.get("COHERE_API_KEY"))),
      GEMINI: providerStatus("GEMINI", isConfiguredSecret(Deno.env.get("GEMINI_API_KEY"))),
      OPENROUTER: providerStatus("OPENROUTER", isConfiguredSecret(Deno.env.get("OPENROUTER_API_KEY"))),
      LAJIWA: providerStatus("LAJIWA", isConfiguredSecret(Deno.env.get("LAJIWA_API_KEY"))),
      HYBRID: {
        ...hybridReadiness({
          enabled: (runtime as Record<string, unknown>).hybrid_enabled === true,
          sourceReleased: true,
          runtimeReady: true,
          contractRevision: (runtime as Record<string, unknown>).hybrid_contract_revision,
          consentProtocolRevision: (runtime as Record<string, unknown>).hybrid_consent_revision,
        }),
        source_gate: "released",
        selection_authority: "server-only",
        manifest_revision: "cyclecare-ai-manifest-v3",
      },
    };

    return jsonResponse({
      active: {
        ai: activeAi,
        mpesa: runtime.active_mpesa_provider,
        updated_at: runtime.updated_at,
      },
      ai,
      image_generation: {
        POLLINATIONS: { configured: isConfiguredSecret(Deno.env.get("POLLINATIONS_API_KEY")), validated: false, key_present: isConfiguredSecret(Deno.env.get("POLLINATIONS_API_KEY")) },
      },
      mpesa: {
        PAYHERO: { configured: payheroReady, available: true },
        PAYNEXUS: {
          configured: false,
          available: false,
          reason: "Not enabled: callback signature and payment-verification contract still require validation.",
        },
      },
      paypal: { configured: paypalReady },
    });
  } catch {
    return jsonResponse({ error: "Could not load provider status" }, 503);
  }
});
