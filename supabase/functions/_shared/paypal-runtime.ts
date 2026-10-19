import { fetchJsonBounded } from "./bounded-json.js";
import { authenticatedUserId, isAccountRestricted, isConfiguredSecret, supabaseSecretKey } from "./payments.ts";
import { verifiedProviderGate } from "./payment-request.ts";
import { PayPalReadOnlyApi, type PaymentEvidence, type StoredPayPalPayment } from "./paypal-reconciliation.ts";
import type { PaymentStore, PayPalConfig, PayPalDependencies } from "./paypal-handler.ts";

class SupabasePaymentStore implements PaymentStore {
  private config: PayPalConfig;
  constructor(config: PayPalConfig) { this.config = config; }
  private async request(path: string, body?: unknown): Promise<any> {
    const { response, data } = await fetchJsonBounded(`${this.config.url.replace(/\/$/, "")}/rest/v1/${path}`, { method: body === undefined ? "GET" : "POST", redirect: "error", headers: { apikey: this.config.serviceKey, Authorization: `Bearer ${this.config.serviceKey}`, "Content-Type": "application/json" }, ...(body === undefined ? {} : { body: JSON.stringify(body) }) }, { timeoutMs: 8_000, maxBytes: 65_536 });
    if (!response.ok) throw new Error("database_not_ready");
    return data;
  }
  readiness(): Promise<Record<string, any>> { return this.request("rpc/get_payment_protocol_readiness", {}); }
  create(owner: string, external: string, packageId: string, minor: number, coins: number): Promise<Record<string, any>> {
    return this.request("rpc/create_payment_intent", { p_owner: owner, p_provider: "paypal", p_external_reference: external, p_amount_minor: minor, p_currency: "USD", p_metadata: { package_id: packageId, coins_to_credit: coins } });
  }
  claim(intent: string, action: string, requestId: string): Promise<Record<string, any>> { return this.request("rpc/claim_payment_provider_action", { p_intent: intent, p_action: action, p_request_id: requestId }); }
  attach(intent: string, order: string): Promise<Record<string, any>> { return this.request("rpc/attach_provider_order", { p_intent: intent, p_order: order }); }
  private async find(filters: Record<string, string>): Promise<StoredPayPalPayment | null> {
    const query = new URLSearchParams({ select: "id,user_id,external_reference,provider,amount,amount_minor,currency,status,provider_reference,payment_intent_id,metadata", provider: "eq.paypal", limit: "2", ...filters });
    const rows = await this.request(`transactions?${query}`);
    if (!Array.isArray(rows) || rows.length > 1) throw new Error("ambiguous_payment_record");
    return rows[0] ?? null;
  }
  findOwn(owner: string, external: string): Promise<StoredPayPalPayment | null> { return this.find({ user_id: `eq.${owner}`, external_reference: `eq.${external}` }); }
  findOrder(order: string): Promise<StoredPayPalPayment | null> { return this.find({ "metadata->>provider_order_id": `eq.${order}` }); }
  async batch(intent: string, evidence: PaymentEvidence[]): Promise<{ success?: boolean; status?: string }> {
    const value = await this.request("rpc/reconcile_verified_payment_batch", { p_intent: intent, p_events: evidence });
    return { success: value?.success === true, status: value?.last?.status };
  }
}

function configuration(): PayPalConfig | null {
  const mode = Deno.env.get("PAYPAL_MODE")?.toLowerCase();
  const url = Deno.env.get("SUPABASE_URL"); const serviceKey = supabaseSecretKey();
  const clientId = Deno.env.get("PAYPAL_CLIENT_ID"); const clientSecret = Deno.env.get("PAYPAL_CLIENT_SECRET");
  if (!["sandbox", "live"].includes(mode ?? "") || !url || !serviceKey || !isConfiguredSecret(clientId) || !isConfiguredSecret(clientSecret) || (mode === "live" && Deno.env.get("PAYPAL_LIVE_VERIFIED") !== "true")) return null;
  return { mode: mode as "sandbox" | "live", url, serviceKey, clientId, clientSecret, returnUrl: Deno.env.get("PAYPAL_RETURN_URL"), cancelUrl: Deno.env.get("PAYPAL_CANCEL_URL"), webhookId: Deno.env.get("PAYPAL_WEBHOOK_ID") };
}

/** This factory is the only production wiring; source gate remains false. */
export function payPalRuntimeDependencies(): PayPalDependencies {
  return {
    released: () => verifiedProviderGate("paypal"), authenticate: authenticatedUserId,
    config: configuration, restricted: (config, owner) => isAccountRestricted(config.url, config.serviceKey, owner),
    store: config => new SupabasePaymentStore(config), api: config => new PayPalReadOnlyApi(config),
  };
}
