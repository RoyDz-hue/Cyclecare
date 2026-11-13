-- Admin Command conversation thread: persists the chat-style Admin Command
-- messages per administrator and exposes them over realtime.
CREATE TABLE IF NOT EXISTS public.admin_command_messages (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    admin_user_id UUID NOT NULL,
    role TEXT NOT NULL,
    kind TEXT NOT NULL DEFAULT 'chat',
    content TEXT NOT NULL,
    run_id UUID,
    meta JSONB NOT NULL DEFAULT '{}'::JSONB,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS admin_command_messages_admin_ts_idx
    ON public.admin_command_messages(admin_user_id, created_at DESC);

ALTER TABLE public.admin_command_messages ENABLE ROW LEVEL SECURITY;

-- Each administrator reads and writes only their own thread, and only while
-- they hold the CycleCare admin role. The edge function additionally scopes
-- every service-role read/write to the verified caller's id.
DROP POLICY IF EXISTS admin_command_messages_select_own ON public.admin_command_messages;
CREATE POLICY admin_command_messages_select_own ON public.admin_command_messages
    FOR SELECT TO authenticated
    USING (admin_user_id = auth.uid() AND public.is_cyclecare_admin());

DROP POLICY IF EXISTS admin_command_messages_insert_own ON public.admin_command_messages;
CREATE POLICY admin_command_messages_insert_own ON public.admin_command_messages
    FOR INSERT TO authenticated
    WITH CHECK (admin_user_id = auth.uid() AND public.is_cyclecare_admin());

ALTER PUBLICATION supabase_realtime ADD TABLE public.admin_command_messages;
