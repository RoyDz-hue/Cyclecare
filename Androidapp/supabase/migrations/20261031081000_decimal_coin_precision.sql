-- Decimal coin precision (Decimal Precision Rules, 2026-10-31): coins support up
-- to 3 decimal places across database, backend, API, calculations and display.
-- Storage boundary: unscaled NUMERIC plus a scale<=3 CHECK, so a >3-decimal write
-- fails loudly instead of rounding silently, and whole balances keep their exact
-- form (4 stays 4, not 4.000) so older app builds keep reading them. Existing
-- balances convert losslessly (BIGINT/INTEGER -> NUMERIC). The only non-exact
-- calculation, the percentage penalty, rounds half away from zero to 3 decimals
-- at this boundary. Server texts use trim_scale so trailing zeros never display.

ALTER TABLE public.economy_profiles ALTER COLUMN coin_balance TYPE NUMERIC USING coin_balance::NUMERIC;
ALTER TABLE public.economy_profiles ALTER COLUMN lifetime_earned TYPE NUMERIC USING lifetime_earned::NUMERIC;
ALTER TABLE public.economy_profiles ALTER COLUMN lifetime_spent TYPE NUMERIC USING lifetime_spent::NUMERIC;
ALTER TABLE public.coin_transactions ALTER COLUMN delta TYPE NUMERIC USING delta::NUMERIC;
ALTER TABLE public.coin_transactions ALTER COLUMN balance_after TYPE NUMERIC USING balance_after::NUMERIC;
ALTER TABLE public.mining_records ALTER COLUMN amount_earned TYPE NUMERIC USING amount_earned::NUMERIC;
ALTER TABLE public.cosmetic_catalog ALTER COLUMN price_coins TYPE NUMERIC USING price_coins::NUMERIC;
ALTER TABLE public.payment_transactions ALTER COLUMN coins_to_credit TYPE NUMERIC USING coins_to_credit::NUMERIC;
ALTER TABLE public.chat_topics ALTER COLUMN coin_gate TYPE NUMERIC USING coin_gate::NUMERIC;

ALTER TABLE public.economy_profiles ADD CONSTRAINT ck_economy_profiles_coin_balance_scale3 CHECK (scale(coin_balance) <= 3);
ALTER TABLE public.economy_profiles ADD CONSTRAINT ck_economy_profiles_lifetime_earned_scale3 CHECK (scale(lifetime_earned) <= 3);
ALTER TABLE public.economy_profiles ADD CONSTRAINT ck_economy_profiles_lifetime_spent_scale3 CHECK (scale(lifetime_spent) <= 3);
ALTER TABLE public.coin_transactions ADD CONSTRAINT ck_coin_transactions_delta_scale3 CHECK (scale(delta) <= 3);
ALTER TABLE public.coin_transactions ADD CONSTRAINT ck_coin_transactions_balance_after_scale3 CHECK (scale(balance_after) <= 3);
ALTER TABLE public.mining_records ADD CONSTRAINT ck_mining_records_amount_earned_scale3 CHECK (scale(amount_earned) <= 3);
ALTER TABLE public.cosmetic_catalog ADD CONSTRAINT ck_cosmetic_catalog_price_coins_scale3 CHECK (scale(price_coins) <= 3);
ALTER TABLE public.payment_transactions ADD CONSTRAINT ck_payment_transactions_coins_to_credit_scale3 CHECK (scale(coins_to_credit) <= 3);
ALTER TABLE public.chat_topics ADD CONSTRAINT ck_chat_topics_coin_gate_scale3 CHECK (scale(coin_gate) <= 3);

