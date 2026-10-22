CREATE TABLE IF NOT EXISTS public.admin_coin_dialog_state (
 admin_user_id UUID PRIMARY KEY, state JSONB NOT NULL DEFAULT '{}'::jsonb,updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
ALTER TABLE public.admin_coin_dialog_state ENABLE ROW LEVEL SECURITY;
REVOKE ALL ON public.admin_coin_dialog_state FROM PUBLIC,anon,authenticated;
