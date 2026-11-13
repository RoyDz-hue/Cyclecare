import { boundedResponseJson, fetchDeadline } from "./payment-request.ts";

export interface StoredPayPalPayment {
  id: string; user_id: string; external_reference: string; provider: string;
  amount: unknown; amount_minor: unknown; currency: string; status: string;
  provider_reference?: string | null; payment_intent_id?: string;
  metadata?: Record<string, unknown>;
}
export interface PaymentEvidence {
  event_type: "capture" | "refund" | "reversal"; provider: "paypal";
  provider_reference: string; owner: string; amount_minor: number; currency: "USD";
  payload: Record<string, unknown>;
}
export interface ReconciliationResult { success: boolean; settled: boolean; status: string; reason?: string; }
const VERIFIED = new WeakSet<object>();
export interface VerifiedPayPalEvent { readonly id: string; readonly event_type: string; readonly resource: Record<string, any>; }
const ID = /^[A-Za-z0-9_-]{1,100}$/;
function validId(value: unknown): value is string { return typeof value === "string" && ID.test(value); }
export function moneyMinor(value: unknown): number | null {
  let text = typeof value === "number" && Number.isFinite(value) ? String(value) : value;
  if (typeof text !== "string") return null;
  if (/^(0|[1-9]\d{0,8})$/.test(text)) text += ".00";
  else if (/^(0|[1-9]\d{0,8})\.\d$/.test(text)) text += "0";
  if (!/^(0|[1-9]\d{0,8})\.\d{2}$/.test(text)) return null;
  const result = Number(text.replace(".", ""));
  return Number.isSafeInteger(result) && result > 0 ? result : null;
}
function assertEvidence(ok: unknown): asserts ok { if (!ok) throw new Error("payment_evidence_mismatch"); }
function freezeDeep<T>(value: T): T {
  if (value && typeof value === "object") { Object.freeze(value); for (const child of Object.values(value)) freezeDeep(child); }
  return value;
}

