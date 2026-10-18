-- CycleCare Cross-Platform Consistency, Gift Unlock & Telemetry Migration (2026-10-15)
-- 1. Resolves display names and canonical IDs for 3D artifacts & profile frames.
-- 2. Safely normalizes any non-array JSON in economy_profiles.artifacts / unlocked_items
--    and backfills gifted artifacts into unlocked_items without calling jsonb_array_length
--    or jsonb_array_elements on non-array JSON values.
-- 3. Updates admin_apply_action('GIFT'), purchase_economy_item(), and equip_economy_item()
--    to guard against non-array JSON in artifacts / unlocked_items.
-- 4. Enriches admin_get_data() with complete economy telemetry while preserving health data privacy.
-- 5. Adds composite indexes for faster notification and transaction queries.

CREATE OR REPLACE FUNCTION public.resolve_cyclecare_artifact_id(p_name_or_id TEXT)
RETURNS TEXT
LANGUAGE SQL
IMMUTABLE
AS $$
    SELECT CASE LOWER(TRIM(COALESCE(p_name_or_id, '')))
        WHEN 'artifact_moon' THEN 'artifact_moon'
        WHEN 'luminescent moon' THEN 'artifact_moon'
        WHEN 'artifact_bloom' THEN 'artifact_bloom'
        WHEN 'golden blossom' THEN 'artifact_bloom'
        WHEN 'blooming lotus' THEN 'artifact_bloom'
        WHEN 'artifact_crystal' THEN 'artifact_crystal'
        WHEN 'rose obelisk' THEN 'artifact_crystal'
        WHEN 'rose quartz crystal' THEN 'artifact_crystal'
        WHEN 'artifact_orbit' THEN 'artifact_orbit'
        WHEN 'dual eclipse' THEN 'artifact_orbit'
        WHEN 'dual eclipse rings' THEN 'artifact_orbit'
        WHEN 'celestial orbit' THEN 'artifact_orbit'
        WHEN 'frame_elegant' THEN 'frame_elegant'
        WHEN 'celestial tiara' THEN 'frame_elegant'
        WHEN 'imperial pearl halo' THEN 'frame_elegant'
        WHEN 'golden pearl frame' THEN 'frame_elegant'
        ELSE NULL
    END;
$$;

-- Safely normalize legacy non-array JSON in artifacts / unlocked_items and backfill gifted items
DO $$
DECLARE
    r RECORD;
    v_elem JSONB;
    v_item_id TEXT;
    v_artifacts JSONB;
    v_unlocked JSONB;
    v_unlocked_source JSONB;
    v_candidate JSONB;
