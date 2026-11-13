-- Fix admin_get_control_data: the topics subquery does not select created_at,
-- so ORDER BY q.created_at raised 42703 and broke every admin Home load.
CREATE OR REPLACE FUNCTION public.admin_get_control_data()
 RETURNS jsonb
 LANGUAGE plpgsql
 SECURITY DEFINER
 SET search_path TO 'pg_catalog', 'public'
AS $function$
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

    SELECT COALESCE(jsonb_agg(to_jsonb(q) ORDER BY q.title), '[]'::JSONB) INTO v_topics
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
$function$


