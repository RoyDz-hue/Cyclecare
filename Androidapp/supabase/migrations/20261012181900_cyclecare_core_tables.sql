-- Core remote tables that were absent in the supplied production-schema audit.
CREATE EXTENSION IF NOT EXISTS pgcrypto;

CREATE TABLE IF NOT EXISTS public.transactions (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id TEXT NOT NULL,
    external_reference TEXT UNIQUE NOT NULL,
    provider TEXT NOT NULL CHECK (provider IN ('paypal', 'paypal_acdc', 'payhero', 'okx')),
    amount NUMERIC(12, 2) NOT NULL,
    currency TEXT NOT NULL DEFAULT 'USD',
    status TEXT NOT NULL DEFAULT 'pending' CHECK (status IN ('pending', 'success', 'failed', 'cancelled', 'expired')),
    provider_reference TEXT,
    raw_payload JSONB NOT NULL DEFAULT '{}'::JSONB,
    metadata JSONB NOT NULL DEFAULT '{}'::JSONB,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
ALTER TABLE public.transactions ADD COLUMN IF NOT EXISTS provider_reference TEXT;
ALTER TABLE public.transactions ADD COLUMN IF NOT EXISTS raw_payload JSONB NOT NULL DEFAULT '{}'::JSONB;
ALTER TABLE public.transactions ADD COLUMN IF NOT EXISTS metadata JSONB NOT NULL DEFAULT '{}'::JSONB;
ALTER TABLE public.transactions ADD COLUMN IF NOT EXISTS updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW();
CREATE INDEX IF NOT EXISTS idx_transactions_user_id ON public.transactions(user_id);
CREATE INDEX IF NOT EXISTS idx_transactions_status ON public.transactions(status);
CREATE UNIQUE INDEX IF NOT EXISTS idx_transactions_external_reference ON public.transactions(external_reference);

CREATE TABLE IF NOT EXISTS public.broadcast_dispatches (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    title TEXT NOT NULL,
    body TEXT NOT NULL,
    type TEXT NOT NULL DEFAULT 'SYSTEM_ANNOUNCEMENT',
    priority TEXT NOT NULL DEFAULT 'NORMAL',
    sender TEXT NOT NULL DEFAULT 'CYCLECARE_OFFICIAL',
    action_url TEXT,
    target_user_id TEXT,
    is_active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
ALTER TABLE public.broadcast_dispatches ADD COLUMN IF NOT EXISTS body TEXT;
ALTER TABLE public.broadcast_dispatches ADD COLUMN IF NOT EXISTS type TEXT NOT NULL DEFAULT 'SYSTEM_ANNOUNCEMENT';
ALTER TABLE public.broadcast_dispatches ADD COLUMN IF NOT EXISTS priority TEXT NOT NULL DEFAULT 'NORMAL';
ALTER TABLE public.broadcast_dispatches ADD COLUMN IF NOT EXISTS sender TEXT NOT NULL DEFAULT 'CYCLECARE_OFFICIAL';
ALTER TABLE public.broadcast_dispatches ADD COLUMN IF NOT EXISTS action_url TEXT;
ALTER TABLE public.broadcast_dispatches ADD COLUMN IF NOT EXISTS target_user_id TEXT;
ALTER TABLE public.broadcast_dispatches ADD COLUMN IF NOT EXISTS is_active BOOLEAN NOT NULL DEFAULT TRUE;
ALTER TABLE public.broadcast_dispatches ADD COLUMN IF NOT EXISTS created_at TIMESTAMPTZ NOT NULL DEFAULT NOW();
CREATE INDEX IF NOT EXISTS idx_broadcast_dispatches_active_created ON public.broadcast_dispatches(is_active, created_at DESC);

CREATE TABLE IF NOT EXISTS public.admin_audit_logs (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    admin_user_id UUID,
    admin_action TEXT NOT NULL,
    target_user_id TEXT,
    details JSONB NOT NULL DEFAULT '{}'::JSONB,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
ALTER TABLE public.admin_audit_logs ADD COLUMN IF NOT EXISTS admin_user_id UUID;
ALTER TABLE public.admin_audit_logs ADD COLUMN IF NOT EXISTS admin_action TEXT;
ALTER TABLE public.admin_audit_logs ADD COLUMN IF NOT EXISTS target_user_id TEXT;
ALTER TABLE public.admin_audit_logs ADD COLUMN IF NOT EXISTS details JSONB NOT NULL DEFAULT '{}'::JSONB;
ALTER TABLE public.admin_audit_logs ADD COLUMN IF NOT EXISTS created_at TIMESTAMPTZ NOT NULL DEFAULT NOW();
CREATE INDEX IF NOT EXISTS idx_admin_audit_logs_created ON public.admin_audit_logs(created_at DESC);

CREATE TABLE IF NOT EXISTS public.in_house_messages (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    title TEXT NOT NULL,
    message TEXT NOT NULL,
    message_type TEXT NOT NULL DEFAULT 'SYSTEM_ANNOUNCEMENT',
    target_audience TEXT NOT NULL DEFAULT 'ALL',
    target_user_id TEXT,
    action_url TEXT,
    sent_by TEXT NOT NULL DEFAULT 'CycleCare Official Team',
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
ALTER TABLE public.in_house_messages ADD COLUMN IF NOT EXISTS title TEXT;
ALTER TABLE public.in_house_messages ADD COLUMN IF NOT EXISTS message TEXT;
ALTER TABLE public.in_house_messages ADD COLUMN IF NOT EXISTS message_type TEXT NOT NULL DEFAULT 'SYSTEM_ANNOUNCEMENT';
ALTER TABLE public.in_house_messages ADD COLUMN IF NOT EXISTS target_audience TEXT NOT NULL DEFAULT 'ALL';
ALTER TABLE public.in_house_messages ADD COLUMN IF NOT EXISTS target_user_id TEXT;
ALTER TABLE public.in_house_messages ADD COLUMN IF NOT EXISTS action_url TEXT;
ALTER TABLE public.in_house_messages ADD COLUMN IF NOT EXISTS sent_by TEXT NOT NULL DEFAULT 'CycleCare Official Team';
ALTER TABLE public.in_house_messages ADD COLUMN IF NOT EXISTS created_at TIMESTAMPTZ NOT NULL DEFAULT NOW();
CREATE INDEX IF NOT EXISTS idx_in_house_messages_created ON public.in_house_messages(created_at DESC);

CREATE TABLE IF NOT EXISTS public.mining_records (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id TEXT NOT NULL,
    claimed_date DATE NOT NULL,
    amount_earned BIGINT NOT NULL CHECK (amount_earned > 0),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT unique_user_calendar_day_claim UNIQUE (user_id, claimed_date)
);
CREATE INDEX IF NOT EXISTS idx_mining_records_user_date ON public.mining_records(user_id, claimed_date DESC);

CREATE TABLE IF NOT EXISTS public.user_artifacts (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id TEXT NOT NULL,
    artifact_id TEXT NOT NULL,
    source TEXT NOT NULL DEFAULT 'PURCHASE',
    gifted_by TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT unique_user_artifact UNIQUE (user_id, artifact_id)
);
ALTER TABLE public.user_artifacts ADD COLUMN IF NOT EXISTS title TEXT NOT NULL DEFAULT '';
ALTER TABLE public.user_artifacts ADD COLUMN IF NOT EXISTS description TEXT;
ALTER TABLE public.user_artifacts ADD COLUMN IF NOT EXISTS icon_res TEXT;
ALTER TABLE public.user_artifacts ADD COLUMN IF NOT EXISTS acquired_via TEXT NOT NULL DEFAULT 'PURCHASE';
CREATE INDEX IF NOT EXISTS idx_user_artifacts_user ON public.user_artifacts(user_id);
