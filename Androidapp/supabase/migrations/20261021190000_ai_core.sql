-- CycleCare AI Core: shared registry, audit, tool policy and Admin Command.
-- One framework spans the Android Expert, Community Guides and Admin Command so
-- every agent shares voice rules, consent gates, tool limits and an audit trail.

-- 1) Versioned prompt/persona registry. Rollback = reactivate an older version.
CREATE TABLE IF NOT EXISTS public.ai_prompt_registry (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    agent TEXT NOT NULL,
    version TEXT NOT NULL,
    system_prompt TEXT NOT NULL,
    voice_rules JSONB NOT NULL DEFAULT '{}'::JSONB,
    is_active BOOLEAN NOT NULL DEFAULT FALSE,
    notes TEXT,
    created_by TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE (agent, version)
);
ALTER TABLE public.ai_prompt_registry ENABLE ROW LEVEL SECURITY;
REVOKE ALL ON public.ai_prompt_registry FROM PUBLIC, anon, authenticated;

-- 2) Every AI run, every agent, one audit table.
CREATE TABLE IF NOT EXISTS public.ai_runs (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    agent TEXT NOT NULL,
    user_id TEXT,
    provider TEXT,
    prompt_version TEXT,
    status TEXT NOT NULL,
    latency_ms INTEGER,
    error_kind TEXT,
    meta JSONB NOT NULL DEFAULT '{}'::JSONB,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS ai_runs_created_idx ON public.ai_runs (created_at DESC);
CREATE INDEX IF NOT EXISTS ai_runs_agent_idx ON public.ai_runs (agent, created_at DESC);
ALTER TABLE public.ai_runs ENABLE ROW LEVEL SECURITY;
REVOKE ALL ON public.ai_runs FROM PUBLIC, anon, authenticated;

-- 3) Typed tool registry: an agent can only do what is registered for it.
CREATE TABLE IF NOT EXISTS public.ai_tool_registry (
    tool_name TEXT PRIMARY KEY,
    agent TEXT NOT NULL,
    kind TEXT NOT NULL CHECK (kind IN ('READ', 'WRITE', 'RISKY')),
    description TEXT NOT NULL DEFAULT '',
    limits JSONB NOT NULL DEFAULT '{}'::JSONB,
    is_active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
ALTER TABLE public.ai_tool_registry ENABLE ROW LEVEL SECURITY;
REVOKE ALL ON public.ai_tool_registry FROM PUBLIC, anon, authenticated;

INSERT INTO public.ai_tool_registry (tool_name, agent, kind, description, limits) VALUES
    ('get_user_insights', 'admin_command', 'READ', 'Ranked member insights: richest, newest, most active, inactive, tier split', '{}'),
    ('get_flagged_messages', 'admin_command', 'READ', 'Open moderation queue', '{}'),
    ('get_safety_signals', 'admin_command', 'READ', 'App-detected safety events', '{}'),
    ('get_pricing', 'admin_command', 'READ', 'Current coin packages, multipliers, cosmetics', '{}'),
    ('get_audit_log', 'admin_command', 'READ', 'Recent admin audit log', '{}'),
    ('set_coin_packages', 'admin_command', 'RISKY', 'Replace the coin package price list', '{"max_packages": 20, "min_price_kes": 1, "max_price_kes": 100000, "min_price_usd": 0.5, "max_price_usd": 500}'),
    ('set_cosmetic_price', 'admin_command', 'WRITE', 'Change one cosmetic price or availability', '{"max_price_coins": 1000000}'),
    ('adjust_coins', 'admin_command', 'RISKY', 'Grant or deduct coins for a member', '{"max_abs_delta": 10000, "max_targets_per_run": 5}'),
    ('set_user_status', 'admin_command', 'RISKY', 'Ban, suspend or activate a member', '{"max_targets_per_run": 5, "max_duration_hours": 8760}'),
    ('set_user_tier', 'admin_command', 'RISKY', 'Change a member tier', '{"max_targets_per_run": 5}'),
    ('gift_artifact', 'admin_command', 'WRITE', 'Gift a catalog artifact', '{"max_targets_per_run": 5}'),
    ('send_notice', 'admin_command', 'WRITE', 'Send an official CycleCare team notice to members', '{"max_targets_per_run": 25, "max_body_chars": 1000}'),
    ('set_guides_enabled', 'admin_command', 'RISKY', 'Turn Community Guides on or off', '{}'),
    ('update_guide', 'admin_command', 'WRITE', 'Guide post caps and activation', '{"max_daily_post_cap": 50}'),
    ('moderate_message', 'admin_command', 'RISKY', 'Dismiss, remove or restore a flagged message', '{"max_targets_per_run": 10}')
ON CONFLICT (tool_name) DO NOTHING;

-- 4) Admin Command runs: prompt -> proposed plan -> approved -> executed.
CREATE TABLE IF NOT EXISTS public.admin_command_runs (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    admin_user_id TEXT NOT NULL,
    prompt TEXT NOT NULL,
    plan JSONB NOT NULL,
    status TEXT NOT NULL DEFAULT 'proposed' CHECK (status IN ('proposed', 'approved', 'executing', 'done', 'failed', 'rejected')),
    step_results JSONB,
    error TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    approved_at TIMESTAMPTZ,
    completed_at TIMESTAMPTZ
);
CREATE INDEX IF NOT EXISTS admin_command_runs_admin_idx ON public.admin_command_runs (admin_user_id, created_at DESC);
ALTER TABLE public.admin_command_runs ENABLE ROW LEVEL SECURITY;
REVOKE ALL ON public.admin_command_runs FROM PUBLIC, anon, authenticated;

