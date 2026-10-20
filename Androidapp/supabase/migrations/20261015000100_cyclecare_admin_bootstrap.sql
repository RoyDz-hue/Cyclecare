-- Grant the existing, email-verified CycleCare owner account PWA administrator access.
-- The prior cyclecare@gmail.com identity is absent from auth.users, so this is idempotent.
DO $$
DECLARE
    v_admin_id UUID;
BEGIN
    SELECT id INTO v_admin_id
    FROM auth.users
    WHERE lower(email) = 'roydeburgs@gmail.com'
      AND email_confirmed_at IS NOT NULL
    LIMIT 1;

    IF v_admin_id IS NULL THEN
        RAISE EXCEPTION 'Verified admin account roydeburgs@gmail.com was not found';
    END IF;

    DELETE FROM public.cyclecare_admin_roles AS r
    USING auth.users AS u
    WHERE r.user_id = u.id
      AND lower(u.email) = 'cyclecare@gmail.com';

    INSERT INTO public.cyclecare_admin_roles(user_id)
    VALUES (v_admin_id)
    ON CONFLICT (user_id) DO NOTHING;
END;
$$;
