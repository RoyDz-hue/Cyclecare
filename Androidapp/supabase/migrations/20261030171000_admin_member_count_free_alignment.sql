-- Align the FREE tier count with canonical app behavior: a member with no economy row
-- fails every premium/supporter gate (COALESCE(tier,'') misses, 20261018100000) and
-- materializes as FREE on first economy contact, so they count as FREE members.
-- Economy rows can be keyed by auth uid, device id, or profile id (the same fallback
-- chain admin_gift_all_coins_snapshot uses), so all three keys must be checked before
-- treating a member as having no economy row. SUPPORTER/PREMIUM still require an
-- economy row; absent rows never qualify there.
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
BEGIN
    IF NOT public.is_cyclecare_admin() THEN RAISE EXCEPTION 'Administrator access required'; END IF;
    IF p_tier IS NOT NULL AND UPPER(TRIM(p_tier)) NOT IN ('FREE','SUPPORTER','PREMIUM') THEN
        RAISE EXCEPTION 'Unknown membership tier';
    END IF;
    IF p_tier IS NOT NULL AND UPPER(TRIM(p_tier)) = 'FREE' THEN
        SELECT count(*) INTO v_total FROM public.profiles p
         WHERE p.user_id::TEXT NOT LIKE 'persona:%'
           AND NOT EXISTS (SELECT 1 FROM public.economy_profiles e
                            WHERE e.user_id IN (p.user_id::TEXT, p.device_id::TEXT, p.id::TEXT)
                              AND UPPER(COALESCE(e.tier, 'FREE')) <> 'FREE');
    ELSIF p_tier IS NOT NULL THEN
        SELECT count(*) INTO v_total FROM public.economy_profiles
         WHERE UPPER(COALESCE(tier, 'FREE')) = UPPER(TRIM(p_tier)) AND user_id NOT LIKE 'persona:%';
    ELSE
        SELECT count(*) INTO v_total FROM public.profiles
         WHERE user_id::TEXT NOT LIKE 'persona:%'
           AND (p_since IS NULL OR created_at >= p_since);
    END IF;
    RETURN jsonb_build_object('total', v_total);
END;
$$;
