-- DISPOSABLE TEST FIXTURE ONLY. Representative inspected UUID-era tables; not a production migration.
\set ON_ERROR_STOP on
CREATE EXTENSION IF NOT EXISTS pgcrypto;
CREATE SCHEMA IF NOT EXISTS auth;
DO $$BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='anon') THEN CREATE ROLE anon NOLOGIN; END IF;
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='authenticated') THEN CREATE ROLE authenticated NOLOGIN; END IF;
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='service_role') THEN CREATE ROLE service_role NOLOGIN BYPASSRLS; END IF;
END$$;
CREATE TABLE IF NOT EXISTS auth.users(id uuid PRIMARY KEY, email text);
CREATE OR REPLACE FUNCTION auth.role() RETURNS text LANGUAGE sql STABLE AS $$
  SELECT COALESCE(NULLIF(current_setting('request.jwt.claim.role',true),''), NULLIF(current_setting('role',true),''))
$$;
CREATE OR REPLACE FUNCTION auth.jwt() RETURNS jsonb LANGUAGE sql STABLE AS $$
  SELECT COALESCE(NULLIF(current_setting('request.jwt.claims',true),''),'{}')::jsonb
$$;
-- Correct operator precedence in uid implementation.
CREATE OR REPLACE FUNCTION auth.uid() RETURNS uuid LANGUAGE plpgsql STABLE AS $$DECLARE j jsonb; BEGIN j:=COALESCE(NULLIF(current_setting('request.jwt.claims',true),''),'{}')::jsonb; RETURN NULLIF(j->>'sub','')::uuid; END$$;
CREATE OR REPLACE FUNCTION public.is_cyclecare_account_restricted(p_user_id text) RETURNS boolean LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,public AS $$
DECLARE v boolean; BEGIN EXECUTE $q$SELECT EXISTS (SELECT 1 FROM public.profiles WHERE user_id=$1::uuid AND upper(status) IN ('BANNED','SUSPENDED') AND (upper(status)='BANNED' OR suspended_until IS NULL OR suspended_until>now())) OR EXISTS (SELECT 1 FROM public.economy_profiles WHERE user_id=$1 AND upper(status) IN ('BANNED','SUSPENDED') AND (upper(status)='BANNED' OR suspended_until IS NULL OR suspended_until>now()))$q$ INTO v USING p_user_id; RETURN v; END$$;
CREATE TABLE public.profiles(
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(), user_id uuid UNIQUE,
 device_id text, account_mode text DEFAULT 'ANONYMOUS', goals text, last_period_date date,
 cycle_length int DEFAULT 28, period_duration int DEFAULT 5, typical_symptoms text, typical_energy text, typical_sleep text,
 created_at timestamptz DEFAULT now(), updated_at timestamptz DEFAULT now(), status text DEFAULT 'ACTIVE', suspension_reason text,
 suspended_until timestamptz, role text DEFAULT 'USER');
CREATE TABLE public.cycles(id uuid PRIMARY KEY DEFAULT gen_random_uuid(), user_id uuid NOT NULL REFERENCES auth.users(id) ON DELETE CASCADE,
 start_date date NOT NULL,end_date date,cycle_length int,created_at timestamptz DEFAULT now(),period_length int NOT NULL DEFAULT 5,notes text NOT NULL DEFAULT '',updated_at timestamptz DEFAULT now());
CREATE TABLE public.daily_logs(id uuid PRIMARY KEY DEFAULT gen_random_uuid(),user_id uuid NOT NULL REFERENCES auth.users(id) ON DELETE CASCADE,
 date date NOT NULL,mood text,symptoms text,notes text,created_at timestamptz NOT NULL DEFAULT now(),flow text NOT NULL DEFAULT 'NONE',pain text NOT NULL DEFAULT 'NONE',sexual_activity boolean NOT NULL DEFAULT false,temperature double precision,updated_at timestamptz NOT NULL DEFAULT now(),UNIQUE(user_id,date));
