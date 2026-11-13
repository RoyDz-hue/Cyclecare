-- Filtered member counts: joined-after window and/or membership tier, consistent with
-- member insights (tier from economy_profiles with FREE default, guides excluded).
DROP FUNCTION IF EXISTS public.admin_member_count();

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
    IF p_tier IS NOT NULL THEN
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

REVOKE ALL ON FUNCTION public.admin_member_count(TIMESTAMPTZ, TEXT) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.admin_member_count(TIMESTAMPTZ, TEXT) TO authenticated;
