// fx-refresh: the single end-to-end rate refresh. Two callers:
//   1. Admin ping from the PWA Pricing tab (admin JWT -> triggered_by 'admin')
//   2. Lazy from the Android app (publishable key -> 'lazy'), which the
//      staleness gate limits to at most one real refresh per day.
// Flow: staleness check -> fetch (open.er-api.com, Frankfurter fallback) ->
// per-quote sanity bounds in fx_apply_market_rates -> upsert -> refresh log.
// Safe failure: any error keeps the old rates; displays fall back to USD only
// after 48h stale (enforced in get_public_pricing).
import { serve } from "https://deno.land/std@0.224.0/http/server.ts";
import { createClient } from "https://esm.sh/@supabase/supabase-js@2.118.0";
import { jsonResponse, supabaseSecretKey } from "../_shared/payments.ts";

const SOURCES = [
  { name: "open.er-api.com", url: "https://open.er-api.com/v6/latest/USD" },
  { name: "frankfurter.dev", url: "https://api.frankfurter.dev/v1/latest?base=USD" },
];

serve(async (req: Request) => {
  if (req.method === "OPTIONS") return jsonResponse({ ok: true });
  if (req.method !== "POST") return jsonResponse({ error: "Method not allowed" }, 405);
  const supabaseUrl = Deno.env.get("SUPABASE_URL")?.replace(/\/$/, "");
  const secretKey = supabaseSecretKey();
  const anonKey = Deno.env.get("SUPABASE_ANON_KEY");
  if (!supabaseUrl || !secretKey || !anonKey) return jsonResponse({ error: "Backend is not configured" }, 503);
  const admin = createClient(supabaseUrl, secretKey, { auth: { autoRefreshToken: false, persistSession: false } });

  // Caller: admin JWT -> 'admin'; otherwise the app's publishable key -> 'lazy'.
  let triggeredBy = "lazy";
  let actor: string | null = null;
  const authHeader = req.headers.get("Authorization") ?? "";
  if (authHeader.startsWith("Bearer ") && !authHeader.includes(anonKey)) {
    const userClient = createClient(supabaseUrl, anonKey, { auth: { autoRefreshToken: false, persistSession: false }, global: { headers: { Authorization: authHeader } } });
    const { data: userData } = await userClient.auth.getUser();
    if (userData?.user?.id) {
      const { data: role } = await admin.from("cyclecare_admin_roles").select("user_id").eq("user_id", userData.user.id).maybeSingle();
      if (role?.user_id) { triggeredBy = "admin"; actor = userData.user.id; }
    }
  }

  // Staleness + once-a-day gate: lazy callers only proceed when rates are stale
  // AND no refresh succeeded in the last 20 hours. Admins can always ping.
  const { data: status } = await admin.rpc("fx_status");
  // Atomic daily lease (same claim pattern as the Community Guides pulse): the
  // first lazy caller of the day wins; everyone else loses instantly. Admin
  // pings bypass the lease. Failure releases it so a retry can happen today.
  let leaseHeld = false;
  if (triggeredBy === "lazy") {
    if (status?.stale !== true) return jsonResponse({ status: "skipped", reason: "fresh", fx: status });
    const { data: claimed } = await admin.rpc("fx_claim_daily_refresh", { p_claimed_by: "lazy" });
    if (claimed !== true) return jsonResponse({ status: "skipped", reason: "lease_held", fx: status });
    leaseHeld = true;
  }

  const { data: rows } = await admin.from("fx_rates").select("quote_currency").eq("is_active", true).neq("quote_currency", "USD");
  const quotes = (rows ?? []).map((r: { quote_currency: string }) => r.quote_currency);
  if (!quotes.length) return jsonResponse({ status: "no_currencies", fx: status });

  let payload: { quote: string; rate: number }[] = [];
  let usedSource = "";
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), 15_000);
  try {
    for (const source of SOURCES) {
      try {
        const res = await fetch(source.url, { signal: controller.signal });
        if (!res.ok) continue;
        const data = await res.json();
        payload = quotes.map((q: string) => ({ quote: q, rate: data?.rates?.[q] }))
          .filter((r: { rate?: number }) => typeof r.rate === "number" && r.rate > 0);
        if (payload.length) { usedSource = source.name; break; }
      } catch { /* try the next source */ }
    }
  } finally {
    clearTimeout(timer);
  }

  if (!payload.length) {
    await admin.from("fx_refresh_log").insert({ triggered_by: triggeredBy, actor, status: "failed", source: null, detail: "all rate sources unavailable" });
    if (leaseHeld) await admin.rpc("fx_settle_daily_refresh", { p_ok: false, p_claimed_by: "lazy" });
    // Safe failure: old rates stay; get_public_pricing falls back to USD at 48h.
    return jsonResponse({ status: "failed", error: "Rate sources unavailable; keeping current rates." }, 502);
  }

  const { data: applied, error } = await admin.rpc("fx_apply_market_rates", { p_rates: payload, p_source: usedSource });
  if (error) {
    await admin.from("fx_refresh_log").insert({ triggered_by: triggeredBy, actor, status: "failed", source: usedSource, detail: "rates could not be stored" });
    if (leaseHeld) await admin.rpc("fx_settle_daily_refresh", { p_ok: false, p_claimed_by: "lazy" });
    return jsonResponse({ status: "failed", error: "Rates could not be stored; keeping current rates." }, 500);
  }
  const updated = Number(applied?.updated ?? 0);
  const skipped = Array.isArray(applied?.skipped) ? applied.skipped : [];
  const finalStatus = updated === quotes.length ? "ok" : updated > 0 ? "partial" : "failed";
  await admin.from("fx_refresh_log").insert({
    triggered_by: triggeredBy, actor, status: finalStatus, source: usedSource,
    quotes_updated: updated, skipped,
    detail: updated === 0 ? "every quote was rejected by sanity bounds" : null,
  });
  if (leaseHeld) await admin.rpc("fx_settle_daily_refresh", { p_ok: updated > 0, p_claimed_by: "lazy" });
  if (actor) {
    await admin.from("admin_audit_logs").insert({
      admin_user_id: actor, admin_action: "ADMIN_FX_REFRESH", target_user_id: "fx_rates",
      details: { status: finalStatus, source: usedSource, updated, skipped }, performed_by: actor });
  }
  return jsonResponse({ status: finalStatus, source: usedSource, updated, skipped, fx: (await admin.rpc("fx_status")).data });
});
