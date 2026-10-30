import { parseCoinRequest, applyCoinReply, isConfirmation, selectCandidate, startsCoinCommand } from "../_shared/coin-dialog.js";
import { asksMemberCount, mixesCoinWithOtherAction, asksJoinedCount, asksTierCount, asksTopBalance } from "../_shared/command-intents.js";
import { coinDialog, executeStep, persistMessage } from "../_shared/admin-command-flow.ts";
import { formatCommandOutcome, formatCommandStep } from "../_shared/command-results.js";
// Admin Command: prompt-driven admin agent for the CycleCare Admin PWA.
// Flow: prompt -> planner (existing provider chain) -> validated plan card
// (DB policy gate: tools, kinds, limits) -> admin approval for WRITE/RISKY ->
// execution through the existing audited admin RPCs with the admin's own JWT
// (every action re-checks is_cyclecare_admin). Read-only plans run instantly.
import { serve } from "https://deno.land/std@0.224.0/http/server.ts";
import { createClient } from "https://esm.sh/@supabase/supabase-js@2.118.0";
import { isConfiguredSecret, jsonResponse, supabaseSecretKey } from "../_shared/payments.ts";
import { AGENT_ADMIN_COMMAND, ADMIN_PLANNER_PROMPT_V2, loadAgentPrompt, recordAiRun } from "../_shared/ai-core.ts";

type ProviderId = "GEMINI" | "COHERE" | "OPENROUTER";

const TOOL_CATALOG = `
Available tools (use only these, with only these args):
- get_user_insights {focus?: "richest"|"newest"|"most_active"|"inactive"|"tier_split"|"all"} - ranked member analytics.
- get_member_count {} - the total number of members only; prefer this over get_user_insights for count questions.
- get_flagged_messages {} - open moderation queue.
- get_safety_signals {} - app-detected safety events.
- get_pricing {} - coin packages, multipliers, cosmetics.
- get_audit_log {} - recent admin audit log.
- set_coin_packages {packages: [{id, title, coins, bonus_coins, price_kes, price_usd, tag?}]} - REPLACE the price list (psychological pricing rules apply).
- set_cosmetic_price {item_id, price_coins?, is_active?} - change one cosmetic.
- adjust_coins {targets: [user_id,...], delta: integer, reason} - grant (positive) or deduct (negative) coins; max 10000 per step, max 5 targets.
- set_user_status {targets: [user_id,...], status: "ACTIVE"|"SUSPENDED"|"BANNED", reason?, duration_hours?} - max 5 targets.
- set_user_tier {targets: [user_id,...], tier: "FREE"|"SUPPORTER"|"PREMIUM"} - max 5 targets.
- gift_artifact {targets: [user_id,...], artifact_name: "Luminescent Moon"|"Golden Blossom"|"Rose Obelisk"|"Dual Eclipse"|"Celestial Tiara", artifact_rarity?}
- send_notice {targets: [user_id,...] or audience: "ALL"|"FREE"|"SUPPORTER"|"PREMIUM", title, body} - official CycleCare team notice; max 25 individual targets.
- set_guides_enabled {enabled: boolean} - Community Guides kill switch.
- update_guide {persona_id, daily_post_cap?, is_active?}
- moderate_message {targets: [message_id,...], action: "DISMISS"|"REMOVE"|"RESTORE"} - max 10 targets.`;

function fnv1a(input: string): number {
  let hash = 0x811c9dc5;
  for (let i = 0; i < input.length; i++) { hash ^= input.charCodeAt(i); hash = Math.imul(hash, 0x01000193); }
  return hash >>> 0;
}

type ProviderOutcome = { text: string | null; errorKind: string | null };

function httpErrorKind(status: number): string {
  return status === 429 ? "http_429" : status >= 500 ? "http_5xx" : "http_4xx";
}

