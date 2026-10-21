-- Grant the CycleCare owner account PWA administrator access.
--
-- The admin identity is intentionally NOT committed to this repository
-- (a personal email in a migration is both PII and a fragile single point
-- of failure). Before running this migration on a fresh project, set the
-- admin email once, outside version control:
--
--   ALTER DATABASE postgres SET app.cyclecare_admin_email = 'you@example.com';
--
-- (Supabase SQL Editor -> run the ALTER once; it persists for the project.)
-- The migration fails closed with a clear error when the setting is absent.
DO $$
DECLARE
    v_admin_email TEXT;
    v_admin_id UUID;
BEGIN
    v_admin_email := nullif(current_setting('app.cyclecare_admin_email', true), '');

    IF v_admin_email IS NULL THEN
        RAISE EXCEPTION 'app.cyclecare_admin_email is not set. Run: ALTER DATABASE postgres SET app.cyclecare_admin_email = ''you@example.com''; before migrating.';
    END IF;

    SELECT id INTO v_admin_id
    FROM auth.users
    WHERE lower(email) = lower(v_admin_email)
      AND email_confirmed_at IS NOT NULL
    LIMIT 1;

    IF v_admin_id IS NULL THEN
        RAISE EXCEPTION 'Verified admin account matching app.cyclecare_admin_email was not found in auth.users';
    END IF;

    -- The legacy cyclecare@gmail.com placeholder identity is never an admin.
    DELETE FROM public.cyclecare_admin_roles AS r
    USING auth.users AS u
    WHERE r.user_id = u.id
      AND lower(u.email) = 'cyclecare@gmail.com';

    INSERT INTO public.cyclecare_admin_roles(user_id)
    VALUES (v_admin_id)
    ON CONFLICT (user_id) DO NOTHING;
END;
$$;
