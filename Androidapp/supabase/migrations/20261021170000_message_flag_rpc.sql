-- ==============================================================================
-- CycleCare Migration: 20261021170000_message_flag_rpc.sql
-- Member-driven message flagging for Community Circles review.
-- ==============================================================================

-- Members flag a message in a circle they belong to; moderators review the
-- existing is_flagged column. Reporters stay anonymous to authors.
CREATE OR REPLACE FUNCTION public.flag_chat_message(p_message_id UUID)
RETURNS BOOLEAN
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, public
AS $$
DECLARE
    v_user_id TEXT := auth.uid()::TEXT;
    v_topic_id UUID;
BEGIN
    IF v_user_id IS NULL THEN RETURN FALSE; END IF;

    SELECT topic_id INTO v_topic_id FROM public.chat_messages WHERE id = p_message_id;
    IF NOT FOUND THEN RETURN FALSE; END IF;

    IF NOT EXISTS (
        SELECT 1 FROM public.chat_memberships m
        WHERE m.topic_id = v_topic_id AND m.user_id = v_user_id
    ) THEN RETURN FALSE; END IF;

    UPDATE public.chat_messages SET is_flagged = TRUE WHERE id = p_message_id;
    RETURN TRUE;
END;
$$;

REVOKE ALL ON FUNCTION public.flag_chat_message(UUID) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.flag_chat_message(UUID) TO authenticated;
