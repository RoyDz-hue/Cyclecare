-- Fix admin_update_app_config validation: coin package prices/bonus are now
-- read in both camelCase (app writes) and snake_case (Admin Command writes).
CREATE OR REPLACE FUNCTION public.admin_update_app_config(p_key text, p_value jsonb)
 RETURNS jsonb
 LANGUAGE plpgsql
 SECURITY DEFINER
 SET search_path TO 'pg_catalog', 'public'
AS $function$
DECLARE
    v_key TEXT := LOWER(TRIM(COALESCE(p_key, '')));
    v_pkg JSONB;
    v_ids TEXT[];
    v_num NUMERIC;
BEGIN
    IF NOT public.is_cyclecare_admin() THEN RAISE EXCEPTION 'Administrator access required'; END IF;
    IF p_value IS NULL THEN RAISE EXCEPTION 'A configuration value is required'; END IF;

    IF v_key = 'coin_packages' THEN
        IF jsonb_typeof(p_value) <> 'array' OR jsonb_array_length(p_value) NOT BETWEEN 1 AND 20 THEN
            RAISE EXCEPTION 'Coin packages must be a list of 1 to 20 offers'; END IF;
        FOR v_pkg IN SELECT * FROM jsonb_array_elements(p_value) LOOP
            IF jsonb_typeof(v_pkg) <> 'object' THEN RAISE EXCEPTION 'Every package must be an object'; END IF;
            IF COALESCE(v_pkg->>'id','') !~ '^[a-z0-9_]{3,40}$' THEN RAISE EXCEPTION 'Every package needs a lowercase id like pkg_starter'; END IF;
            IF LENGTH(COALESCE(v_pkg->>'title','')) NOT BETWEEN 1 AND 60 THEN RAISE EXCEPTION 'Every package needs a title'; END IF;
            IF COALESCE((v_pkg->>'coins')::NUMERIC, -1) < 1 OR (v_pkg->>'coins')::NUMERIC > 1000000 OR (v_pkg->>'coins')::NUMERIC <> TRUNC((v_pkg->>'coins')::NUMERIC) THEN
                RAISE EXCEPTION 'Package % must grant between 1 and 1,000,000 whole coins', v_pkg->>'id'; END IF;
            IF COALESCE(COALESCE(v_pkg->>'bonusCoins', v_pkg->>'bonus_coins')::NUMERIC, 0) < 0 OR COALESCE(COALESCE(v_pkg->>'bonusCoins', v_pkg->>'bonus_coins')::NUMERIC, 0) > 1000000 THEN
                RAISE EXCEPTION 'Package % bonus must be between 0 and 1,000,000', v_pkg->>'id'; END IF;
            IF COALESCE(COALESCE(v_pkg->>'priceKes', v_pkg->>'price_kes')::NUMERIC, -1) < 0 OR COALESCE(v_pkg->>'priceKes', v_pkg->>'price_kes')::NUMERIC > 1000000 THEN
                RAISE EXCEPTION 'Package % KES price cannot be negative', v_pkg->>'id'; END IF;
            IF COALESCE(COALESCE(v_pkg->>'priceUsd', v_pkg->>'price_usd')::NUMERIC, -1) < 0 OR COALESCE(v_pkg->>'priceUsd', v_pkg->>'price_usd')::NUMERIC > 10000 THEN
                RAISE EXCEPTION 'Package % USD price cannot be negative', v_pkg->>'id'; END IF;
            IF COALESCE(v_pkg->>'priceKes', v_pkg->>'price_kes')::NUMERIC = 0 AND COALESCE(v_pkg->>'priceUsd', v_pkg->>'price_usd')::NUMERIC = 0 THEN
                RAISE EXCEPTION 'Package % needs a price in KES or USD', v_pkg->>'id'; END IF;
        END LOOP;
        SELECT array_agg(DISTINCT elem->>'id'), COUNT(DISTINCT elem->>'id') INTO v_ids, v_num FROM jsonb_array_elements(p_value) elem;
        IF v_num <> jsonb_array_length(p_value) THEN RAISE EXCEPTION 'Package ids must be unique'; END IF;
    ELSIF v_key = 'economy_multipliers' THEN
        IF jsonb_typeof(p_value) <> 'object' THEN RAISE EXCEPTION 'Multipliers must be an object'; END IF;
        IF (SELECT COUNT(*) FROM jsonb_each(p_value) WHERE jsonb_typeof(value) <> 'number' OR (value::TEXT)::NUMERIC < 0 OR (value::TEXT)::NUMERIC > 100) > 0 THEN
            RAISE EXCEPTION 'Multipliers must be numbers between 0 and 100'; END IF;
    ELSIF v_key = 'payment_gateway' THEN
        IF jsonb_typeof(p_value) <> 'object' THEN RAISE EXCEPTION 'Payment gateway settings must be an object'; END IF;
        IF p_value ? 'minDepositKes' AND (COALESCE((p_value->>'minDepositKes')::NUMERIC, -1) < 0 OR (p_value->>'minDepositKes')::NUMERIC > 1000000) THEN
            RAISE EXCEPTION 'Minimum deposit must be between 0 and 1,000,000 KES'; END IF;
        IF p_value ? 'activeMpesaProvider' AND UPPER(p_value->>'activeMpesaProvider') <> 'PAYHERO' THEN
            RAISE EXCEPTION 'Only PAYHERO is an approved M-Pesa provider'; END IF;
    ELSIF v_key = 'ai_config' THEN
        IF jsonb_typeof(p_value) <> 'object' THEN RAISE EXCEPTION 'AI settings must be an object'; END IF;
        IF p_value ? 'temperature' AND ((p_value->>'temperature')::NUMERIC < 0 OR (p_value->>'temperature')::NUMERIC > 2) THEN
            RAISE EXCEPTION 'Temperature must be between 0 and 2'; END IF;
        IF p_value ? 'model' AND LENGTH(p_value->>'model') > 100 THEN RAISE EXCEPTION 'Model name is too long'; END IF;
    ELSE
        RAISE EXCEPTION 'This configuration key is not editable from the admin panel';
    END IF;

    INSERT INTO public.app_config(key, value, updated_at) VALUES (v_key, p_value, NOW())
    ON CONFLICT (key) DO UPDATE SET value = EXCLUDED.value, updated_at = NOW();

    INSERT INTO public.admin_audit_logs(admin_user_id, admin_action, target_user_id, details, performed_by)
    VALUES (auth.uid(), 'ADMIN_UPDATE_APP_CONFIG', v_key, jsonb_build_object('key', v_key, 'value', p_value), auth.uid()::TEXT);
    RETURN jsonb_build_object('success', TRUE, 'key', v_key, 'value', p_value);
END;
$function$


