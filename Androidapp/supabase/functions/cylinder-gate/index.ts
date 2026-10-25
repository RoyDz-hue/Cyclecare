// cylinder-gate: authorization center for the custom open-source AI ("Cylinder").
// Sits in front of the Cloudflare-hosted model: API key validation, per-key
// rate limits, then a proxy hop upstream - so the free-tier AI never gets stressed.
//
// Routes (prefix /cylinder-gate):
//   POST /v1/chat         client calls: header x-cylinder-key, JSON body proxied upstream
//   POST /admin/keys      issue a key:    header x-gate-admin, body {label, rpm_limit?, daily_limit?}
//   POST  /admin/revoke   revoke a key:   header x-gate-admin, body {key_id}
//   GET  /admin/keys      list keys:      header x-gate-admin (no hashes returned)
//
// Secrets (supabase secrets set, never in repo):
//   CYLINDER_AI_URL          upstream model endpoint (Cloudflare Workers AI run URL)
//   CYLINDER_AI_TOKEN        upstream bearer token
//   CYLINDER_GATE_ADMIN_SECRET  admin route shared secret
import { serve } from "https://deno.land/std@0.224.0/http/server.ts";
import { createClient } from "https://esm.sh/@supabase/supabase-js@2.118.0";
import { corsHeaders } from "../_shared/cors.ts";

function json(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { ...corsHeaders, "Content-Type": "application/json" },
  });
}

async function sha256Hex(text: string): Promise<string> {
  const buf = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(text));
  return Array.from(new Uint8Array(buf)).map((b) => b.toString(16).padStart(2, "0")).join("");
}

function randomKey(): string {
  const bytes = new Uint8Array(24);
  crypto.getRandomValues(bytes);
  return "ccai_" + Array.from(bytes).map((b) => b.toString(16).padStart(2, "0")).join("");
}

serve(async (req: Request) => {
  if (req.method === "OPTIONS") return json({ ok: true });

  const supabaseUrl = Deno.env.get("SUPABASE_URL")?.replace(/\/$/, "");
  const serviceKey = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY");
  const adminSecret = Deno.env.get("CYLINDER_GATE_ADMIN_SECRET");
  if (!supabaseUrl || !serviceKey) return json({ error: "Gate is not configured" }, 503);
  const db = createClient(supabaseUrl, serviceKey, { auth: { autoRefreshToken: false, persistSession: false } });

  const url = new URL(req.url);
  const path = url.pathname.replace(/^\/cylinder-gate/, "").replace(/\/$/, "") || "/";

  // ---- admin routes ----
  if (path.startsWith("/admin/")) {
    if (!adminSecret || req.headers.get("x-gate-admin") !== adminSecret) {
      return json({ error: "Forbidden" }, 403);
    }
    if (path === "/admin/keys" && req.method === "POST") {
      const body = await req.json().catch(() => ({}));
      if (!body.label || typeof body.label !== "string") return json({ error: "label required" }, 400);
      const key = randomKey();
      const hash = await sha256Hex(key);
      const { data, error } = await db.rpc("cylinder_gate_create_key", {
        p_label: body.label,
        p_key_hash: hash,
        p_key_prefix: key.slice(0, 13),
        p_rpm: body.rpm_limit ?? null,
        p_daily: body.daily_limit ?? null,
      });
      if (error) return json({ error: error.message }, 500);
      // The plaintext key is returned exactly once. Only its hash is stored.
      return json({ id: data, key, note: "Store this key now - it cannot be recovered." });
    }
    if (path === "/admin/revoke" && req.method === "POST") {
      const body = await req.json().catch(() => ({}));
      if (!body.key_id) return json({ error: "key_id required" }, 400);
      const { error } = await db.from("cylinder_gate_keys").update({ revoked_at: new Date().toISOString() }).eq("id", body.key_id).is("revoked_at", null);
      if (error) return json({ error: error.message }, 500);
      return json({ revoked: true });
    }
    if (path === "/admin/keys" && req.method === "GET") {
      const { data, error } = await db
        .from("cylinder_gate_keys")
        .select("id, label, key_prefix, tier, rpm_limit, daily_limit, created_at, revoked_at, last_used_at")
        .order("created_at", { ascending: false });
      if (error) return json({ error: error.message }, 500);
      return json({ keys: data });
    }
    return json({ error: "Not found" }, 404);
  }

  // ---- client route ----
  if (path === "/v1/chat" && req.method === "POST") {
    const clientKey = req.headers.get("x-cylinder-key") ?? "";
    if (!clientKey.startsWith("ccai_")) return json({ error: "Missing or malformed API key" }, 401);
    const hash = await sha256Hex(clientKey);
    const { data: gate, error } = await db.rpc("cylinder_gate_consume", {
      p_key_hash: hash,
      p_day_start: new Date().toISOString(),
    });
    if (error) return json({ error: "Gate check failed" }, 500);
    if (!gate.allowed) {
      const status = gate.reason === "unknown_key" || gate.reason === "revoked" ? 401 : 429;
      return json({ error: gate.reason, remaining_minute: gate.remaining_minute ?? 0, remaining_day: gate.remaining_day ?? 0 }, status);
    }

    const upstreamUrl = Deno.env.get("CYLINDER_AI_URL");
    const upstreamToken = Deno.env.get("CYLINDER_AI_TOKEN");
    if (!upstreamUrl || !upstreamToken) return json({ error: "Upstream model is not configured yet" }, 503);

    const upstream = await fetch(upstreamUrl, {
      method: "POST",
      headers: { "Content-Type": "application/json", Authorization: `Bearer ${upstreamToken}` },
      body: await req.text(),
    });
    const text = await upstream.text();
    return new Response(text, {
      status: upstream.status,
      headers: { ...corsHeaders, "Content-Type": upstream.headers.get("Content-Type") ?? "application/json" },
    });
  }

  return json({ error: "Not found" }, 404);
});
