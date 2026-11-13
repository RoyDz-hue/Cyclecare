-- Owner-only non-health account state. Does not expose health fields or grant consent.
CREATE OR REPLACE FUNCTION public.my_account_start_state()
RETURNS JSONB LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,public AS $$
DECLARE p RECORD; BEGIN
 IF auth.uid() IS NULL THEN RAISE EXCEPTION 'Authentication required'; END IF;
 SELECT user_id,status,suspension_reason,suspended_until INTO p FROM public.profiles WHERE user_id=auth.uid();
 IF NOT FOUND THEN RETURN jsonb_build_object('exists',false,'user_id',auth.uid(),'status','ACTIVE'); END IF;
 RETURN jsonb_build_object('exists',true,'user_id',p.user_id,'status',p.status,'suspension_reason',p.suspension_reason,'suspended_until',p.suspended_until);
END $$;
REVOKE ALL ON FUNCTION public.my_account_start_state() FROM PUBLIC,anon;
GRANT EXECUTE ON FUNCTION public.my_account_start_state() TO authenticated,service_role;
