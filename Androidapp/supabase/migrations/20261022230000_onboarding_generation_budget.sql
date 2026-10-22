-- Counters contain daily salted hashes only, never answers or raw network addresses.
CREATE TABLE IF NOT EXISTS public.onboarding_generation_budget (
  budget_day DATE NOT NULL,
  scope_key TEXT NOT NULL,
  used INTEGER NOT NULL DEFAULT 0 CHECK (used >= 0),
  PRIMARY KEY (budget_day, scope_key)
);
ALTER TABLE public.onboarding_generation_budget ENABLE ROW LEVEL SECURITY;
REVOKE ALL ON public.onboarding_generation_budget FROM PUBLIC, anon, authenticated;
GRANT ALL ON public.onboarding_generation_budget TO service_role;
CREATE OR REPLACE FUNCTION public.claim_onboarding_generation(p_scope_hash TEXT)
RETURNS BOOLEAN LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog,public AS $$
DECLARE d DATE := (NOW() AT TIME ZONE 'UTC')::DATE; g INTEGER; s INTEGER;
BEGIN
 IF auth.role() IS DISTINCT FROM 'service_role' THEN RETURN FALSE; END IF;
 IF p_scope_hash IS NULL OR p_scope_hash !~ '^[a-f0-9]{64}$' THEN RETURN FALSE; END IF;
 INSERT INTO public.onboarding_generation_budget VALUES(d,'global',0) ON CONFLICT DO NOTHING;
 SELECT used INTO g FROM public.onboarding_generation_budget WHERE budget_day=d AND scope_key='global' FOR UPDATE;
 IF g>=1000 THEN RETURN FALSE; END IF;
 INSERT INTO public.onboarding_generation_budget VALUES(d,p_scope_hash,0) ON CONFLICT DO NOTHING;
 SELECT used INTO s FROM public.onboarding_generation_budget WHERE budget_day=d AND scope_key=p_scope_hash FOR UPDATE;
 IF s>=120 THEN RETURN FALSE; END IF;
 UPDATE public.onboarding_generation_budget SET used=used+1 WHERE budget_day=d AND scope_key IN ('global',p_scope_hash);
 DELETE FROM public.onboarding_generation_budget WHERE budget_day<d-2;
 RETURN TRUE;
END $$;
REVOKE ALL ON FUNCTION public.claim_onboarding_generation(TEXT) FROM PUBLIC,anon,authenticated;
GRANT EXECUTE ON FUNCTION public.claim_onboarding_generation(TEXT) TO service_role;
