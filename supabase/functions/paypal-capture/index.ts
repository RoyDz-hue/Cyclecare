import { serve } from "https://deno.land/std@0.168.0/http/server.ts";
import { createClient } from "https://esm.sh/@supabase/supabase-js@2";
import { corsHeaders } from "../_shared/cors.ts";
import { authenticatedUserId, isAccountRestricted, isConfiguredSecret, jsonResponse, supabaseSecretKey } from "../_shared/payments.ts";

interface CaptureRequest {
  external_reference?: string;
  order_id?: string;
}

function firstPurchaseUnit(order: any): any | null {
  return Array.isArray(order?.purchase_units) ? order.purchase_units[0] ?? null : null;
}

function completedCapture(order: any): any | null {
  const unit = firstPurchaseUnit(order);
  const captures = unit?.payments?.captures;
  return Array.isArray(captures) ? captures.find((item: any) => item?.status === "COMPLETED") ?? null : null;
}

serve(async (req: Request) => {
  if (req.method === "OPTIONS") return new Response("ok", { headers: corsHeaders });
  if (req.method !== "POST") return jsonResponse({ error: "Method not allowed" }, 405);

  try {
    const userId = await authenticatedUserId(req);
    if (!userId) return jsonResponse({ error: "Sign in is required to verify this payment." }, 401);

    const body: CaptureRequest = await req.json();
    const externalRef = body.external_reference?.trim();
    if (!externalRef) return jsonResponse({ error: "Missing transaction reference." }, 400);

    const supabaseUrl = Deno.env.get("SUPABASE_URL");
    const serviceKey = supabaseSecretKey();
    const clientId = Deno.env.get("PAYPAL_CLIENT_ID");
    const clientSecret = Deno.env.get("PAYPAL_CLIENT_SECRET");
    const mode = (Deno.env.get("PAYPAL_MODE") ?? "sandbox").toLowerCase();
    if (!supabaseUrl || !serviceKey || !isConfiguredSecret(clientId) || !isConfiguredSecret(clientSecret) || !["sandbox", "live"].includes(mode)) {
      return jsonResponse({ error: "PayPal verification is not configured on the server." }, 503);
    }
    if (await isAccountRestricted(supabaseUrl, serviceKey, userId)) {
      return jsonResponse({ error: "This account is suspended or banned from purchases." }, 403);
    }

    const supabase = createClient(supabaseUrl, serviceKey, { auth: { persistSession: false, autoRefreshToken: false } });
    const { data: tx, error: lookupError } = await supabase
      .from("transactions")
      .select("id,user_id,external_reference,provider,amount,currency,status,provider_reference,metadata")
      .eq("external_reference", externalRef)
      .eq("user_id", userId)
      .maybeSingle();
    if (lookupError || !tx) return jsonResponse({ error: "Transaction not found for this account." }, 404);
    if (tx.provider !== "paypal" || tx.currency !== "USD") return jsonResponse({ error: "Transaction provider mismatch." }, 400);
    if (tx.status === "success") return new Response(JSON.stringify({ success: true, status: "success", duplicate: true }), {
      status: 200,
      headers: { ...corsHeaders, "Content-Type": "application/json", "Cache-Control": "no-store" },
    });
    if (tx.status !== "pending") return jsonResponse({ error: "Transaction is no longer pending." }, 409);

    const orderId = tx.metadata?.paypal_order_id;
    if (typeof orderId !== "string" || !orderId || (body.order_id && body.order_id !== orderId)) {
      return jsonResponse({ error: "PayPal order does not match the stored transaction." }, 400);
    }

    const paypalBase = mode === "live" ? "https://api-m.paypal.com" : "https://api-m.sandbox.paypal.com";
    const authResponse = await fetch(`${paypalBase}/v1/oauth2/token`, {
      method: "POST",
      headers: {
        Authorization: `Basic ${btoa(`${clientId}:${clientSecret}`)}`,
        "Content-Type": "application/x-www-form-urlencoded",
      },
      body: "grant_type=client_credentials",
    });
    if (!authResponse.ok) return jsonResponse({ error: "PayPal authentication failed." }, 502);
    const tokenData = await authResponse.json();
    if (typeof tokenData.access_token !== "string") return jsonResponse({ error: "PayPal returned no access token." }, 502);

    const paypalHeaders = { Authorization: `Bearer ${tokenData.access_token}`, "Content-Type": "application/json" };
    const getOrder = async () => {
      const response = await fetch(`${paypalBase}/v2/checkout/orders/${encodeURIComponent(orderId)}`, { headers: paypalHeaders });
      return { response, order: await response.json().catch(() => ({})) };
    };

    let { response: orderResponse, order } = await getOrder();
    if (!orderResponse.ok || order.id !== orderId) return jsonResponse({ error: "PayPal order could not be verified." }, 502);

    const unit = firstPurchaseUnit(order);
    const storedAmount = Number(tx.amount).toFixed(2);
    const orderAmount = unit?.amount;
    if (unit?.reference_id !== externalRef || unit?.custom_id !== userId ||
        orderAmount?.currency_code !== "USD" || orderAmount?.value !== storedAmount) {
      console.warn("PayPal order verification rejected for transaction", tx.id);
      return jsonResponse({ error: "PayPal order details do not match this transaction." }, 400);
    }

    if (order.status !== "COMPLETED") {
      if (order.status !== "APPROVED") return jsonResponse({ error: "PayPal checkout has not been approved." }, 409);
      const captureResponse = await fetch(`${paypalBase}/v2/checkout/orders/${encodeURIComponent(orderId)}/capture`, {
        method: "POST",
        headers: { ...paypalHeaders, "PayPal-Request-Id": `capture-${tx.id}` },
      });
      if (captureResponse.ok) {
        order = await captureResponse.json().catch(() => ({}));
      } else {
        // Re-read after an ambiguous capture response; never infer success from HTTP text.
        const reread = await getOrder();
        if (!reread.response.ok) return jsonResponse({ error: "PayPal capture status could not be confirmed." }, 502);
        order = reread.order;
      }
    }

    const capture = completedCapture(order);
    const captureAmount = capture?.amount;
    if (order.status !== "COMPLETED" || !capture || captureAmount?.currency_code !== "USD" || captureAmount?.value !== storedAmount) {
      return jsonResponse({ error: "PayPal has not confirmed a completed payment for the exact amount." }, 409);
    }

    const providerReference = String(capture.id ?? orderId);
    const { data: result, error: creditError } = await supabase.rpc("apply_verified_payment", {
      p_transaction_id: tx.id,
      p_expected_provider: "paypal",
      p_expected_amount: Number(tx.amount),
      p_expected_currency: "USD",
      p_provider_reference: providerReference,
      p_provider_payload: { paypal_order_id: orderId, capture_id: providerReference, status: "COMPLETED" },
    });
    if (creditError || result?.success !== true) {
      console.error("Atomic PayPal credit failed:", creditError?.message ?? result?.message ?? "unknown error");
      return jsonResponse({ error: "Verified payment could not yet be posted to the account." }, 500);
    }

    return new Response(JSON.stringify({ success: true, order_id: orderId, status: "success" }), {
      status: 200,
      headers: { ...corsHeaders, "Content-Type": "application/json", "Cache-Control": "no-store" },
    });
  } catch (error) {
    console.error("PayPal capture failed:", error instanceof Error ? error.message : "unknown error");
    return jsonResponse({ error: "Could not verify or capture the PayPal transaction." }, 500);
  }
});
