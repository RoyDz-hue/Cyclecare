-- Single-number member count for Admin Command: proportional answers to count questions.
CREATE OR REPLACE FUNCTION public.admin_member_count()
RETURNS JSONB
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, public
AS $$
DECLARE
    v_total INTEGER;
BEGIN
    IF NOT public.is_cyclecare_admin() THEN RAISE EXCEPTION 'Administrator access required'; END IF;
    SELECT count(*) INTO v_total FROM public.profiles;
    RETURN jsonb_build_object('total', v_total);
END;
$$;

REVOKE ALL ON FUNCTION public.admin_member_count() FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.admin_member_count() TO authenticated;
