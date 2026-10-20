-- Cycle histories and symptom logs are sensitive health data. Do not rely on
-- client-supplied PostgREST filters or public anon-key access for isolation.
DO $$
DECLARE
    t TEXT;
    p RECORD;
BEGIN
    FOREACH t IN ARRAY ARRAY['cycles', 'daily_logs'] LOOP
        IF to_regclass(format('public.%I', t)) IS NOT NULL THEN
            EXECUTE format('ALTER TABLE public.%I ENABLE ROW LEVEL SECURITY', t);
            FOR p IN
                SELECT policyname FROM pg_policies
                WHERE schemaname = 'public' AND tablename = t
                  AND policyname NOT ILIKE '%service role%'
            LOOP
                EXECUTE format('DROP POLICY IF EXISTS %I ON public.%I', p.policyname, t);
            END LOOP;
            EXECUTE format(
                'CREATE POLICY cyclecare_owner_select ON public.%I FOR SELECT TO authenticated USING (user_id = auth.uid()::TEXT)', t
            );
            EXECUTE format(
                'CREATE POLICY cyclecare_owner_insert ON public.%I FOR INSERT TO authenticated WITH CHECK (user_id = auth.uid()::TEXT)', t
            );
            EXECUTE format(
                'CREATE POLICY cyclecare_owner_update ON public.%I FOR UPDATE TO authenticated USING (user_id = auth.uid()::TEXT) WITH CHECK (user_id = auth.uid()::TEXT)', t
            );
            EXECUTE format(
                'CREATE POLICY cyclecare_owner_delete ON public.%I FOR DELETE TO authenticated USING (user_id = auth.uid()::TEXT)', t
            );
            EXECUTE format('GRANT SELECT, INSERT, UPDATE, DELETE ON public.%I TO authenticated', t);
        END IF;
    END LOOP;
END $$;