BEGIN
    IF to_regclass('public.economy_profiles') IS NOT NULL THEN
        FOR r IN
            SELECT user_id, unlocked_items, artifacts
            FROM public.economy_profiles
        LOOP
            -- Normalize every legacy unlock shape without discarding unknown values.
            IF r.unlocked_items IS NULL OR jsonb_typeof(r.unlocked_items) = 'null' THEN
                v_unlocked_source := '[]'::JSONB;
            ELSIF jsonb_typeof(r.unlocked_items) = 'array' THEN
                v_unlocked_source := r.unlocked_items;
            ELSE
                v_unlocked_source := jsonb_build_array(r.unlocked_items);
            END IF;
            v_unlocked := '[]'::JSONB;
            FOR v_elem IN SELECT value FROM jsonb_array_elements(v_unlocked_source)
            LOOP
                v_item_id := NULL;
                IF jsonb_typeof(v_elem) = 'string' THEN
                    v_item_id := public.resolve_cyclecare_artifact_id(v_elem #>> '{}');
                ELSIF jsonb_typeof(v_elem) = 'object' THEN
                    v_item_id := public.resolve_cyclecare_artifact_id(COALESCE(v_elem->>'id', v_elem->>'name', ''));
                END IF;
                v_candidate := CASE WHEN v_item_id IS NOT NULL THEN to_jsonb(v_item_id) ELSE v_elem END;
                IF v_candidate <> 'null'::JSONB AND NOT (v_unlocked @> jsonb_build_array(v_candidate)) THEN
                    v_unlocked := v_unlocked || jsonb_build_array(v_candidate);
                END IF;
            END LOOP;
            IF NOT (v_unlocked ? 'cycle_coin') THEN
                v_unlocked := '["cycle_coin"]'::JSONB || v_unlocked;
            END IF;

            -- Safely normalize artifacts into a JSONB array even if legacy data stored an object, string, or null
            IF r.artifacts IS NULL THEN
                v_artifacts := '[]'::JSONB;
            ELSIF jsonb_typeof(r.artifacts) = 'array' THEN
                v_artifacts := r.artifacts;
            ELSIF jsonb_typeof(r.artifacts) = 'object' THEN
                v_artifacts := jsonb_build_array(r.artifacts);
            ELSIF jsonb_typeof(r.artifacts) = 'string' AND NULLIF(TRIM(r.artifacts #>> '{}'), '') IS NOT NULL THEN
                v_artifacts := jsonb_build_array(jsonb_build_object('name', TRIM(r.artifacts #>> '{}')));
            ELSE
                -- Retain unexpected scalar data in a one-element array; clients ignore unknown shapes.
                v_artifacts := jsonb_build_array(r.artifacts);
            END IF;

            -- Extract canonical artifact IDs from normalized array elements
            IF jsonb_typeof(v_artifacts) = 'array' THEN
                FOR v_elem IN SELECT value FROM jsonb_array_elements(v_artifacts)
                LOOP
                    v_item_id := public.resolve_cyclecare_artifact_id(
                        CASE
                            WHEN jsonb_typeof(v_elem) = 'object' THEN COALESCE(v_elem->>'id', v_elem->>'name', '')
                            WHEN jsonb_typeof(v_elem) = 'string' THEN v_elem #>> '{}'
                            ELSE ''
                        END
                    );
                    IF v_item_id IS NOT NULL AND NOT (v_unlocked ? v_item_id) THEN
                        v_unlocked := v_unlocked || jsonb_build_array(v_item_id);
                    END IF;
                END LOOP;
            END IF;

            IF v_unlocked IS DISTINCT FROM r.unlocked_items OR v_artifacts IS DISTINCT FROM r.artifacts THEN
                UPDATE public.economy_profiles
                   SET unlocked_items = v_unlocked,
                       artifacts = v_artifacts,
                       updated_at = NOW()
                 WHERE user_id = r.user_id;
            END IF;
        END LOOP;
    END IF;
END $$;

-- Harden purchase_economy_item against non-array unlocked_items
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
    v_unlocked JSONB;
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
    v_unlocked := CASE
        WHEN jsonb_typeof(v_profile.unlocked_items) = 'array' THEN v_profile.unlocked_items
        ELSE '["cycle_coin"]'::JSONB
    END;
    IF v_unlocked ? p_item_id THEN
        RETURN jsonb_build_object('success', FALSE, 'message', 'Item already unlocked');
    END IF;
    IF COALESCE(v_profile.coin_balance, 0) < v_price THEN
        RETURN jsonb_build_object('success', FALSE, 'message', 'Insufficient Cycle Coins');
    END IF;
    UPDATE public.economy_profiles SET coin_balance = COALESCE(coin_balance, 0) - v_price,
        lifetime_spent = COALESCE(lifetime_spent, 0) + v_price,
        unlocked_items = v_unlocked || jsonb_build_array(p_item_id),
        active_artifact = CASE WHEN v_kind = 'ARTIFACT' THEN p_item_id ELSE active_artifact END,
        active_frame = CASE WHEN v_kind = 'FRAME' THEN p_item_id ELSE active_frame END, updated_at = NOW()
    WHERE user_id = v_user_id;
    RETURN jsonb_build_object('success', TRUE, 'message', 'Item unlocked', 'item_id', p_item_id);
END;
$$;
REVOKE ALL ON FUNCTION public.purchase_economy_item(TEXT) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.purchase_economy_item(TEXT) TO authenticated;

-- Harden equip_economy_item against non-array unlocked_items
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
    IF NOT FOUND THEN
        RETURN jsonb_build_object('success', FALSE, 'message', 'Item is not unlocked');
    END IF;
    v_unlocked := CASE
        WHEN jsonb_typeof(v_unlocked) = 'array' THEN v_unlocked
        ELSE '["cycle_coin"]'::JSONB
    END;
    IF NOT (v_unlocked ? p_item_id) THEN
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

-- Enrich admin_get_data() with complete economy telemetry while keeping health data private
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
            suspension_reason, suspended_until, created_at, updated_at
        FROM public.profiles ORDER BY created_at DESC LIMIT 50
    ) AS q;
    SELECT COALESCE(jsonb_agg(to_jsonb(q)), '[]'::JSONB) INTO v_economy FROM (
        SELECT id::TEXT AS id, user_id, coin_balance, lifetime_earned, lifetime_spent,
            tier, rank, active_artifact, active_frame, active_theme,
            CASE WHEN jsonb_typeof(unlocked_items) = 'array' THEN unlocked_items ELSE '["cycle_coin"]'::JSONB END AS unlocked_items,
            last_mined_date,
            CASE WHEN UPPER(COALESCE(status, 'ACTIVE')) = 'SUSPENDED'
                      AND suspended_until IS NOT NULL AND suspended_until <= NOW()
                 THEN 'ACTIVE' ELSE COALESCE(status, 'ACTIVE') END AS status,
            suspension_reason, suspended_until,
            CASE WHEN jsonb_typeof(artifacts) = 'array' THEN artifacts ELSE '[]'::JSONB END AS artifacts,
            last_penalized_at, updated_at
        FROM public.economy_profiles ORDER BY updated_at DESC LIMIT 100
    ) AS q;
    SELECT COALESCE(jsonb_agg(to_jsonb(q)), '[]'::JSONB) INTO v_transactions FROM (
        SELECT id::TEXT AS id, user_id, external_reference, provider, amount, currency,
            status, provider_reference,
            CASE WHEN jsonb_typeof(metadata) = 'object' THEN COALESCE(metadata->>'package_id', metadata->>'packageId') ELSE NULL END AS package_id,
            CASE
                WHEN jsonb_typeof(metadata) = 'object' AND (metadata->>'coins_to_credit') ~ '^[0-9]+$'
                THEN (metadata->>'coins_to_credit')::BIGINT
                ELSE 0
            END AS coins_credited,
            created_at, updated_at
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

-- Update admin_apply_action so GIFT safely normalizes non-array JSON and unlocks the canonical item_id in unlocked_items
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
    v_artifact_id TEXT;
    v_rarity TEXT;
    v_artifacts JSONB;
    v_unlocked JSONB;
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
        v_artifact := NULLIF(LEFT(TRIM(COALESCE(p_payload->>'artifact_name', p_payload->>'artifact_id', '')), 120), '');
        v_rarity := NULLIF(LEFT(TRIM(COALESCE(p_payload->>'artifact_rarity', 'Rare')), 40), '');
        v_artifact_id := public.resolve_cyclecare_artifact_id(COALESCE(p_payload->>'artifact_id', v_artifact));
        IF v_artifact_id IS NULL THEN
            RAISE EXCEPTION 'Artifact is not in the approved catalog';
        END IF;
        SELECT artifacts, unlocked_items
          INTO v_artifacts, v_unlocked
          FROM public.economy_profiles WHERE user_id = v_target FOR UPDATE;
        IF NOT FOUND THEN
            v_artifacts := jsonb_build_array(jsonb_build_object(
                'id', v_artifact_id,
                'name', v_artifact,
                'rarity', COALESCE(v_rarity, 'Rare'),
                'giftedAt', NOW(),
                'giftedBy', 'CycleCare Administration'
            ));
            v_unlocked := '["cycle_coin"]'::JSONB || jsonb_build_array(v_artifact_id);
            INSERT INTO public.economy_profiles(user_id, artifacts, unlocked_items)
            VALUES (v_target, v_artifacts, v_unlocked);
        ELSE
            v_artifacts := CASE
                WHEN jsonb_typeof(v_artifacts) = 'array' THEN v_artifacts
                WHEN jsonb_typeof(v_artifacts) = 'object' THEN jsonb_build_array(v_artifacts)
                WHEN jsonb_typeof(v_artifacts) = 'string' AND NULLIF(TRIM(v_artifacts #>> '{}'), '') IS NOT NULL
                    THEN jsonb_build_array(jsonb_build_object('name', TRIM(v_artifacts #>> '{}')))
                ELSE '[]'::JSONB
            END || jsonb_build_array(jsonb_build_object(
                'id', v_artifact_id,
                'name', v_artifact,
                'rarity', COALESCE(v_rarity, 'Rare'),
                'giftedAt', NOW(),
                'giftedBy', 'CycleCare Administration'
            ));
            v_unlocked := CASE
                WHEN jsonb_typeof(v_unlocked) = 'array' THEN v_unlocked
                ELSE '["cycle_coin"]'::JSONB
            END;
            IF NOT (v_unlocked ? v_artifact_id) THEN
                v_unlocked := v_unlocked || jsonb_build_array(v_artifact_id);
            END IF;
            UPDATE public.economy_profiles
               SET artifacts = v_artifacts,
                   unlocked_items = v_unlocked,
                   updated_at = NOW()
             WHERE user_id = v_target;
        END IF;
        INSERT INTO public.user_notifications(user_id, target_identifier, title, body, type, priority, sender)
        VALUES (v_target, v_target, 'Exclusive Gift: ' || v_artifact,
            'CycleCare Administration has gifted you a collectible artifact: ' || v_artifact || '!', 'GIFT', 'HIGH', 'CYCLECARE_OFFICIAL');
        v_payload_details := jsonb_build_object('artifact_id', v_artifact_id, 'artifact', v_artifact, 'rarity', v_rarity);

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

-- Performance indexes for mobile notification polling and transaction ledger queries
CREATE INDEX IF NOT EXISTS idx_transactions_user_created ON public.transactions(user_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_user_notifications_user_read_created ON public.user_notifications(user_id, is_read, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_broadcast_dispatches_active_created ON public.broadcast_dispatches(is_active, created_at DESC);

NOTIFY pgrst, 'reload schema';