async function callProvider(provider: ProviderId, apiKey: string, system: string, user: string): Promise<ProviderOutcome> {
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), 25_000);
  try {
    if (provider === "GEMINI") {
      const model = Deno.env.get("GEMINI_MODEL") || "gemini-3.6-flash";
      const res = await fetch(`https://generativelanguage.googleapis.com/v1beta/models/${encodeURIComponent(model)}:generateContent`, {
        method: "POST", headers: { "Content-Type": "application/json", "x-goog-api-key": apiKey }, signal: controller.signal,
        body: JSON.stringify({ systemInstruction: { parts: [{ text: system }] }, contents: [{ role: "user", parts: [{ text: user }] }], generationConfig: { temperature: 0.2, maxOutputTokens: 1500 } }),
      });
      if (!res.ok) return { text: null, errorKind: httpErrorKind(res.status) };
      const data = await res.json();
      const text = data?.candidates?.[0]?.content?.parts?.map((p: { text?: unknown }) => typeof p?.text === "string" ? p.text : "").join("")?.trim() || null;
      return { text, errorKind: text ? null : "empty" };
    }
    const endpoint = provider === "COHERE" ? "https://api.cohere.com/v2/chat" : "https://openrouter.ai/api/v1/chat/completions";
    const model = provider === "COHERE" ? "command-a-03-2025" : "openai/gpt-4.1-mini";
    const res = await fetch(endpoint, {
      method: "POST", headers: { "Content-Type": "application/json", Authorization: `Bearer ${apiKey}` }, signal: controller.signal,
      body: JSON.stringify({ model, messages: [{ role: "system", content: system }, { role: "user", content: user }], temperature: 0.2, max_tokens: 1500,
        ...(provider === "OPENROUTER" ? { provider: { only: ["openai"], allow_fallbacks: false, data_collection: "deny", zdr: true } } : {}) }),
    });
    if (!res.ok) return { text: null, errorKind: httpErrorKind(res.status) };
    const data = await res.json();
    const raw = provider === "COHERE" ? data?.message?.content : data?.choices?.[0]?.message?.content;
    const joined = Array.isArray(raw) ? raw.map((c: { text?: unknown }) => typeof c?.text === "string" ? c.text : "").join("") : raw;
    const text = typeof joined === "string" && joined.trim() ? joined.trim() : null;
    return { text, errorKind: text ? null : "empty" };
  } catch { return { text: null, errorKind: "network" }; } finally { clearTimeout(timer); }
}

type PlannerResult =
  | { kind: "steps"; plan: any; provider: string }
  | { kind: "chat" | "clarify"; text: string; provider: string };

