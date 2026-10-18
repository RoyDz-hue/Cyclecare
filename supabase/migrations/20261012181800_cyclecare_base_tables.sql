-- Idempotent baseline for the core tables that existed in the audited project.
-- Existing installations keep their rows and column types; missing tables/columns are added.
CREATE EXTENSION IF NOT EXISTS pgcrypto;

CREATE TABLE IF NOT EXISTS public.profiles (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id TEXT NOT NULL,
    device_id UUID,
    account_mode TEXT NOT NULL DEFAULT 'CYCLE_TRACKER',
    goals TEXT NOT NULL DEFAULT '',
    last_period_date TEXT,
    cycle_length INTEGER NOT NULL DEFAULT 28,
    period_duration INTEGER NOT NULL DEFAULT 5,
    typical_symptoms TEXT NOT NULL DEFAULT '',
    typical_energy TEXT NOT NULL DEFAULT 'NORMAL',
    typical_sleep TEXT NOT NULL DEFAULT 'GOOD',
    status TEXT NOT NULL DEFAULT 'ACTIVE',
    suspension_reason TEXT,
    suspended_until TIMESTAMPTZ,
    role TEXT NOT NULL DEFAULT 'USER',
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
ALTER TABLE public.profiles ADD COLUMN IF NOT EXISTS user_id TEXT;
ALTER TABLE public.profiles ADD COLUMN IF NOT EXISTS device_id UUID;
ALTER TABLE public.profiles ADD COLUMN IF NOT EXISTS account_mode TEXT NOT NULL DEFAULT 'CYCLE_TRACKER';
ALTER TABLE public.profiles ADD COLUMN IF NOT EXISTS goals TEXT NOT NULL DEFAULT '';
ALTER TABLE public.profiles ADD COLUMN IF NOT EXISTS last_period_date TEXT;
ALTER TABLE public.profiles ADD COLUMN IF NOT EXISTS cycle_length INTEGER NOT NULL DEFAULT 28;
ALTER TABLE public.profiles ADD COLUMN IF NOT EXISTS period_duration INTEGER NOT NULL DEFAULT 5;
ALTER TABLE public.profiles ADD COLUMN IF NOT EXISTS typical_symptoms TEXT NOT NULL DEFAULT '';
ALTER TABLE public.profiles ADD COLUMN IF NOT EXISTS typical_energy TEXT NOT NULL DEFAULT 'NORMAL';
ALTER TABLE public.profiles ADD COLUMN IF NOT EXISTS typical_sleep TEXT NOT NULL DEFAULT 'GOOD';
ALTER TABLE public.profiles ADD COLUMN IF NOT EXISTS status TEXT NOT NULL DEFAULT 'ACTIVE';
ALTER TABLE public.profiles ADD COLUMN IF NOT EXISTS suspension_reason TEXT;
ALTER TABLE public.profiles ADD COLUMN IF NOT EXISTS suspended_until TIMESTAMPTZ;
ALTER TABLE public.profiles ADD COLUMN IF NOT EXISTS role TEXT NOT NULL DEFAULT 'USER';
ALTER TABLE public.profiles ADD COLUMN IF NOT EXISTS created_at TIMESTAMPTZ NOT NULL DEFAULT NOW();
ALTER TABLE public.profiles ADD COLUMN IF NOT EXISTS updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW();
CREATE UNIQUE INDEX IF NOT EXISTS idx_profiles_user_id_unique ON public.profiles(user_id);

CREATE TABLE IF NOT EXISTS public.economy_profiles (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id TEXT NOT NULL,
    coin_balance BIGINT NOT NULL DEFAULT 120,
    lifetime_earned BIGINT NOT NULL DEFAULT 120,
    lifetime_spent BIGINT NOT NULL DEFAULT 0,
    tier TEXT NOT NULL DEFAULT 'FREE',
    rank TEXT NOT NULL DEFAULT 'LUMEN',
    active_frame TEXT,
    active_artifact TEXT DEFAULT 'cycle_coin',
    active_theme TEXT DEFAULT 'SOOTHING_ROSE',
    unlocked_items JSONB NOT NULL DEFAULT '["cycle_coin"]'::JSONB,
    status TEXT NOT NULL DEFAULT 'ACTIVE',
    suspension_reason TEXT,
    suspended_until TIMESTAMPTZ,
    artifacts JSONB NOT NULL DEFAULT '[]'::JSONB,
    last_penalized_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
ALTER TABLE public.economy_profiles ADD COLUMN IF NOT EXISTS user_id TEXT;
ALTER TABLE public.economy_profiles ADD COLUMN IF NOT EXISTS coin_balance BIGINT NOT NULL DEFAULT 120;
ALTER TABLE public.economy_profiles ADD COLUMN IF NOT EXISTS lifetime_earned BIGINT NOT NULL DEFAULT 120;
ALTER TABLE public.economy_profiles ADD COLUMN IF NOT EXISTS lifetime_spent BIGINT NOT NULL DEFAULT 0;
ALTER TABLE public.economy_profiles ADD COLUMN IF NOT EXISTS tier TEXT NOT NULL DEFAULT 'FREE';
ALTER TABLE public.economy_profiles ADD COLUMN IF NOT EXISTS rank TEXT NOT NULL DEFAULT 'LUMEN';
ALTER TABLE public.economy_profiles ADD COLUMN IF NOT EXISTS active_frame TEXT;
ALTER TABLE public.economy_profiles ADD COLUMN IF NOT EXISTS active_artifact TEXT DEFAULT 'cycle_coin';
ALTER TABLE public.economy_profiles ADD COLUMN IF NOT EXISTS active_theme TEXT DEFAULT 'SOOTHING_ROSE';
ALTER TABLE public.economy_profiles ADD COLUMN IF NOT EXISTS unlocked_items JSONB NOT NULL DEFAULT '["cycle_coin"]'::JSONB;
ALTER TABLE public.economy_profiles ADD COLUMN IF NOT EXISTS status TEXT NOT NULL DEFAULT 'ACTIVE';
ALTER TABLE public.economy_profiles ADD COLUMN IF NOT EXISTS suspension_reason TEXT;
ALTER TABLE public.economy_profiles ADD COLUMN IF NOT EXISTS suspended_until TIMESTAMPTZ;
ALTER TABLE public.economy_profiles ADD COLUMN IF NOT EXISTS artifacts JSONB NOT NULL DEFAULT '[]'::JSONB;
ALTER TABLE public.economy_profiles ADD COLUMN IF NOT EXISTS last_penalized_at TIMESTAMPTZ;
ALTER TABLE public.economy_profiles ADD COLUMN IF NOT EXISTS created_at TIMESTAMPTZ NOT NULL DEFAULT NOW();
ALTER TABLE public.economy_profiles ADD COLUMN IF NOT EXISTS updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW();
CREATE UNIQUE INDEX IF NOT EXISTS idx_economy_profiles_user_id_unique ON public.economy_profiles(user_id);

CREATE TABLE IF NOT EXISTS public.cycles (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id TEXT NOT NULL,
    start_date DATE NOT NULL,
    end_date DATE,
    cycle_length INTEGER NOT NULL DEFAULT 28,
    period_length INTEGER NOT NULL DEFAULT 5,
    notes TEXT NOT NULL DEFAULT '',
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
ALTER TABLE public.cycles ADD COLUMN IF NOT EXISTS user_id TEXT;
ALTER TABLE public.cycles ADD COLUMN IF NOT EXISTS start_date DATE;
ALTER TABLE public.cycles ADD COLUMN IF NOT EXISTS end_date DATE;
ALTER TABLE public.cycles ADD COLUMN IF NOT EXISTS cycle_length INTEGER NOT NULL DEFAULT 28;
ALTER TABLE public.cycles ADD COLUMN IF NOT EXISTS period_length INTEGER NOT NULL DEFAULT 5;
ALTER TABLE public.cycles ADD COLUMN IF NOT EXISTS notes TEXT NOT NULL DEFAULT '';
ALTER TABLE public.cycles ADD COLUMN IF NOT EXISTS created_at TIMESTAMPTZ NOT NULL DEFAULT NOW();
ALTER TABLE public.cycles ADD COLUMN IF NOT EXISTS updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW();
CREATE INDEX IF NOT EXISTS idx_cycles_user_start ON public.cycles(user_id, start_date DESC);

CREATE TABLE IF NOT EXISTS public.daily_logs (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id TEXT NOT NULL,
    date DATE NOT NULL,
    flow TEXT NOT NULL DEFAULT 'NONE',
    pain TEXT NOT NULL DEFAULT 'NONE',
    mood TEXT NOT NULL DEFAULT 'GOOD',
    symptoms TEXT NOT NULL DEFAULT '',
    sexual_activity BOOLEAN NOT NULL DEFAULT FALSE,
    temperature DOUBLE PRECISION,
    notes TEXT NOT NULL DEFAULT '',
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
ALTER TABLE public.daily_logs ADD COLUMN IF NOT EXISTS user_id TEXT;
ALTER TABLE public.daily_logs ADD COLUMN IF NOT EXISTS date DATE;
ALTER TABLE public.daily_logs ADD COLUMN IF NOT EXISTS flow TEXT NOT NULL DEFAULT 'NONE';
ALTER TABLE public.daily_logs ADD COLUMN IF NOT EXISTS pain TEXT NOT NULL DEFAULT 'NONE';
ALTER TABLE public.daily_logs ADD COLUMN IF NOT EXISTS mood TEXT NOT NULL DEFAULT 'GOOD';
ALTER TABLE public.daily_logs ADD COLUMN IF NOT EXISTS symptoms TEXT NOT NULL DEFAULT '';
ALTER TABLE public.daily_logs ADD COLUMN IF NOT EXISTS sexual_activity BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE public.daily_logs ADD COLUMN IF NOT EXISTS temperature DOUBLE PRECISION;
ALTER TABLE public.daily_logs ADD COLUMN IF NOT EXISTS notes TEXT NOT NULL DEFAULT '';
ALTER TABLE public.daily_logs ADD COLUMN IF NOT EXISTS created_at TIMESTAMPTZ NOT NULL DEFAULT NOW();
ALTER TABLE public.daily_logs ADD COLUMN IF NOT EXISTS updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW();
CREATE INDEX IF NOT EXISTS idx_daily_logs_user_date ON public.daily_logs(user_id, date DESC);

CREATE TABLE IF NOT EXISTS public.user_notifications (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    message_id TEXT,
    user_id TEXT NOT NULL,
    target_identifier TEXT,
    title TEXT NOT NULL,
    body TEXT NOT NULL,
    type TEXT NOT NULL DEFAULT 'SYSTEM',
    priority TEXT NOT NULL DEFAULT 'NORMAL',
    sender TEXT NOT NULL DEFAULT 'CYCLECARE_OFFICIAL',
    action_url TEXT,
    is_read BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
ALTER TABLE public.user_notifications ADD COLUMN IF NOT EXISTS message_id TEXT;
ALTER TABLE public.user_notifications ADD COLUMN IF NOT EXISTS user_id TEXT;
ALTER TABLE public.user_notifications ADD COLUMN IF NOT EXISTS target_identifier TEXT;
ALTER TABLE public.user_notifications ADD COLUMN IF NOT EXISTS title TEXT;
ALTER TABLE public.user_notifications ADD COLUMN IF NOT EXISTS body TEXT;
ALTER TABLE public.user_notifications ADD COLUMN IF NOT EXISTS type TEXT NOT NULL DEFAULT 'SYSTEM';
ALTER TABLE public.user_notifications ADD COLUMN IF NOT EXISTS priority TEXT NOT NULL DEFAULT 'NORMAL';
ALTER TABLE public.user_notifications ADD COLUMN IF NOT EXISTS sender TEXT NOT NULL DEFAULT 'CYCLECARE_OFFICIAL';
ALTER TABLE public.user_notifications ADD COLUMN IF NOT EXISTS action_url TEXT;
ALTER TABLE public.user_notifications ADD COLUMN IF NOT EXISTS is_read BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE public.user_notifications ADD COLUMN IF NOT EXISTS created_at TIMESTAMPTZ NOT NULL DEFAULT NOW();
CREATE INDEX IF NOT EXISTS idx_user_notifications_user_created ON public.user_notifications(user_id, created_at DESC);

CREATE TABLE IF NOT EXISTS public.app_config (
    key TEXT PRIMARY KEY,
    value JSONB NOT NULL DEFAULT '{}'::JSONB,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
ALTER TABLE public.app_config ADD COLUMN IF NOT EXISTS value JSONB NOT NULL DEFAULT '{}'::JSONB;
ALTER TABLE public.app_config ADD COLUMN IF NOT EXISTS updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW();
