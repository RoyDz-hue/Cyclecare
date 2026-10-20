-- Forward-only provider switch for the AI gateway.
-- This migration has NOT been applied to the live CycleCare project.
-- Preserve a working configured provider while retiring the old DeepInfra/Hugging Face choices.
BEGIN;

UPDATE public.cyclecare_runtime_config
   SET active_ai_provider = 'GEMINI', updated_at = NOW()
 WHERE id = TRUE
   AND active_ai_provider IN ('DEEPINFRA', 'HUGGINGFACE');

ALTER TABLE public.cyclecare_runtime_config
    DROP CONSTRAINT IF EXISTS cyclecare_runtime_config_active_ai_provider_check;
ALTER TABLE public.cyclecare_runtime_config
    ADD CONSTRAINT cyclecare_runtime_config_active_ai_provider_check
    CHECK (active_ai_provider IN ('COHERE', 'GEMINI', 'OPENROUTER'));

CREATE OR REPLACE FUNCTION public.admin_update_runtime_config(
    p_active_ai_provider TEXT,
    p_active_mpesa_provider TEXT
) RETURNS JSONB
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, public
AS $$
DECLARE
    v_ai TEXT := UPPER(TRIM(COALESCE(p_active_ai_provider, '')));
    v_mpesa TEXT := UPPER(TRIM(COALESCE(p_active_mpesa_provider, '')));
    v_config JSONB;
BEGIN
    IF NOT public.is_cyclecare_admin() THEN
        RAISE EXCEPTION 'Administrator access required';
    END IF;
    IF v_ai NOT IN ('COHERE', 'GEMINI', 'OPENROUTER') THEN
        RAISE EXCEPTION 'Choose a supported AI provider';
    END IF;
    IF v_mpesa <> 'PAYHERO' THEN
        RAISE EXCEPTION 'PayNexus cannot be activated until its callback signature and payment-verification contract is validated';
    END IF;

    UPDATE public.cyclecare_runtime_config
       SET active_ai_provider = v_ai,
           active_mpesa_provider = v_mpesa,
           updated_at = NOW(),
           updated_by = auth.uid()
     WHERE id = TRUE
     RETURNING jsonb_build_object(
         'active_ai_provider', active_ai_provider,
         'active_mpesa_provider', active_mpesa_provider,
         'updated_at', updated_at
     ) INTO v_config;

    IF v_config IS NULL THEN
        RAISE EXCEPTION 'Runtime configuration is not initialized';
    END IF;

    INSERT INTO public.admin_audit_logs(admin_user_id, admin_action, target_user_id, details, performed_by)
    VALUES (auth.uid(), 'UPDATE_RUNTIME_PROVIDER_CONFIG', 'CYCLECARE_RUNTIME', v_config, auth.uid()::TEXT);
    RETURN v_config;
END;
$$;
REVOKE ALL ON FUNCTION public.admin_update_runtime_config(TEXT, TEXT) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.admin_update_runtime_config(TEXT, TEXT) TO authenticated;

COMMIT;
