-- Preserve and canonicalize legacy economy unlock values without losing unknown JSON.
-- This follow-up also repairs aliases in rows where the previous consistency migration
-- was already applied; it is idempotent and does not change coin balances or health data.

CREATE OR REPLACE FUNCTION public.cyclecare_normalize_unlocked_items(p_items JSONB)
RETURNS JSONB
LANGUAGE plpgsql
IMMUTABLE
SET search_path = pg_catalog, public
AS $$
DECLARE
    v_source JSONB;
    v_result JSONB := '[]'::JSONB;
    v_value JSONB;
    v_candidate JSONB;
    v_item_id TEXT;
BEGIN
    IF p_items IS NULL OR jsonb_typeof(p_items) = 'null' THEN
        v_source := '[]'::JSONB;
    ELSIF jsonb_typeof(p_items) = 'array' THEN
        v_source := p_items;
    ELSE
        v_source := jsonb_build_array(p_items);
    END IF;

    FOR v_value IN SELECT value FROM jsonb_array_elements(v_source)
    LOOP
        v_item_id := NULL;
        IF jsonb_typeof(v_value) = 'string' THEN
            v_item_id := public.resolve_cyclecare_artifact_id(v_value #>> '{}');
        ELSIF jsonb_typeof(v_value) = 'object' THEN
            v_item_id := public.resolve_cyclecare_artifact_id(
                COALESCE(v_value->>'id', v_value->>'name', '')
            );
        END IF;

        v_candidate := CASE
            WHEN v_item_id IS NOT NULL THEN to_jsonb(v_item_id)
            ELSE v_value
        END;
        IF v_candidate <> 'null'::JSONB
           AND NOT (v_result @> jsonb_build_array(v_candidate)) THEN
            v_result := v_result || jsonb_build_array(v_candidate);
        END IF;
    END LOOP;

    IF NOT (v_result ? 'cycle_coin') THEN
        v_result := '["cycle_coin"]'::JSONB || v_result;
    END IF;
    RETURN v_result;
END;
$$;

DO $$
DECLARE
    r RECORD;
    v_normalized JSONB;
BEGIN
    IF to_regclass('public.economy_profiles') IS NOT NULL THEN
        FOR r IN SELECT user_id, unlocked_items FROM public.economy_profiles
        LOOP
            v_normalized := public.cyclecare_normalize_unlocked_items(r.unlocked_items);
            IF v_normalized IS DISTINCT FROM r.unlocked_items THEN
                UPDATE public.economy_profiles
                   SET unlocked_items = v_normalized,
                       updated_at = NOW()
                 WHERE user_id = r.user_id;
            END IF;
        END LOOP;
    END IF;
END $$;

REVOKE ALL ON FUNCTION public.cyclecare_normalize_unlocked_items(JSONB)
    FROM PUBLIC, anon, authenticated;
