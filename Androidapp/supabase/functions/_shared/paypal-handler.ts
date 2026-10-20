import { COIN_PACKAGES } from "./payments.ts";
import { boundedJson, boundedResponseJson, fetchDeadline } from "./payment-request.ts";
import { moneyMinor, PayPalReadOnlyApi, reconcilePayPalPayment, type PaymentEvidence, type StoredPayPalPayment, type VerifiedPayPalEvent } from "./paypal-reconciliation.ts";

export interface PayPalConfig { mode: "sandbox" | "live"; clientId: string; clientSecret: string; url: string; serviceKey: string; returnUrl?: string; cancelUrl?: string; webhookId?: string; }
export interface PaymentStore {
  readiness(): Promise<Record<string, any>>;
  create(owner: string, external: string, packageId: string, minor: number, coins: number): Promise<Record<string, any>>;
  claim(intent: string, action: string, requestId: string): Promise<Record<string, any>>;
  attach(intent: string, order: string): Promise<Record<string, any>>;
  findOwn(owner: string, external: string): Promise<StoredPayPalPayment | null>;
  findOrder(order: string): Promise<StoredPayPalPayment | null>;
  batch(intent: string, evidence: PaymentEvidence[]): Promise<{ success?: boolean; status?: string }>;
}
export interface PayPalDependencies {
  released(): boolean; authenticate(req: Request): Promise<string | null>;
  config(): PayPalConfig | null; restricted(config: PayPalConfig, owner: string): Promise<boolean>;
  store(config: PayPalConfig): PaymentStore; api(config: PayPalConfig): PayPalReadOnlyApi;
}
const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;
const ID = /^[A-Za-z0-9_-]{1,100}$/;
const headers = { "Content-Type": "application/json", "Cache-Control": "no-store", "Access-Control-Allow-Origin": "*", "Access-Control-Allow-Headers": "authorization, x-client-info, apikey, content-type", "Access-Control-Allow-Methods": "POST, OPTIONS" };
function reply(body: unknown, status = 200): Response { return new Response(JSON.stringify(body), { status, headers }); }
function assert(ok: unknown): asserts ok { if (!ok) throw new Error("payment_evidence_mismatch"); }
async function ready(store: PaymentStore): Promise<void> { const value = await store.readiness(); if (value.schema_ready !== true || value.version !== 3) throw new Error("database_not_ready"); }
function orderId(tx: StoredPayPalPayment): string {
  const value = tx.metadata?.provider_order_id ?? tx.metadata?.paypal_order_id;
  assert(typeof value === "string" && ID.test(value));
  assert(!tx.metadata?.provider_order_id || !tx.metadata?.paypal_order_id || tx.metadata.provider_order_id === tx.metadata.paypal_order_id);
  assert(typeof tx.payment_intent_id === "string" && UUID.test(tx.payment_intent_id));
  assert(!tx.metadata?.payment_intent_id || tx.metadata.payment_intent_id === tx.payment_intent_id);
  assert(tx.provider === "paypal" && tx.currency === "USD" && moneyMinor(tx.amount) === Number(tx.amount_minor));
  return value;
}
function assertOrder(tx: StoredPayPalPayment, order: Record<string, any>, id: string): void {
  assert(order.id === id && Array.isArray(order.purchase_units) && order.purchase_units.length === 1);
  const unit = order.purchase_units[0];
  assert(unit.reference_id === tx.external_reference && unit.custom_id === tx.user_id && unit.amount?.currency_code === "USD" && moneyMinor(unit.amount?.value) === Number(tx.amount_minor));
}
function approvalLink(order: Record<string, any>, mode: "sandbox" | "live"): string | null {
  const href = order.links?.find((link: any) => ["approve", "payer-action"].includes(link?.rel))?.href;
  if (typeof href !== "string") return null;
  const value = new URL(href);
  const host = mode === "sandbox" ? "www.sandbox.paypal.com" : "www.paypal.com";
  return value.protocol === "https:" && value.hostname === host && !value.username && !value.password && !value.port ? value.href : null;
}
function captureFromEvent(event: VerifiedPayPalEvent, base: string): string | null {
  if (event.event_type !== "PAYMENT.CAPTURE.REFUNDED") return ID.test(event.resource.id) ? event.resource.id : null;
  for (const link of event.resource.links ?? []) {
    try {
      const url = new URL(link.href);
      const hosts = base.includes("sandbox") ? ["api-m.sandbox.paypal.com", "api.sandbox.paypal.com"] : ["api-m.paypal.com", "api.paypal.com"];
      const match = url.pathname.match(/^\/v2\/payments\/captures\/([A-Za-z0-9_-]{1,100})$/);
      if (link.rel === "up" && url.protocol === "https:" && hosts.includes(url.hostname) && !url.port && !url.username && !url.password && !url.search && !url.hash && match) return match[1];
    } catch { /* Never fetch event-supplied URLs. */ }
  }
  return null;
}