-- Bulk gift: per-member amounts may carry up to 3 decimals.
DROP FUNCTION IF EXISTS public.admin_gift_all_coins(INTEGER, TEXT, TEXT, TEXT[]);
CREATE FUNCTION public.admin_gift_all_coins(
    p_delta NUMERIC,
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
$$;
REVOKE ALL ON FUNCTION public.admin_gift_all_coins(NUMERIC, TEXT, TEXT, TEXT[]) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.admin_gift_all_coins(NUMERIC, TEXT, TEXT, TEXT[]) TO authenticated;

-- Peer transfers: amounts may carry up to 3 decimals.
DROP FUNCTION IF EXISTS public.transfer_coins_atomic(TEXT, TEXT, BIGINT);
CREATE FUNCTION public.transfer_coins_atomic(p_sender_id text, p_recipient_email text, p_amount numeric)
 RETURNS jsonb
 LANGUAGE plpgsql
 SECURITY DEFINER
 SET search_path TO 'pg_catalog', 'public'
AS $function$
DECLARE
    v_sender_balance NUMERIC;
    v_recipient_id TEXT;
    v_recipient_balance NUMERIC;
    v_transfer_id UUID := gen_random_uuid();
    v_email TEXT := LOWER(TRIM(p_recipient_email));
BEGIN
    IF auth.uid() IS NULL OR auth.uid()::TEXT <> p_sender_id THEN
        RETURN jsonb_build_object('success', FALSE, 'message', 'Not authorized');
    END IF;
    IF public.is_cyclecare_account_restricted(p_sender_id) THEN
        RETURN jsonb_build_object('success', FALSE, 'message', 'This account is suspended or banned');
    END IF;
    IF p_amount IS NULL OR p_amount <= 0 OR p_amount > 1000000 THEN
        RETURN jsonb_build_object('success', FALSE, 'message', 'Invalid transfer amount');
    END IF;
    IF scale(p_amount) > 3 THEN
        RETURN jsonb_build_object('success', FALSE, 'message', 'Cycle Coins support up to 3 decimal places');
    END IF;
    SELECT coin_balance INTO v_sender_balance FROM public.economy_profiles WHERE user_id = p_sender_id FOR UPDATE;
    IF v_sender_balance IS NULL OR v_sender_balance < p_amount THEN
        RETURN jsonb_build_object('success', FALSE, 'message', 'Insufficient coin balance');
    END IF;
    SELECT id::TEXT INTO v_recipient_id FROM auth.users WHERE LOWER(email) = v_email LIMIT 1;
    IF v_recipient_id IS NULL OR v_recipient_id = p_sender_id THEN
        RETURN jsonb_build_object('success', FALSE, 'message', 'Recipient account is invalid');
    END IF;
    IF public.is_cyclecare_account_restricted(v_recipient_id) THEN
        RETURN jsonb_build_object('success', FALSE, 'message', 'Recipient account cannot receive transfers');
    END IF;
    UPDATE public.economy_profiles SET coin_balance = coin_balance - p_amount,
        lifetime_spent = lifetime_spent + p_amount, updated_at = NOW() WHERE user_id = p_sender_id;
    INSERT INTO public.economy_profiles(user_id, coin_balance, lifetime_earned, lifetime_spent, unlocked_items)
    VALUES (v_recipient_id, 120 + p_amount, 120 + p_amount, 0, '["cycle_coin"]'::JSONB)
    ON CONFLICT (user_id) DO UPDATE SET coin_balance = public.economy_profiles.coin_balance + p_amount,
        lifetime_earned = public.economy_profiles.lifetime_earned + p_amount, updated_at = NOW() RETURNING coin_balance INTO v_recipient_balance;
    INSERT INTO public.coin_transactions(user_id,action,delta,balance_after,metadata) VALUES
    (p_sender_id,'TRANSFER_SENT',-p_amount,v_sender_balance-p_amount,jsonb_build_object('source','transfer_coins_atomic','transfer_id',v_transfer_id,'sender_id',p_sender_id,'receiver_id',v_recipient_id)),
    (v_recipient_id,'TRANSFER_RECEIVED',p_amount,v_recipient_balance,jsonb_build_object('source','transfer_coins_atomic','transfer_id',v_transfer_id,'sender_id',p_sender_id,'receiver_id',v_recipient_id));
    INSERT INTO public.user_notifications(user_id, target_identifier, title, body, type, priority, sender)
    VALUES (v_recipient_id, v_recipient_id, 'Cycle Coins Received!',
        'You have received ' || trim_scale(p_amount) || ' Cycle Coins from a peer.', 'GIFT', 'HIGH', 'CYCLECARE_TRANSFER');
    RETURN jsonb_build_object('success', TRUE, 'message', 'Transfer completed',
        'transferred_amount', p_amount, 'remaining_balance', v_sender_balance - p_amount);
END;
$function$;
REVOKE ALL ON FUNCTION public.transfer_coins_atomic(TEXT, TEXT, NUMERIC) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.transfer_coins_atomic(TEXT, TEXT, NUMERIC) TO authenticated;

-- Cosmetic prices: may carry up to 3 decimals.
DROP FUNCTION IF EXISTS public.admin_upsert_cosmetic(TEXT, TEXT, TEXT, TEXT, BIGINT, TEXT, BOOLEAN, INTEGER, BOOLEAN);
CREATE FUNCTION public.admin_upsert_cosmetic(p_item_id TEXT, p_kind TEXT, p_name TEXT, p_description TEXT, p_price_coins NUMERIC, p_rarity TEXT, p_is_active BOOLEAN, p_sort_order INTEGER DEFAULT 0, p_premium_included BOOLEAN DEFAULT FALSE)
RETURNS JSONB
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, public
AS $$
BEGIN
    IF NOT public.is_cyclecare_admin() THEN RAISE EXCEPTION 'Administrator access required'; END IF;
    IF COALESCE(p_item_id,'') !~ '^[a-z0-9_]{3,60}$' THEN RAISE EXCEPTION 'Item id must be lowercase letters, numbers, underscores'; END IF;
    IF UPPER(COALESCE(p_kind,'')) NOT IN ('ARTIFACT','FRAME','THEME','BADGE') THEN RAISE EXCEPTION 'Kind must be ARTIFACT, FRAME, THEME or BADGE'; END IF;
    IF LENGTH(COALESCE(p_name,'')) NOT BETWEEN 1 AND 80 THEN RAISE EXCEPTION 'Name is required'; END IF;
    IF COALESCE(p_price_coins, -1) < 0 OR p_price_coins > 1000000 THEN RAISE EXCEPTION 'Price must be between 0 and 1,000,000 coins'; END IF;
    IF scale(p_price_coins) > 3 THEN RAISE EXCEPTION 'Cycle Coins support up to 3 decimal places'; END IF;
    IF UPPER(COALESCE(p_rarity,'CLASSIC')) NOT IN ('CLASSIC','RARE','EPIC','LEGENDARY') THEN RAISE EXCEPTION 'Rarity must be CLASSIC, RARE, EPIC or LEGENDARY'; END IF;
    INSERT INTO public.cosmetic_catalog(item_id, kind, name, description, price_coins, premium_included, rarity, asset_key, is_active, sort_order)
    VALUES (p_item_id, UPPER(p_kind), p_name, LEFT(COALESCE(p_description,''), 500), p_price_coins, COALESCE(p_premium_included, FALSE), UPPER(COALESCE(p_rarity,'CLASSIC')), p_item_id, COALESCE(p_is_active, TRUE), COALESCE(p_sort_order, 0))
    ON CONFLICT (item_id) DO UPDATE SET kind = EXCLUDED.kind, name = EXCLUDED.name, description = EXCLUDED.description,
        price_coins = EXCLUDED.price_coins, premium_included = EXCLUDED.premium_included, rarity = EXCLUDED.rarity,
        is_active = EXCLUDED.is_active, sort_order = EXCLUDED.sort_order;
    INSERT INTO public.admin_audit_logs(admin_user_id, admin_action, target_user_id, details, performed_by)
    VALUES (auth.uid(), 'ADMIN_UPSERT_COSMETIC', p_item_id,
            jsonb_build_object('price_coins', p_price_coins, 'is_active', COALESCE(p_is_active, TRUE)), auth.uid()::TEXT);
    RETURN jsonb_build_object('success', TRUE, 'item_id', p_item_id);
END;
$$;
REVOKE ALL ON FUNCTION public.admin_upsert_cosmetic(TEXT, TEXT, TEXT, TEXT, NUMERIC, TEXT, BOOLEAN, INTEGER, BOOLEAN) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.admin_upsert_cosmetic(TEXT, TEXT, TEXT, TEXT, NUMERIC, TEXT, BOOLEAN, INTEGER, BOOLEAN) TO authenticated;

-- Admin coin adjustments accept decimal amounts; the percentage penalty keeps
-- its original whole-coin FLOOR semantics, unchanged.
CREATE OR REPLACE FUNCTION public.admin_apply_action(p_action text, p_target_user_id text, p_payload jsonb DEFAULT '{}'::jsonb)
 RETURNS jsonb
 LANGUAGE plpgsql
 SECURITY DEFINER
 SET search_path TO 'pg_catalog', 'public'
AS $function$
DECLARE
    v_action TEXT := UPPER(TRIM(p_action));
    v_input_target TEXT := NULLIF(TRIM(p_target_user_id), '');
    v_target TEXT;
    v_status TEXT;
    v_reason TEXT;
    v_until TIMESTAMPTZ;
    v_delta_numeric NUMERIC;
    v_delta NUMERIC;
    v_balance NUMERIC;
    v_deducted NUMERIC;
    v_percent NUMERIC;
    v_artifact TEXT;
    v_rarity TEXT;
    v_artifacts JSONB;
    v_title TEXT;
    v_body TEXT;
    v_kind TEXT;
    v_tier TEXT;
    v_message_id UUID;
    v_rows INTEGER;
    v_payload_details JSONB;
BEGIN
    IF NOT public.is_cyclecare_admin() THEN RAISE EXCEPTION 'Administrator access required'; END IF;
    IF v_input_target IS NULL OR length(v_input_target) > 200 THEN RAISE EXCEPTION 'A valid target account is required'; END IF;
    IF p_payload IS NULL OR jsonb_typeof(p_payload) <> 'object' THEN RAISE EXCEPTION 'Invalid action payload'; END IF;
    SELECT COALESCE(NULLIF(user_id::TEXT, ''), NULLIF(device_id, ''), id::TEXT)
      INTO v_target
      FROM public.profiles
     WHERE user_id::TEXT = v_input_target OR device_id = v_input_target OR id::TEXT = v_input_target
     ORDER BY CASE WHEN user_id::TEXT = v_input_target THEN 0 ELSE 1 END
     LIMIT 1;
    IF v_target IS NULL THEN RAISE EXCEPTION 'Target CycleCare profile was not found'; END IF;

    IF v_action = 'STATUS' THEN
        v_status := UPPER(COALESCE(p_payload->>'status', ''));
        IF v_status NOT IN ('ACTIVE', 'SUSPENDED', 'BANNED') THEN RAISE EXCEPTION 'Invalid account status'; END IF;
        v_reason := NULLIF(LEFT(TRIM(COALESCE(p_payload->>'reason', '')), 500), '');
        v_until := NULL;
        IF v_status = 'SUSPENDED' THEN
            IF COALESCE((p_payload->>'duration_hours')::INTEGER, 0) NOT BETWEEN 1 AND 8760 THEN
                RAISE EXCEPTION 'Suspension duration must be between 1 hour and 1 year';
            END IF;
            v_until := NOW() + ((p_payload->>'duration_hours')::INTEGER * INTERVAL '1 hour');
        END IF;
        UPDATE public.profiles SET status = v_status, suspension_reason = v_reason,
            suspended_until = v_until, updated_at = NOW()
        WHERE user_id::TEXT = v_target OR device_id = v_target OR id::TEXT = v_target;
        GET DIAGNOSTICS v_rows = ROW_COUNT;
        UPDATE public.economy_profiles SET status = v_status, suspension_reason = v_reason,
            suspended_until = v_until, updated_at = NOW() WHERE user_id = v_target;
        INSERT INTO public.user_notifications(user_id, target_identifier, title, body, type, priority, sender)
        VALUES (v_target, v_target,
            CASE WHEN v_status = 'BANNED' THEN 'Account Notice: Account Banned' ELSE 'Account Notice: Account Status Updated' END,
            CASE WHEN v_reason IS NULL THEN 'Your account status has been updated to ' || v_status || '.' ELSE 'Admin notice: ' || v_reason END,
            'SECURITY', 'HIGH', 'CYCLECARE_OFFICIAL');
        v_payload_details := jsonb_build_object('status', v_status, 'reason', v_reason, 'suspended_until', v_until, 'profiles_updated', v_rows);

    ELSIF v_action = 'TIER' THEN
        v_tier := UPPER(COALESCE(p_payload->>'tier', ''));
        IF v_tier NOT IN ('FREE', 'SUPPORTER', 'PREMIUM') THEN RAISE EXCEPTION 'Invalid tier'; END IF;
        UPDATE public.economy_profiles SET tier = v_tier, updated_at = NOW() WHERE user_id = v_target;
        IF NOT FOUND THEN RAISE EXCEPTION 'Wallet not found for this account'; END IF;
        INSERT INTO public.user_notifications(user_id, target_identifier, title, body, type, priority, sender)
        VALUES (v_target, v_target, 'Membership Updated',
            'Your CycleCare membership is now ' || v_tier || '.', 'SYSTEM', 'HIGH', 'CYCLECARE_OFFICIAL');
        v_payload_details := jsonb_build_object('tier', v_tier);

    ELSIF v_action = 'COINS' THEN
        v_delta_numeric := COALESCE((p_payload->>'delta')::NUMERIC, 0);
        IF v_delta_numeric = 0 OR scale(v_delta_numeric) > 3 OR ABS(v_delta_numeric) > 1000000 THEN
            RAISE EXCEPTION 'Coin adjustment must be a non-zero amount with up to 3 decimal places, up to 1,000,000';
        END IF;
        v_delta := v_delta_numeric;
        SELECT coin_balance INTO v_balance FROM public.economy_profiles WHERE user_id = v_target FOR UPDATE;
        IF NOT FOUND THEN
            IF v_delta < 0 THEN RAISE EXCEPTION 'Cannot debit a user with no wallet'; END IF;
            INSERT INTO public.economy_profiles(user_id, coin_balance, lifetime_earned, lifetime_spent, unlocked_items)
            VALUES (v_target, v_delta, v_delta, 0, '["cycle_coin"]'::JSONB);
            v_balance := v_delta;
        ELSE
            IF v_delta < 0 THEN v_delta := -LEAST(COALESCE(v_balance, 0), ABS(v_delta)); END IF;
            UPDATE public.economy_profiles SET coin_balance = COALESCE(coin_balance, 0) + v_delta,
                lifetime_earned = COALESCE(lifetime_earned, 0) + GREATEST(v_delta, 0), updated_at = NOW()
            WHERE user_id = v_target RETURNING coin_balance INTO v_balance;
        END IF;
        v_reason := NULLIF(LEFT(TRIM(COALESCE(p_payload->>'reason', '')), 500), '');
        INSERT INTO public.user_notifications(user_id, target_identifier, title, body, type, priority, sender)
        VALUES (v_target, v_target, CASE WHEN v_delta >= 0 THEN 'Cycle Coins Received!' ELSE 'Account Balance Adjustment' END,
            CASE WHEN v_delta >= 0 THEN 'You have received ' || trim_scale(v_delta) || ' Cycle Coins from CycleCare Administration.'
                 ELSE 'Your balance was adjusted by ' || trim_scale(v_delta) || ' Cycle Coins. ' || COALESCE(v_reason, '') END,
            CASE WHEN v_delta >= 0 THEN 'REWARD' ELSE 'PENALTY' END, 'HIGH', 'CYCLECARE_OFFICIAL');
        INSERT INTO public.coin_transactions(user_id, action, delta, balance_after, metadata)
        VALUES (v_target, CASE WHEN v_delta >= 0 THEN 'ADMIN_CREDIT' ELSE 'ADMIN_DEBIT' END,
            v_delta, v_balance, jsonb_build_object('reason', v_reason, 'source', 'admin_apply_action'));
        v_payload_details := jsonb_build_object('delta', v_delta, 'new_balance', v_balance, 'reason', v_reason);

    ELSIF v_action = 'GIFT' THEN
        v_artifact := NULLIF(LEFT(TRIM(COALESCE(p_payload->>'artifact_name', '')), 120), '');
        v_rarity := NULLIF(LEFT(TRIM(COALESCE(p_payload->>'artifact_rarity', 'Rare')), 40), '');
        IF v_artifact NOT IN ('Luminescent Moon', 'Golden Blossom', 'Rose Obelisk', 'Dual Eclipse', 'Celestial Tiara') THEN
            RAISE EXCEPTION 'Artifact is not in the approved catalog';
        END IF;
        SELECT artifacts INTO v_artifacts FROM public.economy_profiles WHERE user_id = v_target FOR UPDATE;
        IF NOT FOUND THEN
            v_artifacts := jsonb_build_array(jsonb_build_object('name', v_artifact, 'rarity', COALESCE(v_rarity, 'Rare'),
                'giftedAt', NOW(), 'giftedBy', 'CycleCare Administration'));
            INSERT INTO public.economy_profiles(user_id, artifacts, unlocked_items) VALUES (v_target, v_artifacts, '["cycle_coin"]'::JSONB);
        ELSE
            v_artifacts := CASE WHEN jsonb_typeof(COALESCE(v_artifacts, '[]'::JSONB)) = 'array' THEN COALESCE(v_artifacts, '[]'::JSONB) ELSE '[]'::JSONB END
                || jsonb_build_array(jsonb_build_object('name', v_artifact, 'rarity', COALESCE(v_rarity, 'Rare'),
                    'giftedAt', NOW(), 'giftedBy', 'CycleCare Administration'));
            UPDATE public.economy_profiles SET artifacts = v_artifacts, updated_at = NOW() WHERE user_id = v_target;
        END IF;
        INSERT INTO public.user_notifications(user_id, target_identifier, title, body, type, priority, sender)
        VALUES (v_target, v_target, 'Exclusive Gift: ' || v_artifact,
            'CycleCare Administration has gifted you a collectible artifact: ' || v_artifact || '!', 'GIFT', 'HIGH', 'CYCLECARE_OFFICIAL');
        v_payload_details := jsonb_build_object('artifact', v_artifact, 'rarity', v_rarity);

    ELSIF v_action = 'PENALTY' THEN
        v_percent := COALESCE((p_payload->>'percent')::NUMERIC, 0);
        IF v_percent <= 0 OR v_percent > 100 THEN RAISE EXCEPTION 'Penalty must be between 1 and 100 percent'; END IF;
        SELECT coin_balance INTO v_balance FROM public.economy_profiles WHERE user_id = v_target FOR UPDATE;
        IF NOT FOUND THEN RAISE EXCEPTION 'Wallet not found'; END IF;
        v_deducted := FLOOR(COALESCE(v_balance, 0) * v_percent / 100);
        UPDATE public.economy_profiles SET coin_balance = GREATEST(0, COALESCE(coin_balance, 0) - v_deducted),
            last_penalized_at = NOW(), updated_at = NOW() WHERE user_id = v_target RETURNING coin_balance INTO v_balance;
        INSERT INTO public.user_notifications(user_id, target_identifier, title, body, type, priority, sender)
        VALUES (v_target, v_target, 'Account Inactivity / Policy Notice',
            'An inactivity adjustment of ' || trim_scale(v_deducted) || ' coins (' || trim_scale(v_percent) || '%) was applied.', 'WARNING', 'NORMAL', 'CYCLECARE_OFFICIAL');
        v_payload_details := jsonb_build_object('deducted', v_deducted, 'new_balance', v_balance, 'percent', v_percent);

    ELSIF v_action = 'CUSTOM_MSG' THEN
        v_title := NULLIF(LEFT(TRIM(COALESCE(p_payload->>'title', '')), 160), '');
        v_body := NULLIF(LEFT(TRIM(COALESCE(p_payload->>'body', '')), 4000), '');
        v_kind := UPPER(COALESCE(p_payload->>'type', 'SYSTEM'));
        IF v_title IS NULL OR v_body IS NULL THEN RAISE EXCEPTION 'Message title and body are required'; END IF;
        IF v_kind NOT IN ('SYSTEM','SECURITY','REWARD','GIFT','WARNING','ALERT','SYSTEM_ANNOUNCEMENT','CARE_TIP','PROMO') THEN
            RAISE EXCEPTION 'Invalid notification type';
        END IF;
        INSERT INTO public.in_house_messages(title, message, message_type, target_audience, target_user_id, sent_by)
        VALUES (v_title, v_body, v_kind, 'INDIVIDUAL', v_target, auth.uid()::TEXT) RETURNING id INTO v_message_id;
        INSERT INTO public.user_notifications(message_id, user_id, target_identifier, title, body, type, priority, sender)
        VALUES (v_message_id, v_target, v_target, v_title, v_body, v_kind, 'HIGH', 'CYCLECARE_OFFICIAL');
        v_payload_details := jsonb_build_object('title', v_title, 'message_id', v_message_id);
    ELSE
        RAISE EXCEPTION 'Unsupported admin action';
    END IF;

    INSERT INTO public.admin_audit_logs(admin_user_id, admin_action, target_user_id, details, performed_by)
    VALUES (auth.uid(), 'ADMIN_' || v_action, v_target, COALESCE(v_payload_details, '{}'::JSONB), auth.uid()::TEXT);
    RETURN jsonb_build_object('success', TRUE, 'action', v_action, 'target_user_id', v_target, 'details', v_payload_details);
END;
$function$;


CREATE OR REPLACE FUNCTION public.verify_and_mine_coins(
    p_user_id TEXT,
    p_timezone_name TEXT DEFAULT 'UTC'
) RETURNS JSONB
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, public
AS $$
DECLARE
    v_profile RECORD;
    v_timezone TEXT;
    v_today DATE;
    v_multiplier NUMERIC := 1.0;
    v_awarded NUMERIC;
    v_new_balance NUMERIC;
    v_new_lifetime NUMERIC;
    v_new_rank TEXT;
BEGIN
    IF auth.uid() IS NULL OR auth.uid()::TEXT <> p_user_id THEN
        RETURN jsonb_build_object('success', FALSE, 'message', 'Not authorized');
    END IF;
    IF NOT EXISTS (SELECT 1 FROM public.cyclecare_user_timezones WHERE user_id = p_user_id) THEN
        IF NOT EXISTS (SELECT 1 FROM pg_timezone_names WHERE name = p_timezone_name) THEN
            RETURN jsonb_build_object('success', FALSE, 'message', 'Invalid timezone');
        END IF;
        INSERT INTO public.cyclecare_user_timezones(user_id, timezone_name)
        VALUES (p_user_id, p_timezone_name)
        ON CONFLICT (user_id) DO NOTHING;
    END IF;
    SELECT timezone_name INTO v_timezone FROM public.cyclecare_user_timezones WHERE user_id = p_user_id;
    v_today := (NOW() AT TIME ZONE v_timezone)::DATE;

    INSERT INTO public.economy_profiles (
        user_id, coin_balance, lifetime_earned, lifetime_spent, tier, rank, last_mined_date, unlocked_items
    ) VALUES (p_user_id, 120, 120, 0, 'FREE', 'LUMEN', NULL, '["cycle_coin"]'::JSONB)
    ON CONFLICT (user_id) DO NOTHING;
    SELECT * INTO v_profile FROM public.economy_profiles WHERE user_id = p_user_id FOR UPDATE;

    IF v_profile.last_mined_date = v_today THEN
        RETURN jsonb_build_object('success', FALSE, 'message', 'You have already claimed your daily reward today. Returns at midnight.');
    END IF;

    CASE UPPER(COALESCE(v_profile.tier, 'FREE'))
        WHEN 'SUPPORTER' THEN v_multiplier := 2.0;
        WHEN 'PREMIUM' THEN v_multiplier := 3.0;
        ELSE v_multiplier := 1.0;
    END CASE;
    v_awarded := ROUND(30 * v_multiplier);
    v_new_balance := COALESCE(v_profile.coin_balance, 0) + v_awarded;
    v_new_lifetime := COALESCE(v_profile.lifetime_earned, 0) + v_awarded;
    v_new_rank := CASE
        WHEN v_new_lifetime >= 7500 THEN 'FOUNDER'
        WHEN v_new_lifetime >= 3000 THEN 'NOCTURNE'
        WHEN v_new_lifetime >= 1200 THEN 'SOLARA'
        WHEN v_new_lifetime >= 500 THEN 'CELESTIA'
        WHEN v_new_lifetime >= 150 THEN 'AURELIA'
        ELSE 'LUMEN' END;

    INSERT INTO public.mining_records(user_id, claimed_date, amount_earned)
    VALUES (p_user_id, v_today, v_awarded);

    -- Log transaction in the immutable ledger
    INSERT INTO public.coin_transactions(user_id, action, delta, balance_after, metadata)
    VALUES (p_user_id, 'DAILY_ATTUNEMENT', v_awarded, v_new_balance, jsonb_build_object('date', v_today, 'multiplier', v_multiplier));

    UPDATE public.economy_profiles
    SET coin_balance = v_new_balance,
        lifetime_earned = v_new_lifetime,
        rank = v_new_rank,
        last_mined_date = v_today,
        updated_at = NOW()
    WHERE user_id = p_user_id;

    RETURN jsonb_build_object(
        'success', TRUE, 'message', 'Daily reward claimed successfully',
        'new_balance', v_new_balance,
        'lifetime_earned', v_new_lifetime,
        'new_rank', v_new_rank,
        'awarded', v_awarded,
        'mined_date', v_today
    );
END;
$$;

REVOKE ALL ON FUNCTION public.verify_and_mine_coins(TEXT, TEXT) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.verify_and_mine_coins(TEXT, TEXT) TO authenticated;

CREATE OR REPLACE FUNCTION public.apply_verified_payment(p_transaction_id uuid, p_expected_provider text, p_expected_amount numeric, p_expected_currency text, p_provider_reference text, p_provider_payload jsonb DEFAULT '{}'::jsonb)
 RETURNS jsonb
 LANGUAGE plpgsql
 SECURITY DEFINER
 SET search_path TO 'pg_catalog', 'public'
AS $function$
DECLARE
    v_tx RECORD;
    v_coins NUMERIC;
    v_balance NUMERIC;
    v_lifetime NUMERIC;
    v_rank TEXT;
BEGIN
    IF auth.role() <> 'service_role' THEN RETURN jsonb_build_object('success', FALSE, 'message', 'Service role required'); END IF;
    SELECT * INTO v_tx FROM public.transactions WHERE id = p_transaction_id FOR UPDATE;
    IF NOT FOUND THEN RETURN jsonb_build_object('success', FALSE, 'message', 'Transaction not found'); END IF;
    IF v_tx.provider <> p_expected_provider OR v_tx.currency <> p_expected_currency OR v_tx.amount <> p_expected_amount THEN
        RETURN jsonb_build_object('success', FALSE, 'message', 'Transaction amount or provider mismatch');
    END IF;
    IF v_tx.status = 'success' THEN RETURN jsonb_build_object('success', TRUE, 'duplicate', TRUE, 'message', 'Already credited'); END IF;
    IF v_tx.status <> 'pending' THEN RETURN jsonb_build_object('success', FALSE, 'message', 'Transaction is not pending'); END IF;
    v_coins := NULLIF(v_tx.metadata->>'coins_to_credit', '')::NUMERIC;
    IF v_coins IS NULL OR v_coins < 1 OR v_coins > 100000 OR scale(v_coins) > 3 THEN
        RETURN jsonb_build_object('success', FALSE, 'message', 'Invalid server package reward');
    END IF;
    INSERT INTO public.economy_profiles(user_id, coin_balance, lifetime_earned, lifetime_spent, tier, rank, unlocked_items)
    VALUES (v_tx.user_id, 120 + v_coins, 120 + v_coins, 0, 'FREE', 'LUMEN', '["cycle_coin"]'::JSONB)
    ON CONFLICT (user_id) DO UPDATE SET
        coin_balance = COALESCE(public.economy_profiles.coin_balance, 0) + v_coins,
        lifetime_earned = COALESCE(public.economy_profiles.lifetime_earned, 0) + v_coins,
        updated_at = NOW()
    RETURNING coin_balance, lifetime_earned INTO v_balance, v_lifetime;
    v_rank := CASE WHEN v_lifetime >= 7500 THEN 'FOUNDER' WHEN v_lifetime >= 3000 THEN 'NOCTURNE'
        WHEN v_lifetime >= 1200 THEN 'SOLARA' WHEN v_lifetime >= 500 THEN 'CELESTIA'
        WHEN v_lifetime >= 150 THEN 'AURELIA' ELSE 'LUMEN' END;
    UPDATE public.economy_profiles SET rank = v_rank, updated_at = NOW() WHERE user_id = v_tx.user_id;
    INSERT INTO public.coin_transactions(user_id,action,delta,balance_after,metadata)
    VALUES(v_tx.user_id,'PAYMENT_CREDIT',v_coins,v_balance,jsonb_build_object('source','apply_verified_payment','transaction_id',p_transaction_id,'provider',p_expected_provider,'provider_reference',p_provider_reference));
    UPDATE public.transactions SET status = 'success' , provider_reference = p_provider_reference,
        raw_payload = COALESCE(p_provider_payload, '{}'::JSONB), updated_at = NOW() WHERE id = p_transaction_id;
    RETURN jsonb_build_object('success', TRUE, 'new_balance', v_balance, 'lifetime_earned', v_lifetime, 'coins_credited', v_coins);
END;
$function$
;

-- PostgREST picks up the new column types and signatures.
NOTIFY pgrst, 'reload schema';
