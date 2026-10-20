-- Live CycleCare handshake repair, 2026-10-14.
-- Additive/data-preserving except for removal of unsafe public policies and one
-- obsolete RPC overload. The service-role Edge Functions remain the only payment writers.

CREATE EXTENSION IF NOT EXISTS pgcrypto;

-- Bring the live schema into line with the current Android DTOs while retaining
-- existing rows. The first deployed schema called this column log_date and stored
-- symptoms as text[]; the current app sends date and a comma-separated text value.
DO $$
BEGIN
    IF to_regclass('public.daily_logs') IS NOT NULL THEN
        IF EXISTS (
            SELECT 1 FROM information_schema.columns
            WHERE table_schema = 'public' AND table_name = 'daily_logs' AND column_name = 'log_date'
        ) AND NOT EXISTS (
            SELECT 1 FROM information_schema.columns
            WHERE table_schema = 'public' AND table_name = 'daily_logs' AND column_name = 'date'
        ) THEN
            ALTER TABLE public.daily_logs RENAME COLUMN log_date TO date;
        END IF;
        IF EXISTS (
            SELECT 1 FROM information_schema.columns
            WHERE table_schema = 'public' AND table_name = 'daily_logs' AND column_name = 'symptoms'
              AND data_type = 'ARRAY'
        ) THEN
            ALTER TABLE public.daily_logs
                ALTER COLUMN symptoms TYPE TEXT USING array_to_string(symptoms, ',');
        END IF;
    END IF;
END $$;

ALTER TABLE public.cycles ADD COLUMN IF NOT EXISTS period_length INTEGER NOT NULL DEFAULT 5;
ALTER TABLE public.cycles ADD COLUMN IF NOT EXISTS notes TEXT NOT NULL DEFAULT '';
ALTER TABLE public.cycles ADD COLUMN IF NOT EXISTS updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW();

ALTER TABLE public.daily_logs ADD COLUMN IF NOT EXISTS flow TEXT NOT NULL DEFAULT 'NONE';
ALTER TABLE public.daily_logs ADD COLUMN IF NOT EXISTS pain TEXT NOT NULL DEFAULT 'NONE';
ALTER TABLE public.daily_logs ADD COLUMN IF NOT EXISTS sexual_activity BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE public.daily_logs ADD COLUMN IF NOT EXISTS temperature DOUBLE PRECISION;
ALTER TABLE public.daily_logs ADD COLUMN IF NOT EXISTS notes TEXT NOT NULL DEFAULT '';
ALTER TABLE public.daily_logs ADD COLUMN IF NOT EXISTS updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW();
CREATE INDEX IF NOT EXISTS idx_cycles_user_start ON public.cycles(user_id, start_date DESC);
CREATE INDEX IF NOT EXISTS idx_daily_logs_user_date ON public.daily_logs(user_id, date DESC);

-- The PWA/mobile notification contract uses both recipient aliases. Keep the
-- legacy target_identifier column and backfill the canonical alias from it.
ALTER TABLE public.user_notifications ADD COLUMN IF NOT EXISTS user_id TEXT;
ALTER TABLE public.user_notifications ADD COLUMN IF NOT EXISTS priority TEXT NOT NULL DEFAULT 'NORMAL';
ALTER TABLE public.user_notifications ADD COLUMN IF NOT EXISTS sender TEXT NOT NULL DEFAULT 'CYCLECARE_OFFICIAL';
ALTER TABLE public.user_notifications ADD COLUMN IF NOT EXISTS action_url TEXT;
UPDATE public.user_notifications SET user_id = target_identifier WHERE user_id IS NULL;

ALTER TABLE public.economy_profiles ADD COLUMN IF NOT EXISTS last_mined_date DATE;
ALTER TABLE public.economy_profiles ADD COLUMN IF NOT EXISTS suspension_reason TEXT;
ALTER TABLE public.economy_profiles ADD COLUMN IF NOT EXISTS suspended_until TIMESTAMPTZ;
ALTER TABLE public.economy_profiles ADD COLUMN IF NOT EXISTS artifacts JSONB NOT NULL DEFAULT '[]'::JSONB;
ALTER TABLE public.economy_profiles ADD COLUMN IF NOT EXISTS last_penalized_at TIMESTAMPTZ;
ALTER TABLE public.economy_profiles ADD COLUMN IF NOT EXISTS updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW();
CREATE UNIQUE INDEX IF NOT EXISTS idx_economy_profiles_user_id_unique ON public.economy_profiles(user_id);
CREATE UNIQUE INDEX IF NOT EXISTS idx_transactions_external_reference ON public.transactions(external_reference);
CREATE UNIQUE INDEX IF NOT EXISTS idx_mining_records_user_day ON public.mining_records(user_id, claimed_date);
CREATE INDEX IF NOT EXISTS idx_user_notifications_recipient_created ON public.user_notifications(target_identifier, created_at DESC);

ALTER TABLE public.admin_audit_logs ADD COLUMN IF NOT EXISTS admin_user_id UUID;
ALTER TABLE public.admin_audit_logs ADD COLUMN IF NOT EXISTS performed_by TEXT NOT NULL DEFAULT 'CycleCare Admin';

