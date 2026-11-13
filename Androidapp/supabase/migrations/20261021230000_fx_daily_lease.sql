-- Atomic daily FX refresh lease (same claim pattern as the Community Guides
-- pulse lease): one row per day; the first caller to insert wins, concurrent
-- callers lose instantly. A failed refresh releases the lease for retry; a
-- successful one holds it for the day. Admin pings bypass the lease entirely.

CREATE TABLE IF NOT EXISTS public.fx_refresh_lease (
    day DATE PRIMARY KEY,
    claimed_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    claimed_by TEXT NOT NULL,
    status TEXT NOT NULL DEFAULT 'running'
);
ALTER TABLE public.fx_refresh_lease ENABLE ROW LEVEL SECURITY;
REVOKE ALL ON public.fx_refresh_lease FROM PUBLIC, anon, authenticated;

CREATE OR REPLACE FUNCTION public.fx_claim_daily_refresh(p_claimed_by TEXT)
RETURNS BOOLEAN
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, public
AS $$
DECLARE
    v_claimed DATE;
BEGIN
    INSERT INTO public.fx_refresh_lease (day, claimed_by)
    VALUES (CURRENT_DATE, LEFT(COALESCE(p_claimed_by, 'lazy'), 60))
    ON CONFLICT (day) DO NOTHING
    RETURNING day INTO v_claimed;
    RETURN v_claimed IS NOT NULL;
END;
$$;
REVOKE ALL ON FUNCTION public.fx_claim_daily_refresh(TEXT) FROM PUBLIC, anon, authenticated;

CREATE OR REPLACE FUNCTION public.fx_settle_daily_refresh(p_ok BOOLEAN, p_claimed_by TEXT)
RETURNS VOID
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, public
AS $$
BEGIN
    IF p_ok THEN
        UPDATE public.fx_refresh_lease SET status = 'done' WHERE day = CURRENT_DATE AND claimed_by = LEFT(COALESCE(p_claimed_by, 'lazy'), 60);
    ELSE
        -- Failure releases the lease so the next caller can retry today.
        DELETE FROM public.fx_refresh_lease WHERE day = CURRENT_DATE AND claimed_by = LEFT(COALESCE(p_claimed_by, 'lazy'), 60);
    END IF;
END;
$$;
REVOKE ALL ON FUNCTION public.fx_settle_daily_refresh(BOOLEAN, TEXT) FROM PUBLIC, anon, authenticated;
