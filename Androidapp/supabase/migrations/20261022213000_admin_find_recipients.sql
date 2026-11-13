CREATE OR REPLACE FUNCTION public.admin_find_recipients(p_query TEXT)
RETURNS JSONB LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,public AS $$
DECLARE q TEXT := lower(trim(p_query));
BEGIN
 IF NOT public.is_cyclecare_admin() THEN RAISE EXCEPTION 'Administrator access required'; END IF;
 IF length(q)<2 OR length(q)>100 THEN RETURN '[]'::jsonb; END IF;
 RETURN (SELECT coalesce(jsonb_agg(to_jsonb(x)),'[]'::jsonb) FROM (
   SELECT u.id::text AS user_id, coalesce(nullif(p.display_name,''),split_part(u.email,'@',1)) AS display_name,u.email
   FROM auth.users u JOIN public.profiles p ON p.user_id::text=u.id::text
   WHERE u.email IS NOT NULL AND (
      (position('@' in q)>0 AND lower(u.email)=q) OR
      (position('@' in q)=0 AND (position(q in lower(coalesce(p.display_name,'')))>0 OR position(q in lower(split_part(u.email,'@',1)))>0 OR
       (length(q)>=3 AND (lower(coalesce(p.display_name,'')) LIKE '%'||left(q,3)||'%' OR lower(split_part(u.email,'@',1)) LIKE '%'||left(q,3)||'%')))))
   ORDER BY lower(coalesce(p.display_name,u.email)),u.id LIMIT 25
 ) x);
END; $$;
REVOKE ALL ON FUNCTION public.admin_find_recipients(TEXT) FROM PUBLIC,anon;
GRANT EXECUTE ON FUNCTION public.admin_find_recipients(TEXT) TO authenticated;
