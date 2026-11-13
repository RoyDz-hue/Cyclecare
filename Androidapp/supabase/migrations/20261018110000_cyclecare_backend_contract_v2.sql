-- CycleCare backend contract v2. Forward-only; prior migrations remain unchanged.
BEGIN;

-- Android SupabaseSyncService v2 daily-log contract. Do not add inferred/unsupported fields.
ALTER TABLE IF EXISTS public.daily_logs
  ADD COLUMN IF NOT EXISTS schema_version INTEGER NOT NULL DEFAULT 2,
  ADD COLUMN IF NOT EXISTS energy TEXT NOT NULL DEFAULT 'UNKNOWN',
  ADD COLUMN IF NOT EXISTS pain_intensity INTEGER NOT NULL DEFAULT 0,
  ADD COLUMN IF NOT EXISTS vaginal_health TEXT NOT NULL DEFAULT '',
  ADD COLUMN IF NOT EXISTS urinary_health TEXT NOT NULL DEFAULT '',
  ADD COLUMN IF NOT EXISTS cervical_mucus TEXT NOT NULL DEFAULT '',
  ADD COLUMN IF NOT EXISTS ovulation_test TEXT NOT NULL DEFAULT '',
  ADD COLUMN IF NOT EXISTS sleep_hours DOUBLE PRECISION,
  ADD COLUMN IF NOT EXISTS sleep_quality TEXT NOT NULL DEFAULT 'UNKNOWN';
UPDATE public.daily_logs SET schema_version = 2 WHERE schema_version IS NULL OR schema_version < 2;
ALTER TABLE IF EXISTS public.daily_logs ALTER COLUMN schema_version SET DEFAULT 2;
DO $$ BEGIN
  IF to_regclass('public.daily_logs') IS NOT NULL AND NOT EXISTS (
    SELECT 1 FROM pg_constraint WHERE conname='daily_logs_schema_version_v2'
  ) THEN
    ALTER TABLE public.daily_logs ADD CONSTRAINT daily_logs_schema_version_v2 CHECK (schema_version = 2);
  END IF;
END $$;


CREATE OR REPLACE FUNCTION public.cyclecare_cloud_health_allowed() RETURNS BOOLEAN
LANGUAGE sql STABLE SECURITY DEFINER SET search_path=pg_catalog,public AS $$
 SELECT EXISTS(SELECT 1 FROM public.cyclecare_privacy_consents c WHERE c.user_id=auth.uid()::TEXT AND c.cloud_sync_enabled)
   AND NOT EXISTS(SELECT 1 FROM public.cyclecare_health_deletions d WHERE d.user_id=auth.uid()::TEXT);
$$;
REVOKE ALL ON FUNCTION public.cyclecare_cloud_health_allowed() FROM PUBLIC,anon;
GRANT EXECUTE ON FUNCTION public.cyclecare_cloud_health_allowed() TO authenticated;

-- A restrictive policy is ANDed with every historical permissive owner policy.
-- This closes older policies that otherwise bypass the consent gate.
DO $$ BEGIN
  IF to_regclass('public.cycles') IS NOT NULL THEN
    ALTER TABLE public.cycles ENABLE ROW LEVEL SECURITY;
    DROP POLICY IF EXISTS cyclecare_cycles_consent_gate ON public.cycles;
    CREATE POLICY cyclecare_cycles_consent_gate ON public.cycles AS RESTRICTIVE FOR ALL TO authenticated
      USING (user_id=auth.uid()::TEXT AND public.cyclecare_cloud_health_allowed())
      WITH CHECK (user_id=auth.uid()::TEXT AND public.cyclecare_cloud_health_allowed());
  END IF;
  IF to_regclass('public.daily_logs') IS NOT NULL THEN
    ALTER TABLE public.daily_logs ENABLE ROW LEVEL SECURITY;
    DROP POLICY IF EXISTS cyclecare_daily_logs_consent_gate ON public.daily_logs;
    CREATE POLICY cyclecare_daily_logs_consent_gate ON public.daily_logs AS RESTRICTIVE FOR ALL TO authenticated
      USING (user_id=auth.uid()::TEXT AND public.cyclecare_cloud_health_allowed())
      WITH CHECK (user_id=auth.uid()::TEXT AND schema_version=2 AND public.cyclecare_cloud_health_allowed());
  END IF;
END $$;

-- Explicit handshake lets clients fail closed before a legacy deployment can receive health data.
CREATE TABLE IF NOT EXISTS public.cyclecare_backend_contract (
  id BOOLEAN PRIMARY KEY DEFAULT TRUE CHECK (id), contract_version INTEGER NOT NULL DEFAULT 2,
  daily_log_schema_version INTEGER NOT NULL DEFAULT 2, updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
INSERT INTO public.cyclecare_backend_contract(id,contract_version,daily_log_schema_version) VALUES(TRUE,2,2)
ON CONFLICT (id) DO UPDATE SET contract_version=2,daily_log_schema_version=2,updated_at=NOW();
ALTER TABLE public.cyclecare_backend_contract ENABLE ROW LEVEL SECURITY;
REVOKE ALL ON public.cyclecare_backend_contract FROM PUBLIC, anon, authenticated;
CREATE OR REPLACE FUNCTION public.get_cyclecare_backend_contract(p_client_version INTEGER DEFAULT NULL)
RETURNS JSONB LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,public AS $$
DECLARE v_has_v2 BOOLEAN; v_client INTEGER:=p_client_version;
BEGIN
  SELECT to_regclass('public.daily_logs') IS NOT NULL AND EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema='public' AND table_name='daily_logs' AND column_name='schema_version') INTO v_has_v2;
  RETURN jsonb_build_object('ready',v_has_v2 AND v_client=2,'contract_version',2,'daily_log_schema_version',2,'reason',CASE WHEN NOT v_has_v2 THEN 'legacy_daily_log_schema' WHEN v_client IS DISTINCT FROM 2 THEN 'client_version_2_required' ELSE 'ready' END);
