import { serve } from "https://deno.land/std@0.168.0/http/server.ts";
import { createClient } from "https://esm.sh/@supabase/supabase-js@2";
import { corsHeaders } from "../_shared/cors.ts";
import { authenticatedUserId, isAccountRestricted, isConfiguredSecret, jsonResponse, supabaseSecretKey } from "../_shared/payments.ts";
import { boundedJson, boundedResponseJson, fetchDeadline, redactedProviderError, verifiedProviderGate, exactMoney } from "../_shared/payment-request.ts";

interface CaptureRequest { external_reference?: string; order_id?: string; }
const noStore = { ...corsHeaders, "Content-Type": "application/json", "Cache-Control": "no-store" };
function unit(order: any) { return Array.isArray(order?.purchase_units) ? order.purchase_units[0] : null; }
function capture(order: any) { const list = unit(order)?.payments?.captures; return Array.isArray(list) ? list.find((x: any) => x?.status === "COMPLETED") : null; }

serve(async (req: Request) => {
  if (req.method === "OPTIONS") return new Response("ok", { headers: corsHeaders });
  if (req.method !== "POST") return jsonResponse({ error: "Method not allowed" }, 405);
  try {
    const userId = await authenticatedUserId(req); if (!userId) return jsonResponse({ error: "Sign in is required to verify this payment." }, 401);
    const body = await boundedJson<CaptureRequest>(req); const externalRef = typeof body.external_reference === "string" ? body.external_reference.trim() : ""; if (!externalRef || externalRef.length > 100) return jsonResponse({ error: "Missing transaction reference." }, 400);
    if (!verifiedProviderGate("paypal")) return jsonResponse({ error: "PayPal capture is disabled pending independent settlement verification." }, 503);
    const url = Deno.env.get("SUPABASE_URL"); const key = supabaseSecretKey(); const id = Deno.env.get("PAYPAL_CLIENT_ID"); const secret = Deno.env.get("PAYPAL_CLIENT_SECRET"); const mode = (Deno.env.get("PAYPAL_MODE") ?? "").toLowerCase();
    if (!url || !key || !isConfiguredSecret(id) || !isConfiguredSecret(secret) || !["sandbox", "live"].includes(mode) || (mode === "live" && Deno.env.get("PAYPAL_LIVE_VERIFIED") !== "true")) return jsonResponse({ error: "PayPal verification is not configured on the server." }, 503);
    if (await isAccountRestricted(url, key, userId)) return jsonResponse({ error: "This account cannot capture payments right now." }, 403);
    const supabase = createClient(url, key, { auth: { persistSession: false, autoRefreshToken: false } });
    const found = await supabase.from("transactions").select("id,user_id,external_reference,provider,amount,currency,status,provider_reference,metadata").eq("external_reference", externalRef).eq("user_id", userId).maybeSingle();
    const tx = found.data; if (found.error || !tx) return jsonResponse({ error: "Transaction not found for this account." }, 404);
    if (tx.provider !== "paypal" || tx.currency !== "USD") return jsonResponse({ error: "Transaction provider mismatch." }, 400);
    if (tx.status === "success") return new Response(JSON.stringify({ success: true, status: "success", duplicate: true }), { status: 200, headers: noStore });
    if (tx.status !== "pending") return jsonResponse({ error: "Transaction is no longer pending." }, 409);
    const orderId = tx.metadata?.paypal_order_id; if (typeof orderId !== "string" || !orderId || (body.order_id && body.order_id !== orderId)) return jsonResponse({ error: "PayPal order does not match the stored transaction." }, 400);
    const base = mode === "live" ? "https://api-m.paypal.com" : "https://api-m.sandbox.paypal.com";
    const auth = await fetchDeadline(`${base}/v1/oauth2/token`, { method: "POST", headers: { Authorization: `Basic ${btoa(`${id}:${secret}`)}`, "Content-Type": "application/x-www-form-urlencoded" }, body: "grant_type=client_credentials" });
    if (!auth.ok) return jsonResponse({ error: redactedProviderError("PayPal") }, 502); const token = await boundedResponseJson<Record<string, any>>(auth).catch(() => ({} as Record<string, any>)); if (typeof token.access_token !== "string") return jsonResponse({ error: redactedProviderError("PayPal") }, 502);
    const headers = { Authorization: `Bearer ${token.access_token}`, "Content-Type": "application/json" };
    const get = () => fetchDeadline(`${base}/v2/checkout/orders/${encodeURIComponent(orderId)}`, { headers }); let response = await get(); let order: any = await boundedResponseJson<Record<string, any>>(response).catch(() => ({} as Record<string, any>));
    if (!response.ok || order.id !== orderId) return jsonResponse({ error: redactedProviderError("PayPal") }, 502);
    const expected = exactMoney(Number(tx.amount).toFixed(2)); const u = unit(order); if (!expected || u?.reference_id !== externalRef || u?.custom_id !== userId || u?.amount?.currency_code !== "USD" || u?.amount?.value !== expected) return jsonResponse({ error: "PayPal order details do not match this transaction." }, 400);
    if (order.status !== "COMPLETED") {
      if (order.status !== "APPROVED") return jsonResponse({ error: "PayPal checkout has not been approved." }, 409);
      const cap = await fetchDeadline(`${base}/v2/checkout/orders/${encodeURIComponent(orderId)}/capture`, { method: "POST", headers: { ...headers, "PayPal-Request-Id": `capture-${tx.id}` } });
      order = await boundedResponseJson<Record<string, any>>(cap).catch(() => ({} as Record<string, any>));
      if (!cap.ok) { const reread = await get(); order = await boundedResponseJson<Record<string, any>>(reread).catch(() => ({} as Record<string, any>)); if (!reread.ok) return jsonResponse({ error: "PayPal capture status is ambiguous; no duplicate capture was attempted." }, 409); }
    }
    const completed = capture(order); if (order.status !== "COMPLETED" || !completed || completed.amount?.currency_code !== "USD" || completed.amount?.value !== expected) return jsonResponse({ error: "PayPal has not confirmed a completed payment for the exact amount." }, 409);
    const providerReference = completed.id; if (typeof providerReference !== "string" || !providerReference) return jsonResponse({ error: "PayPal settlement reference is missing." }, 409);
    const result = await supabase.rpc("apply_verified_payment", { p_transaction_id: tx.id, p_expected_provider: "paypal", p_expected_amount: Number(tx.amount), p_expected_currency: "USD", p_provider_reference: providerReference, p_provider_payload: { paypal_order_id: orderId, capture_id: providerReference, status: "COMPLETED" } });
    if (result.error || result.data?.success !== true) return jsonResponse({ error: "Verified payment could not yet be posted; no duplicate credit was issued." }, 409);
    return new Response(JSON.stringify({ success: true, order_id: orderId, status: "success" }), { status: 200, headers: noStore });
  } catch (error) {
    if (error instanceof Error && error.message === "request_too_large") return jsonResponse({ error: "Request is too large." }, 413);
    if (error instanceof Error && error.message === "invalid_request") return jsonResponse({ error: "Invalid capture request." }, 400);
    if (error instanceof Error && error.message === "io_deadline") return jsonResponse({ error: "Capture status is uncertain; reconcile the existing order rather than starting another payment." }, 503);
    console.error("PayPal capture failed", error instanceof Error ? error.name : "unknown");
    return jsonResponse({ error: "Could not verify or capture the PayPal transaction." }, 500);
  }
});
