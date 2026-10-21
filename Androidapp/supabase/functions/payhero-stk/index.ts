import { serve } from "https://deno.land/std@0.224.0/http/server.ts";
import { corsHeaders } from "../_shared/cors.ts";
import { authenticatedUserId, jsonResponse } from "../_shared/payments.ts";
import { paymentRoutingBlockResponse } from "../_shared/payment-routing.ts";

// No provider fetch, transaction insertion, or debit is permitted until the
// independent settlement and reconciliation contract is implemented and tested.
serve(async (req: Request) => {
  if (req.method === "OPTIONS") return new Response("ok", { headers: corsHeaders });
  if (req.method !== "POST") return jsonResponse({ error: "Method not allowed" }, 405);
  try {
    const userId = await authenticatedUserId(req);
    if (!userId) return jsonResponse({ error: "Sign in is required to purchase Cycle Coins." }, 401);
    // Routing registry gate (M-Pesa -> PAYHERO, regions/currency enforced).
    const body = await req.json().catch(() => ({})) as Record<string, unknown>;
    const blocked = await paymentRoutingBlockResponse("mpesa",
      typeof body?.region === "string" ? body.region : null,
      typeof body?.currency === "string" ? body.currency : "KES");
    if (blocked) return blocked;
    return jsonResponse({ error: "M-Pesa checkout is temporarily disabled: independent payment verification is not established." }, 503);
  } catch {
    return jsonResponse({ error: "M-Pesa checkout is unavailable; no payment was initiated." }, 503);
  }
});