END; $$;
REVOKE ALL ON FUNCTION public.get_cyclecare_backend_contract(INTEGER) FROM PUBLIC,anon,authenticated;
GRANT EXECUTE ON FUNCTION public.get_cyclecare_backend_contract(INTEGER) TO authenticated;

-- Health-only deletion: financial transactions and immutable payment/audit evidence are retained.
DROP FUNCTION IF EXISTS public.delete_my_cyclecare_data();
CREATE OR REPLACE FUNCTION public.delete_my_cyclecare_health_data() RETURNS JSONB
LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,public AS $$
DECLARE v_user TEXT:=auth.uid()::TEXT; v_deleted BIGINT:=0;
BEGIN
 IF v_user IS NULL THEN RETURN jsonb_build_object('success',FALSE,'message','Authentication required'); END IF;
 INSERT INTO public.cyclecare_health_deletions(user_id) VALUES(v_user)
   ON CONFLICT(user_id) DO UPDATE SET deleted_at=NOW();
 DELETE FROM public.daily_logs WHERE user_id=v_user; GET DIAGNOSTICS v_deleted=ROW_COUNT;
 DELETE FROM public.cycles WHERE user_id=v_user;
 DELETE FROM public.cyclecare_privacy_consents WHERE user_id=v_user;
 -- Keep restriction/account metadata while erasing every health-bearing profile field.
 UPDATE public.profiles SET cloud_sync_enabled=FALSE, goals='', last_period_date=NULL,
   cycle_length=28, period_duration=5, typical_symptoms='', typical_energy='', typical_sleep='',
   updated_at=NOW() WHERE user_id=v_user;
 RETURN jsonb_build_object('success',TRUE,'health_data_deleted',TRUE,'deleted_daily_logs',v_deleted,'financial_records_retained',TRUE,'auth_identity_deletion_required',TRUE,'limitations','Transactions, payment references, economy ledger and audit evidence are retained; complete account erasure and the retention policy still require an approved lifecycle service.');
END; $$;
REVOKE ALL ON FUNCTION public.delete_my_cyclecare_health_data() FROM PUBLIC,anon;
GRANT EXECUTE ON FUNCTION public.delete_my_cyclecare_health_data() TO authenticated;

-- Existing privileged profile RPC is replaced, not left as a consent bypass.
CREATE OR REPLACE FUNCTION public.sync_my_profile(
    p_device_id TEXT,
    p_account_mode TEXT,
    p_goals TEXT,
    p_last_period_date TEXT,
    p_cycle_length INTEGER,
    p_period_duration INTEGER,
    p_typical_symptoms TEXT,
    p_typical_energy TEXT,
    p_typical_sleep TEXT
) RETURNS JSONB
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, public
AS $$
DECLARE
    v_user_id TEXT := auth.uid()::TEXT;
BEGIN
    IF v_user_id IS NULL THEN
        RETURN jsonb_build_object('success', FALSE, 'message', 'Authentication required');
    END IF;
    IF NOT public.cyclecare_cloud_health_allowed() THEN
        RETURN jsonb_build_object('success',FALSE,'message','Explicit cloud health consent is required; deleted health data cannot be restored by stale clients.');
    END IF;
    INSERT INTO public.profiles (
        user_id, device_id, account_mode, goals, last_period_date, cycle_length,
        period_duration, typical_symptoms, typical_energy, typical_sleep
    ) VALUES (
        v_user_id, NULLIF(p_device_id, '')::UUID, COALESCE(p_account_mode, 'EMAIL'),
        COALESCE(p_goals, ''), NULLIF(p_last_period_date, ''),
        GREATEST(21, LEAST(COALESCE(p_cycle_length, 28), 45)),
        GREATEST(2, LEAST(COALESCE(p_period_duration, 5), 10)),
        COALESCE(p_typical_symptoms, ''), COALESCE(p_typical_energy, 'NORMAL'),
        COALESCE(p_typical_sleep, 'GOOD')
    )
    ON CONFLICT (user_id) DO UPDATE SET
        device_id = EXCLUDED.device_id,
        account_mode = EXCLUDED.account_mode,
        goals = EXCLUDED.goals,
        last_period_date = EXCLUDED.last_period_date,
        cycle_length = EXCLUDED.cycle_length,
        period_duration = EXCLUDED.period_duration,
        typical_symptoms = EXCLUDED.typical_symptoms,
        typical_energy = EXCLUDED.typical_energy,
        typical_sleep = EXCLUDED.typical_sleep,
        updated_at = NOW();
    RETURN jsonb_build_object('success', TRUE);
END;
$$;
REVOKE ALL ON FUNCTION public.sync_my_profile(TEXT,TEXT,TEXT,TEXT,INTEGER,INTEGER,TEXT,TEXT,TEXT) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.sync_my_profile(TEXT,TEXT,TEXT,TEXT,INTEGER,INTEGER,TEXT,TEXT,TEXT) TO authenticated;

ALTER TABLE public.profiles ENABLE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS cyclecare_profiles_health_consent_gate ON public.profiles;
CREATE POLICY cyclecare_profiles_health_consent_gate ON public.profiles AS RESTRICTIVE FOR ALL TO authenticated
 USING (user_id=auth.uid()::TEXT AND public.cyclecare_cloud_health_allowed())
 WITH CHECK (user_id=auth.uid()::TEXT AND public.cyclecare_cloud_health_allowed());

COMMIT;
