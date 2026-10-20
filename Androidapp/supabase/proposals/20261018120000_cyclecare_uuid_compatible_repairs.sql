-- PROPOSAL ONLY. Apply this single file to the inspected production baseline only after approval.
-- Supersedes BOTH unapplied 20261018100000 / 20261018110000 drafts; never run them first.
-- No existing ID types or historical migration files are changed.
BEGIN;
DO $$ BEGIN
 IF (SELECT atttypid FROM pg_attribute WHERE attrelid='public.profiles'::regclass AND attname='user_id') IS DISTINCT FROM 'uuid'::regtype
 OR (SELECT atttypid FROM pg_attribute WHERE attrelid='public.cycles'::regclass AND attname='user_id') IS DISTINCT FROM 'uuid'::regtype
 OR (SELECT atttypid FROM pg_attribute WHERE attrelid='public.daily_logs'::regclass AND attname='user_id') IS DISTINCT FROM 'uuid'::regtype
 OR (SELECT atttypid FROM pg_attribute WHERE attrelid='public.profiles'::regclass AND attname='device_id') IS DISTINCT FROM 'text'::regtype
 OR (SELECT atttypid FROM pg_attribute WHERE attrelid='public.profiles'::regclass AND attname='last_period_date') IS DISTINCT FROM 'date'::regtype
 OR (SELECT atttypid FROM pg_attribute WHERE attrelid='public.economy_profiles'::regclass AND attname='user_id') IS DISTINCT FROM 'text'::regtype THEN
 RAISE EXCEPTION 'Unexpected baseline types; stop and review'; END IF;
 IF to_regclass('public.cyclecare_privacy_consents') IS NOT NULL
 OR to_regclass('public.cyclecare_health_deletions') IS NOT NULL
 OR to_regclass('public.cyclecare_ai_usage') IS NOT NULL
 OR to_regclass('public.cyclecare_backend_contract') IS NOT NULL THEN
 RAISE EXCEPTION 'Repair tables already exist; stop and review instead of adapting history'; END IF;
END $$;
-- CycleCare backend audit repairs (forward-only; do not edit prior migrations).
-- Compatibility: old health sync fails closed; cloud health reads/writes require
-- explicit cloud_sync_enabled consent (existing rows default FALSE, fail closed).


ALTER TABLE public.profiles ADD COLUMN IF NOT EXISTS cloud_sync_enabled BOOLEAN NOT NULL DEFAULT FALSE;

