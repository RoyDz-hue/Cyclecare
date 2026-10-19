-- PROPOSAL ONLY: ledger candidate; does not credit or reverse any economy ledger.
-- Handlers are intentionally not changed here. Independently review before production use.
BEGIN;

CREATE TABLE IF NOT EXISTS public.payment_intents (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id TEXT NOT NULL,
  provider TEXT NOT NULL CHECK (provider IN ('paypal','payhero')),
  external_reference TEXT NOT NULL UNIQUE,
  provider_reference TEXT,
  provider_order_id TEXT,
  amount NUMERIC(12,2) NOT NULL CHECK (amount > 0 AND amount = trunc(amount,2)),
  currency TEXT NOT NULL CHECK (currency IN ('USD','KES')),
  status TEXT NOT NULL DEFAULT 'pending' CHECK (status IN ('pending','success','failed','ambiguous','refunded','reversed')),
  metadata JSONB NOT NULL DEFAULT '{}'::jsonb,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
ALTER TABLE public.payment_intents ADD COLUMN IF NOT EXISTS provider_order_id TEXT;
CREATE UNIQUE INDEX IF NOT EXISTS payment_intents_provider_reference_uq ON public.payment_intents(provider, provider_reference) WHERE provider_reference IS NOT NULL;

CREATE TABLE IF NOT EXISTS public.payment_settlement_events (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  payment_intent_id UUID NOT NULL REFERENCES public.payment_intents(id),
  provider TEXT NOT NULL CHECK (provider IN ('paypal','payhero')),
  provider_reference TEXT NOT NULL CHECK (btrim(provider_reference) <> ''),
  event_type TEXT NOT NULL CHECK (event_type IN ('capture','refund','reversal')),
  owner_id TEXT NOT NULL CHECK (btrim(owner_id) <> ''),
  amount NUMERIC(12,2) NOT NULL CHECK (amount > 0 AND amount = trunc(amount,2)),
  currency TEXT NOT NULL CHECK (currency IN ('USD','KES')),
  payload JSONB NOT NULL DEFAULT '{}'::jsonb,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE(provider, provider_reference, event_type),
  UNIQUE(payment_intent_id, event_type)
);

ALTER TABLE public.payment_settlement_events ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.payment_intents ENABLE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS payment_intents_service_role_select ON public.payment_intents;
DROP POLICY IF EXISTS payment_settlement_events_service_role_select ON public.payment_settlement_events;
DO $policy$ BEGIN
  IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'service_role') THEN
    CREATE POLICY payment_intents_service_role_select ON public.payment_intents FOR SELECT TO service_role USING (true);
    CREATE POLICY payment_settlement_events_service_role_select ON public.payment_settlement_events FOR SELECT TO service_role USING (true);
  END IF;
END $policy$;

CREATE OR REPLACE FUNCTION public.record_payment_event(
  p_intent UUID, p_provider TEXT, p_provider_reference TEXT, p_event_type TEXT,
  p_owner TEXT, p_amount NUMERIC, p_currency TEXT, p_payload JSONB DEFAULT '{}'::jsonb
) RETURNS JSONB LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, public AS $$
DECLARE i public.payment_intents; existing public.payment_settlement_events;
BEGIN
  IF p_intent IS NULL OR p_provider IS NULL OR p_provider NOT IN ('paypal','payhero')
     OR p_provider_reference IS NULL OR btrim(p_provider_reference) = ''
     OR p_event_type IS NULL OR p_event_type NOT IN ('capture','refund','reversal')
     OR p_owner IS NULL OR btrim(p_owner) = '' OR p_amount IS NULL
     OR p_amount = 'NaN'::numeric OR p_amount <= 0 OR p_amount <> trunc(p_amount,2)
     OR p_currency IS NULL OR p_currency NOT IN ('USD','KES') THEN
    RAISE EXCEPTION 'payment event rejected';
  END IF;
  SELECT * INTO i FROM public.payment_intents WHERE id = p_intent FOR UPDATE;
  IF NOT FOUND OR i.provider <> p_provider OR i.user_id <> p_owner OR i.amount <> p_amount OR i.currency <> p_currency THEN
    RAISE EXCEPTION 'payment event rejected';
  END IF;
  SELECT * INTO existing FROM public.payment_settlement_events WHERE provider = p_provider AND provider_reference = p_provider_reference AND event_type = p_event_type;
  IF FOUND THEN
    IF existing.payment_intent_id = p_intent AND existing.owner_id = p_owner AND existing.amount = p_amount AND existing.currency = p_currency THEN
      RETURN jsonb_build_object('success',true,'decision','duplicate');
    END IF;
    RAISE EXCEPTION 'payment event ambiguous';
  END IF;
  IF p_event_type = 'capture' AND i.status <> 'pending' THEN
    RAISE EXCEPTION 'payment event rejected';
  END IF;
  IF p_event_type IN ('refund','reversal') AND i.status <> 'success' THEN
    RAISE EXCEPTION 'payment event rejected';
  END IF;
  INSERT INTO public.payment_settlement_events(payment_intent_id,provider,provider_reference,event_type,owner_id,amount,currency,payload)
    VALUES (p_intent,p_provider,p_provider_reference,p_event_type,p_owner,p_amount,p_currency,COALESCE(p_payload,'{}'::jsonb));
  UPDATE public.payment_intents SET status = CASE p_event_type WHEN 'capture' THEN 'success' WHEN 'refund' THEN 'refunded' ELSE 'reversed' END,
    provider_reference = CASE WHEN p_event_type = 'capture' THEN p_provider_reference ELSE provider_reference END, updated_at = now() WHERE id = p_intent;
  RETURN jsonb_build_object('success',true,'decision','settle');
EXCEPTION WHEN unique_violation THEN
  RAISE EXCEPTION 'payment event ambiguous';
END; $$;

REVOKE ALL ON FUNCTION public.record_payment_event(UUID,TEXT,TEXT,TEXT,TEXT,NUMERIC,TEXT,JSONB) FROM PUBLIC;
REVOKE ALL ON FUNCTION public.record_payment_event(UUID,TEXT,TEXT,TEXT,TEXT,NUMERIC,TEXT,JSONB) FROM anon, authenticated;
DO $grant$ BEGIN
  IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'service_role') THEN
    GRANT EXECUTE ON FUNCTION public.record_payment_event(UUID,TEXT,TEXT,TEXT,TEXT,NUMERIC,TEXT,JSONB) TO service_role;
  END IF;
END $grant$;
REVOKE ALL ON TABLE public.payment_intents, public.payment_settlement_events FROM PUBLIC, anon, authenticated, service_role;
GRANT SELECT ON TABLE public.payment_intents, public.payment_settlement_events TO service_role;
DO $priv$ BEGIN
  IF to_regclass('public.payment_intents_id_seq') IS NOT NULL THEN
    REVOKE ALL ON SEQUENCE public.payment_intents_id_seq FROM PUBLIC, anon, authenticated, service_role;
  END IF;
  IF to_regclass('public.payment_settlement_events_id_seq') IS NOT NULL THEN
    REVOKE ALL ON SEQUENCE public.payment_settlement_events_id_seq FROM PUBLIC, anon, authenticated, service_role;
  END IF;
END $priv$;

COMMIT;
