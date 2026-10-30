-- Member tier partition: every profile counts exactly once, on the wallet key the app
-- itself would use. Grounded app precedence (SupabaseEconomyService.fetchVerifiedProfile
-- reads economy_profiles only by the session owner id and rejects any other key;
-- ensure_my_economy_profile creates the uid-keyed row as FREE on first open and never
-- merges device- or profile-id-keyed rows): the effective wallet key is the profile's
-- auth uid, with the device-id/profile-id fallback chain kept for exact parity with
-- admin_gift_all_coins_snapshot. Rows on non-effective keys never override the effective
-- key, and economy rows that join no profile are not members and are never counted.
-- A profile lands in UNKNOWN only when its effective key carries conflicting or
-- unrecognized tiers, never by guesswork.
CREATE OR REPLACE FUNCTION public.admin_member_tier_partition()
RETURNS JSONB
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, public
AS $$
DECLARE
    v_result JSONB;
BEGIN
    IF NOT public.is_cyclecare_admin() THEN RAISE EXCEPTION 'Administrator access required'; END IF;
    WITH per_member AS (
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
    )
    SELECT jsonb_build_object(
        'total', count(*),
        'free', count(*) FILTER (WHERE eff_tier = 'FREE'),
        'supporter', count(*) FILTER (WHERE eff_tier = 'SUPPORTER'),
        'premium', count(*) FILTER (WHERE eff_tier = 'PREMIUM'),
        'unknown', count(*) FILTER (WHERE eff_tier = 'UNKNOWN')
    ) INTO v_result FROM per_member;
    RETURN v_result;
END;
$$;

REVOKE ALL ON FUNCTION public.admin_member_tier_partition() FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.admin_member_tier_partition() TO authenticated;

-- Tier counts read the partition, so free + supporter + premium + unknown = total
-- members by construction. The unknown count rides along so callers can say when
-- members are unclassified instead of silently dropping them.
CREATE OR REPLACE FUNCTION public.admin_member_count(
    p_since TIMESTAMPTZ DEFAULT NULL,
    p_tier TEXT DEFAULT NULL
) RETURNS JSONB
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, public
AS $$
DECLARE
    v_total INTEGER;
    v_partition JSONB;
BEGIN
    IF NOT public.is_cyclecare_admin() THEN RAISE EXCEPTION 'Administrator access required'; END IF;
    IF p_tier IS NOT NULL AND UPPER(TRIM(p_tier)) NOT IN ('FREE','SUPPORTER','PREMIUM') THEN
        RAISE EXCEPTION 'Unknown membership tier';
    END IF;
    IF p_tier IS NOT NULL THEN
        v_partition := public.admin_member_tier_partition();
        v_total := (v_partition ->> LOWER(TRIM(p_tier)))::INTEGER;
        RETURN jsonb_build_object('total', v_total, 'unknown', (v_partition ->> 'unknown')::INTEGER);
    END IF;
    SELECT count(*) INTO v_total FROM public.profiles
     WHERE user_id::TEXT NOT LIKE 'persona:%'
       AND (p_since IS NULL OR created_at >= p_since);
    RETURN jsonb_build_object('total', v_total);
END;
$$;

-- Insights tier_split shows the same member partition the count answers use, so the
-- analytics card and the count replies cannot contradict each other. UNKNOWN appears
-- only when members are actually unclassified.
CREATE OR REPLACE FUNCTION public.admin_member_insights_scoped(p_include_guides BOOLEAN DEFAULT FALSE)
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
            SELECT user_id::TEXT AS user_id, account_mode, status, created_at FROM public.profiles
            WHERE p_include_guides OR user_id::text NOT LIKE 'persona:%'
            ORDER BY created_at DESC LIMIT 10) q),
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
        'tier_split', (SELECT jsonb_strip_nulls(jsonb_build_object(
            'FREE', (p ->> 'free')::INTEGER,
            'SUPPORTER', (p ->> 'supporter')::INTEGER,
            'PREMIUM', (p ->> 'premium')::INTEGER,
            'UNKNOWN', NULLIF((p ->> 'unknown')::INTEGER, 0)))
          FROM (SELECT public.admin_member_tier_partition() AS p) part),
        'guides_excluded', NOT p_include_guides,
        'restricted', (SELECT COUNT(*) FROM public.profiles WHERE UPPER(COALESCE(status, 'ACTIVE')) IN ('SUSPENDED', 'BANNED'))
    ) INTO v_result;
    RETURN v_result;
END;
$function$;