CREATE TABLE IF NOT EXISTS public.cyclecare_privacy_consents (
    user_id TEXT PRIMARY KEY,
    ai_context_enabled BOOLEAN NOT NULL DEFAULT FALSE,
    cloud_sync_enabled BOOLEAN NOT NULL DEFAULT FALSE,
    image_generation_enabled BOOLEAN NOT NULL DEFAULT FALSE,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
ALTER TABLE public.cyclecare_privacy_consents ENABLE ROW LEVEL SECURITY;
REVOKE ALL ON public.cyclecare_privacy_consents FROM PUBLIC, anon, authenticated;
CREATE POLICY cyclecare_privacy_owner_read ON public.cyclecare_privacy_consents
    FOR SELECT TO authenticated USING (user_id = auth.uid()::TEXT);
GRANT SELECT ON public.cyclecare_privacy_consents TO authenticated;
GRANT SELECT, INSERT, UPDATE, DELETE ON public.cyclecare_privacy_consents TO service_role;

CREATE TABLE IF NOT EXISTS public.cyclecare_health_deletions (
    user_id TEXT PRIMARY KEY, deleted_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
ALTER TABLE public.cyclecare_health_deletions ENABLE ROW LEVEL SECURITY;
REVOKE ALL ON public.cyclecare_health_deletions FROM PUBLIC, anon, authenticated;

CREATE OR REPLACE FUNCTION public.set_my_privacy_consents(
    p_ai_context_enabled BOOLEAN DEFAULT FALSE,
    p_cloud_sync_enabled BOOLEAN DEFAULT FALSE,
    p_image_generation_enabled BOOLEAN DEFAULT FALSE
) RETURNS JSONB LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, public AS $$
DECLARE v_user TEXT := auth.uid()::TEXT;
BEGIN
    IF v_user IS NULL THEN RETURN jsonb_build_object('success', FALSE, 'message', 'Authentication required'); END IF;
    IF current_setting('transaction_isolation') <> 'read committed' THEN
 RAISE EXCEPTION 'Cloud health mutations require READ COMMITTED isolation' USING ERRCODE='25000';
 END IF;
 PERFORM pg_advisory_xact_lock(hashtextextended('cyclecare-health:' || v_user,0));
    IF COALESCE(p_cloud_sync_enabled,FALSE) AND EXISTS(SELECT 1 FROM public.cyclecare_health_deletions WHERE user_id=v_user) THEN
        RETURN jsonb_build_object('success',FALSE,'message','Health data was deleted; cloud sync cannot resume through a stale consent request.');
    END IF;
    INSERT INTO public.cyclecare_privacy_consents(user_id, ai_context_enabled, cloud_sync_enabled, image_generation_enabled)
    VALUES (v_user, COALESCE(p_ai_context_enabled,FALSE), COALESCE(p_cloud_sync_enabled,FALSE), COALESCE(p_image_generation_enabled,FALSE))
    ON CONFLICT (user_id) DO UPDATE SET ai_context_enabled=EXCLUDED.ai_context_enabled,
      cloud_sync_enabled=EXCLUDED.cloud_sync_enabled, image_generation_enabled=EXCLUDED.image_generation_enabled, updated_at=NOW();
    UPDATE public.profiles SET cloud_sync_enabled=COALESCE(p_cloud_sync_enabled,FALSE), updated_at=NOW() WHERE user_id=v_user::UUID;
    RETURN jsonb_build_object('success', TRUE, 'ai_context_enabled', COALESCE(p_ai_context_enabled,FALSE),
      'cloud_sync_enabled', COALESCE(p_cloud_sync_enabled,FALSE), 'image_generation_enabled', COALESCE(p_image_generation_enabled,FALSE));
END; $$;
REVOKE ALL ON FUNCTION public.set_my_privacy_consents(BOOLEAN,BOOLEAN,BOOLEAN) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.set_my_privacy_consents(BOOLEAN,BOOLEAN,BOOLEAN) TO authenticated;

-- Existing direct health sync paths fail closed unless the owner opted in.
DO $$ DECLARE p RECORD; BEGIN
  IF to_regclass('public.cycles') IS NOT NULL THEN
    DROP POLICY IF EXISTS cyclecare_cycles_owner_insert ON public.cycles;
    DROP POLICY IF EXISTS cyclecare_cycles_owner_update ON public.cycles;
    CREATE POLICY cyclecare_cycles_owner_insert ON public.cycles FOR INSERT TO authenticated
      WITH CHECK (user_id=auth.uid() AND EXISTS (SELECT 1 FROM public.cyclecare_privacy_consents c WHERE c.user_id=auth.uid()::TEXT AND c.cloud_sync_enabled));
    CREATE POLICY cyclecare_cycles_owner_update ON public.cycles FOR UPDATE TO authenticated
      USING (user_id=auth.uid() AND EXISTS (SELECT 1 FROM public.cyclecare_privacy_consents c WHERE c.user_id=auth.uid()::TEXT AND c.cloud_sync_enabled))
      WITH CHECK (user_id=auth.uid());
  END IF;
  IF to_regclass('public.daily_logs') IS NOT NULL THEN
    DROP POLICY IF EXISTS cyclecare_daily_logs_owner_insert ON public.daily_logs;
    DROP POLICY IF EXISTS cyclecare_daily_logs_owner_update ON public.daily_logs;
    CREATE POLICY cyclecare_daily_logs_owner_insert ON public.daily_logs FOR INSERT TO authenticated
      WITH CHECK (user_id=auth.uid() AND EXISTS (SELECT 1 FROM public.cyclecare_privacy_consents c WHERE c.user_id=auth.uid()::TEXT AND c.cloud_sync_enabled));
    CREATE POLICY cyclecare_daily_logs_owner_update ON public.daily_logs FOR UPDATE TO authenticated
      USING (user_id=auth.uid() AND EXISTS (SELECT 1 FROM public.cyclecare_privacy_consents c WHERE c.user_id=auth.uid()::TEXT AND c.cloud_sync_enabled))
      WITH CHECK (user_id=auth.uid());
  END IF;
END $$;

CREATE TABLE IF NOT EXISTS public.cyclecare_ai_usage (
    user_id TEXT NOT NULL, usage_date DATE NOT NULL DEFAULT CURRENT_DATE,
    chat_count INTEGER NOT NULL DEFAULT 0, image_count INTEGER NOT NULL DEFAULT 0,
    PRIMARY KEY(user_id, usage_date)
);
ALTER TABLE public.cyclecare_ai_usage ENABLE ROW LEVEL SECURITY;
REVOKE ALL ON public.cyclecare_ai_usage FROM PUBLIC, anon, authenticated;

CREATE OR REPLACE FUNCTION public.consume_cyclecare_ai_quota(p_user_id TEXT, p_kind TEXT)
RETURNS JSONB LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, public SET timezone = 'UTC' AS $$
DECLARE v_uuid UUID; v_admin BOOLEAN; v_premium BOOLEAN; v_row public.cyclecare_ai_usage%ROWTYPE; v_limit INTEGER;
BEGIN
  IF auth.role() IS DISTINCT FROM 'service_role' THEN RETURN jsonb_build_object('allowed',FALSE,'reason','service_role_required'); END IF;
  IF p_user_id IS NULL OR p_kind IS NULL OR p_kind NOT IN ('chat','image') THEN RETURN jsonb_build_object('allowed',FALSE,'reason','invalid_request'); END IF;
  BEGIN v_uuid := p_user_id::UUID;
  EXCEPTION WHEN invalid_text_representation THEN
    RETURN jsonb_build_object('allowed',FALSE,'reason','invalid_request');
  END;
  p_user_id := v_uuid::TEXT;
  IF public.is_cyclecare_account_restricted(p_user_id) THEN
    RETURN jsonb_build_object('allowed',FALSE,'reason','account_restricted');
  END IF;
  IF NOT EXISTS(SELECT 1 FROM public.cyclecare_privacy_consents c WHERE c.user_id=p_user_id
    AND CASE WHEN p_kind='image' THEN c.image_generation_enabled ELSE c.ai_context_enabled END) THEN
    RETURN jsonb_build_object('allowed',FALSE,'reason','consent_required');
  END IF;
  v_admin := EXISTS(SELECT 1 FROM public.cyclecare_admin_roles WHERE user_id=v_uuid);
  SELECT UPPER(COALESCE(tier,''))='PREMIUM' INTO v_premium FROM public.economy_profiles WHERE user_id=p_user_id;
  IF NOT v_admin AND NOT COALESCE(v_premium,FALSE) THEN RETURN jsonb_build_object('allowed',FALSE,'reason','premium_required'); END IF;
  v_limit := CASE WHEN p_kind='image' THEN CASE WHEN v_admin THEN 30 ELSE 3 END ELSE CASE WHEN v_admin THEN 300 ELSE 60 END END;
  INSERT INTO public.cyclecare_ai_usage(user_id,usage_date) VALUES(p_user_id,CURRENT_DATE) ON CONFLICT DO NOTHING;
  SELECT * INTO v_row FROM public.cyclecare_ai_usage WHERE user_id=p_user_id AND usage_date=CURRENT_DATE FOR UPDATE;
  IF (CASE WHEN p_kind='image' THEN v_row.image_count ELSE v_row.chat_count END) >= v_limit THEN RETURN jsonb_build_object('allowed',FALSE,'reason','daily_limit'); END IF;
  IF p_kind='image' THEN UPDATE public.cyclecare_ai_usage SET image_count=image_count+1 WHERE user_id=p_user_id AND usage_date=CURRENT_DATE;
  ELSE UPDATE public.cyclecare_ai_usage SET chat_count=chat_count+1 WHERE user_id=p_user_id AND usage_date=CURRENT_DATE; END IF;
  RETURN jsonb_build_object('allowed',TRUE,'remaining',v_limit-CASE WHEN p_kind='image' THEN v_row.image_count+1 ELSE v_row.chat_count+1 END);
END; $$;
REVOKE ALL ON FUNCTION public.consume_cyclecare_ai_quota(TEXT,TEXT) FROM PUBLIC, anon, authenticated;
GRANT EXECUTE ON FUNCTION public.consume_cyclecare_ai_quota(TEXT,TEXT) TO service_role;

-- Health-only deletion is defined in the following v2 migration. No broad account,
-- wallet, or asset deletion RPC is installed by this migration.

-- CycleCare backend contract v2. Forward-only; prior migrations remain unchanged.


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
      USING (user_id=auth.uid() AND public.cyclecare_cloud_health_allowed())
      WITH CHECK (user_id=auth.uid() AND public.cyclecare_cloud_health_allowed());
  END IF;
  IF to_regclass('public.daily_logs') IS NOT NULL THEN
    ALTER TABLE public.daily_logs ENABLE ROW LEVEL SECURITY;
    DROP POLICY IF EXISTS cyclecare_daily_logs_consent_gate ON public.daily_logs;
    CREATE POLICY cyclecare_daily_logs_consent_gate ON public.daily_logs AS RESTRICTIVE FOR ALL TO authenticated
      USING (user_id=auth.uid() AND public.cyclecare_cloud_health_allowed())
      WITH CHECK (user_id=auth.uid() AND schema_version=2 AND public.cyclecare_cloud_health_allowed());
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
  RETURN jsonb_build_object('ready',v_has_v2 AND v_client IS NOT DISTINCT FROM 2,'contract_version',2,'daily_log_schema_version',2,'reason',CASE WHEN NOT v_has_v2 THEN 'legacy_daily_log_schema' WHEN v_client IS DISTINCT FROM 2 THEN 'client_version_2_required' ELSE 'ready' END);
END; $$;
REVOKE ALL ON FUNCTION public.get_cyclecare_backend_contract(INTEGER) FROM PUBLIC,anon,authenticated;
GRANT EXECUTE ON FUNCTION public.get_cyclecare_backend_contract(INTEGER) TO authenticated;

-- Health-only deletion: financial transactions and immutable payment/audit evidence are retained.
-- No broad account-delete RPC is created or removed.
CREATE OR REPLACE FUNCTION public.delete_my_cyclecare_health_data() RETURNS JSONB
LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,public AS $$
DECLARE v_user TEXT:=auth.uid()::TEXT; v_deleted BIGINT:=0;
BEGIN
 IF v_user IS NULL THEN RETURN jsonb_build_object('success',FALSE,'message','Authentication required'); END IF;
 IF current_setting('transaction_isolation') <> 'read committed' THEN
 RAISE EXCEPTION 'Cloud health mutations require READ COMMITTED isolation' USING ERRCODE='25000';
 END IF;
 PERFORM pg_advisory_xact_lock(hashtextextended('cyclecare-health:' || v_user,0));
 INSERT INTO public.cyclecare_health_deletions(user_id) VALUES(v_user)
   ON CONFLICT(user_id) DO UPDATE SET deleted_at=NOW();
 DELETE FROM public.daily_logs WHERE user_id=v_user::UUID; GET DIAGNOSTICS v_deleted=ROW_COUNT;
 DELETE FROM public.cycles WHERE user_id=v_user::UUID;
 DELETE FROM public.cyclecare_privacy_consents WHERE user_id=v_user;
 -- Keep restriction/account metadata while erasing every health-bearing profile field.
 UPDATE public.profiles SET cloud_sync_enabled=FALSE, goals='', last_period_date=NULL,
   cycle_length=28, period_duration=5, typical_symptoms='', typical_energy='', typical_sleep='',
   updated_at=NOW() WHERE user_id=v_user::UUID;
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
    v_user_id UUID := auth.uid();
    v_account_mode TEXT := CASE
      WHEN COALESCE((NULLIF(auth.jwt()->>'is_anonymous', ''))::BOOLEAN,FALSE) THEN 'ANONYMOUS'
      ELSE 'EMAIL' END;
BEGIN
    IF v_user_id IS NULL THEN
        RETURN jsonb_build_object('success', FALSE, 'message', 'Authentication required');
    END IF;
    IF current_setting('transaction_isolation') <> 'read committed' THEN
 RAISE EXCEPTION 'Cloud health mutations require READ COMMITTED isolation' USING ERRCODE='25000';
 END IF;
 PERFORM pg_advisory_xact_lock(hashtextextended('cyclecare-health:' || v_user_id::TEXT,0));
    IF NOT public.cyclecare_cloud_health_allowed() THEN
        RETURN jsonb_build_object('success',FALSE,'message','Explicit cloud health consent is required; deleted health data cannot be restored by stale clients.');
    END IF;
    INSERT INTO public.profiles (
        user_id, device_id, account_mode, goals, last_period_date, cycle_length,
        period_duration, typical_symptoms, typical_energy, typical_sleep
    ) VALUES (
        v_user_id, NULLIF(p_device_id, ''), v_account_mode,
        COALESCE(p_goals, ''), NULLIF(p_last_period_date, '')::DATE,
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
 USING (user_id=auth.uid() AND public.cyclecare_cloud_health_allowed())
 WITH CHECK (user_id=auth.uid() AND public.cyclecare_cloud_health_allowed());



-- Explicit service grants do not depend on deployment-specific default privileges.
GRANT SELECT,INSERT,UPDATE,DELETE ON public.cyclecare_health_deletions,public.cyclecare_ai_usage,public.cyclecare_backend_contract TO service_role;
ALTER TABLE public.cyclecare_ai_usage ADD CONSTRAINT cyclecare_ai_usage_nonnegative CHECK(chat_count>=0 AND image_count>=0);

-- RLS alone is statement-snapshot based. Serialize health writes with deletion to
-- prevent an in-flight old writer committing a new health row after deletion.
CREATE OR REPLACE FUNCTION public.cyclecare_guard_health_write() RETURNS TRIGGER
LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,public AS $$
DECLARE v_owner UUID := NEW.user_id;
BEGIN
 IF current_setting('transaction_isolation') <> 'read committed' THEN
 RAISE EXCEPTION 'Cloud health mutations require READ COMMITTED isolation' USING ERRCODE='25000';
 END IF;
 PERFORM pg_advisory_xact_lock(hashtextextended('cyclecare-health:' || v_owner::TEXT,0));
 IF auth.role() IS DISTINCT FROM 'service_role' THEN
   IF auth.uid() IS DISTINCT FROM v_owner OR NOT EXISTS(
     SELECT 1 FROM public.cyclecare_privacy_consents c WHERE c.user_id=v_owner::TEXT AND c.cloud_sync_enabled)
     OR EXISTS(SELECT 1 FROM public.cyclecare_health_deletions d WHERE d.user_id=v_owner::TEXT) THEN
     RAISE EXCEPTION 'Cloud health consent required; deleted data cannot be restored' USING ERRCODE='42501';
   END IF;
 END IF;
 RETURN NEW;
END $$;
REVOKE ALL ON FUNCTION public.cyclecare_guard_health_write() FROM PUBLIC,anon,authenticated;
CREATE TRIGGER cyclecare_cycles_write_guard BEFORE INSERT OR UPDATE ON public.cycles
FOR EACH ROW EXECUTE FUNCTION public.cyclecare_guard_health_write();
CREATE TRIGGER cyclecare_daily_logs_write_guard BEFORE INSERT OR UPDATE ON public.daily_logs
FOR EACH ROW EXECUTE FUNCTION public.cyclecare_guard_health_write();
COMMIT;
