-- CycleCare backend audit repairs (forward-only; do not edit prior migrations).
-- Compatibility: old health sync fails closed; cloud health reads/writes require
-- explicit cloud_sync_enabled consent (existing rows default FALSE, fail closed).
BEGIN;

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
CREATE POLICY cyclecare_privacy_owner_write ON public.cyclecare_privacy_consents
    FOR ALL TO authenticated USING (user_id = auth.uid()::TEXT) WITH CHECK (user_id = auth.uid()::TEXT);
GRANT SELECT, INSERT, UPDATE ON public.cyclecare_privacy_consents TO authenticated;

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
    IF COALESCE(p_cloud_sync_enabled,FALSE) AND EXISTS(SELECT 1 FROM public.cyclecare_health_deletions WHERE user_id=v_user) THEN
        RETURN jsonb_build_object('success',FALSE,'message','Health data was deleted; cloud sync cannot resume through a stale consent request.');
    END IF;
    INSERT INTO public.cyclecare_privacy_consents(user_id, ai_context_enabled, cloud_sync_enabled, image_generation_enabled)
    VALUES (v_user, COALESCE(p_ai_context_enabled,FALSE), COALESCE(p_cloud_sync_enabled,FALSE), COALESCE(p_image_generation_enabled,FALSE))
    ON CONFLICT (user_id) DO UPDATE SET ai_context_enabled=EXCLUDED.ai_context_enabled,
      cloud_sync_enabled=EXCLUDED.cloud_sync_enabled, image_generation_enabled=EXCLUDED.image_generation_enabled, updated_at=NOW();
    UPDATE public.profiles SET cloud_sync_enabled=COALESCE(p_cloud_sync_enabled,FALSE), updated_at=NOW() WHERE user_id=v_user;
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
      WITH CHECK (user_id=auth.uid()::TEXT AND EXISTS (SELECT 1 FROM public.cyclecare_privacy_consents c WHERE c.user_id=auth.uid()::TEXT AND c.cloud_sync_enabled));
    CREATE POLICY cyclecare_cycles_owner_update ON public.cycles FOR UPDATE TO authenticated
      USING (user_id=auth.uid()::TEXT AND EXISTS (SELECT 1 FROM public.cyclecare_privacy_consents c WHERE c.user_id=auth.uid()::TEXT AND c.cloud_sync_enabled))
      WITH CHECK (user_id=auth.uid()::TEXT);
  END IF;
  IF to_regclass('public.daily_logs') IS NOT NULL THEN
    DROP POLICY IF EXISTS cyclecare_daily_logs_owner_insert ON public.daily_logs;
    DROP POLICY IF EXISTS cyclecare_daily_logs_owner_update ON public.daily_logs;
    CREATE POLICY cyclecare_daily_logs_owner_insert ON public.daily_logs FOR INSERT TO authenticated
      WITH CHECK (user_id=auth.uid()::TEXT AND EXISTS (SELECT 1 FROM public.cyclecare_privacy_consents c WHERE c.user_id=auth.uid()::TEXT AND c.cloud_sync_enabled));
    CREATE POLICY cyclecare_daily_logs_owner_update ON public.daily_logs FOR UPDATE TO authenticated
      USING (user_id=auth.uid()::TEXT AND EXISTS (SELECT 1 FROM public.cyclecare_privacy_consents c WHERE c.user_id=auth.uid()::TEXT AND c.cloud_sync_enabled))
      WITH CHECK (user_id=auth.uid()::TEXT);
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
RETURNS JSONB LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, public AS $$
DECLARE v_admin BOOLEAN; v_premium BOOLEAN; v_row public.cyclecare_ai_usage%ROWTYPE; v_limit INTEGER;
BEGIN
  IF auth.role() <> 'service_role' THEN RETURN jsonb_build_object('allowed',FALSE,'reason','service_role_required'); END IF;
  IF p_user_id IS NULL OR p_kind NOT IN ('chat','image') THEN RETURN jsonb_build_object('allowed',FALSE,'reason','invalid_request'); END IF;
  v_admin := EXISTS(SELECT 1 FROM public.cyclecare_admin_roles WHERE user_id=p_user_id::UUID);
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
COMMIT;
