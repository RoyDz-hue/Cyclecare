-- Exact account binding, never model-inferred. Only current admins may resolve.
CREATE OR REPLACE FUNCTION public.admin_resolve_recipient(p_email TEXT)
RETURNS JSONB LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, public AS $$
DECLARE v_id UUID;
BEGIN
 IF NOT public.is_cyclecare_admin() THEN RAISE EXCEPTION 'Administrator access required'; END IF;
 SELECT id INTO v_id FROM auth.users WHERE lower(email)=lower(trim(p_email));
 IF v_id IS NULL THEN RAISE EXCEPTION 'No signed-in account matches that email'; END IF;
 IF NOT EXISTS (SELECT 1 FROM public.profiles WHERE user_id::text=v_id::text) THEN RAISE EXCEPTION 'That account has no CycleCare profile'; END IF;
 RETURN jsonb_build_object('user_id',v_id::text,'email',lower(trim(p_email)));
END; $$;
REVOKE ALL ON FUNCTION public.admin_resolve_recipient(TEXT) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.admin_resolve_recipient(TEXT) TO authenticated;
