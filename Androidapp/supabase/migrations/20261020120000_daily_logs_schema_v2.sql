-- ==============================================================================
-- CycleCare Migration: 20261020120000_daily_logs_schema_v2.sql
-- Aligns Postgres daily_logs schema to the full CycleDayLog Android entity.
-- ==============================================================================

ALTER TABLE public.daily_logs
    ADD COLUMN IF NOT EXISTS energy_level TEXT DEFAULT '',
    ADD COLUMN IF NOT EXISTS vaginal_health TEXT DEFAULT '',
    ADD COLUMN IF NOT EXISTS urinary_health TEXT DEFAULT '',
    ADD COLUMN IF NOT EXISTS cervical_mucus TEXT DEFAULT '',
    ADD COLUMN IF NOT EXISTS ovulation_test TEXT DEFAULT 'NONE',
    ADD COLUMN IF NOT EXISTS bbt_celsius DOUBLE PRECISION DEFAULT 0.0,
    ADD COLUMN IF NOT EXISTS sleep_hours DOUBLE PRECISION DEFAULT 0.0,
    ADD COLUMN IF NOT EXISTS sleep_quality TEXT DEFAULT '',
    ADD COLUMN IF NOT EXISTS water_glasses INTEGER DEFAULT 0,
    ADD COLUMN IF NOT EXISTS pain_intensity INTEGER DEFAULT 0;

-- Maintain indexes for fast queries
CREATE INDEX IF NOT EXISTS idx_daily_logs_user_date_v2 ON public.daily_logs(user_id, date DESC);