/** Only fixed PayPal API origins; never fetch a URL received in a provider event. */
export class PayPalReadOnlyApi {
  readonly base: string;
  private token?: string;
  private started = performance.now();
  private config: { mode: "sandbox" | "live"; clientId: string; clientSecret: string };
  constructor(config: { mode: "sandbox" | "live"; clientId: string; clientSecret: string }) {
    this.config = config;
    this.base = config.mode === "sandbox" ? "https://api-m.sandbox.paypal.com" : "https://api-m.paypal.com";
    if (!["sandbox", "live"].includes(config.mode) || !config.clientId || !config.clientSecret) throw new Error("provider_not_configured");
  }
  private budget(): number {
    const remaining = Math.floor(32_000 - (performance.now() - this.started));
    if (remaining < 1) throw new Error("io_deadline");
    return Math.min(8_000, remaining);
  }
  private async json(path: string, init: RequestInit): Promise<Record<string, any>> {
    const response = await fetchDeadline(this.base + path, { ...init, redirect: "error" }, this.budget());
    const body = await boundedResponseJson<Record<string, any>>(response, 32_768, this.budget());
    if (!response.ok) throw new Error("provider_read_unavailable");
    return body;
  }
  async accessToken(): Promise<string> {
    if (!this.token) {
      const result = await this.json("/v1/oauth2/token", { method: "POST", headers: { Authorization: `Basic ${btoa(`${this.config.clientId}:${this.config.clientSecret}`)}`, "Content-Type": "application/x-www-form-urlencoded" }, body: "grant_type=client_credentials" });
      if (typeof result.access_token !== "string" || !result.access_token) throw new Error("provider_auth_unavailable");
      this.token = result.access_token;
    }
    return this.token;
  }
  async getOrder(id: string): Promise<Record<string, any>> { return await this.get("/v2/checkout/orders/", id); }
  async getCapture(id: string): Promise<Record<string, any>> { return await this.get("/v2/payments/captures/", id); }
  private async get(path: string, id: string): Promise<Record<string, any>> {
    if (!validId(id)) throw new Error("invalid_provider_reference");
    return await this.json(path + encodeURIComponent(id), { headers: { Authorization: `Bearer ${await this.accessToken()}` } });
  }
  async verifyWebhook(event: Record<string, any>, headers: Headers, webhookId: string): Promise<VerifiedPayPalEvent> {
    if (!validId(webhookId)) throw new Error("webhook_not_configured");
    const snapshot = JSON.parse(JSON.stringify(event));
    if (!validId(snapshot.id) || !["PAYMENT.CAPTURE.COMPLETED", "PAYMENT.CAPTURE.REFUNDED", "PAYMENT.CAPTURE.REVERSED"].includes(snapshot.event_type) || !snapshot.resource || typeof snapshot.resource !== "object" || Array.isArray(snapshot.resource)) throw new Error("unsupported_webhook_event");
    const fields: Record<string, string> = {};
    for (const [key, header] of Object.entries({ auth_algo: "paypal-auth-algo", cert_url: "paypal-cert-url", transmission_id: "paypal-transmission-id", transmission_sig: "paypal-transmission-sig", transmission_time: "paypal-transmission-time" })) {
      const value = headers.get(header);
      if (!value || value.length > 4096) throw new Error("invalid_webhook_headers");
      fields[key] = value;
    }
    const cert = new URL(fields.cert_url);
    const hosts = this.config.mode === "sandbox" ? ["api.sandbox.paypal.com", "api-m.sandbox.paypal.com"] : ["api.paypal.com", "api-m.paypal.com"];
    if (cert.protocol !== "https:" || !hosts.includes(cert.hostname) || cert.username || cert.password || cert.search || cert.hash || !cert.pathname.startsWith("/v1/notifications/certs/") || fields.auth_algo !== "SHA256withRSA" || !Number.isFinite(Date.parse(fields.transmission_time))) throw new Error("invalid_webhook_headers");
    const result = await this.json("/v1/notifications/verify-webhook-signature", { method: "POST", headers: { Authorization: `Bearer ${await this.accessToken()}`, "Content-Type": "application/json" }, body: JSON.stringify({ ...fields, webhook_id: webhookId, webhook_event: snapshot }) });
    if (result.verification_status !== "SUCCESS") throw new Error("unverified_webhook");
    const verified: VerifiedPayPalEvent = freezeDeep({ id: snapshot.id, event_type: snapshot.event_type, resource: snapshot.resource });
    VERIFIED.add(verified);
    return verified;
  }
}