async function planWithProviders(admin: any, userId: string, system: string, user: string, promptVersion: string): Promise<PlannerResult | null> {
  const keys: Record<ProviderId, string | undefined> = { GEMINI: Deno.env.get("GEMINI_API_KEY"), COHERE: Deno.env.get("COHERE_API_KEY"), OPENROUTER: Deno.env.get("OPENROUTER_API_KEY") };
  const configured = (Object.keys(keys) as ProviderId[]).filter((p) => isConfiguredSecret(keys[p]));
  if (configured.length === 0) return null;
  const start = (fnv1a(userId) + Math.floor(Date.now() / 600_000)) % configured.length;
  for (const provider of configured.map((_, i) => configured[(start + i) % configured.length])) {
    const attemptStart = Date.now();
    const { text, errorKind } = await callProvider(provider, keys[provider]!, system, user);
    const logFailure = (kind: string) => void recordAiRun(admin, {
      agent: AGENT_ADMIN_COMMAND, userId, provider, promptVersion, status: "failed",
      errorKind: kind, latencyMs: Date.now() - attemptStart });
    if (!text) { logFailure(errorKind ?? "empty"); continue; }
    const cleaned = text.replace(/```[a-z]*\n?/gi, "").trim();
    const match = cleaned.match(/\{[\s\S]*\}/);
    if (!match) { logFailure("invalid_schema"); continue; }
    try {
      const parsed = JSON.parse(match[0]);
      if (parsed && Array.isArray(parsed.steps) && parsed.steps.length > 0) return { kind: "steps", plan: parsed, provider };
      if (parsed && typeof parsed.chat === "string" && parsed.chat.trim()) return { kind: "chat", text: parsed.chat.trim(), provider };
      if (parsed && typeof parsed.clarify === "string" && parsed.clarify.trim()) return { kind: "clarify", text: parsed.clarify.trim(), provider };
    } catch { /* fall through to schema failure */ }
    logFailure("invalid_schema");
  }
  return null;
}


function namedRecipient(text: string): string | null {
  const coin=parseCoinRequest(text);
  if(coin)return coin.query;
  const match = text.match(/\b(?:give|grant|gift|award|credit|deduct(?:\s+from)?|ban|suspend)\s+(.+?)(?=\s+\d|\s+(?:some|coins|a gift|an artifact)\b|[.!?]|$)/i);
  const name = match?.[1]?.trim();
  if (!name || name.includes("@") || /^(?:the |them$|those |all |me$|myself$)/i.test(name) || name.length>100) return null;
  return name;
}
async function checkNamedRecipient(userClient: any, text: string): Promise<{ query?:string; candidates:any[]; clarification?:string }> {
  const query = namedRecipient(text);
  if (!query) return {candidates:[]};
  const { data, error } = await userClient.rpc("admin_find_recipients",{p_query:query});
  if (error || !Array.isArray(data)) return {query,candidates:[],clarification:"I couldn't check the member list. No change has been made."};
  if (!data.length) return {query,candidates:[],clarification:`I couldn't find a member matching "${query}". Please give their full name or email. No change has been made.`};
  if (data.length>1) return {query,candidates:data,clarification:`Which member did you mean? No change has been made.\n${data.map((p:any,i:number)=>`${i+1}. ${p.display_name} (${p.email})`).join("\n")}\nPlease reply with their email.`};
  return {query,candidates:data};
}

async function recipientContext(userClient: any, text: string): Promise<string> {
  const emails = [...new Set(text.match(/[A-Z0-9._%+-]+@[A-Z0-9.-]+\.[A-Z]{2,}/gi) ?? [])].slice(-5);
  const found = [];
  for (const email of emails) {
    const { data, error } = await userClient.rpc("admin_resolve_recipient", {p_email:email});
    if (!error && data?.user_id) found.push(data);
  }
  return JSON.stringify(found);
}

// Resolve explicit email references through trusted account records, not the model.
async function bindRecipients(userClient: any, steps: any[], context: string, rewrite: boolean): Promise<string[]> {
  const resolvedEmails: string[] = [];
  const latest = context.split("\n").filter(Boolean)[0] ?? "";
  const named = await checkNamedRecipient(userClient, latest);
  if (named.clarification) throw new Error(named.clarification);
  const emails = [...new Set((context.match(/[A-Z0-9._%+-]+@[A-Z0-9.-]+\.[A-Z]{2,}/gi) ?? []).map((x) => x.toLowerCase()))];
  for (const step of steps) {
    if (!Array.isArray(step.args?.targets) || !step.args.targets.length || step.tool === "moderate_message") continue;
    const ownEmails = [...new Set((String(step.label ?? "").match(/[A-Z0-9._%+-]+@[A-Z0-9.-]+\.[A-Z]{2,}/gi) ?? []).map((x) => x.toLowerCase()))];
    if (ownEmails.some((email) => !emails.includes(email))) throw new Error("The proposed email was not supplied in this conversation. Please name the recipient again.");
    const latestEmails = latest.match(/[A-Z0-9._%+-]+@[A-Z0-9.-]+\.[A-Z]{2,}/gi) ?? [];
    const references = latestEmails.length ? latestEmails.map(x=>x.toLowerCase()) : ownEmails.length ? ownEmails : emails;
    if (!references.length) {
      if (named.candidates.length === 1) {
        const person = named.candidates[0];
        if (!rewrite && step.args.targets[0] !== person.user_id) throw new Error("The recipient could not be confirmed. Prepare a new plan.");
        if (step.args.targets.length !== 1) throw new Error("Please select one confirmed recipient at a time.");
        step.args.targets = [person.user_id];
        if (rewrite) {step.label += ` (confirmed account: ${person.display_name}, ${person.email})`;resolvedEmails.push(person.email);}
        continue;
      }
      const explicitIds = context.match(/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}/gi) ?? [];
      if (!step.args.targets.every((id:string)=>explicitIds.includes(id))) throw new Error("Please give the recipient's full name or email so I can confirm the right account before preparing a change.");
      continue;
    }
    if (references.length !== 1 || step.args.targets.length !== 1) throw new Error("Please prepare one email-addressed member action at a time.");
    const { data, error } = await userClient.rpc("admin_resolve_recipient", { p_email: references[0] });
    if (error || !data?.user_id) throw new Error("I couldn't confirm the account for that email. No change was made.");
    if (rewrite) {
      step.args.targets = [data.user_id];
      step.label = `${step.label} (confirmed account: ${data.email})`;
      resolvedEmails.push(data.email);
    } else if (step.args.targets[0] !== data.user_id) throw new Error("The planned recipient does not match that email. Prepare a new plan; no change was made.");
  }
  return resolvedEmails;
}

function summarizePlan(plan: any): string {
  return (plan.steps || []).map((s: any) => s?.label || s?.tool).filter(Boolean).join(" -> ");
}


// Conversation history for the Admin Command chat thread. Service-role writes
// are always scoped to the verified admin's own id (RLS also enforces this for
// user-context clients; the service client bypasses RLS, so filter explicitly).

function resultSummaryText(outcome: { status: string; steps: unknown[] }): string {
  return formatCommandOutcome(outcome);
}

// Post-execution read-back for writes: confirms the change actually landed.
// Returns true/false when verifiable, null when there is nothing cheap to check.
async function verifyWrite(admin: any, tool: string, args: any): Promise<boolean | null> {
  try {
    const targets: string[] = Array.isArray(args?.targets) ? args.targets.slice(0, 25).map(String) : [];
    switch (tool) {
      case "set_coin_packages": {
        const { data } = await admin.from("app_config").select("value").eq("key", "coin_packages").maybeSingle();
        const live = Array.isArray(data?.value) ? data.value : [];
        const fields = ["id", "title", "coins", "bonus_coins", "price_kes", "price_usd", "tag"];
        return live.length === (args?.packages ?? []).length && live.every((row: any, i: number) => fields.every((key) => (row[key] ?? null) === (args.packages[i][key] ?? null)));
      }
      case "set_cosmetic_price": {
        const { data } = await admin.from("cosmetic_catalog").select("price_coins,is_active").eq("item_id", String(args?.item_id ?? "")).maybeSingle();
        if (!data) return false;
        if (typeof args?.price_coins === "number" && data.price_coins !== Math.round(args.price_coins)) return false;
        if (typeof args?.is_active === "boolean" && data.is_active !== args.is_active) return false;
        return true;
      }
      case "set_user_status": {
        if (targets.length === 0) return null;
        const { data } = await admin.from("profiles").select("status").in("user_id", targets);
        const want = String(args?.status ?? "").toUpperCase();
        return Array.isArray(data) && data.length >= targets.length && data.every((r: any) => String(r?.status ?? "").toUpperCase() === want);
      }
      case "set_user_tier": {
        if (targets.length === 0) return null;
        const { data } = await admin.from("economy_profiles").select("tier").in("user_id", targets);
        const want = String(args?.tier ?? "").toUpperCase();
        return Array.isArray(data) && data.length >= targets.length && data.every((r: any) => String(r?.tier ?? "").toUpperCase() === want);
      }
      default:
        return null;
    }
  } catch { return null; }
}

async function attachMemberNames(admin: any, result: any): Promise<any> {
  const rows = Array.isArray(result) ? result : Object.values(result ?? {}).flatMap((v: any) => Array.isArray(v) ? v : []);
  const ids = [...new Set(rows.map((r: any) => r?.user_id).filter(Boolean))];
  if (!ids.length) return result;
  const { data: profiles } = await admin.from("profiles").select("user_id,display_name").in("user_id", ids);
  const { data: personas } = await admin.from("personas").select("persona_id,display_name").in("persona_id", ids.filter((id: any) => String(id).startsWith("persona:")).map((id: any) => String(id).slice(8)));
  const names = new Map((profiles ?? []).map((p: any) => [p.user_id, p.display_name]));
  for (const p of personas ?? []) names.set(`persona:${p.persona_id}`, p.display_name);
  for (const row of rows) if (row?.user_id && names.get(row.user_id)) row.display_name = names.get(row.user_id);
  return result;
}

const KIND_CACHE: Record<string, string> = {};
async function toolKind(admin: any, tool: string): Promise<string> {
  if (KIND_CACHE[tool]) return KIND_CACHE[tool];
  const { data } = await admin.from("ai_tool_registry").select("kind").eq("tool_name", tool).maybeSingle();
  KIND_CACHE[tool] = data?.kind ?? "RISKY";
  return KIND_CACHE[tool];
}

async function executeRun(admin: any, userClient: any, runId: string, adminId: string): Promise<{ status: string; steps: unknown[] }> {
  const { data: run } = await admin.from("admin_command_runs").select("*").eq("id", runId).maybeSingle();
  if (!run || run.admin_user_id !== adminId) return { status: "not_found", steps: [] };
  if (run.status !== "approved") return { status: run.status, steps: run.step_results ?? [] };
  if (!Number.isFinite(Date.parse(run.created_at)) || Date.now()-Date.parse(run.created_at)>30*60_000) {
    await admin.from("admin_command_runs").update({status:"rejected",error:"This plan expired. Ask again."}).eq("id",runId).eq("status","approved");
    return {status:"expired",steps:[]};
  }
  const { data: claimed, error: claimError } = await admin.from("admin_command_runs")
    .update({ status: "executing" }).eq("id", runId).eq("status", "approved").select("id").maybeSingle();
  if (claimError || !claimed) return { status: "already_started", steps: [] };
  const steps: any[] = run.plan?.steps ?? [];
  try { await bindRecipients(userClient, steps, run.prompt, false); }
  catch {
    await admin.from("admin_command_runs").update({status:"failed", error:"Recipient identity could not be confirmed"}).eq("id", runId);
    return { status:"failed", steps:[{ok:false,label:"Recipient identity check"}] };
  }
  const results: unknown[] = [];
  // Independent READ steps run in parallel; WRITE/RISKY run sequentially after.
  const reads: { index: number; step: any }[] = [];
  const writes: { index: number; step: any }[] = [];
  for (let i = 0; i < steps.length; i++) {
    const kind = await toolKind(admin, String(steps[i]?.tool ?? ""));
    (kind === "READ" ? reads : writes).push({ index: i, step: steps[i] });
  }
  const includeGuides = /\binclude\s+(?:community\s+)?guides\b/i.test(run.prompt);
  const readResults = await Promise.all(reads.map(({ step }) => executeStep(userClient, String(step.tool), step.args ?? {}, includeGuides)));
  for (let i = 0; i < reads.length; i++) {
    if (readResults[i].ok && ["get_user_insights", "get_safety_signals"].includes(String(reads[i].step.tool))) {
      readResults[i].result = await attachMemberNames(admin, readResults[i].result);
    }
  }
  const indexed: Record<number, unknown> = {};
  reads.forEach(({ index, step }, i) => { indexed[index] = { tool: step.tool, label: step.label ?? step.tool, kind: "READ", ...readResults[i] }; });
  for (const { index, step } of writes) {
    const out = await executeStep(userClient, String(step.tool), step.args ?? {});
    let verified = out.ok ? await verifyWrite(admin, String(step.tool), step.args ?? {}) : null;
    if (out.ok && step.tool === "adjust_coins") {
      const receipts = Array.isArray(out.result) ? out.result as any[] : [];
      const balances: any[] = [];
      verified = receipts.length > 0;
      for (const receipt of receipts) {
        const expected = receipt.result?.details?.new_balance;
        const { data, error } = await admin.from("economy_profiles").select("user_id,coin_balance").eq("user_id", receipt.target).maybeSingle();
        const confirmed = !error && data && typeof expected === "number" && data.coin_balance === expected;
        if (!confirmed) verified = false;
        balances.push({target:receipt.target,balance:confirmed ? data.coin_balance : null,confirmed:!!confirmed});
      }
      (out as any).balances = balances;
    }
    indexed[index] = { tool: step.tool, label: step.label ?? step.tool, kind: "WRITE", verified, ...out };
  }
  for (let i = 0; i < steps.length; i++) results.push(indexed[i]);
  const failed = (results as any[]).some((r) => r?.ok !== true);
  const unconfirmed = (results as any[]).some((r) => r?.kind === "WRITE" && r?.ok === true && r?.verified !== true);
  await admin.from("admin_command_runs").update({
    status: failed || unconfirmed ? "failed" : "done", step_results: results,
    error: failed ? "One or more steps failed" : unconfirmed ? "Accepted but not confirmed. Do not repeat; review the current state." : null, completed_at: new Date().toISOString() }).eq("id", runId);
  await admin.from("admin_audit_logs").insert({
    admin_user_id: adminId, admin_action: failed || unconfirmed ? "ADMIN_COMMAND_FAILED" : "ADMIN_COMMAND_EXECUTED",
    target_user_id: "admin_command", details: { run_id: runId, prompt: run.prompt, steps: (results as any[]).map((r) => ({ tool: r.tool, ok: r.ok })) }, performed_by: adminId });
  return { status: failed ? "failed" : unconfirmed ? "unconfirmed" : "done", steps: results };
}

function buildSuggestions(insights: any): { label: string; prompt: string }[] {
  const chips: { label: string; prompt: string }[] = [];
  const richest = insights?.richest?.[0]; const active = insights?.most_active?.[0]; const inactive = insights?.inactive?.[0]; const newest = insights?.newest?.[0];
  if (active?.user_id) chips.push({ label: "Reward the most committed member", prompt: `Check the most active member and gift ${active.user_id} 500 coins with a thank-you notice` });
  if (inactive?.user_id) chips.push({ label: "Win back inactive members", prompt: "Show me the most inactive members and send them a warm CycleCare team notice inviting them back" });
  if (richest?.user_id) chips.push({ label: "Richest members", prompt: "Show me the richest users by coin balance" });
  if (newest?.user_id) chips.push({ label: "Newest members", prompt: "Show me the newest users and their tier split" });
  chips.push({ label: "Pricing check", prompt: "Show me the current coin packages and cosmetic prices" });
  chips.push({ label: "Moderation queue", prompt: "Show me the flagged messages waiting for review" });
  chips.push({ label: "Premium vs free", prompt: "Compare premium vs free members and show the tier split" });
  return chips.slice(0, 6);
}

serve(async (req: Request) => {
  if (req.method === "OPTIONS") return jsonResponse({ ok: true });
  if (req.method !== "POST") return jsonResponse({ error: "Method not allowed" }, 405);
  const supabaseUrl = Deno.env.get("SUPABASE_URL")?.replace(/\/$/, "");
  const secretKey = supabaseSecretKey();
  const anonKey = Deno.env.get("SUPABASE_ANON_KEY");
  const authHeader = req.headers.get("Authorization") ?? "";
  if (!supabaseUrl || !secretKey || !anonKey) return jsonResponse({ error: "Backend is not configured" }, 503);
  if (!authHeader.startsWith("Bearer ")) return jsonResponse({ error: "Sign in is required" }, 401);

  const userClient = createClient(supabaseUrl, anonKey, { auth: { autoRefreshToken: false, persistSession: false }, global: { headers: { Authorization: authHeader } } });
  const { data: userData } = await userClient.auth.getUser();
  const userId = userData?.user?.id;
  if (!userId) return jsonResponse({ error: "Sign in is required" }, 401);
  const admin = createClient(supabaseUrl, secretKey, { auth: { autoRefreshToken: false, persistSession: false } });
  const { data: adminRole } = await admin.from("cyclecare_admin_roles").select("user_id").eq("user_id", userId).maybeSingle();
  if (!adminRole?.user_id) return jsonResponse({ error: "Administrator access required" }, 403);

  const body = await req.json().catch(() => ({}));
  const action = typeof body?.action === "string" ? body.action : "plan";

  if (action === "suggest") {
    const { data: insights } = await userClient.rpc("admin_member_insights", {});
    return jsonResponse({ success: true, suggestions: buildSuggestions(insights ?? {}) });
  }
  if (action === "list") {
    const { data, error } = await userClient.rpc("admin_command_list", { p_limit: 20 });
    return error ? jsonResponse({ error: "History is unavailable" }, 500) : jsonResponse({ success: true, runs: data });
  }
  if (action === "decide") {
    const { data, error } = await userClient.rpc("admin_command_decide", { p_run_id: String(body?.run_id ?? ""), p_approve: body?.approve === true });
    if (error) return jsonResponse({ error: error.message }, 400);
    await admin.from("admin_coin_dialog_state").delete().eq("admin_user_id",userId).filter("state->>run_id","eq",String(body.run_id));
    const { data: planMessages } = await admin.from("admin_command_messages").select("id,meta")
      .eq("admin_user_id", userId).eq("run_id", String(body.run_id)).eq("kind", "plan");
    for (const message of planMessages ?? []) await admin.from("admin_command_messages")
      .update({ meta: { ...message.meta, decided: true } }).eq("id", message.id).eq("admin_user_id", userId);
    if (body?.approve === true && body?.execute === true) {
      const outcome = await executeRun(admin, userClient, String(body.run_id), userId);
      await persistMessage(admin, userId, "assistant", "result", resultSummaryText(outcome), String(body.run_id), { status: outcome.status, steps: outcome.steps });
      return jsonResponse({ success: outcome.status === "done", run_id: body.run_id, ...outcome });
    }
    return jsonResponse({ success: true, ...(data as object) });
  }
  if (action === "execute") {
    const outcome = await executeRun(admin, userClient, String(body?.run_id ?? ""), userId);
    return jsonResponse({ success: outcome.status === "done", run_id: body?.run_id, ...outcome });
  }

  if (action === "history") {
    const { data, error } = await admin.from("admin_command_messages")
      .select("id,role,kind,content,run_id,meta,created_at")
      .eq("admin_user_id", userId).order("created_at", { ascending: false }).limit(60);
    return error ? jsonResponse({ error: "History is unavailable" }, 500)
      : jsonResponse({ success: true, messages: (data ?? []).reverse() });
  }

  if (action === "message") {
    const chatPrompt = typeof body?.prompt === "string" ? body.prompt.trim().slice(0, 2000) : "";
    if (!chatPrompt) return jsonResponse({ error: "A prompt is required" }, 400);
    // Fair-use limit: 30 messages per rolling 10 minutes per admin.
    const since = new Date(Date.now() - 10 * 60_000).toISOString();
    const { count } = await admin.from("admin_command_messages")
      .select("id", { count: "exact", head: true })
      .eq("admin_user_id", userId).eq("role", "user").gte("created_at", since);
    if ((count ?? 0) >= 30) return jsonResponse({ error: "Too many messages in a short time. Give it a few minutes and try again." }, 429);
    await persistMessage(admin, userId, "user", "chat", chatPrompt);
    try {
      if (mixesCoinWithOtherAction(chatPrompt)) {
        const msg = "That asks for more than one change. Send them one at a time so each change gets its own approval; nothing has been done yet.";
        await persistMessage(admin, userId, "assistant", "chat", msg);
        return jsonResponse({ success: true, type: "clarify", message: msg });
      }
      const dialog=await coinDialog(admin,userClient,userId,chatPrompt);
      if(dialog)return jsonResponse(dialog);
    } catch {return jsonResponse({error:"I couldn't save the pending request. No change was made."},503);}
    if (asksMemberCount(chatPrompt)) {
      const { data: mc, error: mcErr } = await userClient.rpc("admin_member_count", {});
      const msg = mcErr || typeof (mc as any)?.total !== "number" ? "I couldn't count the members just now. No change was made." : `You have ${(mc as any).total} members.`;
      await persistMessage(admin, userId, "assistant", "chat", msg);
      return jsonResponse({ success: true, type: "chat", message: msg });
    }
    const joinedWindow = asksJoinedCount(chatPrompt);
    if (joinedWindow) {
      const now = Date.now();
      let since: string; let span: string;
      if (joinedWindow === "today") {
        const eat = new Date(now + 3 * 3600_000);
        since = new Date(Date.UTC(eat.getUTCFullYear(), eat.getUTCMonth(), eat.getUTCDate()) - 3 * 3600_000).toISOString();
        span = "since midnight";
      } else if (joinedWindow === "this week") {
        since = new Date(now - 7 * 86400_000).toISOString(); span = "in the last 7 days";
      } else {
        since = new Date(now - 30 * 86400_000).toISOString(); span = "in the last 30 days";
      }
      const { data: jc, error: jcErr } = await userClient.rpc("admin_member_count", { p_since: since });
      const msg = jcErr || typeof (jc as any)?.total !== "number" ? "I couldn't count the members just now. No change was made." : `${(jc as any).total} members joined ${span}.`;
      await persistMessage(admin, userId, "assistant", "chat", msg);
      return jsonResponse({ success: true, type: "chat", message: msg });
    }
    const tierAsk = asksTierCount(chatPrompt);
    if (tierAsk) {
      const { data: tc, error: tcErr } = await userClient.rpc("admin_member_count", { p_tier: tierAsk });
      const msg = tcErr || typeof (tc as any)?.total !== "number" ? "I couldn't count the members just now. No change was made." : `You have ${(tc as any).total} ${tierAsk} members.`;
      await persistMessage(admin, userId, "assistant", "chat", msg);
      return jsonResponse({ success: true, type: "chat", message: msg });
    }
    if (asksTopBalance(chatPrompt)) {
      const { data: ins, error: insErr } = await userClient.rpc("admin_member_insights_scoped", { p_include_guides: false });
      const rows = Array.isArray((ins as any)?.richest) ? (ins as any).richest.slice(0, 3) : [];
      const msg = insErr || !rows.length ? "I couldn't check the balances just now." : formatCommandStep({ ok: true, kind: "READ", tool: "get_user_insights", result: { richest: rows } });
      await persistMessage(admin, userId, "assistant", "chat", msg);
      return jsonResponse({ success: true, type: "chat", message: msg });
    }
    const nameCheck = await checkNamedRecipient(userClient, chatPrompt);
    if (nameCheck.clarification) {
      await persistMessage(admin,userId,"assistant","chat",nameCheck.clarification);
      return jsonResponse({success:true,type:"clarify",message:nameCheck.clarification});
    }
    const { data: recent } = await admin.from("admin_command_messages")
      .select("role,content").eq("admin_user_id", userId)
      .order("created_at", { ascending: false }).limit(24);
    const convo = (recent ?? []).reverse()
      .map((m: any) => `${m.role === "user" ? "Admin" : "Assistant"}: ${String(m.content ?? "").slice(0, 800)}`).join("\n");
    const recipientText = (recent ?? []).filter((m: any) => m.role === "user").map((m: any) => m.content).join("\n") + "\n" + chatPrompt;
    const recipients = await recipientContext(userClient, recipientText);
    const nameCandidates = JSON.stringify(nameCheck.candidates);
    const { data: insights } = await userClient.rpc("admin_member_insights", {});
    const planner = await loadAgentPrompt(admin, AGENT_ADMIN_COMMAND, ADMIN_PLANNER_PROMPT_V2);
    const userMsg = `${TOOL_CATALOG}\n\n<live-data>\n${JSON.stringify(insights ?? {}).slice(0, 6000)}\nVerified email recipients: ${recipients}\nVerified name candidates: ${nameCandidates}\n</live-data>\n\n<conversation>\n${convo}\n</conversation>\n\nAdministrator's latest message: ${chatPrompt}\n\nReply with the JSON now.`;
    const started = Date.now();
    const planned = await planWithProviders(admin, userId, planner.prompt, userMsg, planner.version);
    if (!planned) {
      void recordAiRun(admin, { agent: AGENT_ADMIN_COMMAND, userId, promptVersion: planner.version, status: "failed", errorKind: "planner_unavailable", latencyMs: Date.now() - started });
      const sorry = "I'm having trouble putting that together right now. Give me a moment and try again.";
      await persistMessage(admin, userId, "assistant", "chat", sorry);
      return jsonResponse({ success: false, type: "chat", message: sorry }, 503);
    }
    if (planned.kind === "chat" || planned.kind === "clarify") {
      void recordAiRun(admin, { agent: AGENT_ADMIN_COMMAND, userId, provider: planned.provider, promptVersion: planner.version, status: planned.kind, latencyMs: Date.now() - started });
      await persistMessage(admin, userId, "assistant", "chat", planned.text);
      return jsonResponse({ success: true, type: planned.kind, message: planned.text });
    }
    // Action/analysis plan: run it through the same policy-gated propose flow.
    const steps = (planned.plan.steps as any[]).slice(0, 15).map((st: any) => ({
      tool: String(st?.tool ?? ""), label: String(st?.label ?? st?.tool ?? "").slice(0, 140), args: (st?.args && typeof st.args === "object") ? st.args : {} }));
    let resolvedEmails: string[] = [];
    try { resolvedEmails = await bindRecipients(userClient, steps, chatPrompt + "\n" + recipientText, true); }
    catch (error) { return jsonResponse({error: error instanceof Error ? error.message : "Recipient could not be confirmed"}, 422); }
    const { data: proposed, error: proposeError } = await userClient.rpc("admin_command_propose", { p_prompt: chatPrompt + (resolvedEmails.length ? "\nConfirmed recipients: " + resolvedEmails.join(", ") : ""), p_plan: { steps } });
    if (proposeError) {
      void recordAiRun(admin, { agent: AGENT_ADMIN_COMMAND, userId, provider: planned.provider, promptVersion: planner.version, status: "rejected", errorKind: "policy_gate", latencyMs: Date.now() - started, meta: { reason: proposeError.message } });
      await persistMessage(admin, userId, "assistant", "chat", `I couldn't prepare that safely: ${proposeError.message}`);
      return jsonResponse({ error: proposeError.message, plan_rejected: true }, 422);
    }
    void recordAiRun(admin, { agent: AGENT_ADMIN_COMMAND, userId, provider: planned.provider, promptVersion: planner.version, status: "planned", latencyMs: Date.now() - started, meta: { steps: steps.length } });
    const runId = (proposed as any)?.run_id;
    const summary = summarizePlan({ steps });
    const needsApproval = (proposed as any)?.requires_approval !== false;
    await persistMessage(admin, userId, "assistant", "plan", summary, runId, { steps, requires_approval: needsApproval });
    if (!needsApproval) {
      const outcome = await executeRun(admin, userClient, runId, userId);
      await persistMessage(admin, userId, "assistant", "result", resultSummaryText(outcome), runId, { status: outcome.status, steps: outcome.steps });
      return jsonResponse({ success: outcome.status === "done", type: "plan", run_id: runId, requires_approval: false, summary, ...outcome });
    }
    return jsonResponse({ success: true, type: "plan", run_id: runId, requires_approval: true, summary, plan: { steps } });
  }

  // action === "plan"
  const prompt = typeof body?.prompt === "string" ? body.prompt.trim().slice(0, 2000) : "";
  if (!prompt) return jsonResponse({ error: "A prompt is required" }, 400);
  const nameCheck = await checkNamedRecipient(userClient,prompt);
  if (nameCheck.clarification) return jsonResponse({success:true,type:"clarify",message:nameCheck.clarification});
  const recipients = await recipientContext(userClient, prompt);
  const { data: insights } = await userClient.rpc("admin_member_insights", {});
  const planner = await loadAgentPrompt(admin, AGENT_ADMIN_COMMAND, ADMIN_PLANNER_PROMPT_V2);
  const userMsg = `${TOOL_CATALOG}\n\n<live-data>\n${JSON.stringify(insights ?? {}).slice(0, 6000)}\nVerified email recipients: ${recipients}\nVerified name candidates: ${JSON.stringify(nameCheck.candidates)}\n</live-data>\n\n<conversation>\n(no prior messages)\n</conversation>\n\nAdministrator's latest message: ${prompt}\n\nReply with the JSON now.`;
  const started = Date.now();
  const planned = await planWithProviders(admin, userId, planner.prompt, userMsg, planner.version);
  if (!planned) {
    void recordAiRun(admin, { agent: AGENT_ADMIN_COMMAND, userId, promptVersion: planner.version, status: "failed", errorKind: "planner_unavailable", latencyMs: Date.now() - started });
    return jsonResponse({ error: "The planner is temporarily unavailable. Try again in a moment." }, 503);
  }
  if (planned.kind !== "steps") {
    void recordAiRun(admin, { agent: AGENT_ADMIN_COMMAND, userId, provider: planned.provider, promptVersion: planner.version, status: planned.kind, latencyMs: Date.now() - started });
    await persistMessage(admin, userId, "user", "chat", prompt);
    await persistMessage(admin, userId, "assistant", "chat", planned.text);
    return jsonResponse({ success: true, type: planned.kind, message: planned.text });
  }
  const steps = (planned.plan.steps as any[]).slice(0, 15).map((s: any) => ({
    tool: String(s?.tool ?? ""), label: String(s?.label ?? s?.tool ?? "").slice(0, 140), args: (s?.args && typeof s.args === "object") ? s.args : {} }));
  let resolvedEmails: string[] = [];
  try { resolvedEmails = await bindRecipients(userClient, steps, prompt, true); }
  catch (error) { return jsonResponse({error: error instanceof Error ? error.message : "Recipient could not be confirmed"}, 422); }
  const { data: proposed, error: proposeError } = await userClient.rpc("admin_command_propose", { p_prompt: prompt + (resolvedEmails.length ? "\nConfirmed recipients: " + resolvedEmails.join(", ") : ""), p_plan: { steps } });
  if (proposeError) {
    void recordAiRun(admin, { agent: AGENT_ADMIN_COMMAND, userId, provider: planned.provider, promptVersion: planner.version, status: "rejected", errorKind: "policy_gate", latencyMs: Date.now() - started, meta: { reason: proposeError.message } });
    return jsonResponse({ error: proposeError.message, plan_rejected: true }, 422);
  }
  void recordAiRun(admin, { agent: AGENT_ADMIN_COMMAND, userId, provider: planned.provider, promptVersion: planner.version, status: "planned", latencyMs: Date.now() - started, meta: { steps: steps.length } });
  const runId = (proposed as any)?.run_id;
  if ((proposed as any)?.requires_approval === false) {
    const outcome = await executeRun(admin, userClient, runId, userId);
    return jsonResponse({ success: outcome.status === "done", run_id: runId, requires_approval: false, summary: summarizePlan({ steps }), ...outcome });
  }
  return jsonResponse({ success: true, run_id: runId, requires_approval: true, summary: summarizePlan({ steps }), plan: { steps } });
});
