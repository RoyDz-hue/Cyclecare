-- Currency metadata (name/symbol/decimals/countries) on fx_rates; seeded from
-- Androidapp/docs/currencies.json, which is the repo copy of this data.

ALTER TABLE public.fx_rates
    ADD COLUMN IF NOT EXISTS name TEXT,
    ADD COLUMN IF NOT EXISTS symbol TEXT,
    ADD COLUMN IF NOT EXISTS decimals INTEGER NOT NULL DEFAULT 2,
    ADD COLUMN IF NOT EXISTS countries TEXT[] NOT NULL DEFAULT '{}';

UPDATE public.fx_rates SET name = 'US Dollar', symbol = '$', decimals = 2, countries = '{"US"}' WHERE quote_currency = 'USD';
UPDATE public.fx_rates SET name = 'Kenyan Shilling', symbol = 'KSh', decimals = 0, countries = '{"KE"}' WHERE quote_currency = 'KES';
UPDATE public.fx_rates SET name = 'Ugandan Shilling', symbol = 'USh', decimals = 0, countries = '{"UG"}' WHERE quote_currency = 'UGX';
UPDATE public.fx_rates SET name = 'Tanzanian Shilling', symbol = 'TSh', decimals = 0, countries = '{"TZ"}' WHERE quote_currency = 'TZS';
UPDATE public.fx_rates SET name = 'Nigerian Naira', symbol = 'NGN', decimals = 2, countries = '{"NG"}' WHERE quote_currency = 'NGN';
UPDATE public.fx_rates SET name = 'Euro', symbol = 'EUR', decimals = 2, countries = '{"DE","FR","IT","ES","NL","BE","AT","PT","FI","IE","GR"}' WHERE quote_currency = 'EUR';
UPDATE public.fx_rates SET name = 'British Pound', symbol = 'GBP', decimals = 2, countries = '{"GB"}' WHERE quote_currency = 'GBP';

CREATE OR REPLACE FUNCTION public.admin_get_fx_rates()
RETURNS JSONB
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, public
AS $$
BEGIN
    IF NOT public.is_cyclecare_admin() THEN RAISE EXCEPTION 'Administrator access required'; END IF;
    RETURN (SELECT COALESCE(jsonb_agg(to_jsonb(q) ORDER BY q.sort_order, q.quote_currency), '[]'::JSONB)
            FROM (SELECT quote_currency, name, symbol, decimals, countries, rate_per_usd, admin_override, source, is_active, updated_at
                  FROM public.fx_rates) q);
END;
$$;
