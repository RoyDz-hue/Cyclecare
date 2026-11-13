// Payment routing enforcement: every checkout edge function calls this gate
// FIRST. The routing registry (payment_routing_registry table) maps each UI
// option to its real provider, allowed regions and charged currency. A gate
// failure always fails closed - no payment is ever initiated on a gate error.
import { createClient } from "https://esm.sh/@supabase/supabase-js@2.118.0";
import { jsonResponse, supabaseSecretKey } from "./payments.ts";

const BLOCK_MESSAGES: Record<string, string> = {
  region_not_allowed: "This payment option is not available in your region.",
  currency_not_allowed: "This payment option does not support this currency.",
  option_disabled: "This payment option is temporarily unavailable.",
  unknown_option: "Unknown payment option.",
};

// Returns null when the option may proceed, or a Response that must be
// returned to the caller immediately. Region is the client-detected ISO
// country code; currency is the currency the checkout intends to charge.
export async function paymentRoutingBlockResponse(
  optionKey: string,
  region: string | null | undefined,
  currency: string | null | undefined,
): Promise<Response | null> {
  const supabaseUrl = Deno.env.get("SUPABASE_URL")?.replace(/\/$/, "");
  const secretKey = supabaseSecretKey();
  if (!supabaseUrl || !secretKey) {
    return jsonResponse({ error: "Payment routing is unavailable; no payment was initiated." }, 503);
  }
  const admin = createClient(supabaseUrl, secretKey, { auth: { autoRefreshToken: false, persistSession: false } });
  let verdict: any = null;
  try {
    const { data, error } = await admin.rpc("payment_option_allowed", {
      p_option_key: optionKey, p_region: region ?? "", p_currency: currency ?? "",
    });
    if (error) throw error;
    verdict = data;
  } catch {
    return jsonResponse({ error: "Payment routing is unavailable; no payment was initiated." }, 503);
  }
  if (verdict?.allowed === true) return null;
  const reason = typeof verdict?.reason === "string" ? verdict.reason : "option_unavailable";
  const status = reason === "region_not_allowed" || reason === "currency_not_allowed" ? 403 : 503;
  return jsonResponse({ error: BLOCK_MESSAGES[reason] ?? "This payment option is unavailable.", reason }, status);
}
