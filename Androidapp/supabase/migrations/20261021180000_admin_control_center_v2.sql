-- Admin Control Center v2: Supabase as the single source of truth for pricing,
-- catalog, moderation, guides and audit, all behind is_cyclecare_admin().

-- 1) Moderation needs a reversible remove: keep the original content once.
ALTER TABLE public.chat_messages ADD COLUMN IF NOT EXISTS original_content TEXT;
ALTER TABLE public.chat_messages ADD COLUMN IF NOT EXISTS removed_at TIMESTAMPTZ;
ALTER TABLE public.chat_messages ADD COLUMN IF NOT EXISTS removed_by TEXT;

-- 2) Public, whitelisted app-config reads so the Android app and the Admin PWA
--    consume the SAME server values (app falls back to bundled defaults offline).
CREATE OR REPLACE FUNCTION public.get_public_app_config(p_key TEXT)
RETURNS JSONB
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, public
AS $$
DECLARE
    v_key TEXT := LOWER(TRIM(COALESCE(p_key, '')));
    v_value JSONB;
BEGIN
    IF v_key NOT IN ('coin_packages', 'economy_multipliers') THEN
        RAISE EXCEPTION 'This configuration is not public';
    END IF;
    SELECT value INTO v_value FROM public.app_config WHERE key = v_key;
    RETURN COALESCE(v_value, 'null'::JSONB);
END;
$$;
REVOKE ALL ON FUNCTION public.get_public_app_config(TEXT) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.get_public_app_config(TEXT) TO anon, authenticated;

-- 3) One read for the Admin PWA control surfaces.
CREATE OR REPLACE FUNCTION public.admin_get_control_data()
RETURNS JSONB
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, public
AS $$
DECLARE
    v_config JSONB;
    v_personas JSONB;
    v_cosmetics JSONB;
    v_flagged JSONB;
    v_signals JSONB;
    v_audit JSONB;
    v_runtime JSONB;
    v_topics JSONB;
BEGIN
    IF NOT public.is_cyclecare_admin() THEN RAISE EXCEPTION 'Administrator access required'; END IF;

    SELECT COALESCE(jsonb_object_agg(key, value), '{}'::JSONB) INTO v_config
      FROM public.app_config
     WHERE key IN ('coin_packages', 'economy_multipliers', 'payment_gateway', 'ai_config');

    SELECT COALESCE(jsonb_agg(to_jsonb(q) ORDER BY q.sort_order, q.item_id), '[]'::JSONB) INTO v_cosmetics
      FROM (SELECT item_id, kind, name, description, price_coins, premium_included, rarity, asset_key, is_active, sort_order
            FROM public.cosmetic_catalog) q;

    SELECT COALESCE(jsonb_agg(to_jsonb(q) ORDER BY q.created_at DESC), '[]'::JSONB) INTO v_flagged
      FROM (SELECT m.id::TEXT AS id, m.sender_alias, m.content, m.is_flagged, m.removed_at, m.created_at,
                   t.title AS topic_title, m.topic_id::TEXT AS topic_id
            FROM public.chat_messages m LEFT JOIN public.chat_topics t ON t.id = m.topic_id
            WHERE m.is_flagged = TRUE OR m.removed_at IS NOT NULL
            ORDER BY m.created_at DESC LIMIT 100) q;

    SELECT COALESCE(jsonb_agg(to_jsonb(q) ORDER BY q.created_at DESC), '[]'::JSONB) INTO v_signals
      FROM (SELECT id::TEXT AS id, user_id, level, trigger_key, resolved_at, created_at
            FROM public.safety_events ORDER BY created_at DESC LIMIT 100) q;

    SELECT COALESCE(jsonb_agg(to_jsonb(q) ORDER BY q.created_at DESC), '[]'::JSONB) INTO v_audit
      FROM (SELECT id::TEXT AS id, admin_user_id::TEXT AS admin_user_id, admin_action, target_user_id, details, created_at
            FROM public.admin_audit_logs ORDER BY created_at DESC LIMIT 200) q;

    SELECT to_jsonb(q) INTO v_runtime FROM (
        SELECT active_ai_provider, active_mpesa_provider, community_personas_enabled, hybrid_enabled, updated_at
        FROM public.cyclecare_runtime_config WHERE id = TRUE) q;

    SELECT COALESCE(jsonb_agg(to_jsonb(q) ORDER BY q.created_at DESC), '[]'::JSONB) INTO v_topics
      FROM (SELECT id::TEXT AS id, title, description, category, coin_gate, is_active
            FROM public.chat_topics LIMIT 50) q;

    SELECT COALESCE(jsonb_agg(to_jsonb(q)), '[]'::JSONB) INTO v_personas
      FROM (SELECT persona_id, display_name, tagline, look_tier, topics, daily_post_cap, activity_weight, is_active
            FROM public.personas ORDER BY persona_id) q;

    RETURN jsonb_build_object(
        'app_config', v_config,
        'personas', v_personas,
        'cosmetics', v_cosmetics,
        'flagged_messages', v_flagged,
        'safety_events', v_signals,
        'audit_log', v_audit,
        'runtime_config', v_runtime,
        'topics', v_topics,
        'counts', jsonb_build_object(
            'profiles', (SELECT COUNT(*) FROM public.profiles),
            'suspended', (SELECT COUNT(*) FROM public.profiles WHERE UPPER(COALESCE(status,'ACTIVE')) = 'SUSPENDED' AND (suspended_until IS NULL OR suspended_until > NOW())),
            'banned', (SELECT COUNT(*) FROM public.profiles WHERE UPPER(COALESCE(status,'ACTIVE')) = 'BANNED'),
            'flagged_open', (SELECT COUNT(*) FROM public.chat_messages WHERE is_flagged = TRUE),
            'safety_open', (SELECT COUNT(*) FROM public.safety_events WHERE resolved_at IS NULL),
            'signups_7d', (SELECT COUNT(*) FROM public.profiles WHERE created_at > NOW() - INTERVAL '7 days')
        )
    );
