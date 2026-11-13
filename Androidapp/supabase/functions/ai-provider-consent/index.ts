import { serve } from "https://deno.land/std@0.224.0/http/server.ts";
import { corsHeaders } from "../_shared/cors.ts";
import { authenticatedUserId, supabasePublishableKey, supabaseSecretKey } from "../_shared/payments.ts";
import { MANIFEST_REVISION, manifestWithFingerprint } from "../_shared/provider-manifests.js";
import { fetchJsonBounded } from "../_shared/bounded-json.js";
import { boundedJson } from "../_shared/payment-request.ts";

const json = (body: unknown, status = 200) => new Response(JSON.stringify(body), { status, headers: { ...corsHeaders, "Content-Type": "application/json", "Cache-Control": "no-store" } });
const providers = ["COHERE", "GEMINI", "OPENROUTER", "LAJIWA"];
serve(async (req: Request) => {
  if (req.method === "OPTIONS") return new Response("ok", { headers: corsHeaders });
  if (req.method !== "GET" && req.method !== "POST") return json({ error: "Method not allowed" }, 405);
  try {
    const userId = await authenticatedUserId(req);
    if (!userId) return json({ error: "Authentication required" }, 401);
    const url = Deno.env.get("SUPABASE_URL")?.replace(/\/$/, "");
    const key = supabaseSecretKey(); const publishable = supabasePublishableKey();
    if (!url || !key || !publishable) return json({ error: "Backend is not configured" }, 503);
    const read = async (table: string, fields: string, owner = false): Promise<any[]> => {
      const path = `${url}/rest/v1/${table}?select=${fields}${owner ? `&user_id=eq.${encodeURIComponent(userId)}` : ""}`;
      const { response, data } = await fetchJsonBounded(path, { headers: { apikey: key, Authorization: `Bearer ${key}` }, redirect: "error" }, { timeoutMs: 5000, maxBytes: 65536 });
      if (!response.ok || !Array.isArray(data)) throw new Error("contract unavailable");
      return data;
    };
    if (req.method === "POST") {
      const body = await boundedJson<Record<string, unknown>>(req, 8192, 5000);
      if (Object.keys(body).some(k => !["provider_id", "manifest_fingerprint", "enabled", "expected_revision"].includes(k))) return json({ error: "Invalid consent request" }, 400);
      const id = typeof body.provider_id === "string" ? body.provider_id.trim().toUpperCase() : "";
      const fingerprint = typeof body.manifest_fingerprint === "string" ? body.manifest_fingerprint : "";
      const enabled = body.enabled; const expected = body.expected_revision;
      if (!providers.includes(id) || typeof enabled !== "boolean" || !Number.isInteger(expected) || Number(expected) < 0 || Number(expected) >= 2147483647 || (enabled ? !/^[a-f0-9]{64}$/.test(fingerprint) : fingerprint !== "" && !/^[a-f0-9]{64}$/.test(fingerprint))) return json({ error: "Invalid consent request" }, 400);
      const manifest = await manifestWithFingerprint(id);
      if (!manifest || (enabled && fingerprint !== manifest.manifest_fingerprint)) return json({ error: "Manifest fingerprint is not current" }, 409);
      const { response, data } = await fetchJsonBounded(`${url}/rest/v1/rpc/set_my_ai_processor_consent_v3`, {
        method: "POST", redirect: "error", headers: { apikey: publishable, Authorization: req.headers.get("authorization") || "", "Content-Type": "application/json" },
        body: JSON.stringify({ p_processor: id, p_enabled: enabled, p_expected_revision: expected, p_manifest_fingerprint: fingerprint, p_manifest_revision: MANIFEST_REVISION }),
      }, { timeoutMs: 5000, maxBytes: 16384 });
      const receipt = data as Record<string, unknown>;
      if (!response.ok) {
        const conflict = typeof receipt?.message === "string" && /revision/i.test(receipt.message);
        return json({ error: conflict ? "Consent revision conflict" : "Consent unavailable", code: conflict ? "revision_conflict" : "consent_unavailable" }, conflict ? 409 : 503);
      }
      if (!receipt || receipt.account_snapshot !== userId || receipt.provider_id !== id || receipt.enabled !== enabled || receipt.revision !== Number(expected) + 1 || (enabled && receipt.manifest_fingerprint !== fingerprint)) throw new Error("receipt unavailable");
      return json({ version: 3, account_snapshot: userId, provider_id: id, manifest_revision: MANIFEST_REVISION,
        receipt: { enabled, revision: receipt.revision, manifest_fingerprint: String(receipt.manifest_fingerprint || "") } });
    }
    const [consents, clocks, policies, manifests] = await Promise.all([
      read("cyclecare_ai_processor_consents", "processor,granted,consent_revision,account_snapshot,manifest_fingerprint,revoked_at", true),
      read("cyclecare_ai_processor_consent_clocks", "processor,revision", true),
      read("cyclecare_ai_provider_policy_readiness", "processor,reviewed,eligible,policy_revision,manifest_fingerprint"),
      read("cyclecare_ai_provider_manifests", "processor,eligible,account_reviewed,use_case_reviewed,audience_reviewed,manifest_revision,manifest_fingerprint"),
    ]);
    const rows = await Promise.all(providers.map(async id => {
      const manifest = await manifestWithFingerprint(id); if (!manifest) throw new Error("manifest unavailable");
      const policy = policies.find(p => p.processor === id); const reviewed = manifests.find(p => p.processor === id);
      const eligible = policy?.reviewed === true && policy.eligible === true && policy.policy_revision === manifest.policy_revision && policy.manifest_fingerprint === manifest.manifest_fingerprint &&
        reviewed?.eligible === true && reviewed.account_reviewed === true && reviewed.use_case_reviewed === true && reviewed.audience_reviewed === true && reviewed.manifest_revision === MANIFEST_REVISION && reviewed.manifest_fingerprint === manifest.manifest_fingerprint;
      const data = consents.find(c => c.processor === id); const clock = clocks.find(c => c.processor === id);
      const revision = clock?.revision ?? 0;
      if (!Number.isSafeInteger(revision) || revision < 0 || revision > 2147483647 || (data && (!clock || Number(String(data.consent_revision).match(/[0-9]+$/)?.[0]) !== revision))) throw new Error("revision unavailable");
      return { id, display_name: manifest.display_name, model: manifest.model, policy_revision: manifest.policy_revision,
        manifest_fingerprint: manifest.manifest_fingerprint, processors: manifest.processors, eligible,
        unavailable_reason: eligible ? "" : manifest.unavailable_reason,
        consent: { enabled: Boolean(eligible && data?.granted === true && data.account_snapshot === userId && data.revoked_at == null && data.manifest_fingerprint === manifest.manifest_fingerprint), revision, manifest_fingerprint: String(data?.manifest_fingerprint || "") } };
    }));
    return json({ version: 3, account_snapshot: userId, manifest_revision: MANIFEST_REVISION, providers: rows, hybrid: { released: true, max_attempts: 3 } });
  } catch (error) {
    const message = error instanceof Error ? error.message : "";
    return json({ error: "Consent contract unavailable" }, message === "request_too_large" ? 413 : message === "invalid_request" ? 400 : message === "io_deadline" ? 408 : 503);
  }
});