-- 5) Member insights for Admin Command analytics questions.
CREATE OR REPLACE FUNCTION public.admin_member_insights()
RETURNS JSONB
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, public
AS $$
DECLARE
    v_result JSONB;
BEGIN
    IF NOT public.is_cyclecare_admin() THEN RAISE EXCEPTION 'Administrator access required'; END IF;
    SELECT jsonb_build_object(
        'richest', (SELECT COALESCE(jsonb_agg(to_jsonb(q)), '[]'::JSONB) FROM (
            SELECT user_id, coin_balance, lifetime_earned, tier FROM public.economy_profiles
            ORDER BY coin_balance DESC LIMIT 10) q),
        'newest', (SELECT COALESCE(jsonb_agg(to_jsonb(q)), '[]'::JSONB) FROM (
            SELECT user_id::TEXT AS user_id, account_mode, status, created_at FROM public.profiles
            ORDER BY created_at DESC LIMIT 10) q),
        'most_active', (SELECT COALESCE(jsonb_agg(to_jsonb(q)), '[]'::JSONB) FROM (
            SELECT user_id, COUNT(*) AS logs_30d, MAX(date) AS last_log_date FROM public.daily_logs
            WHERE created_at > NOW() - INTERVAL '30 days'
            GROUP BY user_id ORDER BY COUNT(*) DESC LIMIT 10) q),
        'inactive', (SELECT COALESCE(jsonb_agg(to_jsonb(q)), '[]'::JSONB) FROM (
            SELECT p.user_id::TEXT AS user_id, p.created_at AS joined_at, MAX(d.date) AS last_log_date
            FROM public.profiles p LEFT JOIN public.daily_logs d ON d.user_id = p.user_id::TEXT
            GROUP BY p.user_id, p.created_at
            HAVING MAX(d.date) IS NULL OR MAX(d.date) < CURRENT_DATE - INTERVAL '14 days'
            ORDER BY p.created_at DESC LIMIT 10) q),
        'tier_split', (SELECT COALESCE(jsonb_object_agg(tier, n), '{}'::JSONB) FROM (
            SELECT COALESCE(tier, 'FREE') AS tier, COUNT(*) AS n FROM public.economy_profiles GROUP BY 1) q),
        'restricted', (SELECT COUNT(*) FROM public.profiles WHERE UPPER(COALESCE(status, 'ACTIVE')) IN ('SUSPENDED', 'BANNED'))
    ) INTO v_result;
    RETURN v_result;
END;
$$;
REVOKE ALL ON FUNCTION public.admin_member_insights() FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.admin_member_insights() TO authenticated;

-- 6) Validate + store a proposed Admin Command plan (writes nothing else).
CREATE OR REPLACE FUNCTION public.admin_command_propose(p_prompt TEXT, p_plan JSONB)
RETURNS JSONB
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, public
AS $$
DECLARE
    v_step JSONB;
    v_tool RECORD;
    v_risky_count INTEGER := 0;
    v_run_id UUID;
    v_prompt TEXT := LEFT(TRIM(COALESCE(p_prompt, '')), 2000);
    v_steps_count INTEGER;
    v_total_coins NUMERIC := 0;