END;
$$;
REVOKE ALL ON FUNCTION public.admin_get_control_data() FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.admin_get_control_data() TO authenticated;

-- 4) Validated, audited app-config writes (pricing, multipliers, gateway, ai).
CREATE OR REPLACE FUNCTION public.admin_update_app_config(p_key TEXT, p_value JSONB)
RETURNS JSONB
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, public
AS $$
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
            IF COALESCE((v_pkg->>'bonusCoins')::NUMERIC, 0) < 0 OR COALESCE((v_pkg->>'bonusCoins')::NUMERIC, 0) > 1000000 THEN
                RAISE EXCEPTION 'Package % bonus must be between 0 and 1,000,000', v_pkg->>'id'; END IF;
            IF COALESCE((v_pkg->>'priceKes')::NUMERIC, -1) < 0 OR (v_pkg->>'priceKes')::NUMERIC > 1000000 THEN
                RAISE EXCEPTION 'Package % KES price cannot be negative', v_pkg->>'id'; END IF;
            IF COALESCE((v_pkg->>'priceUsd')::NUMERIC, -1) < 0 OR (v_pkg->>'priceUsd')::NUMERIC > 10000 THEN
                RAISE EXCEPTION 'Package % USD price cannot be negative', v_pkg->>'id'; END IF;
            IF (v_pkg->>'priceKes')::NUMERIC = 0 AND (v_pkg->>'priceUsd')::NUMERIC = 0 THEN
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
$$;
REVOKE ALL ON FUNCTION public.admin_update_app_config(TEXT, JSONB) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.admin_update_app_config(TEXT, JSONB) TO authenticated;

-- 5) Guides kill switch (audited; launch flip stays a deliberate admin action).
CREATE OR REPLACE FUNCTION public.admin_set_personas_enabled(p_enabled BOOLEAN)
RETURNS JSONB
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, public
AS $$
BEGIN
    IF NOT public.is_cyclecare_admin() THEN RAISE EXCEPTION 'Administrator access required'; END IF;
    UPDATE public.cyclecare_runtime_config
       SET community_personas_enabled = COALESCE(p_enabled, FALSE), updated_at = NOW(), updated_by = auth.uid()
     WHERE id = TRUE;
    INSERT INTO public.admin_audit_logs(admin_user_id, admin_action, target_user_id, details, performed_by)
    VALUES (auth.uid(), 'ADMIN_SET_PERSONAS_ENABLED', 'CYCLECARE_RUNTIME', jsonb_build_object('enabled', COALESCE(p_enabled, FALSE)), auth.uid()::TEXT);
    RETURN jsonb_build_object('success', TRUE, 'community_personas_enabled', COALESCE(p_enabled, FALSE));
END;
$$;
REVOKE ALL ON FUNCTION public.admin_set_personas_enabled(BOOLEAN) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.admin_set_personas_enabled(BOOLEAN) TO authenticated;

