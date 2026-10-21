import { serve } from "https://deno.land/std@0.224.0/http/server.ts";
import { corsHeaders } from "../_shared/cors.ts";
import { paymentRoutingBlockResponse } from "../_shared/payment-routing.ts";

serve(async (req: Request) => {
  if (req.method === "OPTIONS") {
    return new Response("ok", { headers: corsHeaders });
  }
  // Routing registry gate (Crypto -> OKX, regions/currency enforced).
  const body = req.method === "POST" ? await req.json().catch(() => ({})) as Record<string, unknown> : {};
  const blocked = await paymentRoutingBlockResponse("crypto",
    typeof body?.region === "string" ? body.region : null,
    typeof body?.currency === "string" ? body.currency : "USD");
  if (blocked) return blocked;
  return new Response(JSON.stringify({
    success: false,
    error: "Crypto checkout is unavailable until an authenticated merchant integration is configured.",
  }), {
    status: 503,
    headers: { ...corsHeaders, "Content-Type": "application/json", "Cache-Control": "no-store" },
  });
});
