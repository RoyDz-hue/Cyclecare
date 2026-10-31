-- Tier split must count every member exactly once, using the same canonical
-- rule as admin_member_count / admin_member_tier_partition: partition over the
-- profiles registry, wallet row matched by uid then device_id then id,
-- absent wallet row = FREE, conflicting or unrecognized tiers = UNKNOWN.
-- The previous implementation grouped raw economy_profiles rows, so members
-- without a wallet row vanished from the breakdown (18 of 20 shown).
CREATE OR REPLACE FUNCTION public.admin_member_insights_scoped(p_include_guides boolean DEFAULT false)
 RETURNS jsonb
 LANGUAGE plpgsql
 SECURITY DEFINER
 SET search_path TO 'pg_catalog', 'public'
AS $function$
DECLARE
    v_result JSONB;
BEGIN
    IF NOT public.is_cyclecare_admin() THEN RAISE EXCEPTION 'Administrator access required'; END IF;
    SELECT jsonb_build_object(
        'richest', (SELECT COALESCE(jsonb_agg(to_jsonb(q)), '[]'::JSONB) FROM (
            SELECT user_id, coin_balance, lifetime_earned, tier FROM public.economy_profiles
            WHERE p_include_guides OR user_id NOT LIKE 'persona:%'
            ORDER BY coin_balance DESC LIMIT 10) q),
        'newest', (SELECT COALESCE(jsonb_agg(to_jsonb(q)), '[]'::JSONB) FROM (
            SELECT p.user_id::TEXT AS user_id, p.account_mode, p.status, p.created_at,
                   coalesce(nullif(p.display_name,''), split_part(u.email,'@',1)) AS display_name,
                   u.email
            FROM public.profiles p LEFT JOIN auth.users u ON u.id = p.user_id
            WHERE p_include_guides OR p.user_id::text NOT LIKE 'persona:%'
            ORDER BY p.created_at DESC LIMIT 10) q),
        'most_active', (SELECT COALESCE(jsonb_agg(to_jsonb(q)), '[]'::JSONB) FROM (
            SELECT user_id::TEXT AS user_id, COUNT(*) AS logs_30d, MAX(date) AS last_log_date FROM public.daily_logs
            WHERE created_at > NOW() - INTERVAL '30 days' AND (p_include_guides OR user_id::text NOT LIKE 'persona:%')
            GROUP BY user_id ORDER BY COUNT(*) DESC LIMIT 10) q),
        'inactive', (SELECT COALESCE(jsonb_agg(to_jsonb(q)), '[]'::JSONB) FROM (
            SELECT p.user_id::TEXT AS user_id, p.created_at AS joined_at, MAX(d.date) AS last_log_date
            FROM public.profiles p LEFT JOIN public.daily_logs d ON d.user_id = p.user_id
            WHERE p_include_guides OR p.user_id::text NOT LIKE 'persona:%'
            GROUP BY p.user_id, p.created_at
            HAVING MAX(d.date) IS NULL OR MAX(d.date) < CURRENT_DATE - INTERVAL '14 days'
            ORDER BY p.created_at DESC LIMIT 10) q),
        -- Canonical member partition: identical semantics to
        -- admin_member_tier_partition() (every profile exactly once, absent
        -- wallet = FREE, conflicts/unrecognized = UNKNOWN).
        'tier_split', (SELECT jsonb_build_object(
            'FREE', count(*) FILTER (WHERE eff_tier = 'FREE'),
            'SUPPORTER', count(*) FILTER (WHERE eff_tier = 'SUPPORTER'),
            'PREMIUM', count(*) FILTER (WHERE eff_tier = 'PREMIUM'),
            'UNKNOWN', count(*) FILTER (WHERE eff_tier = 'UNKNOWN'),
            'total', count(*)
        ) FROM (
            SELECT CASE
                     WHEN w.matches = 0 THEN 'FREE'
                     WHEN w.distinct_tiers > 1 THEN 'UNKNOWN'
                     WHEN w.tier IN ('FREE','SUPPORTER','PREMIUM') THEN w.tier
                     ELSE 'UNKNOWN'
                   END AS eff_tier
            FROM public.profiles p
            LEFT JOIN LATERAL (
                SELECT count(*) AS matches,
                       count(DISTINCT UPPER(COALESCE(e.tier, 'FREE'))) AS distinct_tiers,
                       MIN(UPPER(COALESCE(e.tier, 'FREE'))) AS tier
                FROM public.economy_profiles e
                WHERE e.user_id = COALESCE(NULLIF(p.user_id::TEXT, ''), p.device_id::TEXT, p.id::TEXT)
                  AND e.user_id NOT LIKE 'persona:%'
            ) w ON TRUE
            WHERE p.user_id::TEXT NOT LIKE 'persona:%'
        ) per_member),
        'guides_excluded', NOT p_include_guides,
        'restricted', (SELECT COUNT(*) FROM public.profiles WHERE UPPER(COALESCE(status, 'ACTIVE')) IN ('SUSPENDED', 'BANNED'))
    ) INTO v_result;
    RETURN v_result;
END;
$function$;
