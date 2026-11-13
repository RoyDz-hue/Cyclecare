-- PROPOSAL ONLY: PAYMENT SETTLEMENT PROTOCOL v3 (release remains disabled).
-- SQL trusts only service-role provider evidence; provider verification is external.
BEGIN;
CREATE EXTENSION IF NOT EXISTS pgcrypto;

CREATE TABLE IF NOT EXISTS public.payment_catalog (
  package_id TEXT PRIMARY KEY,
  usd_amount_minor BIGINT NOT NULL,
  kes_amount_minor BIGINT NOT NULL,
  coins BIGINT NOT NULL,
  CHECK (usd_amount_minor > 0 AND kes_amount_minor > 0 AND coins > 0),
  UNIQUE (usd_amount_minor), UNIQUE (kes_amount_minor), UNIQUE (coins)
);
INSERT INTO public.payment_catalog(package_id,usd_amount_minor,kes_amount_minor,coins) VALUES
 ('pkg_seed',299,38900,250), ('pkg_sanctuary',699,90900,850), ('pkg_celestial',1499,194900,2400)
ON CONFLICT (package_id) DO NOTHING;
ALTER TABLE public.payment_catalog ENABLE ROW LEVEL SECURITY;
REVOKE ALL ON public.payment_catalog FROM PUBLIC,anon,authenticated,service_role;
GRANT SELECT ON public.payment_catalog TO service_role;