/** Read-only provider lookup + one atomic SQL batch; NEVER capture/refund/charge here. */
export async function reconcilePayPalPayment(
  tx: StoredPayPalPayment,
  api: Pick<PayPalReadOnlyApi, "getOrder" | "getCapture">,
  postEvidence: (intentId: string, events: PaymentEvidence[]) => Promise<{ success?: boolean; status?: string }>,
  event?: VerifiedPayPalEvent,
): Promise<ReconciliationResult> {
  if (event && !VERIFIED.has(event)) throw new Error("unverified_webhook");
  const amount = moneyMinor(tx.amount);
  const minor = Number(tx.amount_minor);
  const intentId = tx.payment_intent_id ?? tx.metadata?.payment_intent_id;
  const orderId = tx.metadata?.provider_order_id ?? tx.metadata?.paypal_order_id;
  assertEvidence(!tx.metadata?.provider_order_id || !tx.metadata?.paypal_order_id || tx.metadata.provider_order_id === tx.metadata.paypal_order_id);
  assertEvidence(!tx.payment_intent_id || !tx.metadata?.payment_intent_id || tx.payment_intent_id === tx.metadata.payment_intent_id);
  assertEvidence(tx.provider === "paypal" && tx.currency === "USD" && amount !== null && amount === minor && Number.isSafeInteger(minor) && typeof intentId === "string" && validId(orderId));
  assertEvidence(typeof tx.user_id === "string" && tx.user_id.length > 0 && typeof tx.external_reference === "string" && tx.external_reference.length > 0);
  if (["failed", "cancelled", "expired", "ambiguous"].includes(tx.status)) return { success: false, settled: false, status: tx.status, reason: "manual_review_required" };
  const order = await api.getOrder(orderId);
  assertEvidence(order.id === orderId && Array.isArray(order.purchase_units) && order.purchase_units.length === 1);
  if (order.status !== "COMPLETED") return { success: false, settled: false, status: "pending" };
  const unit = order.purchase_units[0];
  assertEvidence(unit.reference_id === tx.external_reference && unit.custom_id === tx.user_id && unit.amount?.currency_code === "USD" && moneyMinor(unit.amount?.value) === minor);
  const captures = unit.payments?.captures;
  if (!Array.isArray(captures) || captures.length === 0) return { success: false, settled: false, status: "pending" };
  assertEvidence(captures.length === 1 && validId(captures[0]?.id));
  const captureId = captures[0].id;
  assertEvidence(!tx.provider_reference || tx.provider_reference === captureId);
  const capture = await api.getCapture(captureId);
  assertEvidence(capture.id === captureId && capture.amount?.currency_code === "USD" && moneyMinor(capture.amount?.value) === minor && capture.supplementary_data?.related_ids?.order_id === orderId);
  let reversal = false;
  if (event) {
    const resource = event.resource;
    if (event.event_type === "PAYMENT.CAPTURE.REFUNDED" && resource.id !== captureId) {
      const matches = Array.isArray(resource.links) && resource.links.some((link: any) => {
        try { const url = new URL(link?.href); return link.rel === "up" && ["https://api-m.paypal.com", "https://api.paypal.com", "https://api-m.sandbox.paypal.com", "https://api.sandbox.paypal.com"].includes(url.origin) && url.pathname === `/v2/payments/captures/${captureId}` && !url.search && !url.hash && !url.username && !url.password; } catch { return false; }
      });
      assertEvidence(matches);
    } else {
      assertEvidence(resource.id === captureId && resource.amount?.currency_code === "USD" && moneyMinor(resource.amount?.value) === minor);
    }
    reversal = event.event_type === "PAYMENT.CAPTURE.REVERSED";
    if (event.event_type === "PAYMENT.CAPTURE.REFUNDED" && capture.status !== "REFUNDED") return { success: false, settled: false, status: "ambiguous", reason: "refund_pending_or_partial_manual_review" };
  }
  if (capture.status === "PARTIALLY_REFUNDED") return { success: false, settled: false, status: "ambiguous", reason: "partial_refund_policy_review_required" };
  if (!reversal && !["COMPLETED", "REFUNDED"].includes(capture.status)) return { success: false, settled: false, status: "pending" };
  const terminal = reversal ? "reversed" : capture.status === "REFUNDED" ? "refunded" : "success";
  if (["refunded", "reversed"].includes(tx.status)) {
    if (tx.status !== terminal) return { success: false, settled: false, status: "ambiguous", reason: "terminal_provider_state_conflict" };
    return { success: false, settled: true, status: tx.status };
  }
  const payload = { paypal_order_id: orderId, capture_id: captureId, provider_status: capture.status, ...(event ? { verified_webhook_id: event.id, verified_event_type: event.event_type } : {}) };
  const evidence = (kind: PaymentEvidence["event_type"]): PaymentEvidence => ({ event_type: kind, provider: "paypal", provider_reference: captureId, owner: tx.user_id, amount_minor: minor, currency: "USD", payload });
  const events: PaymentEvidence[] = [];
  if (tx.status === "pending") events.push(evidence("capture"));
  else assertEvidence(tx.status === "success");
  if (terminal !== "success") events.push(evidence(terminal === "refunded" ? "refund" : "reversal"));
  if (events.length) {
    const result = await postEvidence(intentId, events);
    if (result.success !== true) return { success: false, settled: false, status: "ambiguous", reason: "posting_requires_reconciliation" };
    if (result.status && result.status !== terminal) return { success: false, settled: false, status: result.status, reason: "terminal_state_changed" };
  }
  return { success: terminal === "success", settled: true, status: terminal };
}
