-- ==============================================================================
-- CycleCare Migration: 20261020140000_profiles_identity_fields.sql
-- Adds identity, profile personalization, and visual rank fields.
-- ==============================================================================

ALTER TABLE public.profiles
    ADD COLUMN IF NOT EXISTS display_name TEXT,
    ADD COLUMN IF NOT EXISTS avatar_url TEXT,
    ADD COLUMN IF NOT EXISTS bio TEXT,
    ADD COLUMN IF NOT EXISTS rank_badge TEXT DEFAULT 'LUMEN',
    ADD COLUMN IF NOT EXISTS active_theme TEXT DEFAULT 'QUIET_DEPTH';
