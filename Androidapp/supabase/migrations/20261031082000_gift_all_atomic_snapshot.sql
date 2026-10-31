-- Make the approved gift recipient set atomic with execution.
-- Before, a profile deleted between the review card and Confirm was silently
-- skipped: the write CTE gifted only the surviving subset and the edge could
-- only flag the drift after balances had already moved. Now, when an approved
-- recipient list is supplied, the function locks those profile rows, verifies
-- the full approved set still exists, and aborts the whole transaction (zero
-- balance writes) if any approved member is gone. The exact approved set,
-- count and total are honored or nothing happens at all.
CREATE OR REPLACE FUNCTION public.admin_gift_all_coins(p_delta numeric, p_reason text DEFAULT NULL::text, p_run_key text DEFAULT NULL::text, p_recipients text[] DEFAULT NULL::text[])
 RETURNS jsonb
 LANGUAGE plpgsql
 SECURITY DEFINER
 SET search_path TO 'pg_catalog', 'public'
AS $function$
DECLARE
    v_reason TEXT;
    v_recipients INTEGER;
    v_prev_details JSONB;
    v_approved TEXT[];
    v_requested INTEGER;
    v_matched INTEGER;
BEGIN
    IF NOT public.is_cyclecare_admin() THEN RAISE EXCEPTION 'Administrator access required'; END IF;
    IF p_delta IS NULL OR p_delta < 1 OR p_delta > 10000 THEN
        RAISE EXCEPTION 'Gift must be between 1 and 10,000 coins for each member';
    END IF;
    IF scale(p_delta) > 3 THEN
        RAISE EXCEPTION 'Cycle Coins support up to 3 decimal places';
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
            'total_coins', COALESCE((v_prev_details->>'total_coins')::NUMERIC, 0),
            'reason', v_prev_details->>'reason');
    END IF;

    v_reason := NULLIF(LEFT(TRIM(COALESCE(p_reason, '')), 500), '');

    -- Atomic approved-set validation: lock the approved profile rows (blocking
    -- concurrent deletion until this transaction ends), then require the full
    -- approved set to still exist. Any mismatch aborts before a single write.
    IF p_recipients IS NOT NULL THEN
        SELECT ARRAY(SELECT DISTINCT x FROM unnest(p_recipients) AS u(x)) INTO v_approved;
        v_requested := cardinality(v_approved);
        SELECT count(DISTINCT uid) INTO v_matched FROM (
            SELECT COALESCE(NULLIF(p.user_id::TEXT, ''), NULLIF(p.device_id, ''), p.id::TEXT) AS uid
            FROM public.profiles p
            WHERE COALESCE(NULLIF(p.user_id::TEXT, ''), NULLIF(p.device_id, ''), p.id::TEXT) = ANY(v_approved)
            FOR UPDATE OF p
        ) locked;
        IF v_matched <> v_requested THEN
            RAISE EXCEPTION 'The approved member set changed after review. No coins were moved; prepare a fresh plan.';
        END IF;
    END IF;

    WITH source AS (
        SELECT DISTINCT COALESCE(NULLIF(p.user_id::TEXT, ''), NULLIF(p.device_id, ''), p.id::TEXT) AS uid
        FROM public.profiles p
        WHERE p_recipients IS NULL
           OR COALESCE(NULLIF(p.user_id::TEXT, ''), NULLIF(p.device_id, ''), p.id::TEXT) = ANY(v_approved)
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
               'You have received ' || trim_scale(p_delta) || ' Cycle Coins from CycleCare Administration.',
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
$function$;