CREATE TABLE IF NOT EXISTS public.payment_intents (
 id UUID PRIMARY KEY DEFAULT gen_random_uuid(), user_id TEXT NOT NULL,
 provider TEXT NOT NULL CHECK(provider IN ('paypal','payhero')), external_reference TEXT NOT NULL UNIQUE,
 provider_reference TEXT, provider_order_id TEXT, amount NUMERIC(12,2) NOT NULL,
 amount_minor BIGINT NOT NULL CHECK(amount_minor>0), currency TEXT NOT NULL CHECK(currency IN ('USD','KES')),
 status TEXT NOT NULL DEFAULT 'pending' CHECK(status IN ('pending','success','failed','ambiguous','refunded','reversed')),
 metadata JSONB NOT NULL DEFAULT '{}'::jsonb, created_at TIMESTAMPTZ NOT NULL DEFAULT now(), updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
ALTER TABLE public.payment_intents ADD COLUMN IF NOT EXISTS amount_minor BIGINT DEFAULT 0;
UPDATE public.payment_intents SET amount_minor=round(amount*100)::BIGINT WHERE amount_minor IS NULL OR amount_minor=0;
ALTER TABLE public.payment_intents ALTER COLUMN amount_minor SET NOT NULL;
CREATE UNIQUE INDEX IF NOT EXISTS payment_intents_provider_reference_uq ON public.payment_intents(provider,provider_reference) WHERE provider_reference IS NOT NULL;
CREATE UNIQUE INDEX IF NOT EXISTS payment_intents_provider_order_uq ON public.payment_intents(provider,provider_order_id) WHERE provider_order_id IS NOT NULL;

CREATE TABLE IF NOT EXISTS public.transactions (
 id UUID PRIMARY KEY DEFAULT gen_random_uuid(), user_id TEXT NOT NULL, external_reference TEXT NOT NULL UNIQUE,
 provider TEXT NOT NULL, amount NUMERIC(12,2) NOT NULL, amount_minor BIGINT NOT NULL DEFAULT 0, currency TEXT NOT NULL,
 status TEXT NOT NULL DEFAULT 'pending', provider_reference TEXT, raw_payload JSONB NOT NULL DEFAULT '{}'::jsonb,
 metadata JSONB NOT NULL DEFAULT '{}'::jsonb, payment_intent_id UUID, created_at TIMESTAMPTZ NOT NULL DEFAULT now(), updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
ALTER TABLE public.transactions ADD COLUMN IF NOT EXISTS payment_intent_id UUID;
ALTER TABLE public.transactions ADD COLUMN IF NOT EXISTS amount_minor BIGINT DEFAULT 0;
UPDATE public.transactions SET amount_minor=round(amount*100)::BIGINT WHERE amount_minor IS NULL OR amount_minor=0;
ALTER TABLE public.transactions ALTER COLUMN amount_minor SET NOT NULL;
ALTER TABLE public.transactions DROP CONSTRAINT IF EXISTS transactions_status_check;
ALTER TABLE public.transactions ADD CONSTRAINT transactions_status_check CHECK(status IN ('pending','success','failed','cancelled','expired','ambiguous','refunded','reversed'));
CREATE UNIQUE INDEX IF NOT EXISTS transactions_payment_intent_uq ON public.transactions(payment_intent_id) WHERE payment_intent_id IS NOT NULL;
CREATE UNIQUE INDEX IF NOT EXISTS transactions_provider_reference_uq ON public.transactions(provider,provider_reference) WHERE provider_reference IS NOT NULL;

CREATE TABLE IF NOT EXISTS public.payment_settlement_events (
 id UUID PRIMARY KEY DEFAULT gen_random_uuid(), payment_intent_id UUID NOT NULL REFERENCES public.payment_intents(id),
 provider TEXT NOT NULL CHECK(provider IN ('paypal','payhero')), provider_reference TEXT NOT NULL CHECK(btrim(provider_reference)<>''),
 event_type TEXT NOT NULL CHECK(event_type IN ('capture','refund','reversal')), owner_id TEXT NOT NULL CHECK(btrim(owner_id)<>''),
 amount NUMERIC(12,2) NOT NULL CHECK(amount>0), currency TEXT NOT NULL CHECK(currency IN ('USD','KES')),
 payload JSONB NOT NULL DEFAULT '{}'::jsonb, created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
 UNIQUE(provider,provider_reference,event_type), UNIQUE(payment_intent_id,event_type)
);
CREATE TABLE IF NOT EXISTS public.payment_economy_ledger (
 id UUID PRIMARY KEY DEFAULT gen_random_uuid(), payment_intent_id UUID NOT NULL REFERENCES public.payment_intents(id),
 transaction_id UUID NOT NULL REFERENCES public.transactions(id), event_id UUID NOT NULL REFERENCES public.payment_settlement_events(id),
 owner_id TEXT NOT NULL, event_type TEXT NOT NULL CHECK(event_type IN ('capture','refund','reversal')), coin_delta BIGINT NOT NULL CHECK(coin_delta<>0),
 balance_before BIGINT NOT NULL, balance_after BIGINT NOT NULL, debt_before BIGINT NOT NULL DEFAULT 0, debt_after BIGINT NOT NULL DEFAULT 0,
 lifetime_earned_before BIGINT NOT NULL DEFAULT 0, lifetime_earned_after BIGINT NOT NULL DEFAULT 0, created_at TIMESTAMPTZ NOT NULL DEFAULT now(), UNIQUE(payment_intent_id,event_type), UNIQUE(event_id)
);

-- Forward-compatible financial columns required by settlement, without assuming they exist in legacy UUID-era fixtures.
ALTER TABLE public.economy_profiles ADD COLUMN IF NOT EXISTS lifetime_earned BIGINT NOT NULL DEFAULT 0;
ALTER TABLE public.economy_profiles ADD COLUMN IF NOT EXISTS lifetime_spent BIGINT NOT NULL DEFAULT 0;
ALTER TABLE public.economy_profiles ADD COLUMN IF NOT EXISTS rank TEXT NOT NULL DEFAULT 'LUMEN';
ALTER TABLE public.economy_profiles ADD COLUMN IF NOT EXISTS updated_at TIMESTAMPTZ NOT NULL DEFAULT now();
ALTER TABLE public.economy_profiles ADD COLUMN IF NOT EXISTS payment_coin_debt BIGINT NOT NULL DEFAULT 0;
ALTER TABLE public.payment_economy_ledger ADD COLUMN IF NOT EXISTS lifetime_earned_before BIGINT NOT NULL DEFAULT 0;
ALTER TABLE public.payment_economy_ledger ADD COLUMN IF NOT EXISTS lifetime_earned_after BIGINT NOT NULL DEFAULT 0;
UPDATE public.economy_profiles SET lifetime_earned=COALESCE(coin_balance,0) WHERE lifetime_earned=0 AND COALESCE(coin_balance,0)>0;

CREATE OR REPLACE FUNCTION public.get_payment_protocol_readiness()
RETURNS JSONB LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,public AS $$
DECLARE missing TEXT[] := ARRAY[]::TEXT[]; n INT;
BEGIN
 IF to_regclass('public.payment_catalog') IS NULL THEN missing:=array_append(missing,'payment_catalog'); END IF;
 IF to_regclass('public.payment_intents') IS NULL THEN missing:=array_append(missing,'payment_intents'); END IF;
 IF to_regclass('public.transactions') IS NULL THEN missing:=array_append(missing,'transactions'); END IF;
 IF to_regclass('public.payment_settlement_events') IS NULL THEN missing:=array_append(missing,'payment_settlement_events'); END IF;
 IF to_regclass('public.payment_economy_ledger') IS NULL THEN missing:=array_append(missing,'payment_economy_ledger'); END IF;
 IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema='public' AND table_name='economy_profiles' AND column_name IN ('lifetime_earned','lifetime_spent','rank','updated_at','payment_coin_debt') GROUP BY table_name HAVING count(*)=5) THEN missing:=array_append(missing,'economy_financial_columns'); END IF;
 IF NOT EXISTS (SELECT 1 FROM public.payment_catalog WHERE package_id='pkg_seed' AND usd_amount_minor=299 AND kes_amount_minor=38900 AND coins=250) OR NOT EXISTS (SELECT 1 FROM public.payment_catalog WHERE package_id='pkg_sanctuary' AND usd_amount_minor=699 AND kes_amount_minor=90900 AND coins=850) OR NOT EXISTS (SELECT 1 FROM public.payment_catalog WHERE package_id='pkg_celestial' AND usd_amount_minor=1499 AND kes_amount_minor=194900 AND coins=2400) THEN missing:=array_append(missing,'authoritative_catalog'); END IF;
 IF to_regprocedure('public.create_payment_intent(text,text,text,bigint,text,jsonb)') IS NULL OR to_regprocedure('public.attach_provider_order(uuid,text)') IS NULL OR to_regprocedure('public.reconcile_verified_payment(uuid,text,text,text,text,bigint,text,jsonb)') IS NULL THEN missing:=array_append(missing,'rpc_signatures'); END IF;
 IF to_regprocedure('public.reconcile_verified_payment_batch(uuid,jsonb)') IS NULL THEN missing:=array_append(missing,'batch_rpc_signature'); END IF;
 IF to_regprocedure('public.claim_payment_provider_action(uuid,text,text)') IS NULL THEN missing:=array_append(missing,'provider_action_claim'); END IF;
 IF array_length(missing,1) IS NOT NULL THEN RAISE EXCEPTION 'payment protocol schema not ready: %',array_to_string(missing,','); END IF;
 RETURN jsonb_build_object('schema_ready',true,'version',3);
END; $$;

CREATE OR REPLACE FUNCTION public.create_payment_intent(p_owner TEXT,p_provider TEXT,p_external_reference TEXT,p_amount_minor BIGINT,p_currency TEXT,p_metadata JSONB DEFAULT '{}'::jsonb)
RETURNS JSONB LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,public AS $$
DECLARE c RECORD; m JSONB; i RECORD; t RECORD; v_amount NUMERIC;
BEGIN
 PERFORM public.get_payment_protocol_readiness();
 IF p_owner IS NULL OR btrim(p_owner)='' OR p_provider NOT IN ('paypal','payhero') OR p_external_reference IS NULL OR btrim(p_external_reference)='' OR p_amount_minor IS NULL OR p_amount_minor<=0 OR p_currency NOT IN ('USD','KES') THEN RAISE EXCEPTION 'payment intent rejected'; END IF;
 m:=coalesce(p_metadata,'{}'::jsonb); SELECT * INTO c FROM public.payment_catalog WHERE package_id=m->>'package_id' AND coins=(m->>'coins_to_credit')::BIGINT AND ((p_currency='USD' AND usd_amount_minor=p_amount_minor) OR (p_currency='KES' AND kes_amount_minor=p_amount_minor));
 IF NOT FOUND OR jsonb_typeof(m->'package_id')<>'string' OR jsonb_typeof(m->'coins_to_credit')<>'number' THEN RAISE EXCEPTION 'unknown or mismatched payment package'; END IF;
 v_amount:=p_amount_minor/100.0;
 SELECT * INTO i FROM public.payment_intents WHERE external_reference=p_external_reference FOR UPDATE;
 IF FOUND THEN
   IF i.user_id<>p_owner OR i.provider<>p_provider OR i.amount_minor<>p_amount_minor OR i.currency<>p_currency OR i.metadata->>'package_id'<>c.package_id OR (i.metadata->>'coins_to_credit')::BIGINT<>c.coins THEN RAISE EXCEPTION 'external reference conflict'; END IF;
   SELECT id INTO t FROM public.transactions WHERE payment_intent_id=i.id;
   RETURN jsonb_build_object('success',true,'decision','duplicate','payment_intent_id',i.id,'transaction_id',t.id,'external_reference',i.external_reference,'provider_order_id',i.provider_order_id,'status',i.status);
 END IF;
 m:=jsonb_build_object('package_id',c.package_id,'coins_to_credit',c.coins);
 INSERT INTO public.payment_intents(user_id,provider,external_reference,amount,amount_minor,currency,metadata) VALUES(p_owner,p_provider,p_external_reference,v_amount,p_amount_minor,p_currency,m) RETURNING * INTO i;
 INSERT INTO public.transactions(user_id,external_reference,provider,amount,amount_minor,currency,status,metadata,payment_intent_id) VALUES(p_owner,p_external_reference,p_provider,v_amount,p_amount_minor,p_currency,'pending',m||jsonb_build_object('payment_intent_id',i.id),i.id) RETURNING id INTO t;
 RETURN jsonb_build_object('success',true,'decision','created','payment_intent_id',i.id,'transaction_id',t.id,'external_reference',p_external_reference,'status','pending');
EXCEPTION WHEN unique_violation THEN
 SELECT * INTO i FROM public.payment_intents WHERE external_reference=p_external_reference;
 IF FOUND AND i.user_id=p_owner AND i.provider=p_provider AND i.amount_minor=p_amount_minor AND i.currency=p_currency AND i.metadata->>'package_id'=c.package_id AND (i.metadata->>'coins_to_credit')::BIGINT=c.coins THEN
   SELECT id INTO t FROM public.transactions WHERE payment_intent_id=i.id;
   RETURN jsonb_build_object('success',true,'decision','duplicate','payment_intent_id',i.id,'transaction_id',t.id,'external_reference',i.external_reference,'provider_order_id',i.provider_order_id,'status',i.status);
 END IF;
 RAISE EXCEPTION 'external reference conflict'; END; $$;

CREATE OR REPLACE FUNCTION public.attach_provider_order(p_intent UUID,p_order TEXT) RETURNS JSONB LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,public AS $$
DECLARE i RECORD; t RECORD;
BEGIN
 PERFORM public.get_payment_protocol_readiness();
 IF p_intent IS NULL OR p_order IS NULL OR btrim(p_order)='' THEN RAISE EXCEPTION 'provider order rejected'; END IF;
 SELECT * INTO i FROM public.payment_intents WHERE id=p_intent FOR UPDATE; IF NOT FOUND THEN RAISE EXCEPTION 'payment intent not found'; END IF;
 IF i.status<>'pending' THEN RAISE EXCEPTION 'cannot attach order to terminal intent'; END IF;
 IF i.provider_order_id IS NOT NULL AND i.provider_order_id<>p_order THEN RAISE EXCEPTION 'provider order immutable'; END IF;
 UPDATE public.payment_intents SET provider_order_id=coalesce(provider_order_id,p_order),metadata=metadata||jsonb_build_object('provider_order_id',coalesce(provider_order_id,p_order)),updated_at=now() WHERE id=p_intent;
 UPDATE public.transactions SET metadata=metadata||jsonb_build_object('provider_order_id',coalesce(i.provider_order_id,p_order)),updated_at=now() WHERE payment_intent_id=p_intent RETURNING id INTO t;
 IF t.id IS NULL THEN RAISE EXCEPTION 'payment transaction not found'; END IF;
 RETURN jsonb_build_object('success',true,'decision',CASE WHEN i.provider_order_id IS NULL THEN 'attached' ELSE 'duplicate' END,'transaction_id',t.id,'external_reference',i.external_reference,'provider_order_id',coalesce(i.provider_order_id,p_order));
EXCEPTION WHEN unique_violation THEN RAISE EXCEPTION 'provider order already attached'; END; $$;

CREATE OR REPLACE FUNCTION public.claim_payment_provider_action(p_intent UUID,p_action TEXT,p_request_id TEXT)
RETURNS JSONB LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,public AS $$
DECLARE i RECORD; previous JSONB; actions JSONB;
BEGIN
 PERFORM public.get_payment_protocol_readiness();
 IF p_intent IS NULL OR p_action IS NULL OR p_action NOT IN ('create_order','capture') OR p_request_id IS NULL OR p_request_id !~ '^[A-Za-z0-9_-]{1,100}$' THEN RAISE EXCEPTION 'provider action rejected'; END IF;
 SELECT * INTO i FROM public.payment_intents WHERE id=p_intent FOR UPDATE;
 IF NOT FOUND OR i.provider<>'paypal' OR i.status<>'pending' THEN RAISE EXCEPTION 'provider action not pending'; END IF;
 IF p_action='capture' AND i.provider_order_id IS NULL THEN RAISE EXCEPTION 'provider order required'; END IF;
 actions:=coalesce(i.metadata->'provider_actions','{}'::jsonb); previous:=actions->p_action;
 IF previous IS NOT NULL THEN
   IF previous->>'request_id'<>p_request_id THEN RAISE EXCEPTION 'provider action identity changed'; END IF;
   RETURN jsonb_build_object('may_submit',false,'request_id',p_request_id,'reason','existing_attempt_requires_read_only_reconciliation');
 END IF;
 actions:=actions||jsonb_build_object(p_action,jsonb_build_object('request_id',p_request_id,'claimed_at',clock_timestamp()));
 UPDATE public.payment_intents SET metadata=metadata||jsonb_build_object('provider_actions',actions),updated_at=now() WHERE id=p_intent;
 UPDATE public.transactions SET metadata=metadata||jsonb_build_object('provider_actions',actions),updated_at=now() WHERE payment_intent_id=p_intent;
 RETURN jsonb_build_object('may_submit',true,'request_id',p_request_id);
END; $$;
REVOKE ALL ON FUNCTION public.claim_payment_provider_action(UUID,TEXT,TEXT) FROM PUBLIC,anon,authenticated;
GRANT EXECUTE ON FUNCTION public.claim_payment_provider_action(UUID,TEXT,TEXT) TO service_role;

-- Legacy reward RPCs must not bypass outstanding reversal debt. The settlement
-- RPC already changes debt explicitly; only unaccounted positive increments
-- with unchanged debt are intercepted here. No real-money debt is collected.
CREATE OR REPLACE FUNCTION public.repay_payment_coin_debt_on_credit()
RETURNS TRIGGER LANGUAGE plpgsql SET search_path=pg_catalog,public AS $$
DECLARE repayment BIGINT;
BEGIN
 IF NEW.coin_balance>OLD.coin_balance AND NEW.payment_coin_debt=OLD.payment_coin_debt AND OLD.payment_coin_debt>0 THEN
   repayment:=least(OLD.payment_coin_debt,NEW.coin_balance-OLD.coin_balance);
   NEW.coin_balance:=NEW.coin_balance-repayment;
   NEW.payment_coin_debt:=NEW.payment_coin_debt-repayment;
 END IF;
 RETURN NEW;
END; $$;
DROP TRIGGER IF EXISTS repay_payment_coin_debt_on_credit ON public.economy_profiles;
CREATE TRIGGER repay_payment_coin_debt_on_credit BEFORE UPDATE OF coin_balance,payment_coin_debt ON public.economy_profiles FOR EACH ROW EXECUTE FUNCTION public.repay_payment_coin_debt_on_credit();
REVOKE ALL ON FUNCTION public.repay_payment_coin_debt_on_credit() FROM PUBLIC,anon,authenticated,service_role;

CREATE OR REPLACE FUNCTION public.reconcile_verified_payment(p_intent UUID,p_event_type TEXT,p_provider TEXT,p_provider_reference TEXT,p_owner TEXT,p_amount_minor BIGINT,p_currency TEXT,p_payload JSONB DEFAULT '{}'::jsonb)
RETURNS JSONB LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,public AS $$
DECLARE i RECORD; t RECORD; e RECORD; w RECORD; c RECORD; v_coins BIGINT; v_delta BIGINT; v_before BIGINT; v_after BIGINT; v_debt_before BIGINT; v_debt_after BIGINT; v_repay BIGINT; v_lifetime_before BIGINT; v_lifetime_after BIGINT;
BEGIN
 PERFORM public.get_payment_protocol_readiness();
 IF p_event_type NOT IN ('capture','refund','reversal') OR p_provider NOT IN ('paypal','payhero') OR p_owner IS NULL OR btrim(p_owner)='' OR p_provider_reference IS NULL OR btrim(p_provider_reference)='' OR p_amount_minor IS NULL OR p_amount_minor<=0 OR p_currency NOT IN ('USD','KES') THEN RAISE EXCEPTION 'payment event rejected'; END IF;
 SELECT * INTO i FROM public.payment_intents WHERE id=p_intent FOR UPDATE; IF NOT FOUND THEN RAISE EXCEPTION 'payment intent not found'; END IF;
 SELECT * INTO t FROM public.transactions WHERE payment_intent_id=p_intent FOR UPDATE; IF NOT FOUND THEN RAISE EXCEPTION 'payment transaction not found'; END IF;
 IF t.user_id<>p_owner OR t.provider<>p_provider OR t.currency<>p_currency OR t.amount_minor<>p_amount_minor OR i.user_id<>p_owner OR i.provider<>p_provider OR i.currency<>p_currency OR i.amount_minor<>p_amount_minor OR t.metadata->>'package_id'<>i.metadata->>'package_id' OR t.metadata->>'coins_to_credit'<>i.metadata->>'coins_to_credit' THEN RAISE EXCEPTION 'payment evidence mismatch'; END IF;
 SELECT * INTO c FROM public.payment_catalog WHERE package_id=i.metadata->>'package_id' AND coins=(i.metadata->>'coins_to_credit')::BIGINT AND ((p_currency='USD' AND usd_amount_minor=p_amount_minor) OR (p_currency='KES' AND kes_amount_minor=p_amount_minor)); IF NOT FOUND THEN RAISE EXCEPTION 'payment package no longer catalogued'; END IF;
 SELECT * INTO e FROM public.payment_settlement_events WHERE provider=p_provider AND provider_reference=p_provider_reference AND event_type=p_event_type;
 IF FOUND THEN
   IF e.payment_intent_id=p_intent AND e.owner_id=p_owner AND e.amount=i.amount AND e.currency=p_currency THEN RETURN jsonb_build_object('success',true,'decision','duplicate','status',i.status,'transaction_status',t.status,'coin_debt',(SELECT payment_coin_debt FROM public.economy_profiles WHERE user_id=p_owner)); END IF;
   RAISE EXCEPTION 'payment event ambiguous';
 END IF;
 IF p_event_type='capture' AND i.status<>'pending' THEN RAISE EXCEPTION 'capture terminal state'; END IF;
 IF p_event_type IN ('refund','reversal') AND i.status<>'success' THEN RAISE EXCEPTION 'refund or reversal requires captured payment'; END IF;
 v_coins:=c.coins; v_delta:=CASE WHEN p_event_type='capture' THEN v_coins ELSE -v_coins END;
 INSERT INTO public.payment_settlement_events(payment_intent_id,provider,provider_reference,event_type,owner_id,amount,currency,payload) VALUES(p_intent,p_provider,p_provider_reference,p_event_type,p_owner,i.amount,p_currency,coalesce(p_payload,'{}'::jsonb)) RETURNING * INTO e;
 INSERT INTO public.economy_profiles(user_id) VALUES(p_owner) ON CONFLICT(user_id) DO NOTHING;
 UPDATE public.economy_profiles SET lifetime_earned=coin_balance WHERE user_id=p_owner AND lifetime_earned=0 AND COALESCE(coin_balance,0)>0;
 SELECT * INTO w FROM public.economy_profiles WHERE user_id=p_owner FOR UPDATE; v_before:=coalesce(w.coin_balance,0); v_debt_before:=coalesce(w.payment_coin_debt,0); v_lifetime_before:=coalesce(w.lifetime_earned,0);
 IF v_delta<0 THEN v_after:=greatest(0,v_before+v_delta); v_debt_after:=v_debt_before+greatest(0,-(v_before+v_delta)); v_lifetime_after:=greatest(0,v_lifetime_before+v_delta); ELSE v_repay:=least(v_debt_before,v_delta); v_debt_after:=v_debt_before-v_repay; v_after:=v_before+(v_delta-v_repay); v_lifetime_after:=v_lifetime_before+v_delta; END IF;
 UPDATE public.economy_profiles SET coin_balance=v_after,payment_coin_debt=v_debt_after,lifetime_earned=v_lifetime_after,lifetime_spent=CASE WHEN v_delta<0 THEN lifetime_spent+(-v_delta) ELSE lifetime_spent END,rank=CASE WHEN v_lifetime_after>=7500 THEN 'FOUNDER' WHEN v_lifetime_after>=3000 THEN 'NOCTURNE' WHEN v_lifetime_after>=1200 THEN 'SOLARA' WHEN v_lifetime_after>=500 THEN 'CELESTIA' WHEN v_lifetime_after>=150 THEN 'AURELIA' ELSE 'LUMEN' END,updated_at=now() WHERE user_id=p_owner;
 INSERT INTO public.payment_economy_ledger(payment_intent_id,transaction_id,event_id,owner_id,event_type,coin_delta,balance_before,balance_after,debt_before,debt_after,lifetime_earned_before,lifetime_earned_after) VALUES(p_intent,t.id,e.id,p_owner,p_event_type,v_delta,v_before,v_after,v_debt_before,v_debt_after,v_lifetime_before,v_lifetime_after);
 UPDATE public.payment_intents SET status=CASE p_event_type WHEN 'capture' THEN 'success' WHEN 'refund' THEN 'refunded' ELSE 'reversed' END,provider_reference=CASE WHEN p_event_type='capture' THEN p_provider_reference ELSE provider_reference END,updated_at=now() WHERE id=p_intent;
 UPDATE public.transactions SET status=CASE p_event_type WHEN 'capture' THEN 'success' WHEN 'refund' THEN 'refunded' ELSE 'reversed' END,provider_reference=CASE WHEN p_event_type='capture' THEN p_provider_reference ELSE provider_reference END,raw_payload=coalesce(p_payload,'{}'::jsonb),updated_at=now() WHERE id=t.id;
 RETURN jsonb_build_object('success',true,'decision','settle','status',CASE p_event_type WHEN 'capture' THEN 'success' WHEN 'refund' THEN 'refunded' ELSE 'reversed' END,'coins_delta',v_delta,'new_balance',v_after,'coin_debt',v_debt_after);
EXCEPTION WHEN unique_violation THEN RAISE EXCEPTION 'payment event ambiguous'; END; $$;

CREATE OR REPLACE FUNCTION public.reconcile_verified_payment_batch(p_intent UUID,p_events JSONB)
RETURNS JSONB LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,public AS $$
DECLARE ev JSONB; r JSONB; n INT:=0; allowed CONSTANT TEXT[]:=ARRAY['event_type','provider','provider_reference','owner','amount_minor','currency','payload'];
BEGIN
 IF p_intent IS NULL OR p_events IS NULL OR jsonb_typeof(p_events)<>'array' OR jsonb_array_length(p_events)<1 OR jsonb_array_length(p_events)>2 THEN RAISE EXCEPTION 'payment event batch rejected'; END IF;
 -- Only the exact provider-evidence envelope is accepted; client metadata is never forwarded.
 FOR ev IN SELECT value FROM jsonb_array_elements(p_events) LOOP
   IF jsonb_typeof(ev)<>'object' OR EXISTS (SELECT 1 FROM jsonb_object_keys(ev) k WHERE NOT (k=ANY(allowed))) OR NOT (ev ? 'event_type' AND ev ? 'provider' AND ev ? 'provider_reference' AND ev ? 'owner' AND ev ? 'amount_minor' AND ev ? 'currency') THEN RAISE EXCEPTION 'payment event batch fields rejected'; END IF;
   n:=n+1;
 END LOOP;
 -- Capture is deliberately applied first; each call retains the intent/account locks and unique-event proof.
 FOR ev IN SELECT value FROM jsonb_array_elements(p_events) ORDER BY CASE WHEN value->>'event_type'='capture' THEN 0 ELSE 1 END LOOP
   r:=public.reconcile_verified_payment(p_intent,ev->>'event_type',ev->>'provider',ev->>'provider_reference',ev->>'owner',(ev->>'amount_minor')::BIGINT,ev->>'currency',coalesce(ev->'payload','{}'::jsonb));
 END LOOP;
 RETURN jsonb_build_object('success',true,'decision','batch','events',n,'last',r);
END; $$;

DROP FUNCTION IF EXISTS public.record_payment_event(UUID,TEXT,TEXT,TEXT,TEXT,NUMERIC,TEXT,JSONB);
DROP FUNCTION IF EXISTS public.apply_verified_payment(UUID,TEXT,NUMERIC,TEXT,TEXT,JSONB);
REVOKE ALL ON FUNCTION public.get_payment_protocol_readiness(),public.reconcile_verified_payment_batch(UUID,JSONB),public.create_payment_intent(TEXT,TEXT,TEXT,BIGINT,TEXT,JSONB),public.attach_provider_order(UUID,TEXT),public.reconcile_verified_payment(UUID,TEXT,TEXT,TEXT,TEXT,BIGINT,TEXT,JSONB) FROM PUBLIC,anon,authenticated;
DO $$ BEGIN IF EXISTS(SELECT 1 FROM pg_roles WHERE rolname='service_role') THEN GRANT EXECUTE ON FUNCTION public.get_payment_protocol_readiness(),public.reconcile_verified_payment_batch(UUID,JSONB),public.create_payment_intent(TEXT,TEXT,TEXT,BIGINT,TEXT,JSONB),public.attach_provider_order(UUID,TEXT),public.reconcile_verified_payment(UUID,TEXT,TEXT,TEXT,TEXT,BIGINT,TEXT,JSONB) TO service_role; END IF; END $$;

-- Sensitive financial tables are RLS-protected. Owners may read their own profile/transaction only;
-- service_role retains the SELECT and DML needed by handlers/RPCs, but no client receives DML.
ALTER TABLE public.payment_intents ENABLE ROW LEVEL SECURITY; ALTER TABLE public.payment_settlement_events ENABLE ROW LEVEL SECURITY; ALTER TABLE public.payment_economy_ledger ENABLE ROW LEVEL SECURITY; ALTER TABLE public.transactions ENABLE ROW LEVEL SECURITY; ALTER TABLE public.economy_profiles ENABLE ROW LEVEL SECURITY;
REVOKE ALL ON public.payment_intents,public.payment_settlement_events,public.payment_economy_ledger,public.transactions,public.economy_profiles FROM PUBLIC,anon,authenticated,service_role;
GRANT SELECT ON public.payment_intents,public.payment_settlement_events,public.payment_economy_ledger,public.transactions,public.economy_profiles TO service_role;
GRANT INSERT,UPDATE,DELETE ON public.payment_intents,public.transactions,public.economy_profiles TO service_role;
-- Evidence/ledger inserts occur only inside the atomic SECURITY DEFINER RPCs.
-- The service key cannot edit/delete or inject unaccounted immutable evidence.
GRANT SELECT ON public.economy_profiles,public.transactions TO authenticated;
DROP POLICY IF EXISTS payment_intents_owner_select ON public.payment_intents; CREATE POLICY payment_intents_owner_select ON public.payment_intents FOR SELECT TO authenticated USING(user_id=auth.uid()::text);
DROP POLICY IF EXISTS transactions_owner_select ON public.transactions; CREATE POLICY transactions_owner_select ON public.transactions FOR SELECT TO authenticated USING(user_id=auth.uid()::text);
DROP POLICY IF EXISTS economy_profiles_owner_select ON public.economy_profiles; CREATE POLICY economy_profiles_owner_select ON public.economy_profiles FOR SELECT TO authenticated USING(user_id=auth.uid()::text);
DROP POLICY IF EXISTS payment_intents_service ON public.payment_intents; CREATE POLICY payment_intents_service ON public.payment_intents FOR ALL TO service_role USING(true) WITH CHECK(true);
DROP POLICY IF EXISTS payment_events_service ON public.payment_settlement_events; CREATE POLICY payment_events_service ON public.payment_settlement_events FOR ALL TO service_role USING(true) WITH CHECK(true);
DROP POLICY IF EXISTS payment_ledger_service ON public.payment_economy_ledger; CREATE POLICY payment_ledger_service ON public.payment_economy_ledger FOR ALL TO service_role USING(true) WITH CHECK(true);
DROP POLICY IF EXISTS transactions_service ON public.transactions; CREATE POLICY transactions_service ON public.transactions FOR ALL TO service_role USING(true) WITH CHECK(true);
DROP POLICY IF EXISTS economy_profiles_service ON public.economy_profiles; CREATE POLICY economy_profiles_service ON public.economy_profiles FOR ALL TO service_role USING(true) WITH CHECK(true);
COMMIT;
