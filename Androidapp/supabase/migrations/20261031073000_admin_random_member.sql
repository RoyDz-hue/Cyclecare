-- Pick one random real member (guides excluded) for "gift a random member" requests.
-- The flow re-binds the returned email through admin_resolve_recipient before proposing,
-- so the plan card always names the actual selected account.
CREATE OR REPLACE FUNCTION public.admin_random_member()
RETURNS JSONB
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, public
AS $$
BEGIN
    IF NOT public.is_cyclecare_admin() THEN RAISE EXCEPTION 'Administrator access required'; END IF;
    RETURN (SELECT jsonb_build_object(
        'user_id', u.id::text,
        'email', u.email,
        'display_name', coalesce(nullif(p.display_name, ''), split_part(u.email, '@', 1)))
      FROM auth.users u
      JOIN public.profiles p ON p.user_id::text = u.id::text
      WHERE u.email IS NOT NULL AND p.user_id::text NOT LIKE 'persona:%'
      ORDER BY random()
      LIMIT 1);
END;
$$;
REVOKE ALL ON FUNCTION public.admin_random_member() FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.admin_random_member() TO authenticated;
