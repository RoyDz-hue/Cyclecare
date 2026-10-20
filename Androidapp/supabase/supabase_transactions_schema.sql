-- DEPRECATED AND DISABLED.
-- This legacy payment script installs obsolete guest/local-user transaction
-- policies. Use the canonical, versioned migrations under supabase/migrations/.
DO $$
BEGIN
    RAISE EXCEPTION 'Deprecated CycleCare payment SQL is disabled. Apply the ordered supabase/migrations with Supabase CLI.';
END $$;
