-- CycleCare security and payment hardening.
-- Apply after 20261012181800_cyclecare_base_tables.sql and 20261012181900_cyclecare_core_tables.sql.

CREATE TABLE IF NOT EXISTS public.cyclecare_admin_roles (
    user_id UUID PRIMARY KEY REFERENCES auth.users(id) ON DELETE CASCADE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
ALTER TABLE public.cyclecare_admin_roles ENABLE ROW LEVEL SECURITY;
REVOKE ALL ON public.cyclecare_admin_roles FROM PUBLIC, anon, authenticated;
GRANT SELECT, INSERT, UPDATE, DELETE ON public.cyclecare_admin_roles TO service_role;

CREATE OR REPLACE FUNCTION public.is_cyclecare_admin()
RETURNS BOOLEAN
LANGUAGE SQL
STABLE
SECURITY DEFINER
SET search_path = pg_catalog, public
AS $$
    SELECT EXISTS (
        SELECT 1 FROM public.cyclecare_admin_roles r
        WHERE r.user_id = auth.uid()
    );
$$;
REVOKE ALL ON FUNCTION public.is_cyclecare_admin() FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.is_cyclecare_admin() TO authenticated, service_role;

-- Only authenticated, explicitly provisioned admins can use the management tables.
DO $$
DECLARE
    t TEXT;
    p RECORD;
BEGIN
    FOREACH t IN ARRAY ARRAY[
        'profiles', 'economy_profiles', 'transactions', 'user_notifications',
        'app_config', 'admin_audit_logs', 'in_house_messages', 'broadcast_dispatches',
        'mining_records', 'user_artifacts'
    ] LOOP
        IF to_regclass(format('public.%I', t)) IS NOT NULL THEN
            EXECUTE format('ALTER TABLE public.%I ENABLE ROW LEVEL SECURITY', t);
            FOR p IN
                SELECT policyname FROM pg_policies
                WHERE schemaname = 'public' AND tablename = t
                  AND policyname NOT ILIKE '%service role%'
            LOOP
                EXECUTE format('DROP POLICY IF EXISTS %I ON public.%I', p.policyname, t);
            END LOOP;
            EXECUTE format(
                'CREATE POLICY cyclecare_admin_all ON public.%I FOR ALL TO authenticated USING (public.is_cyclecare_admin()) WITH CHECK (public.is_cyclecare_admin())',
                t
            );
        END IF;
    END LOOP;
END $$;

-- Normal users may read only their own records; sensitive writes use the RPCs below.
CREATE POLICY cyclecare_profiles_owner_read ON public.profiles
    FOR SELECT TO authenticated USING (user_id = auth.uid()::TEXT);
CREATE POLICY cyclecare_economy_owner_read ON public.economy_profiles
    FOR SELECT TO authenticated USING (user_id = auth.uid()::TEXT);
CREATE POLICY cyclecare_transactions_owner_read ON public.transactions
    FOR SELECT TO authenticated USING (user_id = auth.uid()::TEXT);
CREATE POLICY cyclecare_notifications_owner_read ON public.user_notifications
    FOR SELECT TO authenticated USING (
        user_id = auth.uid()::TEXT OR target_identifier = auth.uid()::TEXT
        OR user_id = 'ALL' OR target_identifier = 'ALL'
    );
CREATE POLICY cyclecare_broadcast_owner_read ON public.broadcast_dispatches
    FOR SELECT TO authenticated USING (
        is_active = TRUE AND (target_user_id IS NULL OR target_user_id = auth.uid()::TEXT)
    );

GRANT SELECT ON public.profiles, public.economy_profiles, public.transactions,
    public.user_notifications, public.broadcast_dispatches TO authenticated;
GRANT SELECT, INSERT, UPDATE, DELETE ON public.profiles, public.economy_profiles,
    public.transactions, public.user_notifications, public.broadcast_dispatches,
    public.app_config, public.admin_audit_logs, public.in_house_messages TO authenticated;

-- Profile sync is scoped to the caller and cannot modify administrative status/role fields.
CREATE OR REPLACE FUNCTION public.sync_my_profile(
    p_device_id TEXT,
    p_account_mode TEXT,
    p_goals TEXT,
    p_last_period_date TEXT,
    p_cycle_length INTEGER,
    p_period_duration INTEGER,
    p_typical_symptoms TEXT,
    p_typical_energy TEXT,
    p_typical_sleep TEXT
) RETURNS JSONB
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, public
AS $$
DECLARE
    v_user_id TEXT := auth.uid()::TEXT;
BEGIN
    IF v_user_id IS NULL THEN
        RETURN jsonb_build_object('success', FALSE, 'message', 'Authentication required');
    END IF;
    INSERT INTO public.profiles (
        user_id, device_id, account_mode, goals, last_period_date, cycle_length,
        period_duration, typical_symptoms, typical_energy, typical_sleep
    ) VALUES (
        v_user_id, NULLIF(p_device_id, '')::UUID, COALESCE(p_account_mode, 'EMAIL'),
        COALESCE(p_goals, ''), NULLIF(p_last_period_date, ''),
        GREATEST(21, LEAST(COALESCE(p_cycle_length, 28), 45)),
        GREATEST(2, LEAST(COALESCE(p_period_duration, 5), 10)),
        COALESCE(p_typical_symptoms, ''), COALESCE(p_typical_energy, 'NORMAL'),
        COALESCE(p_typical_sleep, 'GOOD')
    )
    ON CONFLICT (user_id) DO UPDATE SET
        device_id = EXCLUDED.device_id,
        account_mode = EXCLUDED.account_mode,
        goals = EXCLUDED.goals,
        last_period_date = EXCLUDED.last_period_date,
        cycle_length = EXCLUDED.cycle_length,
        period_duration = EXCLUDED.period_duration,
        typical_symptoms = EXCLUDED.typical_symptoms,
        typical_energy = EXCLUDED.typical_energy,
        typical_sleep = EXCLUDED.typical_sleep,
        updated_at = NOW();
    RETURN jsonb_build_object('success', TRUE);
END;
$$;
REVOKE ALL ON FUNCTION public.sync_my_profile(TEXT,TEXT,TEXT,TEXT,INTEGER,INTEGER,TEXT,TEXT,TEXT) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.sync_my_profile(TEXT,TEXT,TEXT,TEXT,INTEGER,INTEGER,TEXT,TEXT,TEXT) TO authenticated;

-- Notification read-state changes cannot alter notification content or target identifiers.
CREATE OR REPLACE FUNCTION public.mark_my_notification_read(p_notification_id UUID DEFAULT NULL)
RETURNS JSONB
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, public
AS $$
DECLARE
    v_user_id TEXT := auth.uid()::TEXT;
    v_count INTEGER := 0;
BEGIN
    IF v_user_id IS NULL THEN
        RETURN jsonb_build_object('success', FALSE, 'message', 'Authentication required');
    END IF;
    IF p_notification_id IS NULL THEN
        UPDATE public.user_notifications
        SET is_read = TRUE
        WHERE user_id = v_user_id OR target_identifier = v_user_id
           OR user_id = 'ALL' OR target_identifier = 'ALL';
    ELSE
        UPDATE public.user_notifications
        SET is_read = TRUE
        WHERE id = p_notification_id
          AND (user_id = v_user_id OR target_identifier = v_user_id OR user_id = 'ALL' OR target_identifier = 'ALL');
    END IF;
    GET DIAGNOSTICS v_count = ROW_COUNT;
    RETURN jsonb_build_object('success', TRUE, 'updated', v_count);
END;
$$;
REVOKE ALL ON FUNCTION public.mark_my_notification_read(UUID) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.mark_my_notification_read(UUID) TO authenticated;

ALTER TABLE public.economy_profiles ADD COLUMN IF NOT EXISTS last_mined_date DATE;
CREATE TABLE IF NOT EXISTS public.cyclecare_user_timezones (
    user_id TEXT PRIMARY KEY,
    timezone_name TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
ALTER TABLE public.cyclecare_user_timezones ENABLE ROW LEVEL SECURITY;
REVOKE ALL ON public.cyclecare_user_timezones FROM PUBLIC, anon, authenticated;

CREATE TABLE IF NOT EXISTS public.mining_records (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id TEXT NOT NULL REFERENCES auth.users(id) ON DELETE CASCADE,
    claimed_date DATE NOT NULL,
    amount_earned BIGINT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT unique_user_calendar_day_claim UNIQUE (user_id, claimed_date)
);
ALTER TABLE public.mining_records ENABLE ROW LEVEL SECURITY;
CREATE POLICY cyclecare_mining_owner_read ON public.mining_records
    FOR SELECT TO authenticated USING (user_id = auth.uid()::TEXT);
GRANT SELECT ON public.mining_records TO authenticated;

-- Keep a single secure signature; timezone is pinned at the first authenticated claim.
DROP FUNCTION IF EXISTS public.verify_and_mine_coins(TEXT, BIGINT);
DROP FUNCTION IF EXISTS public.verify_and_mine_coins(TEXT, BIGINT, TEXT);
CREATE FUNCTION public.verify_and_mine_coins(
    p_user_id TEXT,
    p_base_reward BIGINT DEFAULT 25,
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
    v_awarded BIGINT;
    v_new_balance BIGINT;
    v_new_lifetime BIGINT;
    v_new_rank TEXT;
BEGIN
    IF auth.uid() IS NULL OR auth.uid()::TEXT <> p_user_id THEN
        RETURN jsonb_build_object('success', FALSE, 'message', 'Not authorized');
    END IF;
    IF p_base_reward IS NULL OR p_base_reward < 1 OR p_base_reward > 100 THEN
        RETURN jsonb_build_object('success', FALSE, 'message', 'Invalid reward amount');
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

    INSERT INTO public.economy_profiles (user_id, coin_balance, lifetime_earned, lifetime_spent, tier, rank, last_mined_date, unlocked_items)
    VALUES (p_user_id, 120, 120, 0, 'FREE', 'LUMEN', NULL, '["cycle_coin"]'::JSONB)
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
    v_awarded := ROUND(p_base_reward * v_multiplier);
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
    UPDATE public.economy_profiles
    SET coin_balance = v_new_balance,
        lifetime_earned = v_new_lifetime,
        rank = v_new_rank,
        last_mined_date = v_today,
        updated_at = NOW()
    WHERE user_id = p_user_id;

    RETURN jsonb_build_object(
        'success', TRUE, 'message', 'Daily reward claimed successfully',
        'awarded_coins', v_awarded, 'new_balance', v_new_balance,
        'lifetime_earned', v_new_lifetime, 'new_rank', v_new_rank,
        'mined_date', v_today::TEXT
    );
EXCEPTION WHEN unique_violation THEN
    RETURN jsonb_build_object('success', FALSE, 'message', 'You have already claimed your daily reward today. Returns at midnight.');
END;
$$;
REVOKE ALL ON FUNCTION public.verify_and_mine_coins(TEXT,BIGINT,TEXT) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.verify_and_mine_coins(TEXT,BIGINT,TEXT) TO authenticated;

-- Atomic server-side cosmetic purchase; costs come from this fixed catalog, not the client.
CREATE OR REPLACE FUNCTION public.purchase_economy_item(p_item_id TEXT)
RETURNS JSONB
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, public
AS $$
DECLARE
    v_user_id TEXT := auth.uid()::TEXT;
    v_price BIGINT;
    v_kind TEXT;
    v_profile RECORD;
BEGIN
    IF v_user_id IS NULL THEN RETURN jsonb_build_object('success', FALSE, 'message', 'Authentication required'); END IF;
    SELECT price, kind INTO v_price, v_kind FROM (VALUES
        ('artifact_moon', 250::BIGINT, 'ARTIFACT'),
        ('artifact_bloom', 450::BIGINT, 'ARTIFACT'),
        ('artifact_crystal', 800::BIGINT, 'ARTIFACT'),
        ('artifact_orbit', 1200::BIGINT, 'ARTIFACT'),
        ('frame_elegant', 350::BIGINT, 'FRAME')
    ) AS catalog(item_id, price, kind) WHERE item_id = p_item_id;
    IF v_price IS NULL THEN RETURN jsonb_build_object('success', FALSE, 'message', 'Item is not available'); END IF;

    SELECT * INTO v_profile FROM public.economy_profiles WHERE user_id = v_user_id FOR UPDATE;
    IF NOT FOUND THEN RETURN jsonb_build_object('success', FALSE, 'message', 'Economy profile not found'); END IF;
    IF COALESCE(v_profile.unlocked_items, '[]'::JSONB) ? p_item_id THEN
        RETURN jsonb_build_object('success', FALSE, 'message', 'Item already unlocked');
    END IF;
    IF COALESCE(v_profile.coin_balance, 0) < v_price THEN
        RETURN jsonb_build_object('success', FALSE, 'message', 'Insufficient Cycle Coins');
    END IF;

    UPDATE public.economy_profiles SET
        coin_balance = COALESCE(coin_balance, 0) - v_price,
        lifetime_spent = COALESCE(lifetime_spent, 0) + v_price,
        unlocked_items = COALESCE(unlocked_items, '["cycle_coin"]'::JSONB) || jsonb_build_array(p_item_id),
        active_artifact = CASE WHEN v_kind = 'ARTIFACT' THEN p_item_id ELSE active_artifact END,
        active_frame = CASE WHEN v_kind = 'FRAME' THEN p_item_id ELSE active_frame END,
        updated_at = NOW()
    WHERE user_id = v_user_id;
    RETURN jsonb_build_object('success', TRUE, 'message', 'Item unlocked', 'item_id', p_item_id);
END;
$$;
REVOKE ALL ON FUNCTION public.purchase_economy_item(TEXT) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.purchase_economy_item(TEXT) TO authenticated;

-- Do not accept caller-supplied sender IDs for transfers.
CREATE OR REPLACE FUNCTION public.transfer_coins_atomic(
    p_sender_id TEXT,
    p_recipient_email TEXT,
    p_amount BIGINT
) RETURNS JSONB
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, public
AS $$
DECLARE
    v_sender_balance BIGINT;
    v_recipient_id TEXT;
    v_clean_email TEXT;
BEGIN
    IF auth.uid() IS NULL OR auth.uid()::TEXT <> p_sender_id THEN
        RETURN jsonb_build_object('success', FALSE, 'message', 'Not authorized');
    END IF;
    IF p_amount <= 0 OR p_amount > 1000000 THEN
        RETURN jsonb_build_object('success', FALSE, 'message', 'Invalid transfer amount');
    END IF;
    v_clean_email := LOWER(TRIM(p_recipient_email));
    SELECT coin_balance INTO v_sender_balance FROM public.economy_profiles WHERE user_id = p_sender_id FOR UPDATE;
    IF v_sender_balance IS NULL OR v_sender_balance < p_amount THEN
        RETURN jsonb_build_object('success', FALSE, 'message', 'Insufficient coin balance');
    END IF;
    SELECT id::TEXT INTO v_recipient_id FROM auth.users WHERE LOWER(email) = v_clean_email LIMIT 1;
    IF v_recipient_id IS NULL THEN
        SELECT user_id INTO v_recipient_id FROM public.profiles WHERE LOWER(user_id) = v_clean_email LIMIT 1;
    END IF;
    IF v_recipient_id IS NULL OR v_recipient_id = p_sender_id THEN
        RETURN jsonb_build_object('success', FALSE, 'message', 'Recipient account is invalid');
    END IF;

    UPDATE public.economy_profiles
    SET coin_balance = coin_balance - p_amount, lifetime_spent = lifetime_spent + p_amount, updated_at = NOW()
    WHERE user_id = p_sender_id;
    INSERT INTO public.economy_profiles(user_id, coin_balance, lifetime_earned, lifetime_spent, unlocked_items)
    VALUES (v_recipient_id, p_amount, p_amount, 0, '["cycle_coin"]'::JSONB)
    ON CONFLICT (user_id) DO UPDATE SET
        coin_balance = public.economy_profiles.coin_balance + EXCLUDED.coin_balance,
        lifetime_earned = public.economy_profiles.lifetime_earned + EXCLUDED.lifetime_earned,
        updated_at = NOW();
    RETURN jsonb_build_object('success', TRUE, 'message', 'Transfer completed', 'transferred_amount', p_amount, 'remaining_balance', v_sender_balance - p_amount);
END;
$$;
REVOKE ALL ON FUNCTION public.transfer_coins_atomic(TEXT,TEXT,BIGINT) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.transfer_coins_atomic(TEXT,TEXT,BIGINT) TO authenticated;

-- Payment completion is callable only by service-role Edge Functions and credits once atomically.
CREATE OR REPLACE FUNCTION public.apply_verified_payment(
    p_transaction_id UUID,
    p_expected_provider TEXT,
    p_expected_amount NUMERIC,
    p_expected_currency TEXT,
    p_provider_reference TEXT,
    p_provider_payload JSONB DEFAULT '{}'::JSONB
) RETURNS JSONB
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, public
AS $$
DECLARE
    v_tx RECORD;
    v_coins BIGINT;
    v_balance BIGINT;
    v_lifetime BIGINT;
    v_rank TEXT;
BEGIN
    IF auth.role() <> 'service_role' THEN
        RETURN jsonb_build_object('success', FALSE, 'message', 'Service role required');
    END IF;
    SELECT * INTO v_tx FROM public.transactions WHERE id = p_transaction_id FOR UPDATE;
    IF NOT FOUND THEN RETURN jsonb_build_object('success', FALSE, 'message', 'Transaction not found'); END IF;
    IF v_tx.provider <> p_expected_provider OR v_tx.currency <> p_expected_currency
       OR v_tx.amount <> p_expected_amount THEN
        RETURN jsonb_build_object('success', FALSE, 'message', 'Transaction amount or provider mismatch');
    END IF;
    IF v_tx.status = 'success' THEN
        RETURN jsonb_build_object('success', TRUE, 'duplicate', TRUE, 'message', 'Already credited');
    END IF;
    IF v_tx.status <> 'pending' THEN
        RETURN jsonb_build_object('success', FALSE, 'message', 'Transaction is not pending');
    END IF;
    v_coins := NULLIF(v_tx.metadata->>'coins_to_credit', '')::BIGINT;
    IF v_coins IS NULL OR v_coins < 1 OR v_coins > 100000 THEN
        RETURN jsonb_build_object('success', FALSE, 'message', 'Invalid server package reward');
    END IF;

    INSERT INTO public.economy_profiles(user_id, coin_balance, lifetime_earned, lifetime_spent, tier, rank, unlocked_items)
    VALUES (v_tx.user_id, 120 + v_coins, 120 + v_coins, 0, 'FREE', 'LUMEN', '["cycle_coin"]'::JSONB)
    ON CONFLICT (user_id) DO UPDATE SET
        coin_balance = COALESCE(public.economy_profiles.coin_balance, 0) + v_coins,
        lifetime_earned = COALESCE(public.economy_profiles.lifetime_earned, 0) + v_coins,
        updated_at = NOW()
    RETURNING coin_balance, lifetime_earned INTO v_balance, v_lifetime;
    v_rank := CASE
        WHEN v_lifetime >= 7500 THEN 'FOUNDER'
        WHEN v_lifetime >= 3000 THEN 'NOCTURNE'
        WHEN v_lifetime >= 1200 THEN 'SOLARA'
        WHEN v_lifetime >= 500 THEN 'CELESTIA'
        WHEN v_lifetime >= 150 THEN 'AURELIA'
        ELSE 'LUMEN' END;
    UPDATE public.economy_profiles SET rank = v_rank, updated_at = NOW() WHERE user_id = v_tx.user_id;
    UPDATE public.transactions SET
        status = 'success', provider_reference = p_provider_reference,
        raw_payload = COALESCE(p_provider_payload, '{}'::JSONB), updated_at = NOW()
    WHERE id = p_transaction_id;
    RETURN jsonb_build_object('success', TRUE, 'new_balance', v_balance, 'lifetime_earned', v_lifetime, 'coins_credited', v_coins);
END;
$$;
REVOKE ALL ON FUNCTION public.apply_verified_payment(UUID,TEXT,NUMERIC,TEXT,TEXT,JSONB) FROM PUBLIC, anon, authenticated;
GRANT EXECUTE ON FUNCTION public.apply_verified_payment(UUID,TEXT,NUMERIC,TEXT,TEXT,JSONB) TO service_role;

-- Existing admin-only grant RPC must never be callable by the public anon role.
DO $$
BEGIN
    IF to_regprocedure('public.grant_artifact(text,text,text,text)') IS NOT NULL THEN
        REVOKE ALL ON FUNCTION public.grant_artifact(TEXT,TEXT,TEXT,TEXT) FROM PUBLIC, anon, authenticated;
        GRANT EXECUTE ON FUNCTION public.grant_artifact(TEXT,TEXT,TEXT,TEXT) TO service_role;
    END IF;
END $$;
