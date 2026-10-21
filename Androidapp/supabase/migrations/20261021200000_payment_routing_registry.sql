-- Payment routing registry: UI option -> real provider -> allowed regions ->
-- currency, server-side and data-driven. UI labels stay exactly what members
-- see today; this table is the backend truth each checkout edge function
-- enforces before any payment logic runs. Placeholder/fail-closed behavior is
-- unchanged: options ship is_enabled=false until real credentials land.

CREATE TABLE IF NOT EXISTS public.payment_routing_registry (
    option_key TEXT PRIMARY KEY,            -- stable key the app uses: mpesa, card, paypal, crypto
    ui_label TEXT NOT NULL,                 -- must match the UI label members see
    provider TEXT NOT NULL,                 -- real backend provider: PAYHERO, PAYPAL_ACDC, PAYPAL_STANDARD, OKX
    allowed_regions TEXT[] NOT NULL DEFAULT '{}',  -- ISO country codes; '{"*"}' means everywhere
    currency TEXT NOT NULL,                 -- charged currency: KES, USD
    sort_order INTEGER NOT NULL DEFAULT 0,
    is_enabled BOOLEAN NOT NULL DEFAULT FALSE,
    notes TEXT,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
ALTER TABLE public.payment_routing_registry ENABLE ROW LEVEL SECURITY;
REVOKE ALL ON public.payment_routing_registry FROM PUBLIC, anon, authenticated;

INSERT INTO public.payment_routing_registry (option_key, ui_label, provider, allowed_regions, currency, sort_order, is_enabled, notes) VALUES
    ('mpesa',  'M-Pesa',           'PAYHERO',         '{"KE"}', 'KES', 10, FALSE, 'PayHero/Nexus STK push. Kenya M-Pesa rail; UG/TZ mobile money are different rails (decision pending).'),
    ('card',   'Debit/Credit Card','PAYPAL_ACDC',     '{"*"}',  'USD', 20, FALSE, 'Visa, Mastercard, Amex via PayPal Advanced Credit/Debit Cards.'),
    ('paypal', 'PayPal',           'PAYPAL_STANDARD', '{"*"}',  'USD', 30, FALSE, 'PayPal wallet checkout.'),
    ('crypto', 'Crypto',           'OKX',             '{"*"}',  'USD', 40, FALSE, 'USDT, BTC, ETH via OKX Pay.')
ON CONFLICT (option_key) DO NOTHING;

-- Read for apps and the admin console. No secrets live here; safe for anon.
CREATE OR REPLACE FUNCTION public.get_payment_routing()
RETURNS JSONB
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, public
AS $$
BEGIN
    RETURN (SELECT COALESCE(jsonb_agg(to_jsonb(q) ORDER BY q.sort_order), '[]'::JSONB)
            FROM (SELECT option_key, ui_label, provider, allowed_regions, currency, is_enabled, sort_order
                  FROM public.payment_routing_registry) q);
END;
$$;
REVOKE ALL ON FUNCTION public.get_payment_routing() FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.get_payment_routing() TO anon, authenticated;

-- The enforcement gate every checkout edge function calls first.
CREATE OR REPLACE FUNCTION public.payment_option_allowed(p_option_key TEXT, p_region TEXT, p_currency TEXT)
RETURNS JSONB
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, public
AS $$
DECLARE
    v_row public.payment_routing_registry%ROWTYPE;
    v_region TEXT := UPPER(TRIM(COALESCE(p_region, '')));
    v_currency TEXT := UPPER(TRIM(COALESCE(p_currency, '')));
BEGIN
    SELECT * INTO v_row FROM public.payment_routing_registry WHERE option_key = LOWER(TRIM(COALESCE(p_option_key, '')));
    IF NOT FOUND THEN
        RETURN jsonb_build_object('allowed', FALSE, 'reason', 'unknown_option'); END IF;
    IF v_row.is_enabled IS NOT TRUE THEN
        RETURN jsonb_build_object('allowed', FALSE, 'reason', 'option_disabled'); END IF;
    IF v_currency <> '' AND UPPER(v_row.currency) <> v_currency THEN
        RETURN jsonb_build_object('allowed', FALSE, 'reason', 'currency_not_allowed', 'expected_currency', v_row.currency); END IF;
    IF v_region <> '' AND NOT ('*' = ANY(v_row.allowed_regions) OR v_region = ANY(v_row.allowed_regions)) THEN
        RETURN jsonb_build_object('allowed', FALSE, 'reason', 'region_not_allowed', 'allowed_regions', v_row.allowed_regions); END IF;
    RETURN jsonb_build_object('allowed', TRUE, 'provider', v_row.provider, 'currency', v_row.currency);
END;
$$;
REVOKE ALL ON FUNCTION public.payment_option_allowed(TEXT, TEXT, TEXT) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.payment_option_allowed(TEXT, TEXT, TEXT) TO authenticated;

-- Admin edit: regions, currency and enablement stay data-driven (audited).
CREATE OR REPLACE FUNCTION public.admin_set_payment_routing(
    p_option_key TEXT,
    p_allowed_regions TEXT[] DEFAULT NULL,
    p_currency TEXT DEFAULT NULL,
    p_is_enabled BOOLEAN DEFAULT NULL,
    p_notes TEXT DEFAULT NULL
) RETURNS JSONB
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, public
AS $$
DECLARE
    v_existing public.payment_routing_registry%ROWTYPE;
BEGIN
    IF NOT public.is_cyclecare_admin() THEN RAISE EXCEPTION 'Administrator access required'; END IF;
    SELECT * INTO v_existing FROM public.payment_routing_registry WHERE option_key = LOWER(TRIM(COALESCE(p_option_key, '')));
    IF NOT FOUND THEN RAISE EXCEPTION 'Unknown payment option %', p_option_key; END IF;
    IF p_allowed_regions IS NOT NULL THEN
        IF cardinality(p_allowed_regions) = 0 THEN RAISE EXCEPTION 'Allowed regions cannot be empty (use ''{"*"}'')'; END IF;
        IF EXISTS (SELECT 1 FROM unnest(p_allowed_regions) r WHERE r <> '*' AND r !~ '^[A-Z]{2}$') THEN
            RAISE EXCEPTION 'Regions must be ISO country codes like KE, UG, TZ or *'; END IF;
    END IF;
    IF p_currency IS NOT NULL AND UPPER(p_currency) NOT IN ('KES', 'USD') THEN
        RAISE EXCEPTION 'Currency must be KES or USD'; END IF;
    UPDATE public.payment_routing_registry
       SET allowed_regions = COALESCE(p_allowed_regions, allowed_regions),
           currency = COALESCE(UPPER(p_currency), currency),
           is_enabled = COALESCE(p_is_enabled, is_enabled),
           notes = COALESCE(p_notes, notes),
           updated_at = NOW()
     WHERE option_key = v_existing.option_key;
    INSERT INTO public.admin_audit_logs(admin_user_id, admin_action, target_user_id, details, performed_by)
    VALUES (auth.uid(), 'ADMIN_SET_PAYMENT_ROUTING', 'payment:' || v_existing.option_key,
            jsonb_build_object('option_key', v_existing.option_key, 'before', to_jsonb(v_existing),
                'regions', p_allowed_regions, 'currency', p_currency, 'is_enabled', p_is_enabled),
            auth.uid()::TEXT);
    RETURN jsonb_build_object('success', TRUE, 'option_key', v_existing.option_key);
END;
$$;
REVOKE ALL ON FUNCTION public.admin_set_payment_routing(TEXT, TEXT[], TEXT, BOOLEAN, TEXT) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.admin_set_payment_routing(TEXT, TEXT[], TEXT, BOOLEAN, TEXT) TO authenticated;