CREATE TABLE public.economy_profiles(id uuid PRIMARY KEY DEFAULT gen_random_uuid(),user_id text UNIQUE NOT NULL,tier text DEFAULT 'FREE',coin_balance bigint DEFAULT 120,status text DEFAULT 'ACTIVE',suspended_until timestamptz);
CREATE TABLE public.cyclecare_admin_roles(user_id uuid PRIMARY KEY REFERENCES auth.users(id) ON DELETE CASCADE);
ALTER TABLE public.profiles ENABLE ROW LEVEL SECURITY; ALTER TABLE public.cycles ENABLE ROW LEVEL SECURITY; ALTER TABLE public.daily_logs ENABLE ROW LEVEL SECURITY; ALTER TABLE public.economy_profiles ENABLE ROW LEVEL SECURITY; ALTER TABLE public.cyclecare_admin_roles ENABLE ROW LEVEL SECURITY;
CREATE POLICY cyclecare_profiles_owner_select ON public.profiles FOR SELECT TO authenticated USING(user_id=auth.uid());
CREATE POLICY cyclecare_economy_owner_select ON public.economy_profiles FOR SELECT TO authenticated USING(user_id=auth.uid()::text);
DO $$DECLARE t text; BEGIN FOR t IN SELECT unnest(ARRAY['cycles','daily_logs']) LOOP EXECUTE format('CREATE POLICY cyclecare_%s_owner_select ON public.%I FOR SELECT TO authenticated USING(user_id=auth.uid())',t,t); EXECUTE format('CREATE POLICY cyclecare_%s_owner_insert ON public.%I FOR INSERT TO authenticated WITH CHECK(user_id=auth.uid())',t,t); EXECUTE format('CREATE POLICY cyclecare_%s_owner_update ON public.%I FOR UPDATE TO authenticated USING(user_id=auth.uid()) WITH CHECK(user_id=auth.uid())',t,t); EXECUTE format('CREATE POLICY cyclecare_%s_owner_delete ON public.%I FOR DELETE TO authenticated USING(user_id=auth.uid())',t,t); END LOOP; END$$;
CREATE POLICY cyclecare_profiles_service ON public.profiles FOR ALL TO service_role USING(true) WITH CHECK(true);
CREATE POLICY cyclecare_cycles_service ON public.cycles FOR ALL TO service_role USING(true) WITH CHECK(true);
CREATE POLICY cyclecare_daily_logs_service ON public.daily_logs FOR ALL TO service_role USING(true) WITH CHECK(true);
CREATE POLICY cyclecare_economy_service ON public.economy_profiles FOR ALL TO service_role USING(true) WITH CHECK(true);
CREATE POLICY cyclecare_admin_service ON public.cyclecare_admin_roles FOR ALL TO service_role USING(true) WITH CHECK(true);
CREATE OR REPLACE FUNCTION public.sync_my_profile(text,text,text,text,integer,integer,text,text,text) RETURNS jsonb LANGUAGE sql SECURITY DEFINER AS $$SELECT jsonb_build_object('success',true)$$;
GRANT USAGE ON SCHEMA public,auth TO anon,authenticated,service_role;
GRANT SELECT,INSERT,UPDATE,DELETE ON auth.users TO service_role;
GRANT SELECT ON public.profiles,public.economy_profiles TO authenticated;
GRANT SELECT,INSERT,UPDATE,DELETE ON public.cycles,public.daily_logs TO authenticated;
GRANT SELECT,INSERT,UPDATE,DELETE ON public.cyclecare_admin_roles TO authenticated;
GRANT SELECT,INSERT,UPDATE,DELETE ON public.profiles,public.cycles,public.daily_logs,public.economy_profiles,public.cyclecare_admin_roles TO service_role;
GRANT EXECUTE ON FUNCTION auth.uid(),auth.role(),auth.jwt(),public.is_cyclecare_account_restricted(text),public.sync_my_profile(text,text,text,text,integer,integer,text,text,text) TO anon,authenticated,service_role;

-- Representative runtime contract reported for the current deployment, not a catalog export.
CREATE TABLE public.cyclecare_runtime_config (
 id boolean PRIMARY KEY DEFAULT true CHECK(id=true),
 active_ai_provider text NOT NULL DEFAULT 'COHERE' CHECK(active_ai_provider IN ('COHERE','GEMINI','OPENROUTER')),
 active_mpesa_provider text NOT NULL DEFAULT 'PAYHERO' CHECK(active_mpesa_provider='PAYHERO'),
 updated_at timestamptz NOT NULL DEFAULT now(), updated_by uuid
);
ALTER TABLE public.cyclecare_runtime_config ENABLE ROW LEVEL SECURITY;
REVOKE ALL ON public.cyclecare_runtime_config FROM PUBLIC,anon,authenticated;
GRANT SELECT,INSERT,UPDATE,DELETE ON public.cyclecare_runtime_config TO service_role;
INSERT INTO public.cyclecare_runtime_config(id) VALUES(true);
CREATE TABLE public.transactions (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(), user_id text NOT NULL,
 external_reference text NOT NULL UNIQUE, provider text NOT NULL CHECK(provider IN ('paypal','paypal_acdc','payhero','okx')),
 amount numeric(12,2) NOT NULL, currency text NOT NULL DEFAULT 'USD',
 status text NOT NULL DEFAULT 'pending' CHECK(status IN ('pending','success','failed','cancelled','expired')),
 provider_reference text, raw_payload jsonb NOT NULL DEFAULT '{}', metadata jsonb NOT NULL DEFAULT '{}',
 created_at timestamptz NOT NULL DEFAULT now(), updated_at timestamptz NOT NULL DEFAULT now()
);
ALTER TABLE public.transactions ENABLE ROW LEVEL SECURITY;
GRANT SELECT,INSERT,UPDATE ON public.transactions TO service_role;
CREATE POLICY transactions_service ON public.transactions FOR ALL TO service_role USING(true) WITH CHECK(true);
-- Install only the separately reviewed historical repair into this fresh fixture.
\ir ../../proposals/20261018120000_cyclecare_uuid_compatible_repairs.sql

-- Representative protected administrator membership, not a test-only always-true alias.
CREATE TABLE IF NOT EXISTS public.cyclecare_admin_roles(user_id uuid PRIMARY KEY REFERENCES auth.users(id) ON DELETE CASCADE,created_at timestamptz NOT NULL DEFAULT now());
ALTER TABLE public.cyclecare_admin_roles ENABLE ROW LEVEL SECURITY;
REVOKE ALL ON public.cyclecare_admin_roles FROM PUBLIC,anon,authenticated;
GRANT SELECT,INSERT,UPDATE,DELETE ON public.cyclecare_admin_roles TO service_role;
CREATE OR REPLACE FUNCTION public.is_cyclecare_admin() RETURNS boolean LANGUAGE sql STABLE SECURITY DEFINER SET search_path=pg_catalog,public AS $$ SELECT EXISTS(SELECT 1 FROM public.cyclecare_admin_roles WHERE user_id=auth.uid()) $$;
REVOKE ALL ON FUNCTION public.is_cyclecare_admin() FROM PUBLIC,anon;
GRANT EXECUTE ON FUNCTION public.is_cyclecare_admin() TO authenticated,service_role;