/** Dependencies are fixed by server code; no HTTP field can enable a release gate. */
export function createPayPalHandler(kind: "create" | "capture" | "reconcile" | "webhook", dependencies: PayPalDependencies): (req: Request) => Promise<Response> {
  return async req => {
    if (req.method === "OPTIONS") return new Response("ok", { headers });
    if (req.method !== "POST") return reply({ error: "Method not allowed" }, 405);
    try {
      const owner = kind === "webhook" ? null : await dependencies.authenticate(req);
      if (kind !== "webhook" && !owner) return reply({ error: "Sign in is required." }, 401);
      const body = await boundedJson<Record<string, any>>(req, kind === "webhook" ? 65_536 : 2048);
      if (kind === "create" && (typeof body.package_id !== "string" || !Object.hasOwn(COIN_PACKAGES, body.package_id))) return reply({ error: "Unknown coin package." }, 400);
      if (["capture", "reconcile"].includes(kind) && (typeof body.external_reference !== "string" || !body.external_reference || body.external_reference.length > 128)) return reply({ error: "Invalid transaction reference." }, 400);
      if (!dependencies.released()) return reply({ error: "Payments are disabled pending independent provider and settlement acceptance.", status: "unavailable" }, 503);
      const config = dependencies.config();
      if (!config) return reply({ error: "Payment verification is not configured." }, 503);
      const store = dependencies.store(config);
      await ready(store); // No provider token, read or write before the v3 catalog preflight.
      const api = dependencies.api(config);
      if (kind === "webhook") {
        const verified = await api.verifyWebhook(body, req.headers, config.webhookId ?? "");
        const captureId = captureFromEvent(verified, api.base);
        if (!captureId) return reply({ error: "Verified event requires manual reconciliation." }, 409);
        const capture = await api.getCapture(captureId);
        const linkedOrder = capture.supplementary_data?.related_ids?.order_id;
        if (typeof linkedOrder !== "string" || !ID.test(linkedOrder)) return reply({ error: "Verified capture has no matching stored order." }, 409);
        const tx = await store.findOrder(linkedOrder);
        if (!tx) return reply({ error: "Verified order is not recorded; reconciliation is required." }, 409);
        const result = await reconcilePayPalPayment(tx, api, (intent, events) => store.batch(intent, events), verified);
        return reply(result, result.settled ? 200 : 409);
      }
      if (await dependencies.restricted(config, owner!)) return reply({ error: "This account cannot perform payment operations." }, 403);
      if (kind === "create") {
        if (Object.keys(body).some(key => !["package_id", "checkout_request_id", "is_acdc_card"].includes(key)) || body.is_acdc_card || typeof body.checkout_request_id !== "string" || !UUID.test(body.checkout_request_id)) return reply({ error: "A valid stable checkout request ID is required; direct card entry is disabled." }, 400);
        const pkg = typeof body.package_id === "string" && Object.hasOwn(COIN_PACKAGES, body.package_id) ? COIN_PACKAGES[body.package_id] : undefined;
        if (!pkg) return reply({ error: "Unknown coin package." }, 400);
        const external = `CC_${owner}_${body.checkout_request_id.toLowerCase()}`;
        const created = await store.create(owner!, external, pkg.id, Math.round(pkg.priceUsd * 100), pkg.coins);
        if (created.success !== true || typeof created.payment_intent_id !== "string") throw new Error("database_not_ready");
        if (created.status !== "pending") return reply({ success: false, settled: ["success", "refunded", "reversed"].includes(created.status), status: created.status, external_reference: external, reason: "existing_checkout_terminal" }, 409);
        let order: Record<string, any>;
        if (typeof created.provider_order_id === "string" && created.provider_order_id) {
          order = await api.getOrder(created.provider_order_id);
          assertOrder({ user_id: owner!, external_reference: external, amount_minor: Math.round(pkg.priceUsd * 100) } as StoredPayPalPayment, order, created.provider_order_id);
        } else {
          const token = await api.accessToken();
          const requestId = `order-${created.payment_intent_id}`;
          const claim = await store.claim(created.payment_intent_id, "create_order", requestId);
          if (claim.may_submit !== true) return reply({ success: false, status: "ambiguous", external_reference: external, error: "An existing order attempt needs read-only reconciliation; no second order was submitted." }, 409);
          const response = await fetchDeadline(`${api.base}/v2/checkout/orders`, { method: "POST", redirect: "error", headers: { Authorization: `Bearer ${token}`, "Content-Type": "application/json", "PayPal-Request-Id": requestId }, body: JSON.stringify({ intent: "CAPTURE", purchase_units: [{ reference_id: external, custom_id: owner, amount: { currency_code: "USD", value: pkg.priceUsd.toFixed(2) }, description: `CycleCare ${pkg.title} (${pkg.coins} Cycle Coins)` }], application_context: { brand_name: "CycleCare", user_action: "PAY_NOW", return_url: config.returnUrl, cancel_url: config.cancelUrl } }) });
          order = await boundedResponseJson<Record<string, any>>(response, 32_768);
          if (!response.ok || typeof order.id !== "string" || !ID.test(order.id)) throw new Error("provider_read_unavailable");
          const attached = await store.attach(created.payment_intent_id, order.id);
          if (attached.success !== true) return reply({ success: false, status: "ambiguous", external_reference: external, error: "Created order could not be recorded; no second order will be submitted." }, 409);
        }
        const approval = approvalLink(order, config.mode);
        if (!approval) return reply({ success: false, status: "pending", external_reference: external, order_id: order.id, error: "Existing checkout requires reconciliation, not another purchase." }, 409);
        return reply({ success: true, status: "pending", settled: false, external_reference: external, order_id: order.id, approval_url: approval, amount_usd: pkg.priceUsd, coins_to_credit: pkg.coins });
      }
      if (Object.keys(body).some(key => key !== "external_reference") || typeof body.external_reference !== "string" || !body.external_reference || body.external_reference.length > 128) return reply({ error: "Invalid transaction reference." }, 400);
      const tx = await store.findOwn(owner!, body.external_reference);
      if (!tx || tx.user_id !== owner) return reply({ error: "Transaction not found for this account." }, 404);
      const id = orderId(tx); // Legacy/unlinked records cannot reach a provider.
      if (kind === "capture" && tx.status === "pending") {
        const order = await api.getOrder(id);
        assertOrder(tx, order, id);
        if (order.status === "APPROVED") {
          const token = await api.accessToken();
          const requestId = `capture-${tx.id}`;
          const claim = await store.claim(tx.payment_intent_id!, "capture", requestId);
          if (claim.may_submit !== true) return reply({ success: false, status: "ambiguous", error: "An existing capture attempt needs read-only reconciliation; no duplicate capture was submitted." }, 409);
          try {
            const response = await fetchDeadline(`${api.base}/v2/checkout/orders/${encodeURIComponent(id)}/capture`, { method: "POST", redirect: "error", headers: { Authorization: `Bearer ${token}`, "Content-Type": "application/json", "PayPal-Request-Id": requestId } });
            await boundedResponseJson(response, 32_768);
          } catch { /* Status may be uncertain: only GETs follow, never a second POST. */ }
        }
      }
      const result = await reconcilePayPalPayment(tx, api, (intent, events) => store.batch(intent, events));
      return reply(result, result.settled ? 200 : 409);
    } catch (error) {
      const code = error instanceof Error ? error.message : "unknown";
      if (code === "request_too_large") return reply({ error: "Request is too large." }, 413);
      if (code === "invalid_request") return reply({ error: "Invalid payment request." }, 400);
      if (["unverified_webhook", "invalid_webhook_headers"].includes(code)) return reply({ error: "Webhook verification failed." }, 401);
      if (code === "payment_evidence_mismatch") return reply({ error: "Stored and provider payment evidence do not match; manual reconciliation is required." }, 409);
      return reply({ success: false, status: "ambiguous", error: "Payment verification is unavailable. Reconcile the existing transaction; do not start another purchase to retry it." }, 503);
    }
  };
}
