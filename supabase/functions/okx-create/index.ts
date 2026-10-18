import { serve } from "https://deno.land/std@0.168.0/http/server.ts";
import { corsHeaders } from "../_shared/cors.ts";

serve((_req: Request) => {
  return new Response(JSON.stringify({
    success: false,
    error: "Crypto checkout is unavailable until an authenticated merchant integration is configured.",
  }), {
    status: 503,
    headers: { ...corsHeaders, "Content-Type": "application/json", "Cache-Control": "no-store" },
  });
});
