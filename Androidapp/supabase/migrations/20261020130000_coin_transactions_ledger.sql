-- ==============================================================================
-- CycleCare Migration: 20261020130000_coin_transactions_ledger.sql
-- Establishes immutable coin transaction ledger and updates claim RPC.
-- ==============================================================================

CREATE TABLE IF NOT EXISTS public.coin_transactions (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id TEXT NOT NULL,
    action TEXT NOT NULL,         -- 'DAILY_ATTUNEMENT', 'ITEM_PURCHASE', 'TRANSFER_SENT', 'TRANSFER_RECEIVED', 'PACKAGE_PURCHASE', 'TIER_UPGRADE'
    delta INTEGER NOT NULL,        -- Positive = earn, negative = spend
    balance_after INTEGER NOT NULL,
    metadata JSONB DEFAULT '{}'::JSONB,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

ALTER TABLE public.coin_transactions ENABLE ROW LEVEL SECURITY;

DO $$ BEGIN
    DROP POLICY IF EXISTS "Users see own coin transactions" ON public.coin_transactions;
    DROP POLICY IF EXISTS "Service role manages coin transactions" ON public.coin_transactions;
EXCEPTION WHEN OTHERS THEN NULL;
END $$;

CREATE POLICY "Users see own coin transactions"
    ON public.coin_transactions FOR SELECT
    USING (auth.uid() IS NOT NULL AND auth.uid()::TEXT = user_id);

CREATE POLICY "Service role manages coin transactions"
    ON public.coin_transactions FOR ALL
    USING (auth.role() = 'service_role');

-- Index for timeline queries
CREATE INDEX IF NOT EXISTS idx_coin_transactions_user_time ON public.coin_transactions(user_id, created_at DESC);

-- Update verify_and_mine_coins to write to coin_transactions ledger atomically
CREATE OR REPLACE FUNCTION public.verify_and_mine_coins(
    p_user_id TEXT,
    p_base_reward BIGINT DEFAULT 30,
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
    VALUES (p_user_id, 'DAILY_ATTUNEMENT', v_awarded::INTEGER, v_new_balance::INTEGER, jsonb_build_object('date', v_today, 'multiplier', v_multiplier));

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
