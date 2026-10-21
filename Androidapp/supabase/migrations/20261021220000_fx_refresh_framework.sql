-- FX refresh framework: who/when/what for every refresh, admin status reads,
-- and staleness exposed to apps for the lazy once-a-day trigger.

CREATE TABLE IF NOT EXISTS public.fx_refresh_log (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    triggered_by TEXT NOT NULL,          -- 'admin' or 'lazy'
    actor TEXT,                          -- admin uid when triggered_by = 'admin'
    status TEXT NOT NULL,                -- 'ok', 'partial', 'failed'
    source TEXT,                         -- rate source that supplied rates
    quotes_updated INTEGER NOT NULL DEFAULT 0,
    skipped JSONB NOT NULL DEFAULT '[]'::JSONB,   -- quotes rejected by sanity bounds
    detail TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
ALTER TABLE public.fx_refresh_log ENABLE ROW LEVEL SECURITY;
REVOKE ALL ON public.fx_refresh_log FROM PUBLIC, anon, authenticated;

DROP FUNCTION IF EXISTS public.fx_apply_market_rates(JSONB, TEXT);
DROP FUNCTION IF EXISTS public.fx_apply_market_rates(JSONB);
DROP FUNCTION IF EXISTS public.get_public_pricing(TEXT);

-- Per-quote apply with sanity bounds: a new rate must be positive and within
-- 30% of the stored rate when one exists (first-seen rates accept a broad band).
CREATE OR REPLACE FUNCTION public.fx_apply_market_rates(p_rates JSONB, p_source TEXT DEFAULT 'open.er-api.com')
RETURNS JSONB
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, public
AS $$
DECLARE
    r JSONB;
    v_old NUMERIC;
    v_new NUMERIC;
    v_updated INTEGER := 0;
    v_skipped JSONB := '[]'::JSONB;
BEGIN
    FOR r IN SELECT * FROM jsonb_array_elements(p_rates) LOOP
        v_new := (r->>'rate')::NUMERIC;
        SELECT rate_per_usd INTO v_old FROM public.fx_rates WHERE quote_currency = UPPER(r->>'quote') AND is_active = TRUE;
        IF NOT FOUND OR v_new IS NULL OR v_new <= 0 OR v_new > 10000000 THEN
            v_skipped := v_skipped || jsonb_build_array(jsonb_build_object('quote', r->>'quote', 'reason', 'unknown_or_out_of_band')); CONTINUE;
        END IF;
        IF v_old IS NOT NULL AND v_old > 0 AND ABS(v_new - v_old) / v_old > 0.30 THEN
            v_skipped := v_skipped || jsonb_build_array(jsonb_build_object('quote', r->>'quote', 'reason', 'outside_30pct_band', 'old', v_old, 'new', v_new)); CONTINUE;
        END IF;
        UPDATE public.fx_rates
           SET rate_per_usd = v_new, source = LEFT(COALESCE(p_source, 'open.er-api.com'), 60), updated_at = NOW()
         WHERE quote_currency = UPPER(r->>'quote');
        v_updated := v_updated + 1;
    END LOOP;
    RETURN jsonb_build_object('updated', v_updated, 'skipped', v_skipped);
END;
$$;
REVOKE ALL ON FUNCTION public.fx_apply_market_rates(JSONB, TEXT) FROM PUBLIC, anon, authenticated;

-- Staleness + last refresh for the lazy trigger and the admin status card.
CREATE OR REPLACE FUNCTION public.fx_status()
RETURNS JSONB
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, public
AS $$
DECLARE
    v_oldest TIMESTAMPTZ;
    v_last public.fx_refresh_log%ROWTYPE;
BEGIN
    SELECT MIN(updated_at) INTO v_oldest FROM public.fx_rates WHERE is_active = TRUE;
    SELECT * INTO v_last FROM public.fx_refresh_log ORDER BY created_at DESC LIMIT 1;
    RETURN jsonb_build_object(
        'stale', v_oldest IS NULL OR v_oldest < NOW() - INTERVAL '20 hours',
        'oldest_rate_at', v_oldest,
        'last_refresh', CASE WHEN v_last.id IS NULL THEN NULL ELSE to_jsonb(v_last) END);
END;
$$;
REVOKE ALL ON FUNCTION public.fx_status() FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.fx_status() TO anon, authenticated;

-- Expose staleness on the public pricing payload so the Android app knows when
-- to fire the lazy refresh.
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
    v_status JSONB;
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

    SELECT jsonb_build_object('stale', s->>'stale', 'oldest_rate_at', s->>'oldest_rate_at')
      INTO v_status FROM (SELECT jsonb_build_object('stale',
              (SELECT MIN(updated_at) FROM public.fx_rates WHERE is_active = TRUE) IS NULL
              OR (SELECT MIN(updated_at) FROM public.fx_rates WHERE is_active = TRUE) < NOW() - INTERVAL '20 hours',
              'oldest_rate_at', (SELECT MIN(updated_at) FROM public.fx_rates WHERE is_active = TRUE)) s) t;

    RETURN jsonb_build_object('currency', v_currency, 'packages', COALESCE(v_packages, '[]'::JSONB),
                              'rates_stale', COALESCE((v_status->>'stale')::BOOLEAN, FALSE),
                              'rates_oldest_at', v_status->>'oldest_rate_at');
END;
$$;
