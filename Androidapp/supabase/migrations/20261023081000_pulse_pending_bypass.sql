-- ==============================================================================
-- CycleCare Migration: 20261023081000_pulse_pending_bypass.sql
-- Reply latency: when member messages wait unanswered, a pulse round may start
-- after a short 4-minute spacing instead of waiting out the long cooldown.
-- The app also pings the pulse right after a member posts; combined with the
-- 15-minute cron driver this makes replies land in seconds-to-minutes instead
-- of up to 90 minutes. Budget, kill switch, caps and care mode are unchanged.
-- ==============================================================================
CREATE OR REPLACE FUNCTION public.community_pulse_acquire()
RETURNS BOOLEAN
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, public
AS $$
DECLARE v_got BOOLEAN; v_pending BOOLEAN;
BEGIN
    SELECT EXISTS (
        SELECT 1
          FROM public.chat_messages m
         WHERE m.user_id IS NOT NULL
           AND COALESCE(m.is_flagged, FALSE) = FALSE
           AND m.created_at > NOW() - INTERVAL '3 days'
           AND NOT EXISTS (
               SELECT 1
                 FROM public.chat_messages g
                WHERE g.topic_id = m.topic_id
                  AND g.persona_id IS NOT NULL
                  AND g.created_at > m.created_at
           )
    ) INTO v_pending;

    UPDATE public.community_pulse_state
       SET next_run_at = NOW() + CASE WHEN v_pending
               THEN INTERVAL '8 minutes' + random() * INTERVAL '10 minutes'
               ELSE INTERVAL '45 minutes' + random() * INTERVAL '45 minutes' END,
           last_run_at = NOW(),
           llm_calls_today = CASE WHEN llm_day = CURRENT_DATE THEN llm_calls_today ELSE 0 END,
           llm_day = CURRENT_DATE
     WHERE id = TRUE
       AND (next_run_at <= NOW()
            OR (v_pending AND COALESCE(last_run_at, '-infinity'::timestamptz) < NOW() - INTERVAL '4 minutes'))
    RETURNING TRUE INTO v_got;
    RETURN COALESCE(v_got, FALSE);
END $$;
