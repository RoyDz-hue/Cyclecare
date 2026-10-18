-- DEPRECATED AND DISABLED.
-- This legacy master script contains pre-hardening policies and privileged functions.
-- Do not apply it. Use the ordered, reviewed files under supabase/migrations/ with
-- `supabase db push` after linking the intended project. Those migrations include
-- the current baseline, core tables, RLS policies, and server-authoritative RPCs.
DO $$
BEGIN
    RAISE EXCEPTION 'Deprecated CycleCare master SQL is disabled. Apply the ordered supabase/migrations with Supabase CLI.';
END $$;
