import { serve } from "https://deno.land/std@0.168.0/http/server.ts";
import { createClient } from "https://esm.sh/@supabase/supabase-js@2";
import { corsHeaders } from "../_shared/cors.ts";
import { authenticatedUserId, COIN_PACKAGES, isAccountRestricted, isConfiguredSecret, jsonResponse, supabaseSecretKey } from "../_shared/payments.ts";
import { boundedJson, boundedResponseJson, fetchDeadline, redactedProviderError, verifiedProviderGate, exactMoney } from "../_shared/payment-request.ts";

interface PaypalOrderRequest { package_id?: string; is_acdc_card?: boolean; }
const noStore = { ...corsHeaders, "Content-Type": "application/json", "Cache-Control": "no-store" };

serve(async (req: Request) => {
  if (req.method === "OPTIONS") return new Response("ok", { headers: corsHeaders });
  if (req.method !== "POST") return jsonResponse({ error: "Method not allowed" }, 405);
  try {
    const userId = await authenticatedUserId(req);
    if (!userId) return jsonResponse({ error: "Sign in is required to purchase Cycle Coins." }, 401);
    const body = await boundedJson<PaypalOrderRequest>(req);
    if (body.is_acdc_card) return jsonResponse({ error: "Direct card entry is disabled. Use PayPal's hosted checkout instead." }, 501);
    const pkg = typeof body.package_id === "string" && Object.hasOwn(COIN_PACKAGES, body.package_id) ? COIN_PACKAGES[body.package_id] : undefined;
    if (!pkg) return jsonResponse({ error: "Unknown coin package." }, 400);
    // Deliberately off by default. A missing/legacy schema cannot reach a provider.
    if (!verifiedProviderGate("paypal")) return jsonResponse({ error: "PayPal checkout is disabled pending independent settlement verification." }, 503);
    const clientId = Deno.env.get("PAYPAL_CLIENT_ID"); const clientSecret = Deno.env.get("PAYPAL_CLIENT_SECRET");
    const mode = (Deno.env.get("PAYPAL_MODE") ?? "").toLowerCase(); const supabaseUrl = Deno.env.get("SUPABASE_URL"); const serviceKey = supabaseSecretKey();
    if (!isConfiguredSecret(clientId) || !isConfiguredSecret(clientSecret) || !supabaseUrl || !serviceKey || !["sandbox", "live"].includes(mode)) return jsonResponse({ error: "PayPal checkout is not configured on the server." }, 503);
    if (mode === "live" && Deno.env.get("PAYPAL_LIVE_VERIFIED") !== "true") return jsonResponse({ error: "Live PayPal mode is not independently verified." }, 503);
    if (await isAccountRestricted(supabaseUrl, serviceKey, userId)) return jsonResponse({ error: "This account is suspended or banned from purchases." }, 403);
    const supabase = createClient(supabaseUrl, serviceKey, { auth: { persistSession: false, autoRefreshToken: false } });
    const externalRef = `CC_${crypto.randomUUID()}`;
    // Forward schema is mandatory: old databases fail closed rather than taking the unsafe path.
    const intent = await supabase.from("payment_intents").insert({ user_id: userId, provider: "paypal", external_reference: externalRef, amount: pkg.priceUsd, currency: "USD", status: "pending", metadata: { package_id: pkg.id, coins_to_credit: pkg.coins } }).select("id").single();
    if (intent.error || !intent.data) return jsonResponse({ error: "Payment protocol is not installed; no provider call was made." }, 503);
    const tx = await supabase.from("transactions").insert({ user_id: userId, external_reference: externalRef, provider: "paypal", amount: pkg.priceUsd, currency: "USD", status: "pending", metadata: { package_id: pkg.id, coins_to_credit: pkg.coins, payment_intent_id: intent.data.id } }).select("id").single();
    if (tx.error || !tx.data) return jsonResponse({ error: "Could not persist the payment intent; no provider call was made." }, 503);
    const base = mode === "live" ? "https://api-m.paypal.com" : "https://api-m.sandbox.paypal.com";
    const auth = await fetchDeadline(`${base}/v1/oauth2/token`, { method: "POST", headers: { Authorization: `Basic ${btoa(`${clientId}:${clientSecret}`)}`, "Content-Type": "application/x-www-form-urlencoded" }, body: "grant_type=client_credentials" });
    if (!auth.ok) return jsonResponse({ error: redactedProviderError("PayPal") }, 502);
    const token = await boundedResponseJson<Record<string, any>>(auth).catch(() => ({} as Record<string, any>)); if (typeof token.access_token !== "string") return jsonResponse({ error: redactedProviderError("PayPal") }, 502);
    const orderResponse = await fetchDeadline(`${base}/v2/checkout/orders`, { method: "POST", headers: { Authorization: `Bearer ${token.access_token}`, "Content-Type": "application/json", "PayPal-Request-Id": externalRef }, body: JSON.stringify({ intent: "CAPTURE", purchase_units: [{ reference_id: externalRef, custom_id: userId, amount: { currency_code: "USD", value: pkg.priceUsd.toFixed(2) }, description: `CycleCare ${pkg.title} (${pkg.coins} Cycle Coins)` }], application_context: { brand_name: "CycleCare", user_action: "PAY_NOW", return_url: Deno.env.get("PAYPAL_RETURN_URL"), cancel_url: Deno.env.get("PAYPAL_CANCEL_URL") } }) });
    const order = await boundedResponseJson<Record<string, any>>(orderResponse).catch(() => ({} as Record<string, any>));
    if (!orderResponse.ok || typeof order.id !== "string") return jsonResponse({ error: redactedProviderError("PayPal") }, 502);
    const approvalUrl = order.links?.find((link: { rel?: string }) => link.rel === "approve" || link.rel === "payer-action")?.href;
    if (typeof approvalUrl !== "string") return jsonResponse({ error: redactedProviderError("PayPal") }, 502);
    const approval = new URL(approvalUrl);
    if (approval.protocol !== "https:" || !["www.paypal.com", "www.sandbox.paypal.com"].includes(approval.hostname)) return jsonResponse({ error: redactedProviderError("PayPal") }, 502);
    const saved = await supabase.from("payment_intents").update({ provider_order_id: order.id, metadata: { package_id: pkg.id, coins_to_credit: pkg.coins, paypal_order_id: order.id } }).eq("id", intent.data.id);
    if (saved.error) return jsonResponse({ error: "PayPal order was created but could not be recorded; payment remains pending reconciliation." }, 409);
    const savedTx = await supabase.from("transactions").update({ metadata: { package_id: pkg.id, coins_to_credit: pkg.coins, payment_intent_id: intent.data.id, paypal_order_id: order.id } }).eq("id", tx.data.id);
    if (savedTx.error) return jsonResponse({ error: "PayPal order remains pending reconciliation; do not create a replacement charge." }, 409);
    return new Response(JSON.stringify({ success: true, order_id: order.id, external_reference: externalRef, status: "pending", provider: "paypal", amount_usd: pkg.priceUsd, coins_to_credit: pkg.coins, approval_url: approvalUrl }), { status: 200, headers: noStore });
  } catch (error) {
    if (error instanceof Error && error.message === "request_too_large") return jsonResponse({ error: "Request is too large." }, 413);
    if (error instanceof Error && error.message === "invalid_request") return jsonResponse({ error: "Invalid checkout request." }, 400);
    if (error instanceof Error && error.message === "io_deadline") return jsonResponse({ error: "Payment verification timed out; do not retry an uncertain charge." }, 503);
    console.error("PayPal create failed", error instanceof Error ? error.name : "unknown"); return jsonResponse({ error: "Could not create the PayPal checkout." }, 500);
  }
});
