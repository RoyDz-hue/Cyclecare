-- Snapshot-scoped bulk gift: the approval card's member list is exactly what executes.
-- Drop the 3-argument original first: with all-default parameters the 4-argument
-- replacement would otherwise overload ambiguously.
DROP FUNCTION IF EXISTS public.admin_gift_all_coins(INTEGER, TEXT, TEXT);

-- p_recipients is the resolved recipient set captured at proposal time; NULL keeps the
-- original all-profiles behavior for backward compatibility.
CREATE OR REPLACE FUNCTION public.admin_gift_all_coins(
    p_delta INTEGER,
    p_reason TEXT DEFAULT NULL,
    p_run_key TEXT DEFAULT NULL,
    p_recipients TEXT[] DEFAULT NULL
) RETURNS JSONB
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, public
AS $$
DECLARE
    v_reason TEXT;
    v_recipients INTEGER;
    v_prev_details JSONB;
BEGIN
    IF NOT public.is_cyclecare_admin() THEN RAISE EXCEPTION 'Administrator access required'; END IF;
    IF p_delta IS NULL OR p_delta < 1 OR p_delta > 10000 THEN
        RAISE EXCEPTION 'Gift must be a whole number of coins between 1 and 10,000';
    END IF;
    IF p_run_key IS NULL OR length(TRIM(p_run_key)) = 0 OR length(p_run_key) > 120 THEN
        RAISE EXCEPTION 'A gift run key is required';
    END IF;
    IF p_recipients IS NOT NULL AND cardinality(p_recipients) = 0 THEN
        RAISE EXCEPTION 'The recipient list is empty';
    END IF;

    PERFORM pg_advisory_xact_lock(hashtext('admin_gift_all_coins:' || p_run_key));
    SELECT details INTO v_prev_details FROM public.admin_audit_logs
     WHERE admin_action = 'ADMIN_GIFT_ALL_COINS' AND details->>'run_key' = p_run_key
     ORDER BY created_at ASC LIMIT 1;
    IF FOUND THEN
        RETURN jsonb_build_object('success', TRUE, 'duplicate', TRUE,
            'recipients', COALESCE((v_prev_details->>'recipients')::INTEGER, 0),
            'total_coins', COALESCE((v_prev_details->>'total_coins')::BIGINT, 0),
            'reason', v_prev_details->>'reason');
    END IF;

    v_reason := NULLIF(LEFT(TRIM(COALESCE(p_reason, '')), 500), '');

    WITH source AS (
        SELECT COALESCE(NULLIF(p.user_id::TEXT, ''), NULLIF(p.device_id, ''), p.id::TEXT) AS uid
        FROM public.profiles p
        WHERE p_recipients IS NULL
           OR COALESCE(NULLIF(p.user_id::TEXT, ''), NULLIF(p.device_id, ''), p.id::TEXT) = ANY(p_recipients)
    ),
    upserted AS (
        INSERT INTO public.economy_profiles(user_id, coin_balance, lifetime_earned, lifetime_spent, unlocked_items)
        SELECT uid, p_delta, p_delta, 0, '["cycle_coin"]'::JSONB
        FROM source
        ON CONFLICT (user_id) DO UPDATE
        SET coin_balance = COALESCE(economy_profiles.coin_balance, 0) + EXCLUDED.coin_balance,
            lifetime_earned = COALESCE(economy_profiles.lifetime_earned, 0) + EXCLUDED.coin_balance,
            updated_at = NOW()
        RETURNING user_id, coin_balance
    ),
    ledger AS (
        INSERT INTO public.coin_transactions(user_id, action, delta, balance_after, metadata)
        SELECT user_id, 'ADMIN_GIFT_ALL', p_delta, coin_balance,
               jsonb_build_object('run_key', p_run_key, 'reason', v_reason, 'admin_user_id', auth.uid()::TEXT)
        FROM upserted
        RETURNING user_id
    ),
    notices AS (
        INSERT INTO public.user_notifications(user_id, target_identifier, title, body, type, priority, sender)
        SELECT user_id, user_id, 'Cycle Coins Received!',
               'You have received ' || p_delta || ' Cycle Coins from CycleCare Administration.',
               'REWARD', 'HIGH', 'CYCLECARE_OFFICIAL'
        FROM upserted
        RETURNING user_id
    )
    SELECT count(*) INTO v_recipients FROM upserted;

    INSERT INTO public.admin_audit_logs(admin_user_id, admin_action, target_user_id, details, performed_by)
    VALUES (auth.uid(), 'ADMIN_GIFT_ALL_COINS', '*',
        jsonb_build_object('run_key', p_run_key, 'delta_per_user', p_delta, 'recipients', v_recipients,
            'total_coins', v_recipients * p_delta, 'reason', v_reason,
            'snapshot', p_recipients IS NOT NULL), auth.uid()::TEXT);

    RETURN jsonb_build_object('success', TRUE, 'duplicate', FALSE, 'recipients', v_recipients,
        'total_coins', v_recipients * p_delta, 'reason', v_reason);
END;
$$;

REVOKE ALL ON FUNCTION public.admin_gift_all_coins(INTEGER, TEXT, TEXT, TEXT[]) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.admin_gift_all_coins(INTEGER, TEXT, TEXT, TEXT[]) TO authenticated;
