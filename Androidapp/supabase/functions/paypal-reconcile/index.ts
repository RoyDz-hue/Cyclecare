import { serve } from "https://deno.land/std@0.224.0/http/server.ts";
import { createPayPalHandler } from "../_shared/paypal-handler.ts";
import { payPalRuntimeDependencies } from "../_shared/paypal-runtime.ts";

serve(createPayPalHandler("reconcile", payPalRuntimeDependencies()));