BEGIN
    IF NOT public.is_cyclecare_admin() THEN RAISE EXCEPTION 'Administrator access required'; END IF;
    IF v_prompt = '' THEN RAISE EXCEPTION 'A prompt is required'; END IF;
    IF p_plan IS NULL OR jsonb_typeof(p_plan) <> 'object' OR jsonb_typeof(p_plan->'steps') <> 'array' THEN
        RAISE EXCEPTION 'Plan must be an object with a steps array'; END IF;
    v_steps_count := jsonb_array_length(p_plan->'steps');
    IF v_steps_count < 1 OR v_steps_count > 15 THEN RAISE EXCEPTION 'A plan needs 1 to 15 steps'; END IF;

    FOR v_step IN SELECT * FROM jsonb_array_elements(p_plan->'steps') LOOP
        IF jsonb_typeof(v_step) <> 'object' OR COALESCE(v_step->>'tool', '') = '' THEN
            RAISE EXCEPTION 'Every step needs a tool name'; END IF;
        SELECT * INTO v_tool FROM public.ai_tool_registry
         WHERE tool_name = v_step->>'tool' AND agent = 'admin_command' AND is_active = TRUE;
        IF NOT FOUND THEN RAISE EXCEPTION 'Tool % is not available to Admin Command', v_step->>'tool'; END IF;
        IF v_tool.kind = 'RISKY' THEN v_risky_count := v_risky_count + 1; END IF;
        IF v_step->>'tool' = 'adjust_coins' THEN
            v_total_coins := v_total_coins + ABS(COALESCE((v_step->'args'->>'delta')::NUMERIC, 0));
            IF ABS(COALESCE((v_step->'args'->>'delta')::NUMERIC, 0)) > (v_tool.limits->>'max_abs_delta')::NUMERIC THEN
                RAISE EXCEPTION 'Coin change exceeds the per-step limit of %', v_tool.limits->>'max_abs_delta'; END IF;
        END IF;
        IF jsonb_typeof(COALESCE(v_step->'args'->'targets', 'null'::JSONB)) = 'array'
           AND jsonb_array_length(v_step->'args'->'targets') > COALESCE((v_tool.limits->>'max_targets_per_run')::INT, 5) THEN
            RAISE EXCEPTION 'Too many targets for %', v_step->>'tool'; END IF;
    END LOOP;
    IF v_risky_count > 5 THEN RAISE EXCEPTION 'Too many sensitive actions in one prompt (max 5)'; END IF;
    IF v_total_coins > 50000 THEN RAISE EXCEPTION 'Total coins moved in one prompt cannot exceed 50,000'; END IF;

    INSERT INTO public.admin_command_runs(admin_user_id, prompt, plan, status)
    VALUES (auth.uid()::TEXT, v_prompt, p_plan,
            CASE WHEN v_risky_count = 0 AND NOT EXISTS (
                SELECT 1 FROM jsonb_array_elements(p_plan->'steps') s
                JOIN public.ai_tool_registry t ON t.tool_name = s->>'tool' AND t.kind = 'WRITE'
            ) THEN 'approved' ELSE 'proposed' END)
    RETURNING id INTO v_run_id;

    INSERT INTO public.admin_audit_logs(admin_user_id, admin_action, target_user_id, details, performed_by)
    VALUES (auth.uid(), 'ADMIN_COMMAND_PROPOSED', 'admin_command', jsonb_build_object('run_id', v_run_id, 'prompt', v_prompt, 'steps', v_steps_count), auth.uid()::TEXT);
    RETURN jsonb_build_object('run_id', v_run_id, 'requires_approval', (SELECT status = 'proposed' FROM public.admin_command_runs WHERE id = v_run_id));
END;
$$;
REVOKE ALL ON FUNCTION public.admin_command_propose(TEXT, JSONB) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.admin_command_propose(TEXT, JSONB) TO authenticated;

-- 7) Approve or reject a proposed plan (owner only).
CREATE OR REPLACE FUNCTION public.admin_command_decide(p_run_id UUID, p_approve BOOLEAN)
RETURNS JSONB
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, public
AS $$
BEGIN
    IF NOT public.is_cyclecare_admin() THEN RAISE EXCEPTION 'Administrator access required'; END IF;
    UPDATE public.admin_command_runs
       SET status = CASE WHEN COALESCE(p_approve, FALSE) THEN 'approved' ELSE 'rejected' END,
           approved_at = CASE WHEN COALESCE(p_approve, FALSE) THEN NOW() ELSE NULL END
     WHERE id = p_run_id AND admin_user_id = auth.uid()::TEXT AND status = 'proposed';
    IF NOT FOUND THEN RAISE EXCEPTION 'Plan not found or already decided'; END IF;
    INSERT INTO public.admin_audit_logs(admin_user_id, admin_action, target_user_id, details, performed_by)
    VALUES (auth.uid(), CASE WHEN COALESCE(p_approve, FALSE) THEN 'ADMIN_COMMAND_APPROVED' ELSE 'ADMIN_COMMAND_REJECTED' END,
            'admin_command', jsonb_build_object('run_id', p_run_id), auth.uid()::TEXT);
    RETURN jsonb_build_object('success', TRUE, 'run_id', p_run_id, 'approved', COALESCE(p_approve, FALSE));
END;
$$;
REVOKE ALL ON FUNCTION public.admin_command_decide(UUID, BOOLEAN) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.admin_command_decide(UUID, BOOLEAN) TO authenticated;

-- 8) Read back runs for the PWA (own runs only).
CREATE OR REPLACE FUNCTION public.admin_command_list(p_limit INTEGER DEFAULT 20)
RETURNS JSONB
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, public
AS $$
BEGIN
    IF NOT public.is_cyclecare_admin() THEN RAISE EXCEPTION 'Administrator access required'; END IF;
    RETURN (SELECT COALESCE(jsonb_agg(to_jsonb(q)), '[]'::JSONB) FROM (
        SELECT id, prompt, plan, status, step_results, error, created_at, approved_at, completed_at
        FROM public.admin_command_runs WHERE admin_user_id = auth.uid()::TEXT
        ORDER BY created_at DESC LIMIT LEAST(GREATEST(COALESCE(p_limit, 20), 1), 50)) q);
END;
$$;
REVOKE ALL ON FUNCTION public.admin_command_list(INTEGER) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.admin_command_list(INTEGER) TO authenticated;
