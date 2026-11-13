-- The mobile app and authenticated edge functions may read the caller's
-- profile status, but profile writes remain confined to the owner-checked RPC
-- and administrator policies.
ALTER TABLE public.profiles ENABLE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS cyclecare_owner_profile_select ON public.profiles;
CREATE POLICY cyclecare_owner_profile_select
    ON public.profiles FOR SELECT TO authenticated
    USING (user_id = auth.uid()::TEXT);
GRANT SELECT ON public.profiles TO authenticated;
