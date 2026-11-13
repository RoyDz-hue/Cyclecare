-- Joined-window member list: who created an account today/this week/this month,
-- with display names, for deterministic admin answers. Guides excluded, like counts.
-- Also adds display_name/email to the insights "newest" list so it stops showing
-- "Unnamed member" when names exist.
CREATE OR REPLACE FUNCTION public.admin_members_joined(p_since TIMESTAMPTZ, p_limit INTEGER DEFAULT 50)
RETURNS JSONB
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, public
AS $$
DECLARE
    v_members JSONB;
    v_total INTEGER;
BEGIN
    IF NOT public.is_cyclecare_admin() THEN RAISE EXCEPTION 'Administrator access required'; END IF;
    IF p_since IS NULL THEN RAISE EXCEPTION 'A start date is required'; END IF;
    SELECT count(*) INTO v_total FROM public.profiles
     WHERE user_id::TEXT NOT LIKE 'persona:%' AND created_at >= p_since;
    SELECT COALESCE(jsonb_agg(to_jsonb(x)), '[]'::JSONB) INTO v_members FROM (
        SELECT p.user_id::TEXT AS user_id,
               coalesce(nullif(p.display_name,''), split_part(u.email,'@',1)) AS display_name,
               u.email,
               p.created_at,
               p.status
          FROM public.profiles p JOIN auth.users u ON u.id = p.user_id
         WHERE p.user_id::TEXT NOT LIKE 'persona:%' AND p.created_at >= p_since
         ORDER BY p.created_at DESC
         LIMIT LEAST(GREATEST(COALESCE(p_limit, 50), 1), 100)
    ) x;
    RETURN jsonb_build_object('total', v_total, 'members', v_members);
END;
$$;

REVOKE ALL ON FUNCTION public.admin_members_joined(TIMESTAMPTZ, INTEGER) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.admin_members_joined(TIMESTAMPTZ, INTEGER) TO authenticated;

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
        'tier_split', (SELECT COALESCE(jsonb_object_agg(tier, n), '{}'::JSONB) FROM (
            SELECT COALESCE(tier, 'FREE') AS tier, COUNT(*) AS n FROM public.economy_profiles WHERE p_include_guides OR user_id NOT LIKE 'persona:%' GROUP BY 1) q),
        'guides_excluded', NOT p_include_guides,
        'restricted', (SELECT COUNT(*) FROM public.profiles WHERE UPPER(COALESCE(status, 'ACTIVE')) IN ('SUSPENDED', 'BANNED'))
    ) INTO v_result;
    RETURN v_result;
END;
$function$;

REVOKE ALL ON FUNCTION public.admin_member_insights_scoped(BOOLEAN) FROM PUBLIC,anon;
GRANT EXECUTE ON FUNCTION public.admin_member_insights_scoped(BOOLEAN) TO authenticated;
