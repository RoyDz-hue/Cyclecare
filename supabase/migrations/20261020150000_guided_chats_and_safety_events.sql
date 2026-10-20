-- ==============================================================================
-- CycleCare Migration: 20261020150000_guided_chats_and_safety_events.sql
-- Live Guided Chats, safety event auditing, and feature flag management.
-- ==============================================================================

-- 1. Chat Topics
CREATE TABLE IF NOT EXISTS public.chat_topics (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    title TEXT NOT NULL,
    description TEXT,
    category TEXT NOT NULL DEFAULT 'GENERAL',
    coin_gate INTEGER NOT NULL DEFAULT 0,
    is_active BOOLEAN NOT NULL DEFAULT true,
    created_by TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- 2. Chat Messages
CREATE TABLE IF NOT EXISTS public.chat_messages (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    topic_id UUID NOT NULL REFERENCES public.chat_topics(id) ON DELETE CASCADE,
    user_id TEXT NOT NULL,
    sender_alias TEXT NOT NULL DEFAULT 'Member',
    content TEXT NOT NULL CHECK (char_length(content) <= 500),
    is_flagged BOOLEAN NOT NULL DEFAULT false,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- 3. Chat Memberships
CREATE TABLE IF NOT EXISTS public.chat_memberships (
    user_id TEXT NOT NULL,
    topic_id UUID NOT NULL REFERENCES public.chat_topics(id) ON DELETE CASCADE,
    joined_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (user_id, topic_id)
);

-- 4. Safety Events Log (server audit, no raw health details)
CREATE TABLE IF NOT EXISTS public.safety_events (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id TEXT NOT NULL,
    level TEXT NOT NULL,  -- 'URGENT', 'HIGH', 'MEDIUM', 'LOW'
    trigger_key TEXT NOT NULL,
    resolved_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- Enable RLS
ALTER TABLE public.chat_topics ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.chat_messages ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.chat_memberships ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.safety_events ENABLE ROW LEVEL SECURITY;

DO $$ BEGIN
    DROP POLICY IF EXISTS "Active topics visible to authenticated" ON public.chat_topics;
    DROP POLICY IF EXISTS "Members can view topic messages" ON public.chat_messages;
    DROP POLICY IF EXISTS "Members can send messages" ON public.chat_messages;
    DROP POLICY IF EXISTS "Users manage own memberships" ON public.chat_memberships;
    DROP POLICY IF EXISTS "Service role manages safety events" ON public.safety_events;
    DROP POLICY IF EXISTS "Users can insert own safety events" ON public.safety_events;
EXCEPTION WHEN OTHERS THEN NULL;
END $$;

CREATE POLICY "Active topics visible to authenticated"
    ON public.chat_topics FOR SELECT
    USING (is_active = true AND auth.uid() IS NOT NULL);

CREATE POLICY "Members can view topic messages"
    ON public.chat_messages FOR SELECT
    USING (
        auth.uid() IS NOT NULL AND
        EXISTS (
            SELECT 1 FROM public.chat_memberships m
            WHERE m.topic_id = chat_messages.topic_id
              AND m.user_id = auth.uid()::TEXT
        )
    );

CREATE POLICY "Members can send messages"
    ON public.chat_messages FOR INSERT
    WITH CHECK (
        auth.uid() IS NOT NULL AND
        user_id = auth.uid()::TEXT AND
        EXISTS (
            SELECT 1 FROM public.chat_memberships m
            WHERE m.topic_id = chat_messages.topic_id
              AND m.user_id = auth.uid()::TEXT
        )
    );

CREATE POLICY "Users manage own memberships"
    ON public.chat_memberships FOR ALL
    USING (auth.uid() IS NOT NULL AND user_id = auth.uid()::TEXT);

CREATE POLICY "Users can insert own safety events"
    ON public.safety_events FOR INSERT
    WITH CHECK (auth.uid() IS NOT NULL AND user_id = auth.uid()::TEXT);

CREATE POLICY "Service role manages safety events"
    ON public.safety_events FOR ALL
    USING (auth.role() = 'service_role');

-- Seed initial guided chat topics if none exist
INSERT INTO public.chat_topics (title, description, category, coin_gate)
SELECT 'Cycle & Hormone Syncing', 'A gentle space to discuss bodily energy rhythm and phase nutrition.', 'WELLNESS', 0
WHERE NOT EXISTS (SELECT 1 FROM public.chat_topics WHERE title = 'Cycle & Hormone Syncing');

INSERT INTO public.chat_topics (title, description, category, coin_gate)
SELECT 'Rest & Sleep in Luteal Phase', 'Supporting each other through pre-menstrual rest, hydration and sleep quality.', 'REST', 0
WHERE NOT EXISTS (SELECT 1 FROM public.chat_topics WHERE title = 'Rest & Sleep in Luteal Phase');

INSERT INTO public.chat_topics (title, description, category, coin_gate)
SELECT 'Mindful Movement & Flow', 'Sharing low-impact stretching and calming pelvic comfort techniques.', 'MOVEMENT', 0
WHERE NOT EXISTS (SELECT 1 FROM public.chat_topics WHERE title = 'Mindful Movement & Flow');

-- Ensure default app_config feature flag for v3_sync_enabled
INSERT INTO public.app_config (key, value)
VALUES ('v3_sync_enabled', 'false')
ON CONFLICT (key) DO NOTHING;
