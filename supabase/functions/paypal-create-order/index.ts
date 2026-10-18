import { serve } from "https://deno.land/std@0.168.0/http/server.ts";
import { createClient } from "https://esm.sh/@supabase/supabase-js@2";
import { corsHeaders } from "../_shared/cors.ts";
import { authenticatedUserId, COIN_PACKAGES, isAccountRestricted, isConfiguredSecret, jsonResponse, supabaseSecretKey } from "../_shared/payments.ts";

interface PaypalOrderRequest {
  package_id?: string;
  is_acdc_card?: boolean;
}

serve(async (req: Request) => {
  if (req.method === "OPTIONS") return new Response("ok", { headers: corsHeaders });
  if (req.method !== "POST") return jsonResponse({ error: "Method not allowed" }, 405);

  try {
    const userId = await authenticatedUserId(req);
    if (!userId) return jsonResponse({ error: "Sign in is required to purchase Cycle Coins." }, 401);

    const body: PaypalOrderRequest = await req.json();
    if (body.is_acdc_card) {
      return jsonResponse({ error: "Direct card entry is disabled. Use PayPal's hosted checkout instead." }, 501);
    }
    const pkg = body.package_id ? COIN_PACKAGES[body.package_id] : undefined;
    if (!pkg) return jsonResponse({ error: "Unknown coin package." }, 400);

    const clientId = Deno.env.get("PAYPAL_CLIENT_ID");
    const clientSecret = Deno.env.get("PAYPAL_CLIENT_SECRET");
    const mode = (Deno.env.get("PAYPAL_MODE") ?? "sandbox").toLowerCase();
    const supabaseUrl = Deno.env.get("SUPABASE_URL");
    const serviceKey = supabaseSecretKey();
    if (!isConfiguredSecret(clientId) || !isConfiguredSecret(clientSecret) || !supabaseUrl || !serviceKey) {
      return jsonResponse({ error: "PayPal checkout is not configured on the server." }, 503);
    }
    if (await isAccountRestricted(supabaseUrl, serviceKey, userId)) {
      return jsonResponse({ error: "This account is suspended or banned from purchases." }, 403);
    }
    if (mode !== "sandbox" && mode !== "live") return jsonResponse({ error: "Invalid PayPal mode configuration." }, 503);
    const paypalBase = mode === "live" ? "https://api-m.paypal.com" : "https://api-m.sandbox.paypal.com";

    const tokenResponse = await fetch(`${paypalBase}/v1/oauth2/token`, {
      method: "POST",
      headers: {
        Authorization: `Basic ${btoa(`${clientId}:${clientSecret}`)}`,
        "Content-Type": "application/x-www-form-urlencoded",
      },
      body: "grant_type=client_credentials",
    });
    if (!tokenResponse.ok) {
      console.error("PayPal OAuth failed with HTTP status:", tokenResponse.status);
      return jsonResponse({ error: "PayPal could not authenticate this checkout." }, 502);
    }
    const tokenData = await tokenResponse.json();
    if (typeof tokenData.access_token !== "string") return jsonResponse({ error: "PayPal returned no access token." }, 502);

    const externalRef = `CC_${crypto.randomUUID()}`;
    const orderResponse = await fetch(`${paypalBase}/v2/checkout/orders`, {
      method: "POST",
      headers: {
        Authorization: `Bearer ${tokenData.access_token}`,
        "Content-Type": "application/json",
        "PayPal-Request-Id": externalRef,
      },
      body: JSON.stringify({
        intent: "CAPTURE",
        purchase_units: [{
          reference_id: externalRef,
          custom_id: userId,
          amount: { currency_code: "USD", value: pkg.priceUsd.toFixed(2) },
          description: `CycleCare ${pkg.title} (${pkg.coins} Cycle Coins)`,
        }],
        application_context: {
          brand_name: "CycleCare",
          landing_page: "NO_PREFERENCE",
          user_action: "PAY_NOW",
          return_url: Deno.env.get("PAYPAL_RETURN_URL") ?? "https://cyclecare.app/payment/success",
          cancel_url: Deno.env.get("PAYPAL_CANCEL_URL") ?? "https://cyclecare.app/payment/cancel",
        },
      }),
    });
    const order = await orderResponse.json().catch(() => ({}));
    if (!orderResponse.ok || typeof order.id !== "string") {
      console.error("PayPal order creation failed with HTTP status:", orderResponse.status);
      return jsonResponse({ error: "PayPal did not create the checkout order." }, 502);
    }
    const approvalUrl = order.links?.find((link: { rel?: string }) => link.rel === "approve" || link.rel === "payer-action")?.href;
    if (typeof approvalUrl !== "string") return jsonResponse({ error: "PayPal returned no hosted approval link." }, 502);

    const supabase = createClient(supabaseUrl, serviceKey, { auth: { persistSession: false, autoRefreshToken: false } });
    const { error: insertError } = await supabase.from("transactions").insert({
      user_id: userId,
      external_reference: externalRef,
      provider: "paypal",
      amount: pkg.priceUsd,
      currency: "USD",
      status: "pending",
      metadata: { package_id: pkg.id, coins_to_credit: pkg.coins, paypal_order_id: order.id },
    });
    if (insertError) {
      console.error("PayPal transaction insert failed:", insertError.message);
      return jsonResponse({ error: "Could not persist the payment record." }, 500);
    }

    return new Response(JSON.stringify({
      success: true,
      order_id: order.id,
      external_reference: externalRef,
      status: "pending",
      provider: "paypal",
      amount_usd: pkg.priceUsd,
      coins_to_credit: pkg.coins,
      approval_url: approvalUrl,
    }), { status: 200, headers: { ...corsHeaders, "Content-Type": "application/json", "Cache-Control": "no-store" } });
  } catch (error) {
    console.error("PayPal order creation failed:", error instanceof Error ? error.message : "unknown error");
    return jsonResponse({ error: "Could not create the PayPal checkout." }, 500);
  }
});
