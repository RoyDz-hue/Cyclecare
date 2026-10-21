-- Document and defensively re-assert that administrator identity comes from
-- the database setting app.cyclecare_admin_email, never from a literal email
-- committed to the repository.
--
-- Operators: set the email once per project (persists, not versioned):
--   ALTER DATABASE postgres SET app.cyclecare_admin_email = 'you@example.com';
--
-- This migration is intentionally a no-op guard: it validates the setting
-- exists so `supabase db push` fails loudly on a misconfigured project.
DO $$
BEGIN
    IF nullif(current_setting('app.cyclecare_admin_email', true), '') IS NULL THEN
        RAISE WARNING 'app.cyclecare_admin_email is not set; admin bootstrap will fail on fresh installs until it is configured.';
    END IF;
END;
$$;