CREATE TABLE IF NOT EXISTS public.cyclecare_admin_roles (
    user_id UUID PRIMARY KEY REFERENCES auth.users(id) ON DELETE CASCADE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
ALTER TABLE public.cyclecare_admin_roles ENABLE ROW LEVEL SECURITY;
REVOKE ALL ON TABLE public.cyclecare_admin_roles FROM PUBLIC, anon, authenticated;
GRANT SELECT, INSERT, UPDATE, DELETE ON TABLE public.cyclecare_admin_roles TO service_role;

CREATE OR REPLACE FUNCTION public.is_cyclecare_admin()
RETURNS BOOLEAN
LANGUAGE SQL
STABLE
SECURITY DEFINER
SET search_path = pg_catalog, public
AS $$
    SELECT EXISTS (
        SELECT 1 FROM public.cyclecare_admin_roles AS r
        WHERE r.user_id = (SELECT auth.uid())
    );
$$;
REVOKE ALL ON FUNCTION public.is_cyclecare_admin() FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.is_cyclecare_admin() TO authenticated, service_role;

-- Remove legacy permissive policies on every exposed CycleCare data/control table.
-- service_role bypasses RLS and will continue to operate the verified Edge Functions.
DO $$
DECLARE
    t TEXT;
    p RECORD;
BEGIN
    FOREACH t IN ARRAY ARRAY[
        'profiles', 'economy_profiles', 'cycles', 'daily_logs', 'transactions',
        'payment_transactions', 'user_notifications', 'broadcast_dispatches',
        'app_config', 'admin_audit_logs', 'in_house_messages', 'mining_records',
        'user_artifacts'
    ] LOOP
        IF to_regclass(format('public.%I', t)) IS NOT NULL THEN
            EXECUTE format('ALTER TABLE public.%I ENABLE ROW LEVEL SECURITY', t);
            FOR p IN
                SELECT policyname FROM pg_policies
                WHERE schemaname = 'public' AND tablename = t
            LOOP
                EXECUTE format('DROP POLICY IF EXISTS %I ON public.%I', p.policyname, t);
            END LOOP;
        END IF;
    END LOOP;
END $$;

CREATE POLICY cyclecare_profile_owner_read ON public.profiles
    FOR SELECT TO authenticated
    USING (user_id = (SELECT auth.uid()));
CREATE POLICY cyclecare_economy_owner_read ON public.economy_profiles
    FOR SELECT TO authenticated
    USING (user_id = (SELECT auth.uid())::TEXT);
CREATE POLICY cyclecare_cycles_owner_select ON public.cycles
    FOR SELECT TO authenticated USING (user_id = (SELECT auth.uid()));
CREATE POLICY cyclecare_cycles_owner_insert ON public.cycles
    FOR INSERT TO authenticated WITH CHECK (user_id = (SELECT auth.uid()));
CREATE POLICY cyclecare_cycles_owner_update ON public.cycles
    FOR UPDATE TO authenticated USING (user_id = (SELECT auth.uid()))
    WITH CHECK (user_id = (SELECT auth.uid()));
CREATE POLICY cyclecare_cycles_owner_delete ON public.cycles
    FOR DELETE TO authenticated USING (user_id = (SELECT auth.uid()));
CREATE POLICY cyclecare_daily_logs_owner_select ON public.daily_logs
    FOR SELECT TO authenticated USING (user_id = (SELECT auth.uid()));
CREATE POLICY cyclecare_daily_logs_owner_insert ON public.daily_logs
    FOR INSERT TO authenticated WITH CHECK (user_id = (SELECT auth.uid()));
CREATE POLICY cyclecare_daily_logs_owner_update ON public.daily_logs
    FOR UPDATE TO authenticated USING (user_id = (SELECT auth.uid()))
    WITH CHECK (user_id = (SELECT auth.uid()));
CREATE POLICY cyclecare_daily_logs_owner_delete ON public.daily_logs
    FOR DELETE TO authenticated USING (user_id = (SELECT auth.uid()));
CREATE POLICY cyclecare_transactions_owner_read ON public.transactions
    FOR SELECT TO authenticated USING (user_id = (SELECT auth.uid())::TEXT);
CREATE POLICY cyclecare_notifications_owner_read ON public.user_notifications
    FOR SELECT TO authenticated USING (
        user_id = (SELECT auth.uid())::TEXT
        OR target_identifier = (SELECT auth.uid())::TEXT
        OR user_id = 'ALL' OR target_identifier = 'ALL'
    );
CREATE POLICY cyclecare_broadcasts_owner_read ON public.broadcast_dispatches
    FOR SELECT TO authenticated USING (
        is_active = TRUE AND (target_user_id IS NULL OR target_user_id = (SELECT auth.uid())::TEXT)
    );
CREATE POLICY cyclecare_mining_owner_read ON public.mining_records
    FOR SELECT TO authenticated USING (user_id = (SELECT auth.uid())::TEXT);
CREATE POLICY cyclecare_artifacts_owner_read ON public.user_artifacts
    FOR SELECT TO authenticated USING (user_id = (SELECT auth.uid())::TEXT);
CREATE POLICY cyclecare_app_config_public_read ON public.app_config
    FOR SELECT TO anon, authenticated USING (TRUE);
CREATE POLICY cyclecare_admin_message_history_read ON public.in_house_messages
    FOR SELECT TO authenticated USING ((SELECT public.is_cyclecare_admin()));

-- Minimal direct table permissions. All wallet/notification/admin writes go through
-- identity-checked RPCs; health logs remain owner-scoped. No anon access to PII.
REVOKE ALL ON TABLE public.profiles, public.economy_profiles, public.cycles,
    public.daily_logs, public.transactions, public.payment_transactions,
    public.user_notifications, public.broadcast_dispatches, public.app_config,
    public.admin_audit_logs, public.in_house_messages, public.mining_records,
    public.user_artifacts FROM PUBLIC, anon, authenticated;
GRANT SELECT ON public.profiles, public.economy_profiles, public.cycles,
    public.daily_logs, public.transactions, public.user_notifications,
    public.broadcast_dispatches, public.mining_records, public.user_artifacts TO authenticated;
GRANT INSERT, UPDATE, DELETE ON public.cycles, public.daily_logs TO authenticated;
GRANT SELECT ON public.in_house_messages TO authenticated;
GRANT SELECT ON public.app_config TO anon, authenticated;

CREATE TABLE IF NOT EXISTS public.cyclecare_user_timezones (
    user_id TEXT PRIMARY KEY,
    timezone_name TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
ALTER TABLE public.cyclecare_user_timezones ENABLE ROW LEVEL SECURITY;
REVOKE ALL ON TABLE public.cyclecare_user_timezones FROM PUBLIC, anon, authenticated;
GRANT SELECT, INSERT, UPDATE, DELETE ON TABLE public.cyclecare_user_timezones TO service_role;

-- Profile upsert is bound to the access-token identity; caller cannot set role/status.
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
    v_user_id UUID := auth.uid();
    v_account_mode TEXT := CASE
        WHEN COALESCE((NULLIF(auth.jwt()->>'is_anonymous', ''))::BOOLEAN, FALSE) THEN 'ANONYMOUS'
        ELSE 'EMAIL'
    END;
BEGIN
    IF v_user_id IS NULL THEN
        RETURN jsonb_build_object('success', FALSE, 'message', 'Authentication required');
    END IF;
    INSERT INTO public.profiles (
        user_id, device_id, account_mode, goals, last_period_date, cycle_length,
        period_duration, typical_symptoms, typical_energy, typical_sleep
    ) VALUES (
        v_user_id, NULLIF(p_device_id, ''), v_account_mode,
        COALESCE(p_goals, ''), NULLIF(p_last_period_date, '')::DATE,
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
    INSERT INTO public.economy_profiles (user_id, coin_balance, lifetime_earned, lifetime_spent, tier, rank, unlocked_items)
    VALUES (v_user_id, 120, 120, 0, 'FREE', 'LUMEN', '["cycle_coin"]'::JSONB)
    ON CONFLICT (user_id) DO NOTHING;
    RETURN jsonb_build_object('success', TRUE);
END;
$$;
REVOKE ALL ON FUNCTION public.ensure_my_economy_profile() FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.ensure_my_economy_profile() TO authenticated;

CREATE OR REPLACE FUNCTION public.is_cyclecare_account_restricted(p_user_id TEXT)
RETURNS BOOLEAN
LANGUAGE SQL
STABLE
SECURITY DEFINER
SET search_path = pg_catalog, public
AS $$
    SELECT EXISTS (
        SELECT 1 FROM public.profiles
        WHERE user_id::TEXT = p_user_id
          AND (
              UPPER(COALESCE(status, 'ACTIVE')) = 'BANNED'
              OR (UPPER(COALESCE(status, 'ACTIVE')) = 'SUSPENDED'
                  AND (suspended_until IS NULL OR suspended_until > NOW()))
          )
    ) OR EXISTS (
        SELECT 1 FROM public.economy_profiles
        WHERE user_id = p_user_id
          AND (
              UPPER(COALESCE(status, 'ACTIVE')) = 'BANNED'
              OR (UPPER(COALESCE(status, 'ACTIVE')) = 'SUSPENDED'
                  AND (suspended_until IS NULL OR suspended_until > NOW()))
          )
    );
$$;
REVOKE ALL ON FUNCTION public.is_cyclecare_account_restricted(TEXT) FROM PUBLIC, anon, authenticated;

-- Daily reward amount is server-owned (30); user identity and timezone are verified.
CREATE OR REPLACE FUNCTION public.verify_and_mine_coins(
    p_user_id TEXT,
    p_base_reward BIGINT,
    p_timezone_name TEXT
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
    IF public.is_cyclecare_account_restricted(p_user_id) THEN
        RETURN jsonb_build_object('success', FALSE, 'message', 'This account is suspended or banned');
    END IF;
    IF NOT EXISTS (SELECT 1 FROM public.cyclecare_user_timezones WHERE user_id = p_user_id) THEN
        IF NOT EXISTS (SELECT 1 FROM pg_timezone_names WHERE name = p_timezone_name) THEN
            RETURN jsonb_build_object('success', FALSE, 'message', 'Invalid timezone');
        END IF;
        INSERT INTO public.cyclecare_user_timezones(user_id, timezone_name)
        VALUES (p_user_id, p_timezone_name) ON CONFLICT (user_id) DO NOTHING;
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
    UPDATE public.economy_profiles SET coin_balance = v_new_balance, lifetime_earned = v_new_lifetime,
        rank = v_new_rank, last_mined_date = v_today, updated_at = NOW()
    WHERE user_id = p_user_id;
    RETURN jsonb_build_object('success', TRUE, 'message', 'Daily reward claimed successfully',
        'awarded_coins', v_awarded, 'new_balance', v_new_balance, 'lifetime_earned', v_new_lifetime,
        'new_rank', v_new_rank, 'mined_date', v_today::TEXT);
EXCEPTION WHEN unique_violation THEN
    RETURN jsonb_build_object('success', FALSE, 'message', 'You have already claimed your daily reward today. Returns at midnight.');
END;
$$;
REVOKE ALL ON FUNCTION public.verify_and_mine_coins(TEXT,BIGINT,TEXT) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.verify_and_mine_coins(TEXT,BIGINT,TEXT) TO authenticated;

-- Backwards-compatible two-argument clients route into the same verified RPC.
CREATE OR REPLACE FUNCTION public.verify_and_mine_coins(p_user_id TEXT, p_base_reward BIGINT DEFAULT 25)
RETURNS JSONB
LANGUAGE SQL
SECURITY DEFINER
SET search_path = pg_catalog, public
AS $$ SELECT public.verify_and_mine_coins(p_user_id, p_base_reward, 'UTC'); $$;
REVOKE ALL ON FUNCTION public.verify_and_mine_coins(TEXT,BIGINT) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.verify_and_mine_coins(TEXT,BIGINT) TO authenticated;
DROP FUNCTION IF EXISTS public.verify_and_mine_coins(TEXT,TEXT,NUMERIC);

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
    IF public.is_cyclecare_account_restricted(v_user_id) THEN
        RETURN jsonb_build_object('success', FALSE, 'message', 'This account is suspended or banned');
    END IF;
    SELECT price, kind INTO v_price, v_kind FROM (VALUES
        ('artifact_moon', 250::BIGINT, 'ARTIFACT'), ('artifact_bloom', 450::BIGINT, 'ARTIFACT'),
        ('artifact_crystal', 800::BIGINT, 'ARTIFACT'), ('artifact_orbit', 1200::BIGINT, 'ARTIFACT'),
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
    UPDATE public.economy_profiles SET coin_balance = COALESCE(coin_balance, 0) - v_price,
        lifetime_spent = COALESCE(lifetime_spent, 0) + v_price,
        unlocked_items = COALESCE(unlocked_items, '["cycle_coin"]'::JSONB) || jsonb_build_array(p_item_id),
        active_artifact = CASE WHEN v_kind = 'ARTIFACT' THEN p_item_id ELSE active_artifact END,
        active_frame = CASE WHEN v_kind = 'FRAME' THEN p_item_id ELSE active_frame END, updated_at = NOW()
    WHERE user_id = v_user_id;
    RETURN jsonb_build_object('success', TRUE, 'message', 'Item unlocked', 'item_id', p_item_id);
END;
$$;
REVOKE ALL ON FUNCTION public.purchase_economy_item(TEXT) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.purchase_economy_item(TEXT) TO authenticated;

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
    IF v_user_id IS NULL THEN RETURN jsonb_build_object('success', FALSE, 'message', 'Authentication required'); END IF;
    IF public.is_cyclecare_account_restricted(v_user_id) THEN
        RETURN jsonb_build_object('success', FALSE, 'message', 'This account is suspended or banned');
    END IF;
    SELECT kind INTO v_kind FROM (VALUES
        ('cycle_coin', 'ARTIFACT'), ('artifact_moon', 'ARTIFACT'), ('artifact_bloom', 'ARTIFACT'),
        ('artifact_crystal', 'ARTIFACT'), ('artifact_orbit', 'ARTIFACT'), ('frame_elegant', 'FRAME')
    ) AS catalog(item_id, kind) WHERE item_id = p_item_id;
    IF v_kind IS NULL THEN RETURN jsonb_build_object('success', FALSE, 'message', 'Item is not available'); END IF;
    SELECT unlocked_items INTO v_unlocked FROM public.economy_profiles WHERE user_id = v_user_id FOR UPDATE;
    IF NOT FOUND OR NOT (COALESCE(v_unlocked, '[]'::JSONB) ? p_item_id) THEN
        RETURN jsonb_build_object('success', FALSE, 'message', 'Item is not unlocked');
    END IF;
    UPDATE public.economy_profiles SET active_artifact = CASE WHEN v_kind = 'ARTIFACT' THEN p_item_id ELSE active_artifact END,
        active_frame = CASE WHEN v_kind = 'FRAME' THEN p_item_id ELSE active_frame END, updated_at = NOW()
    WHERE user_id = v_user_id;
    RETURN jsonb_build_object('success', TRUE, 'item_id', p_item_id);
END;
$$;
REVOKE ALL ON FUNCTION public.equip_economy_item(TEXT) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.equip_economy_item(TEXT) TO authenticated;

CREATE OR REPLACE FUNCTION public.transfer_coins_atomic(p_sender_id TEXT, p_recipient_email TEXT, p_amount BIGINT)
RETURNS JSONB
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, public
AS $$
DECLARE
    v_sender_balance BIGINT;
    v_recipient_id TEXT;
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
        lifetime_earned = public.economy_profiles.lifetime_earned + p_amount, updated_at = NOW();
    INSERT INTO public.user_notifications(user_id, target_identifier, title, body, type, priority, sender)
    VALUES (v_recipient_id, v_recipient_id, 'Cycle Coins Received!',
        'You have received ' || p_amount || ' Cycle Coins from a peer.', 'GIFT', 'HIGH', 'CYCLECARE_TRANSFER');
    RETURN jsonb_build_object('success', TRUE, 'message', 'Transfer completed',
        'transferred_amount', p_amount, 'remaining_balance', v_sender_balance - p_amount);
END;
$$;
REVOKE ALL ON FUNCTION public.transfer_coins_atomic(TEXT,TEXT,BIGINT) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.transfer_coins_atomic(TEXT,TEXT,BIGINT) TO authenticated;

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
    IF v_user_id IS NULL THEN RETURN jsonb_build_object('success', FALSE, 'message', 'Authentication required'); END IF;
    IF p_notification_id IS NULL THEN
        UPDATE public.user_notifications SET is_read = TRUE
        WHERE user_id = v_user_id OR target_identifier = v_user_id;
    ELSE
        UPDATE public.user_notifications SET is_read = TRUE
        WHERE id = p_notification_id AND (user_id = v_user_id OR target_identifier = v_user_id);
    END IF;
    GET DIAGNOSTICS v_count = ROW_COUNT;
    RETURN jsonb_build_object('success', TRUE, 'updated', v_count);
END;
$$;
REVOKE ALL ON FUNCTION public.mark_my_notification_read(UUID) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.mark_my_notification_read(UUID) TO authenticated;

CREATE OR REPLACE FUNCTION public.cancel_my_transaction(p_external_reference TEXT)
RETURNS JSONB
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, public
AS $$
DECLARE v_status TEXT;
BEGIN
    IF auth.uid() IS NULL THEN RETURN jsonb_build_object('success', FALSE, 'message', 'Authentication required'); END IF;
    SELECT status INTO v_status FROM public.transactions
    WHERE external_reference = p_external_reference AND user_id = auth.uid()::TEXT FOR UPDATE;
    IF NOT FOUND THEN RETURN jsonb_build_object('success', FALSE, 'message', 'Transaction not found'); END IF;
    IF v_status <> 'pending' THEN RETURN jsonb_build_object('success', FALSE, 'message', 'Only pending transactions can be checked'); END IF;
    RETURN jsonb_build_object('success', FALSE, 'message', 'Cancellation must be confirmed by the payment provider; no state was changed.');
END;
$$;
REVOKE ALL ON FUNCTION public.cancel_my_transaction(TEXT) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.cancel_my_transaction(TEXT) TO authenticated;

-- One transaction lock + one service-role-only RPC for exact, idempotent payment credit.
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
    IF auth.role() <> 'service_role' THEN RETURN jsonb_build_object('success', FALSE, 'message', 'Service role required'); END IF;
    SELECT * INTO v_tx FROM public.transactions WHERE id = p_transaction_id FOR UPDATE;
    IF NOT FOUND THEN RETURN jsonb_build_object('success', FALSE, 'message', 'Transaction not found'); END IF;
    IF v_tx.provider <> p_expected_provider OR v_tx.currency <> p_expected_currency OR v_tx.amount <> p_expected_amount THEN
        RETURN jsonb_build_object('success', FALSE, 'message', 'Transaction amount or provider mismatch');
    END IF;
    IF v_tx.status = 'success' THEN RETURN jsonb_build_object('success', TRUE, 'duplicate', TRUE, 'message', 'Already credited'); END IF;
    IF v_tx.status <> 'pending' THEN RETURN jsonb_build_object('success', FALSE, 'message', 'Transaction is not pending'); END IF;
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
    v_rank := CASE WHEN v_lifetime >= 7500 THEN 'FOUNDER' WHEN v_lifetime >= 3000 THEN 'NOCTURNE'
        WHEN v_lifetime >= 1200 THEN 'SOLARA' WHEN v_lifetime >= 500 THEN 'CELESTIA'
        WHEN v_lifetime >= 150 THEN 'AURELIA' ELSE 'LUMEN' END;
    UPDATE public.economy_profiles SET rank = v_rank, updated_at = NOW() WHERE user_id = v_tx.user_id;
    UPDATE public.transactions SET status = 'success', provider_reference = p_provider_reference,
        raw_payload = COALESCE(p_provider_payload, '{}'::JSONB), updated_at = NOW() WHERE id = p_transaction_id;
    RETURN jsonb_build_object('success', TRUE, 'new_balance', v_balance, 'lifetime_earned', v_lifetime, 'coins_credited', v_coins);
END;
$$;
REVOKE ALL ON FUNCTION public.apply_verified_payment(UUID,TEXT,NUMERIC,TEXT,TEXT,JSONB) FROM PUBLIC, anon, authenticated;
GRANT EXECUTE ON FUNCTION public.apply_verified_payment(UUID,TEXT,NUMERIC,TEXT,TEXT,JSONB) TO service_role;

-- Keep the legacy grant RPC unreachable from client roles; the admin RPC below is
-- the only client-callable admin write path and checks the role table itself.
DO $$
BEGIN
    IF to_regprocedure('public.grant_artifact(text,text,text,text)') IS NOT NULL THEN
        REVOKE ALL ON FUNCTION public.grant_artifact(TEXT,TEXT,TEXT,TEXT) FROM PUBLIC, anon, authenticated;
        GRANT EXECUTE ON FUNCTION public.grant_artifact(TEXT,TEXT,TEXT,TEXT) TO service_role;
    END IF;
END $$;

-- Server-owned runtime switches consumed by the mobile AI and payment gateways.
-- Provider credentials remain Edge Function secrets and are never stored here.
CREATE TABLE IF NOT EXISTS public.cyclecare_runtime_config (
    id BOOLEAN PRIMARY KEY DEFAULT TRUE CHECK (id = TRUE),
    active_ai_provider TEXT NOT NULL DEFAULT 'GEMINI'
        CHECK (active_ai_provider IN ('DEEPINFRA', 'GEMINI', 'HUGGINGFACE')),
    active_mpesa_provider TEXT NOT NULL DEFAULT 'PAYHERO'
        CHECK (active_mpesa_provider IN ('PAYHERO')),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_by UUID
);
ALTER TABLE public.cyclecare_runtime_config ENABLE ROW LEVEL SECURITY;
REVOKE ALL ON TABLE public.cyclecare_runtime_config FROM PUBLIC, anon, authenticated;
GRANT SELECT, INSERT, UPDATE, DELETE ON TABLE public.cyclecare_runtime_config TO service_role;
INSERT INTO public.cyclecare_runtime_config(id) VALUES (TRUE) ON CONFLICT (id) DO NOTHING;

-- Read-only dashboard payload deliberately omits cycle history and health details.
CREATE OR REPLACE FUNCTION public.admin_get_data()
RETURNS JSONB
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, public
AS $$
DECLARE
    v_profiles JSONB;
    v_economy JSONB;
    v_transactions JSONB;
    v_runtime JSONB;
BEGIN
    IF NOT public.is_cyclecare_admin() THEN RAISE EXCEPTION 'Administrator access required'; END IF;
    SELECT COALESCE(jsonb_agg(to_jsonb(q)), '[]'::JSONB) INTO v_profiles FROM (
        SELECT id::TEXT AS id, user_id::TEXT AS user_id, device_id, account_mode,
            CASE WHEN UPPER(COALESCE(status, 'ACTIVE')) = 'SUSPENDED'
                      AND suspended_until IS NOT NULL AND suspended_until <= NOW()
                 THEN 'ACTIVE' ELSE COALESCE(status, 'ACTIVE') END AS status,
            suspension_reason, suspended_until, created_at
        FROM public.profiles ORDER BY created_at DESC LIMIT 50
    ) AS q;
    SELECT COALESCE(jsonb_agg(to_jsonb(q)), '[]'::JSONB) INTO v_economy FROM (
        SELECT id::TEXT AS id, user_id, coin_balance, lifetime_earned, lifetime_spent,
            tier, rank,
            CASE WHEN UPPER(COALESCE(status, 'ACTIVE')) = 'SUSPENDED'
                      AND suspended_until IS NOT NULL AND suspended_until <= NOW()
                 THEN 'ACTIVE' ELSE COALESCE(status, 'ACTIVE') END AS status,
            suspension_reason, suspended_until, artifacts, last_penalized_at
        FROM public.economy_profiles ORDER BY updated_at DESC LIMIT 100
    ) AS q;
    SELECT COALESCE(jsonb_agg(to_jsonb(q)), '[]'::JSONB) INTO v_transactions FROM (
        SELECT id::TEXT AS id, user_id, external_reference, provider, amount, currency,
            status, provider_reference, created_at, updated_at
        FROM public.transactions ORDER BY created_at DESC LIMIT 50
    ) AS q;
    SELECT to_jsonb(q) INTO v_runtime FROM (
        SELECT active_ai_provider, active_mpesa_provider, updated_at
        FROM public.cyclecare_runtime_config WHERE id = TRUE
    ) AS q;
    RETURN jsonb_build_object(
        'profiles', v_profiles,
        'economy_profiles', v_economy,
        'transactions', v_transactions,
        'app_config_exists', to_regclass('public.app_config') IS NOT NULL,
        'runtime_config', v_runtime,
        'has_live_profiles', jsonb_array_length(v_profiles) > 0,
        'has_transactions_table', TRUE,
        'has_economy_table', TRUE
    );
END;
$$;
REVOKE ALL ON FUNCTION public.admin_get_data() FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.admin_get_data() TO authenticated;

CREATE OR REPLACE FUNCTION public.admin_update_runtime_config(
    p_active_ai_provider TEXT,
    p_active_mpesa_provider TEXT
) RETURNS JSONB
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, public
AS $$
DECLARE
    v_ai TEXT := UPPER(TRIM(COALESCE(p_active_ai_provider, '')));
    v_mpesa TEXT := UPPER(TRIM(COALESCE(p_active_mpesa_provider, '')));
    v_config JSONB;
BEGIN
    IF NOT public.is_cyclecare_admin() THEN RAISE EXCEPTION 'Administrator access required'; END IF;
    IF v_ai NOT IN ('DEEPINFRA', 'GEMINI', 'HUGGINGFACE') THEN RAISE EXCEPTION 'Choose a supported AI provider'; END IF;
    IF v_mpesa <> 'PAYHERO' THEN RAISE EXCEPTION 'PayNexus cannot be activated until its callback signature and payment-verification contract is validated'; END IF;
    UPDATE public.cyclecare_runtime_config
       SET active_ai_provider = v_ai, active_mpesa_provider = v_mpesa,
           updated_at = NOW(), updated_by = auth.uid()
     WHERE id = TRUE
     RETURNING jsonb_build_object(
         'active_ai_provider', active_ai_provider,
         'active_mpesa_provider', active_mpesa_provider,
         'updated_at', updated_at
     ) INTO v_config;
    INSERT INTO public.admin_audit_logs(admin_user_id, admin_action, target_user_id, details, performed_by)
    VALUES (auth.uid(), 'UPDATE_RUNTIME_PROVIDER_CONFIG', 'CYCLECARE_RUNTIME', v_config, auth.uid()::TEXT);
    RETURN v_config;
END;
$$;
REVOKE ALL ON FUNCTION public.admin_update_runtime_config(TEXT,TEXT) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.admin_update_runtime_config(TEXT,TEXT) TO authenticated;

-- Atomic admin status, wallet, gift, penalty, and individual-message operation.
CREATE OR REPLACE FUNCTION public.admin_apply_action(
    p_action TEXT,
    p_target_user_id TEXT,
    p_payload JSONB DEFAULT '{}'::JSONB
) RETURNS JSONB
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, public
AS $$
DECLARE
    v_action TEXT := UPPER(TRIM(p_action));
    v_input_target TEXT := NULLIF(TRIM(p_target_user_id), '');
    v_target TEXT;
    v_status TEXT;
    v_reason TEXT;
    v_until TIMESTAMPTZ;
    v_delta_numeric NUMERIC;
    v_delta BIGINT;
    v_balance BIGINT;
    v_deducted BIGINT;
    v_percent NUMERIC;
    v_artifact TEXT;
    v_rarity TEXT;
    v_artifacts JSONB;
    v_title TEXT;
    v_body TEXT;
    v_kind TEXT;
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

    ELSIF v_action = 'COINS' THEN
        v_delta_numeric := COALESCE((p_payload->>'delta')::NUMERIC, 0);
        IF v_delta_numeric = 0 OR v_delta_numeric <> TRUNC(v_delta_numeric) OR ABS(v_delta_numeric) > 1000000 THEN
            RAISE EXCEPTION 'Coin adjustment must be a non-zero whole number up to 1,000,000';
        END IF;
        v_delta := v_delta_numeric::BIGINT;
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
            CASE WHEN v_delta >= 0 THEN 'You have received ' || v_delta || ' Cycle Coins from CycleCare Administration.'
                 ELSE 'Your balance was adjusted by ' || v_delta || ' Cycle Coins. ' || COALESCE(v_reason, '') END,
            CASE WHEN v_delta >= 0 THEN 'REWARD' ELSE 'PENALTY' END, 'HIGH', 'CYCLECARE_OFFICIAL');
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
        v_deducted := FLOOR(COALESCE(v_balance, 0) * v_percent / 100)::BIGINT;
        UPDATE public.economy_profiles SET coin_balance = GREATEST(0, COALESCE(coin_balance, 0) - v_deducted),
            last_penalized_at = NOW(), updated_at = NOW() WHERE user_id = v_target RETURNING coin_balance INTO v_balance;
        INSERT INTO public.user_notifications(user_id, target_identifier, title, body, type, priority, sender)
        VALUES (v_target, v_target, 'Account Inactivity / Policy Notice',
            'An inactivity adjustment of ' || v_deducted || ' coins (' || v_percent || '%) was applied.', 'WARNING', 'NORMAL', 'CYCLECARE_OFFICIAL');
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
$$;
REVOKE ALL ON FUNCTION public.admin_apply_action(TEXT,TEXT,JSONB) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.admin_apply_action(TEXT,TEXT,JSONB) TO authenticated;

-- One server-side dispatch avoids PWA's 50-user truncation, incorrect tier filtering,
-- partial multi-request writes, and duplicate global notifications.
CREATE OR REPLACE FUNCTION public.admin_dispatch_message(
    p_target_audience TEXT,
    p_message_type TEXT,
    p_title TEXT,
    p_body TEXT,
    p_action_url TEXT DEFAULT NULL,
    p_custom_user_ids TEXT[] DEFAULT ARRAY[]::TEXT[],
    p_push_notification BOOLEAN DEFAULT TRUE
) RETURNS JSONB
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, public
AS $$
DECLARE
    v_audience TEXT := UPPER(TRIM(p_target_audience));
    v_type TEXT := UPPER(TRIM(p_message_type));
    v_title TEXT := NULLIF(LEFT(TRIM(COALESCE(p_title, '')), 160), '');
    v_body TEXT := NULLIF(LEFT(TRIM(COALESCE(p_body, '')), 4000), '');
    v_message_id UUID;
    v_count INTEGER := 0;
BEGIN
    IF NOT public.is_cyclecare_admin() THEN RAISE EXCEPTION 'Administrator access required'; END IF;
    IF v_title IS NULL OR v_body IS NULL THEN RAISE EXCEPTION 'Message title and body are required'; END IF;
    IF v_audience NOT IN ('ALL','FREE','SUPPORTER','PREMIUM','CUSTOM_LIST') THEN RAISE EXCEPTION 'Invalid message audience'; END IF;
    IF v_type NOT IN ('SYSTEM_ANNOUNCEMENT','ALERT','CARE_TIP','PROMO','SYSTEM','SECURITY','REWARD','GIFT','WARNING') THEN RAISE EXCEPTION 'Invalid message type'; END IF;
    INSERT INTO public.in_house_messages(title, message, message_type, target_audience, action_url, sent_by)
    VALUES (v_title, v_body, v_type, v_audience, NULLIF(p_action_url, ''), auth.uid()::TEXT)
    RETURNING id INTO v_message_id;

    IF p_push_notification THEN
        IF v_audience = 'ALL' THEN
            INSERT INTO public.broadcast_dispatches(title, body, type, priority, sender, action_url, target_user_id, is_active)
            VALUES (v_title, v_body, v_type, 'HIGH', 'CYCLECARE_OFFICIAL', NULLIF(p_action_url, ''), NULL, TRUE);
            SELECT COUNT(*)::INTEGER INTO v_count FROM public.profiles
            WHERE COALESCE(status, 'ACTIVE') NOT IN ('BANNED','SUSPENDED');
        ELSIF v_audience IN ('FREE','SUPPORTER','PREMIUM') THEN
            INSERT INTO public.user_notifications(message_id, user_id, target_identifier, title, body, type, priority, sender, action_url)
            SELECT v_message_id, q.target_id, q.target_id, v_title, v_body, v_type, 'HIGH', 'CYCLECARE_OFFICIAL', NULLIF(p_action_url, '')
            FROM (
                SELECT DISTINCT COALESCE(NULLIF(p.user_id::TEXT, ''), NULLIF(p.device_id, ''), p.id::TEXT) AS target_id
                FROM public.profiles AS p
                LEFT JOIN public.economy_profiles AS e
                  ON e.user_id = COALESCE(NULLIF(p.user_id::TEXT, ''), NULLIF(p.device_id, ''), p.id::TEXT)
                WHERE COALESCE(p.status, 'ACTIVE') NOT IN ('BANNED','SUSPENDED')
                  AND COALESCE(UPPER(e.tier), 'FREE') = v_audience
            ) AS q;
            GET DIAGNOSTICS v_count = ROW_COUNT;
        ELSE
            INSERT INTO public.user_notifications(message_id, user_id, target_identifier, title, body, type, priority, sender, action_url)
            SELECT v_message_id, q.target_id, q.target_id, v_title, v_body, v_type, 'HIGH', 'CYCLECARE_OFFICIAL', NULLIF(p_action_url, '')
            FROM (
                SELECT DISTINCT NULLIF(TRIM(x), '') AS target_id FROM UNNEST(COALESCE(p_custom_user_ids, ARRAY[]::TEXT[])) AS x
                WHERE NULLIF(TRIM(x), '') IS NOT NULL
            ) AS q;
            GET DIAGNOSTICS v_count = ROW_COUNT;
        END IF;
    END IF;

    INSERT INTO public.admin_audit_logs(admin_user_id, admin_action, target_user_id, details, performed_by)
    VALUES (auth.uid(), 'DISPATCH_IN_HOUSE_MESSAGE', v_audience,
        jsonb_build_object('message_id', v_message_id, 'title', v_title, 'target_count', v_count, 'message_type', v_type),
        auth.uid()::TEXT);
    RETURN jsonb_build_object('success', TRUE, 'message_id', v_message_id, 'target_count', v_count);
END;
$$;
REVOKE ALL ON FUNCTION public.admin_dispatch_message(TEXT,TEXT,TEXT,TEXT,TEXT,TEXT[],BOOLEAN) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.admin_dispatch_message(TEXT,TEXT,TEXT,TEXT,TEXT,TEXT[],BOOLEAN) TO authenticated;

-- Force PostgREST to reload added columns and RPC signatures.
NOTIFY pgrst, 'reload schema';
