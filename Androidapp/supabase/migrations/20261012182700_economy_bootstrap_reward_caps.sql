-- Create a user's canonical wallet on demand without exposing direct table writes.
CREATE OR REPLACE FUNCTION public.ensure_my_economy_profile()
RETURNS JSONB
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
    INSERT INTO public.economy_profiles (
        user_id, coin_balance, lifetime_earned, lifetime_spent, tier, rank, unlocked_items
    ) VALUES (v_user_id, 120, 120, 0, 'FREE', 'LUMEN', '["cycle_coin"]'::JSONB)
    ON CONFLICT (user_id) DO NOTHING;
    RETURN jsonb_build_object('success', TRUE);
END;
$$;
REVOKE ALL ON FUNCTION public.ensure_my_economy_profile() FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.ensure_my_economy_profile() TO authenticated;

-- The daily amount and multiplier are server-owned. A legacy/client-supplied
-- reward value is accepted only for compatibility and never controls credit.
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

-- Cosmetics may be equipped only if the fixed server catalog says they exist
-- and they are already in the caller's unlocked_items list.
CREATE OR REPLACE FUNCTION public.equip_economy_item(p_item_id TEXT)
RETURNS JSONB
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, public
AS $$
DECLARE
    v_user_id TEXT := auth.uid()::TEXT;
    v_kind TEXT;
    v_unlocked JSONB;
BEGIN
    IF v_user_id IS NULL THEN
        RETURN jsonb_build_object('success', FALSE, 'message', 'Authentication required');
    END IF;
    SELECT kind INTO v_kind FROM (VALUES
        ('cycle_coin', 'ARTIFACT'),
        ('artifact_moon', 'ARTIFACT'),
        ('artifact_bloom', 'ARTIFACT'),
        ('artifact_crystal', 'ARTIFACT'),
        ('artifact_orbit', 'ARTIFACT'),
        ('frame_elegant', 'FRAME')
    ) AS catalog(item_id, kind)
    WHERE item_id = p_item_id;
    IF v_kind IS NULL THEN
        RETURN jsonb_build_object('success', FALSE, 'message', 'Item is not available');
    END IF;
    SELECT unlocked_items INTO v_unlocked FROM public.economy_profiles
    WHERE user_id = v_user_id FOR UPDATE;
    IF NOT FOUND OR NOT (COALESCE(v_unlocked, '[]'::JSONB) ? p_item_id) THEN
        RETURN jsonb_build_object('success', FALSE, 'message', 'Item is not unlocked');
    END IF;
    UPDATE public.economy_profiles SET
        active_artifact = CASE WHEN v_kind = 'ARTIFACT' THEN p_item_id ELSE active_artifact END,
        active_frame = CASE WHEN v_kind = 'FRAME' THEN p_item_id ELSE active_frame END,
        updated_at = NOW()
    WHERE user_id = v_user_id;
    RETURN jsonb_build_object('success', TRUE, 'item_id', p_item_id);
END;
$$;
REVOKE ALL ON FUNCTION public.equip_economy_item(TEXT) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.equip_economy_item(TEXT) TO authenticated;
