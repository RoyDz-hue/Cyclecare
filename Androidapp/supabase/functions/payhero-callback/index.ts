import { serve } from "https://deno.land/std@0.224.0/http/server.ts";
import { createClient } from "https://esm.sh/@supabase/supabase-js@2.118.0";
import { corsHeaders } from "../_shared/cors.ts";
import { constantTimeHexEquals, jsonResponse, sha256Hex, supabaseSecretKey } from "../_shared/payments.ts";

serve(async (req: Request) => {
  if (req.method === "OPTIONS") return new Response("ok", { headers: corsHeaders });
  if (req.method !== "POST") return jsonResponse({ error: "Method not allowed" }, 405);

  try {
    const callbackToken = new URL(req.url).searchParams.get("token") ?? "";
    if (!callbackToken || callbackToken.length < 32) return jsonResponse({ error: "Unauthorized callback." }, 401);

    const payload = await req.json();
    const externalRef = String(payload.external_reference ?? payload.ExternalReference ?? payload.reference ?? payload.MerchantReference ?? "");
    if (!externalRef) return jsonResponse({ error: "Missing transaction reference." }, 400);

    const supabaseUrl = Deno.env.get("SUPABASE_URL");
    const serviceKey = supabaseSecretKey();
    if (!supabaseUrl || !serviceKey) return jsonResponse({ error: "Payment callback is not configured." }, 503);
    const supabase = createClient(supabaseUrl, serviceKey, { auth: { persistSession: false, autoRefreshToken: false } });

    const { data: tx, error: lookupError } = await supabase
      .from("transactions")
      .select("id,user_id,provider,amount,currency,status,provider_reference,metadata")
      .eq("external_reference", externalRef)
      .maybeSingle();
    if (lookupError || !tx) return jsonResponse({ error: "Transaction not found." }, 404);
    if (tx.provider !== "payhero" || tx.currency !== "KES") return jsonResponse({ error: "Transaction provider mismatch." }, 400);

    const expectedHash = tx.metadata?.callback_token_hash;
    const receivedHash = await sha256Hex(callbackToken);
    if (typeof expectedHash !== "string" || !constantTimeHexEquals(receivedHash, expectedHash)) {
      return jsonResponse({ error: "Unauthorized callback." }, 401);
    }

    if (tx.status === "success") {
      return new Response(JSON.stringify({ success: true, status: "success", duplicate: true }), {
        status: 200,
        headers: { ...corsHeaders, "Content-Type": "application/json", "Cache-Control": "no-store" },
      });
    }
    if (tx.status !== "pending") return jsonResponse({ error: "Transaction is no longer pending." }, 409);

    // A URL capability token authenticates the callback route, not financial settlement.
    // No callback representation (including string ResultCode, success=true, or
    // conflicting status fields) can credit or finalize a transaction here.
    // New initiation is disabled too, until independently verified settlement exists.
    return jsonResponse({ error: "Payment pending independent verification; no account credit was issued." }, 503);
  } catch (error) {
    console.error("PayHero callback processing failed");
    return jsonResponse({ error: "Invalid or unprocessable callback." }, 400);
  }
});