-- 6) Per-guide caps and activation.
CREATE OR REPLACE FUNCTION public.admin_update_persona(p_persona_id TEXT, p_daily_post_cap INTEGER DEFAULT NULL, p_activity_weight NUMERIC DEFAULT NULL, p_is_active BOOLEAN DEFAULT NULL)
RETURNS JSONB
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, public
AS $$
BEGIN
    IF NOT public.is_cyclecare_admin() THEN RAISE EXCEPTION 'Administrator access required'; END IF;
    IF p_daily_post_cap IS NOT NULL AND p_daily_post_cap NOT BETWEEN 0 AND 50 THEN RAISE EXCEPTION 'Daily post cap must be between 0 and 50'; END IF;
    IF p_activity_weight IS NOT NULL AND (p_activity_weight < 0 OR p_activity_weight > 10) THEN RAISE EXCEPTION 'Activity weight must be between 0 and 10'; END IF;
    UPDATE public.personas
       SET daily_post_cap = COALESCE(p_daily_post_cap, daily_post_cap),
           activity_weight = COALESCE(p_activity_weight, activity_weight),
           is_active = COALESCE(p_is_active, is_active)
     WHERE persona_id = p_persona_id;
    IF NOT FOUND THEN RAISE EXCEPTION 'Guide not found'; END IF;
    INSERT INTO public.admin_audit_logs(admin_user_id, admin_action, target_user_id, details, performed_by)
    VALUES (auth.uid(), 'ADMIN_UPDATE_PERSONA', p_persona_id,
            jsonb_build_object('daily_post_cap', p_daily_post_cap, 'activity_weight', p_activity_weight, 'is_active', p_is_active), auth.uid()::TEXT);
    RETURN jsonb_build_object('success', TRUE, 'persona_id', p_persona_id);
END;
$$;
REVOKE ALL ON FUNCTION public.admin_update_persona(TEXT, INTEGER, NUMERIC, BOOLEAN) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.admin_update_persona(TEXT, INTEGER, NUMERIC, BOOLEAN) TO authenticated;

-- 7) Cosmetic catalog pricing and availability.
CREATE OR REPLACE FUNCTION public.admin_upsert_cosmetic(p_item_id TEXT, p_kind TEXT, p_name TEXT, p_description TEXT, p_price_coins BIGINT, p_rarity TEXT, p_is_active BOOLEAN, p_sort_order INTEGER DEFAULT 0, p_premium_included BOOLEAN DEFAULT FALSE)
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
REVOKE ALL ON FUNCTION public.admin_upsert_cosmetic(TEXT, TEXT, TEXT, TEXT, BIGINT, TEXT, BOOLEAN, INTEGER, BOOLEAN) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.admin_upsert_cosmetic(TEXT, TEXT, TEXT, TEXT, BIGINT, TEXT, BOOLEAN, INTEGER, BOOLEAN) TO authenticated;

-- 8) Reversible moderation: dismiss a flag, remove (redact, restorable), restore.
CREATE OR REPLACE FUNCTION public.admin_moderate_message(p_message_id UUID, p_action TEXT)
RETURNS JSONB
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, public
AS $$
DECLARE
    v_action TEXT := UPPER(TRIM(COALESCE(p_action, '')));
    v_msg RECORD;
BEGIN
    IF NOT public.is_cyclecare_admin() THEN RAISE EXCEPTION 'Administrator access required'; END IF;
    SELECT * INTO v_msg FROM public.chat_messages WHERE id = p_message_id;
    IF NOT FOUND THEN RAISE EXCEPTION 'Message not found'; END IF;

    IF v_action = 'DISMISS' THEN
        UPDATE public.chat_messages SET is_flagged = FALSE WHERE id = p_message_id;
    ELSIF v_action = 'REMOVE' THEN
        IF v_msg.removed_at IS NOT NULL THEN RAISE EXCEPTION 'Message is already removed'; END IF;
        UPDATE public.chat_messages
           SET original_content = content, content = '[Removed by CycleCare moderation]', is_flagged = FALSE,
               removed_at = NOW(), removed_by = auth.uid()::TEXT
         WHERE id = p_message_id;
    ELSIF v_action = 'RESTORE' THEN
        IF v_msg.removed_at IS NULL THEN RAISE EXCEPTION 'Message is not removed'; END IF;
        UPDATE public.chat_messages
           SET content = COALESCE(original_content, content), original_content = NULL, removed_at = NULL, removed_by = NULL
         WHERE id = p_message_id;
    ELSE
        RAISE EXCEPTION 'Unsupported moderation action';
    END IF;

    INSERT INTO public.admin_audit_logs(admin_user_id, admin_action, target_user_id, details, performed_by)
    VALUES (auth.uid(), 'ADMIN_MODERATE_' || v_action, COALESCE(v_msg.user_id, 'message:' || p_message_id::TEXT), jsonb_build_object('message_id', p_message_id), auth.uid()::TEXT);
    RETURN jsonb_build_object('success', TRUE, 'action', v_action, 'message_id', p_message_id);
END;
$$;
REVOKE ALL ON FUNCTION public.admin_moderate_message(UUID, TEXT) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.admin_moderate_message(UUID, TEXT) TO authenticated;

-- 9) Tier changes join the existing atomic admin action set.
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
