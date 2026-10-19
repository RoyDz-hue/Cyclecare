import { serve } from "https://deno.land/std@0.168.0/http/server.ts";
import { createPayPalHandler } from "../_shared/paypal-handler.ts";
import { payPalRuntimeDependencies } from "../_shared/paypal-runtime.ts";

serve(createPayPalHandler("webhook", payPalRuntimeDependencies()));
