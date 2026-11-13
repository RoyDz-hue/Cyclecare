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
CREATE OR REPLACE FUNCTION public.admin_member_insights() RETURNS JSONB
LANGUAGE SQL SECURITY INVOKER SET search_path=pg_catalog,public AS $$
 SELECT public.admin_member_insights_scoped(FALSE);
$$;
