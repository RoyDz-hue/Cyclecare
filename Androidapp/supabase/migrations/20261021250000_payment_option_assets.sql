-- Data-driven assets/networks per payment option. Roy's decision: crypto is
-- fixed to USDT and USDC (matches OKX Pay, which supports stablecoins only).
-- More coins can be added later by editing the arrays - no deploy.

ALTER TABLE public.payment_routing_registry
    ADD COLUMN IF NOT EXISTS allowed_assets TEXT[] NOT NULL DEFAULT '{}',
    ADD COLUMN IF NOT EXISTS allowed_networks TEXT[] NOT NULL DEFAULT '{}';

UPDATE public.payment_routing_registry
   SET allowed_assets = '{"USDT","USDC"}',
       allowed_networks = '{"X Layer"}',
       notes = 'USDT, BTC, ETH via OKX Pay -> fixed to USDT/USDC per Roy (OKX Pay supports stablecoins only: USDT, USDC, USDG, on X Layer). Note: OKX Scan to Pay excludes Nigeria.'
 WHERE option_key = 'crypto';

CREATE OR REPLACE FUNCTION public.get_payment_routing()
RETURNS JSONB
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, public
AS $$
BEGIN
    RETURN (SELECT COALESCE(jsonb_agg(to_jsonb(q) ORDER BY q.sort_order), '[]'::JSONB)
            FROM (SELECT option_key, ui_label, provider, allowed_regions, currency, is_enabled, sort_order,
                         allowed_assets, allowed_networks
                  FROM public.payment_routing_registry) q);
END;
$$;

CREATE OR REPLACE FUNCTION public.admin_set_payment_routing(
    p_option_key TEXT,
    p_allowed_regions TEXT[] DEFAULT NULL,
    p_currency TEXT DEFAULT NULL,
    p_is_enabled BOOLEAN DEFAULT NULL,
    p_notes TEXT DEFAULT NULL,
    p_allowed_assets TEXT[] DEFAULT NULL,
    p_allowed_networks TEXT[] DEFAULT NULL
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
    IF p_allowed_assets IS NOT NULL AND EXISTS (SELECT 1 FROM unnest(p_allowed_assets) a WHERE a !~ '^[A-Z0-9]{2,10}$') THEN
        RAISE EXCEPTION 'Assets must be tickers like USDT or USDC'; END IF;
    UPDATE public.payment_routing_registry
       SET allowed_regions = COALESCE(p_allowed_regions, allowed_regions),
           currency = COALESCE(UPPER(p_currency), currency),
           is_enabled = COALESCE(p_is_enabled, is_enabled),
           notes = COALESCE(p_notes, notes),
           allowed_assets = COALESCE(p_allowed_assets, allowed_assets),
           allowed_networks = COALESCE(p_allowed_networks, allowed_networks),
           updated_at = NOW()
     WHERE option_key = v_existing.option_key;
    INSERT INTO public.admin_audit_logs(admin_user_id, admin_action, target_user_id, details, performed_by)
    VALUES (auth.uid(), 'ADMIN_SET_PAYMENT_ROUTING', 'payment:' || v_existing.option_key,
            jsonb_build_object('option_key', v_existing.option_key, 'before', to_jsonb(v_existing),
                'regions', p_allowed_regions, 'currency', p_currency, 'is_enabled', p_is_enabled,
                'assets', p_allowed_assets, 'networks', p_allowed_networks),
            auth.uid()::TEXT);
    RETURN jsonb_build_object('success', TRUE, 'option_key', v_existing.option_key);
END;
$$;
REVOKE ALL ON FUNCTION public.admin_set_payment_routing(TEXT, TEXT[], TEXT, BOOLEAN, TEXT, TEXT[], TEXT[]) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.admin_set_payment_routing(TEXT, TEXT[], TEXT, BOOLEAN, TEXT, TEXT[], TEXT[]) TO authenticated;
