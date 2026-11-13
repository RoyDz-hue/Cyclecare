-- Local-currency display pricing. Charging is unchanged (PayHero KES,
-- PayPal/OKX USD): this layer only computes the local DISPLAY price, marked
-- approximate, while checkout always states the actual charge amount.

CREATE TABLE IF NOT EXISTS public.fx_rates (
    quote_currency TEXT PRIMARY KEY,
    rate_per_usd NUMERIC(18,6) NOT NULL,
    admin_override NUMERIC(18,6),
    source TEXT NOT NULL DEFAULT 'seed',
    is_active BOOLEAN NOT NULL DEFAULT TRUE,
    sort_order INTEGER NOT NULL DEFAULT 0,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
ALTER TABLE public.fx_rates ENABLE ROW LEVEL SECURITY;
REVOKE ALL ON public.fx_rates FROM PUBLIC, anon, authenticated;

INSERT INTO public.fx_rates (quote_currency, rate_per_usd, source, sort_order) VALUES
    ('USD', 1, 'base', 0),
    ('KES', 129.50, 'seed', 10),
    ('UGX', 3680.00, 'seed', 20),
    ('TZS', 2650.00, 'seed', 30),
    ('NGN', 1550.00, 'seed', 40),
    ('EUR', 0.92, 'seed', 50),
    ('GBP', 0.79, 'seed', 60)
ON CONFLICT (quote_currency) DO NOTHING;

-- Psychological rounding per magnitude: charm 9-endings in the local unit.
CREATE OR REPLACE FUNCTION public.psych_round(p_value NUMERIC)
RETURNS NUMERIC
LANGUAGE plpgsql
IMMUTABLE
AS $$
BEGIN
    IF p_value IS NULL OR p_value <= 0 THEN RETURN NULL; END IF;
    IF p_value >= 1000 THEN
        RETURN FLOOR(p_value / 100.0) * 100 + 99;       -- 3,690 -> 3,699
    ELSIF p_value >= 100 THEN
        RETURN FLOOR(p_value / 10.0) * 10 + 9;          -- 462 -> 469
    ELSIF p_value >= 10 THEN
        RETURN FLOOR(p_value) + 0.9;                    -- 45.2 -> 45.9
    ELSE
        RETURN FLOOR(p_value * 10) / 10 + 0.09;         -- 0.91 -> 0.99
    END IF;
END;
$$;

-- Region -> currency. Data-driven currencies live in fx_rates; the region map
-- is static with USD as the default for unlisted countries.
CREATE OR REPLACE FUNCTION public.currency_for_region(p_region TEXT)
RETURNS TEXT
LANGUAGE plpgsql
IMMUTABLE
AS $$
BEGIN
    RETURN CASE UPPER(TRIM(COALESCE(p_region, '')))
        WHEN 'KE' THEN 'KES'
        WHEN 'UG' THEN 'UGX'
        WHEN 'TZ' THEN 'TZS'
        WHEN 'NG' THEN 'NGN'
        WHEN 'US' THEN 'USD' WHEN 'GB' THEN 'GBP' WHEN 'IE' THEN 'EUR'
        WHEN 'DE' THEN 'EUR' WHEN 'FR' THEN 'EUR' WHEN 'IT' THEN 'EUR' WHEN 'ES' THEN 'EUR'
        WHEN 'NL' THEN 'EUR' WHEN 'BE' THEN 'EUR' WHEN 'AT' THEN 'EUR' WHEN 'PT' THEN 'EUR'
        WHEN 'FI' THEN 'EUR' WHEN 'GR' THEN 'EUR' ELSE 'USD'
    END;
END;
$$;

-- Public pricing for a region: every active coin package with its display
-- price. KE uses the package's own KES price (exact); USD is exact; other
-- currencies are approximate conversions with psychological rounding.
CREATE OR REPLACE FUNCTION public.get_public_pricing(p_region TEXT DEFAULT '')
RETURNS JSONB
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, public
AS $$
DECLARE
    v_currency TEXT := public.currency_for_region(p_region);
    v_rate NUMERIC;
    v_rate_fresh BOOLEAN;
    v_packages JSONB;
BEGIN
    SELECT COALESCE(f.admin_override, f.rate_per_usd), f.updated_at > NOW() - INTERVAL '48 hours'
      INTO v_rate, v_rate_fresh
      FROM public.fx_rates f WHERE f.quote_currency = v_currency AND f.is_active = TRUE;
    IF v_rate IS NULL OR v_rate <= 0 OR v_rate_fresh IS NOT TRUE THEN
        v_currency := 'USD'; v_rate := 1;  -- stale or missing rate: safe USD fallback
    END IF;

    SELECT COALESCE(jsonb_agg(to_jsonb(q) ORDER BY q.price_usd), '[]'::JSONB) INTO v_packages
      FROM (
        SELECT elem->>'id' AS id,
               elem->>'title' AS title,
               (elem->>'coins')::BIGINT AS coins,
               COALESCE((elem->>'bonusCoins')::BIGINT, (elem->>'bonus_coins')::BIGINT, 0) AS bonus_coins,
               COALESCE((elem->>'priceKes')::NUMERIC, (elem->>'price_kes')::NUMERIC, 0) AS price_kes,
               COALESCE((elem->>'priceUsd')::NUMERIC, (elem->>'price_usd')::NUMERIC) AS price_usd,
               elem->>'tag' AS tag,
               CASE
                 WHEN v_currency = 'KES' AND COALESCE((elem->>'priceKes')::NUMERIC, (elem->>'price_kes')::NUMERIC, 0) > 0
                   THEN jsonb_build_object('currency', 'KES', 'amount', COALESCE((elem->>'priceKes')::NUMERIC, (elem->>'price_kes')::NUMERIC), 'approx', FALSE,
                                           'charge_currency', 'KES', 'charge_amount', COALESCE((elem->>'priceKes')::NUMERIC, (elem->>'price_kes')::NUMERIC))
                 WHEN v_currency = 'USD'
                   THEN jsonb_build_object('currency', 'USD', 'amount', COALESCE((elem->>'priceUsd')::NUMERIC, (elem->>'price_usd')::NUMERIC), 'approx', FALSE,
                                           'charge_currency', 'USD', 'charge_amount', COALESCE((elem->>'priceUsd')::NUMERIC, (elem->>'price_usd')::NUMERIC))
                 ELSE jsonb_build_object('currency', v_currency,
                                         'amount', public.psych_round(COALESCE((elem->>'priceUsd')::NUMERIC, (elem->>'price_usd')::NUMERIC) * v_rate),
                                         'approx', TRUE, 'charge_currency', 'USD', 'charge_amount', COALESCE((elem->>'priceUsd')::NUMERIC, (elem->>'price_usd')::NUMERIC))
               END AS display
          FROM app_config c, LATERAL jsonb_array_elements(c.value) elem
         WHERE c.key = 'coin_packages'
      ) q;

    RETURN jsonb_build_object('currency', v_currency, 'packages', COALESCE(v_packages, '[]'::JSONB));
END;
$$;
REVOKE ALL ON FUNCTION public.get_public_pricing(TEXT) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.get_public_pricing(TEXT) TO anon, authenticated;

-- Admin: add or update a currency (rate + optional override + enablement).
CREATE OR REPLACE FUNCTION public.admin_set_fx_rate(
    p_quote_currency TEXT,
    p_rate_per_usd NUMERIC DEFAULT NULL,
    p_admin_override NUMERIC DEFAULT NULL,
    p_clear_override BOOLEAN DEFAULT FALSE,
    p_is_active BOOLEAN DEFAULT NULL
) RETURNS JSONB
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, public
AS $$
DECLARE
    v_quote TEXT := UPPER(TRIM(COALESCE(p_quote_currency, '')));
BEGIN
    IF NOT public.is_cyclecare_admin() THEN RAISE EXCEPTION 'Administrator access required'; END IF;
    IF v_quote !~ '^[A-Z]{3}$' THEN RAISE EXCEPTION 'Currency must be a 3-letter ISO code'; END IF;
    IF p_rate_per_usd IS NOT NULL AND p_rate_per_usd <= 0 THEN RAISE EXCEPTION 'Rate must be positive'; END IF;
    IF p_admin_override IS NOT NULL AND p_admin_override <= 0 THEN RAISE EXCEPTION 'Override must be positive'; END IF;
    INSERT INTO public.fx_rates (quote_currency, rate_per_usd, admin_override, is_active, source)
    VALUES (v_quote, COALESCE(p_rate_per_usd, 1), p_admin_override, COALESCE(p_is_active, TRUE), 'admin')
    ON CONFLICT (quote_currency) DO UPDATE SET
        rate_per_usd = COALESCE(p_rate_per_usd, fx_rates.rate_per_usd),
        admin_override = CASE WHEN p_clear_override THEN NULL ELSE COALESCE(p_admin_override, fx_rates.admin_override) END,
        is_active = COALESCE(p_is_active, fx_rates.is_active),
        updated_at = NOW();
    INSERT INTO public.admin_audit_logs(admin_user_id, admin_action, target_user_id, details, performed_by)
    VALUES (auth.uid(), 'ADMIN_SET_FX_RATE', 'fx:' || v_quote,
            jsonb_build_object('quote', v_quote, 'rate', p_rate_per_usd, 'override', p_admin_override,
                               'clear_override', p_clear_override, 'is_active', p_is_active), auth.uid()::TEXT);
    RETURN jsonb_build_object('success', TRUE, 'quote_currency', v_quote);
END;
$$;
REVOKE ALL ON FUNCTION public.admin_set_fx_rate(TEXT, NUMERIC, NUMERIC, BOOLEAN, BOOLEAN) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.admin_set_fx_rate(TEXT, NUMERIC, NUMERIC, BOOLEAN, BOOLEAN) TO authenticated;

-- Admin: read all rates for the Pricing tab.
CREATE OR REPLACE FUNCTION public.admin_get_fx_rates()
RETURNS JSONB
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, public
AS $$
BEGIN
    IF NOT public.is_cyclecare_admin() THEN RAISE EXCEPTION 'Administrator access required'; END IF;
    RETURN (SELECT COALESCE(jsonb_agg(to_jsonb(q) ORDER BY q.sort_order, q.quote_currency), '[]'::JSONB)
            FROM (SELECT quote_currency, rate_per_usd, admin_override, source, is_active, updated_at
                  FROM public.fx_rates) q);
END;
$$;
REVOKE ALL ON FUNCTION public.admin_get_fx_rates() FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.admin_get_fx_rates() TO authenticated;

-- Auto-refresh hook for the fx-refresh edge function (service role only).
CREATE OR REPLACE FUNCTION public.fx_apply_market_rates(p_rates JSONB, p_source TEXT DEFAULT 'open.er-api.com')
RETURNS INTEGER
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, public
AS $$
DECLARE
    v_count INTEGER := 0;
BEGIN
    UPDATE public.fx_rates f
       SET rate_per_usd = (r->>'rate')::NUMERIC, source = LEFT(COALESCE(p_source, 'open.er-api.com'), 60), updated_at = NOW()
      FROM jsonb_array_elements(p_rates) r
     WHERE f.quote_currency = UPPER(r->>'quote') AND (r->>'rate')::NUMERIC > 0;
    GET DIAGNOSTICS v_count = ROW_COUNT;
    RETURN v_count;
END;
$$;
REVOKE ALL ON FUNCTION public.fx_apply_market_rates(JSONB, TEXT) FROM PUBLIC, anon, authenticated;
