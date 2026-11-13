// Read-back verifiers for every WRITE/RISKY ACI tool. A tool with no entry
// here fails closed: executeRun marks the outcome unconfirmed instead of
// claiming success it could not check.
import { giftAllVerified } from "./admin-command-flow.ts";

export type Verifier = (admin: any, args: any, result: any) => Promise<boolean>;

async function perTarget(admin: any, targets: string[], check: (target: string) => Promise<boolean>): Promise<boolean> {
  if (!targets.length) return false;
  for (const target of targets.slice(0, 25)) {
    if (!(await check(target))) return false;
  }
  return true;
}

export const VERIFIERS: Record<string, Verifier> = {
  async set_coin_packages(admin, args) {
    const { data } = await admin.from("app_config").select("value").eq("key", "coin_packages").maybeSingle();
    const live = Array.isArray(data?.value) ? data.value : [];
    const fields = ["id", "title", "coins", "bonus_coins", "price_kes", "price_usd", "tag"];
    return live.length === (args?.packages ?? []).length && live.every((row: any, i: number) => fields.every((key) => (row[key] ?? null) === (args.packages[i][key] ?? null)));
  },
  async set_cosmetic_price(admin, args) {
    const { data } = await admin.from("cosmetic_catalog").select("price_coins,is_active").eq("item_id", String(args?.item_id ?? "")).maybeSingle();
    if (!data) return false;
    if (typeof args?.price_coins === "number" && data.price_coins !== Math.round(args.price_coins)) return false;
    if (typeof args?.is_active === "boolean" && data.is_active !== args.is_active) return false;
    return true;
  },
  async set_user_status(admin, args) {
    const targets: string[] = Array.isArray(args?.targets) ? args.targets.map(String) : [];
    const want = String(args?.status ?? "").toUpperCase();
    const { data } = await admin.from("profiles").select("status").in("user_id", targets);
    return targets.length > 0 && Array.isArray(data) && data.length >= targets.length && data.every((r: any) => String(r?.status ?? "").toUpperCase() === want);
  },
  async set_user_tier(admin, args) {
    const targets: string[] = Array.isArray(args?.targets) ? args.targets.map(String) : [];
    const want = String(args?.tier ?? "").toUpperCase();
    const { data } = await admin.from("economy_profiles").select("tier").in("user_id", targets);
    return targets.length > 0 && Array.isArray(data) && data.length >= targets.length && data.every((r: any) => String(r?.tier ?? "").toUpperCase() === want);
  },
  async adjust_coins(admin, args, result) {
    const receipts = Array.isArray(result) ? result : [];
    if (receipts.length === 0) return false;
    const balances: any[] = [];
    let ok = true;
    for (const receipt of receipts) {
      const expected = receipt.result?.details?.new_balance;
      const { data, error } = await admin.from("economy_profiles").select("user_id,coin_balance").eq("user_id", receipt.target).maybeSingle();
      const confirmed = !error && data && typeof expected === "number" && data.coin_balance === expected;
      if (!confirmed) ok = false;
      balances.push({ target: receipt.target, balance: confirmed ? data.coin_balance : null, confirmed: !!confirmed });
    }
    if (result && typeof result === "object") (result as any).balances = balances;
    return ok;
  },
  async gift_all_coins(_admin, args, result) { return giftAllVerified(result, args); },
  async deduct_all_coins(_admin, args, result) { return giftAllVerified(result, args); },
  async send_notice(admin, args, result) {
    const id = typeof result?.message_id === "string" ? result.message_id : null;
    if (!id) return false;
    const { data } = await admin.from("in_house_messages").select("title,message,target_audience").eq("id", id).maybeSingle();
    if (!data) return false;
    const wantAudience = typeof args?.audience === "string" ? args.audience.toUpperCase() : "ALL";
    return data.title === String(args?.title ?? "CycleCare Notice")
      && data.message === String(args?.body ?? "")
      && String(data.target_audience ?? "").toUpperCase() === wantAudience;
  },
  async set_guides_enabled(admin, args) {
    const { data } = await admin.from("cyclecare_runtime_config").select("community_personas_enabled").limit(1).maybeSingle();
    return !!data?.community_personas_enabled === (args?.enabled === true);
  },
  async update_guide(admin, args) {
    const { data } = await admin.from("personas").select("daily_post_cap,is_active").eq("persona_id", String(args?.persona_id ?? "")).maybeSingle();
    if (!data) return false;
    if (typeof args?.daily_post_cap === "number" && data.daily_post_cap !== Math.round(args.daily_post_cap)) return false;
    if (typeof args?.is_active === "boolean" && data.is_active !== args.is_active) return false;
    return true;
  },
  async gift_artifact(admin, args) {
    const targets: string[] = Array.isArray(args?.targets) ? args.targets.map(String) : [];
    const name = String(args?.artifact_name ?? "");
    return perTarget(admin, targets, async (target) => {
      const { data } = await admin.from("economy_profiles").select("artifacts").eq("user_id", target).maybeSingle();
      const owned = Array.isArray(data?.artifacts) ? data.artifacts : [];
      return owned.some((a: any) => a?.name === name);
    });
  },
  async moderate_message(admin, args) {
    const targets: string[] = Array.isArray(args?.targets) ? args.targets.map(String) : [];
    const action = String(args?.action ?? "").toUpperCase();
    return perTarget(admin, targets, async (target) => {
      const { data } = await admin.from("chat_messages").select("is_flagged,removed_at").eq("id", target).maybeSingle();
      if (!data) return false;
      if (action === "REMOVE") return data.removed_at !== null;
      if (action === "RESTORE") return data.removed_at === null;
      if (action === "DISMISS") return data.is_flagged === false;
      return false;
    });
  },
};

// null = no verifier for this tool: the caller must fail closed.
export async function runVerifier(admin: any, tool: string, args: any, result: any): Promise<boolean | null> {
  const verifier = VERIFIERS[tool];
  if (!verifier) return null;
  try { return await verifier(admin, args ?? {}, result); } catch { return null; }
}
