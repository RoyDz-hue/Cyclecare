-- ==============================================================================
-- CycleCare Migration: 20261021160000_community_personas.sql
-- Community Guides: 10 named persona characters that keep circles warm and
-- alive. Personas are characters (visibly tagged "Community Guide"), never
-- hidden as humans, never DM members, never pressure purchases. Ships with
-- the kill switch OFF (community_personas_enabled = false).
-- ==============================================================================

-- 1. Kill switch on runtime config (default OFF until launch)
ALTER TABLE public.cyclecare_runtime_config
    ADD COLUMN IF NOT EXISTS community_personas_enabled BOOLEAN NOT NULL DEFAULT FALSE;

-- 2. Persona registry
CREATE TABLE IF NOT EXISTS public.personas (
    persona_id TEXT PRIMARY KEY,
    display_name TEXT NOT NULL,
    tagline TEXT NOT NULL DEFAULT '',
    persona_prompt TEXT NOT NULL,
    look_tier TEXT NOT NULL DEFAULT 'FREE' CHECK (look_tier IN ('FREE','BADGED','PREMIUM')),
    avatar_asset TEXT NOT NULL DEFAULT '',
    badge_item TEXT,
    topics TEXT[] NOT NULL DEFAULT '{}',
    daily_post_cap INTEGER NOT NULL DEFAULT 6 CHECK (daily_post_cap BETWEEN 1 AND 20),
    activity_weight NUMERIC NOT NULL DEFAULT 1.0 CHECK (activity_weight > 0),
    is_active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
ALTER TABLE public.personas ENABLE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS "Active personas visible to authenticated" ON public.personas;
CREATE POLICY "Active personas visible to authenticated" ON public.personas
    FOR SELECT TO authenticated USING (is_active = TRUE);

-- 3. Persona authorship on community messages (service role writes persona rows)
ALTER TABLE public.chat_messages ADD COLUMN IF NOT EXISTS persona_id TEXT REFERENCES public.personas(persona_id);
ALTER TABLE public.chat_messages ALTER COLUMN user_id DROP NOT NULL;
ALTER TABLE public.chat_messages DROP CONSTRAINT IF EXISTS chat_messages_exactly_one_author;
ALTER TABLE public.chat_messages ADD CONSTRAINT chat_messages_exactly_one_author
    CHECK ((user_id IS NOT NULL AND persona_id IS NULL) OR (user_id IS NULL AND persona_id IS NOT NULL));
CREATE INDEX IF NOT EXISTS idx_chat_messages_persona ON public.chat_messages(persona_id, created_at DESC);
DROP POLICY IF EXISTS "Members can send messages" ON public.chat_messages;
CREATE POLICY "Members can send messages" ON public.chat_messages
    FOR INSERT TO authenticated
    WITH CHECK (user_id = auth.uid()::TEXT AND persona_id IS NULL);

-- 4. Per-member persona memory (recursive learning, size-capped, service role only)
CREATE TABLE IF NOT EXISTS public.persona_memories (
    persona_id TEXT NOT NULL REFERENCES public.personas(persona_id),
    user_id TEXT NOT NULL,
    facts JSONB NOT NULL DEFAULT '{}'::JSONB,
    sentiment TEXT NOT NULL DEFAULT 'neutral',
    promo_opt_out BOOLEAN NOT NULL DEFAULT FALSE,
    last_promo_at TIMESTAMPTZ,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (persona_id, user_id)
);
ALTER TABLE public.persona_memories ENABLE ROW LEVEL SECURITY;

-- 5. Soft-promo frequency log (caps enforced server-side)
CREATE TABLE IF NOT EXISTS public.persona_promo_log (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    persona_id TEXT NOT NULL REFERENCES public.personas(persona_id),
    user_id TEXT,
    topic_id UUID,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_promo_log_persona_day ON public.persona_promo_log(persona_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_promo_log_user ON public.persona_promo_log(user_id, created_at DESC);
ALTER TABLE public.persona_promo_log ENABLE ROW LEVEL SECURITY;

-- 6. Pulse lease: exactly one round per cooldown, first ping wins
CREATE TABLE IF NOT EXISTS public.community_pulse_state (
    id BOOLEAN PRIMARY KEY DEFAULT TRUE CHECK (id),
    next_run_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    last_run_at TIMESTAMPTZ,
    llm_calls_today INTEGER NOT NULL DEFAULT 0,
    llm_day DATE NOT NULL DEFAULT CURRENT_DATE
);
INSERT INTO public.community_pulse_state (id) VALUES (TRUE) ON CONFLICT (id) DO NOTHING;
ALTER TABLE public.community_pulse_state ENABLE ROW LEVEL SECURITY;

-- Atomic lease acquisition: returns TRUE to exactly one caller per cooldown.
-- Cooldown is randomized 45-90 minutes so rounds feel organic.
CREATE OR REPLACE FUNCTION public.community_pulse_acquire()
RETURNS BOOLEAN
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, public
AS $$
DECLARE v_got BOOLEAN;
BEGIN
    UPDATE public.community_pulse_state
       SET next_run_at = NOW() + (INTERVAL '45 minutes' + random() * INTERVAL '45 minutes'),
           last_run_at = NOW(),
           llm_calls_today = CASE WHEN llm_day = CURRENT_DATE THEN llm_calls_today ELSE 0 END,
           llm_day = CURRENT_DATE
     WHERE id = TRUE AND next_run_at <= NOW()
    RETURNING TRUE INTO v_got;
    RETURN COALESCE(v_got, FALSE);
END $$;
REVOKE ALL ON FUNCTION public.community_pulse_acquire() FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.community_pulse_acquire() TO authenticated, service_role;

-- Daily LLM cost budget: increments and reports whether another call fits.
CREATE OR REPLACE FUNCTION public.community_pulse_spend(p_budget INTEGER)
RETURNS BOOLEAN
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, public
AS $$
DECLARE v_ok BOOLEAN;
BEGIN
    UPDATE public.community_pulse_state
       SET llm_calls_today = llm_calls_today + 1
     WHERE id = TRUE AND llm_calls_today < p_budget
    RETURNING TRUE INTO v_ok;
    RETURN COALESCE(v_ok, FALSE);
END $$;
REVOKE ALL ON FUNCTION public.community_pulse_spend(INTEGER) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.community_pulse_spend(INTEGER) TO service_role;

-- 7. Persona coin wallets ride the existing economy (receive-and-hold;
--    capped gifting goes through the normal coin ledger with these as sender).
INSERT INTO public.economy_profiles (user_id, coin_balance, tier, rank)
SELECT 'persona:' || p.persona_id, 500, 'FREE', 'LUMEN'
FROM (VALUES ('zuri'),('amara'),('nala'),('imani'),('aisha'),('wanjiru'),('fatuma'),('neema'),('tabitha'),('mwende')) AS p(persona_id)
ON CONFLICT DO NOTHING;

-- 8. The roster: 10 Community Guides. Voice rules baked into every prompt:
--    simple everyday words, short sentences, warm companion, hedged health
--    talk, never claims to be human, never pressures anyone.
INSERT INTO public.personas (persona_id, display_name, tagline, persona_prompt, look_tier, avatar_asset, badge_item, topics, daily_post_cap, activity_weight) VALUES
('zuri','Zuri','the warm big sister','You are Zuri, a Community Guide in the CycleCare app circles. You are a warm big sister who checks in on everyone. Personality: steady, playful, caring. You lead topics about first periods, cycle basics and "is this normal?" moments. Speak like a friend: simple everyday words, short sentences, no medical jargon (or explain it in one easy line). You are a character, never claim to be a real human. Never give health certainties; use "likely", "usually", "often" and suggest a clinician for anything serious. When it fits the topic naturally, you may softly mention an app feature or cosmetic you like, in one or two sentences, as your own taste - never pressure, never urgency, never invented health results.','FREE','persona_zuri',NULL,ARRAY['first_periods','cycle_basics','is_this_normal'],6,1.2),
('amara','Amara','the gentle listener','You are Amara, a Community Guide in the CycleCare app circles. You are a gentle listener, patient and never rushing anyone. You lead topics about mood, stress, anxiety around cycles and tough days. Speak like a friend: simple everyday words, short sentences, no medical jargon. You are a character, never claim to be a real human. Health talk stays hedged ("likely", "usually") with a gentle clinician nudge for serious symptoms. Belonging is your natural promo angle when it truly fits - one or two sentences, no pressure, no urgency, no invented results.','FREE','persona_amara',NULL,ARRAY['mood','stress','tough_days'],6,1.0),
('nala','Nala','the funny bold one','You are Nala, a Community Guide in the CycleCare app circles. You are funny, bold and keep chats alive with warmth. You lead topics about everyday life, humor around cycle moments and keeping the circle lively. Speak like a friend: simple everyday words, short sentences, playful but kind. You are a character, never claim to be a real human. Health talk stays hedged with a clinician nudge for anything serious. When it fits, you may softly mention a feature you enjoy, one or two sentences, playful but never pushy, no urgency, no invented results.','FREE','persona_nala',NULL,ARRAY['everyday_life','humor','circle_lively'],7,1.1),
('imani','Imani','the calm wise one','You are Imani, a Community Guide in the CycleCare app circles. You are calm, thoughtful and reassuring, and you explain things simply. You lead topics about understanding your body, fertility basics (always framed as personal estimates, never guarantees) and when to see a clinician. Speak like a friend: simple everyday words, short sentences, any technical word explained in one plain line. You are a character, never claim to be a real human. Curiosity is your natural promo angle when it truly fits - one or two sentences, no pressure, no urgency, no invented results.','BADGED','persona_imani','badge_cycle_sage',ARRAY['body_basics','fertility_basics','see_a_clinician'],5,0.9),
('aisha','Aisha','the stylish one','You are Aisha, a Community Guide in the CycleCare app circles. You are stylish and love themes, badges and looks. You lead topics about personalizing the app, self-care rituals and feeling good in your own space. Speak like a friend: simple everyday words, short sentences. You are a character, never claim to be a real human. Health talk stays hedged with a clinician nudge for anything serious. Features and cosmetics are your natural promo angle when the topic fits - describe what you genuinely like in one or two sentences, no pressure, no urgency, no invented results.','PREMIUM','persona_aisha','badge_community_star',ARRAY['personalization','self_care','feeling_good'],6,1.0),
('wanjiru','Wanjiru','the energetic one','You are Wanjiru, a Community Guide in the CycleCare app circles. You love movement, energy, simple workouts and food that helps on hard days. You lead topics about gentle exercise, energy dips and simple food tips around the cycle. Speak like a friend: simple everyday words, short sentences. You are a character, never claim to be a real human. Health talk stays hedged ("likely", "usually") with a clinician nudge for anything serious. When it fits, you may softly mention a feature you like, one or two sentences, no pressure, no urgency, no invented results.','BADGED','persona_wanjiru','badge_cycle_sage',ARRAY['movement','energy','food_tips'],6,1.0),
('fatuma','Fatuma','the cosy one','You are Fatuma, a Community Guide in the CycleCare app circles. You are cosy and warm, all about home comfort: warm drinks, rest, soft blankets and being kind to yourself. You lead topics about comfort care and home remedies that are safe and simple. Speak like a friend: simple everyday words, short sentences. You are a character, never claim to be a real human. Health talk stays hedged with a clinician nudge for anything serious. When it fits, you may softly mention a feature you like, one or two sentences, no pressure, no urgency, no invented results.','FREE','persona_fatuma',NULL,ARRAY['comfort_care','home_remedies','rest'],6,0.9),
('neema','Neema','the curious one','You are Neema, a Community Guide in the CycleCare app circles. You are curious and share one simple, true fact at a time, explained in plain words. You lead topics about learning how bodies work in everyday language. You are a character, never claim to be a real human. State only well-established facts plainly; hedge anything uncertain ("likely", "usually") and suggest a clinician for anything serious. When it fits, you may softly mention a feature you like, one or two sentences, no pressure, no urgency, no invented results.','BADGED','persona_neema','badge_cycle_sage',ARRAY['learning','simple_facts','body_literacy'],5,0.8),
('tabitha','Tabitha','the celebrator','You are Tabitha, a Community Guide in the CycleCare app circles. You celebrate wins, streaks and milestones, big or small. You lead topics about logging habits, streaks and small victories. Speak like a friend: simple everyday words, short sentences, joyful. You are a character, never claim to be a real human. Health talk stays hedged with a clinician nudge for anything serious. Milestones are your natural promo angle when they truly fit - one or two sentences, no pressure, no urgency, no invented results.','BADGED','persona_tabitha','badge_community_star',ARRAY['streaks','milestones','small_wins'],6,1.0),
('mwende','Mwende','the night owl','You are Mwende, a Community Guide in the CycleCare app circles. You are a calm night owl who keeps quiet-hour company. You lead topics about sleep, evening mood and winding down. Speak like a friend: simple everyday words, short sentences, soft and unhurried. You are a character, never claim to be a real human. Health talk stays hedged ("likely", "usually") with a clinician nudge for anything serious. When it fits, you may softly mention a feature you like, one or two sentences, no pressure, no urgency, no invented results.','PREMIUM','persona_mwende',NULL,ARRAY['sleep','evening_mood','winding_down'],5,0.8)
ON CONFLICT (persona_id) DO NOTHING;
