-- Run after the proposal in a fresh cyclecare_payment_strict database.
BEGIN;
DO $$ BEGIN CREATE ROLE anon NOLOGIN; EXCEPTION WHEN duplicate_object THEN NULL; END $$;
DO $$ BEGIN CREATE ROLE authenticated NOLOGIN; EXCEPTION WHEN duplicate_object THEN NULL; END $$;
DO $$ BEGIN CREATE ROLE service_role NOLOGIN; EXCEPTION WHEN duplicate_object THEN NULL; END $$;

CREATE TEMP TABLE test_ids (intent_id uuid, owner_id text, provider text, amount numeric, currency text);
INSERT INTO public.payment_intents(user_id,provider,external_reference,amount,currency)
VALUES ('owner-a','paypal','order-a',10.25,'USD');
INSERT INTO test_ids SELECT id,user_id,provider,amount,currency FROM public.payment_intents WHERE external_reference = 'order-a';
GRANT SELECT ON test_ids TO service_role, anon, authenticated;

CREATE OR REPLACE FUNCTION pg_temp.assert_raises(p_sql text) RETURNS void LANGUAGE plpgsql AS $f$
BEGIN
  BEGIN EXECUTE p_sql; EXCEPTION WHEN OTHERS THEN RETURN; END;
  RAISE EXCEPTION 'expected statement to raise: %', p_sql;
END $f$;
CREATE OR REPLACE FUNCTION pg_temp.assert_decision(p_sql text, p_decision text, p_success boolean) RETURNS void LANGUAGE plpgsql AS $f$
DECLARE r jsonb;
BEGIN EXECUTE p_sql INTO r;
  IF r->>'decision' <> p_decision OR (r->>'success')::boolean <> p_success THEN
    RAISE EXCEPTION 'unexpected result %, expected success=% decision=%',r,p_success,p_decision;
  END IF;
END $f$;

-- Function is available only to service_role, and writes are through the RPC.
SET LOCAL ROLE service_role;
SELECT pg_temp.assert_decision(format($q$SELECT public.record_payment_event('%s','paypal','cap-a','capture','owner-a',10.25,'USD','{}')$q$,(SELECT intent_id FROM test_ids)),'settle',true);
SELECT pg_temp.assert_decision(format($q$SELECT public.record_payment_event('%s','paypal','cap-a','capture','owner-a',10.25,'USD','{}')$q$,(SELECT intent_id FROM test_ids)),'duplicate',true);
SELECT pg_temp.assert_raises(format($q$SELECT public.record_payment_event('%s','paypal','cap-other','capture','owner-a',10.25,'USD','{}')$q$,(SELECT intent_id FROM test_ids)));
SELECT pg_temp.assert_raises(format($q$SELECT public.record_payment_event('%s','paypal','cap-a','capture','other-owner',10.25,'USD','{}')$q$,(SELECT intent_id FROM test_ids)));
SELECT pg_temp.assert_raises(format($q$SELECT public.record_payment_event('%s','paypal','cap-a','capture','owner-a',9.25,'USD','{}')$q$,(SELECT intent_id FROM test_ids)));
SELECT pg_temp.assert_raises(format($q$SELECT public.record_payment_event('%s','paypal','cap-a','capture','owner-a',10.25,'KES','{}')$q$,(SELECT intent_id FROM test_ids)));
SELECT pg_temp.assert_decision(format($q$SELECT public.record_payment_event('%s','paypal','refund-a','refund','owner-a',10.25,'USD','{}')$q$,(SELECT intent_id FROM test_ids)),'settle',true);
SELECT pg_temp.assert_decision(format($q$SELECT public.record_payment_event('%s','paypal','refund-a','refund','owner-a',10.25,'USD','{}')$q$,(SELECT intent_id FROM test_ids)),'duplicate',true);
SELECT pg_temp.assert_raises(format($q$SELECT public.record_payment_event('%s','paypal','rev-a','reversal','owner-a',10.25,'USD','{}')$q$,(SELECT intent_id FROM test_ids)));
SELECT pg_temp.assert_raises(format($q$SELECT public.record_payment_event('%s','paypal','cap-resurrect','capture','owner-a',10.25,'USD','{}')$q$,(SELECT intent_id FROM test_ids)));
SELECT pg_temp.assert_raises(format($q$SELECT public.record_payment_event('%s','paypal','bad','bogus','owner-a',10.25,'USD','{}')$q$,(SELECT intent_id FROM test_ids)));
SELECT pg_temp.assert_raises(format($q$SELECT public.record_payment_event('%s','paypal','bad','capture','owner-a','NaN','USD','{}')$q$,(SELECT intent_id FROM test_ids)));
SELECT pg_temp.assert_raises(format($q$SELECT public.record_payment_event('%s','paypal','bad','capture','owner-a',10.251,'USD','{}')$q$,(SELECT intent_id FROM test_ids)));
SELECT pg_temp.assert_raises(format($q$SELECT public.record_payment_event('%s','paypal','bad','capture','owner-a',10.25,NULL,'USD','{}')$q$,(SELECT intent_id FROM test_ids)));

-- Terminal rows are immutable even to service_role; service_role is read-only on tables.
SELECT pg_temp.assert_raises(format('UPDATE public.payment_settlement_events SET amount=1 WHERE payment_intent_id=%L',(SELECT intent_id FROM test_ids)));
SELECT pg_temp.assert_raises(format('DELETE FROM public.payment_settlement_events WHERE payment_intent_id=%L',(SELECT intent_id FROM test_ids)));
SELECT pg_temp.assert_raises(format('INSERT INTO public.payment_settlement_events(payment_intent_id,provider,provider_reference,event_type,owner_id,amount,currency) VALUES (%L,''paypal'',''x'',''capture'',''owner-a'',10.25,''USD'')',(SELECT intent_id FROM test_ids)));
RESET ROLE;

-- anon/authenticated have neither RPC execution nor table access.
SET LOCAL ROLE anon;
SELECT pg_temp.assert_raises(format('SELECT public.record_payment_event(%L,''paypal'',''x'',''capture'',''owner-a'',10.25,''USD'',''{}'')',(SELECT intent_id FROM test_ids)));
SELECT pg_temp.assert_raises('SELECT * FROM public.payment_intents');
SELECT pg_temp.assert_raises('SELECT * FROM public.payment_settlement_events');
RESET ROLE;
SET LOCAL ROLE authenticated;
SELECT pg_temp.assert_raises('SELECT public.record_payment_event(NULL,NULL,NULL,NULL,NULL,NULL,NULL,NULL)');
SELECT pg_temp.assert_raises('SELECT * FROM public.payment_intents');
RESET ROLE;

-- No unrelated privilege was changed by the proposal.
DO $$ BEGIN
  IF has_table_privilege('service_role','public.payment_intents','INSERT') OR has_table_privilege('service_role','public.payment_settlement_events','DELETE') THEN RAISE EXCEPTION 'service_role received write table privilege'; END IF;
  IF has_table_privilege('anon','public.payment_intents','SELECT') OR has_table_privilege('authenticated','public.payment_settlement_events','SELECT') THEN RAISE EXCEPTION 'untrusted role received table SELECT'; END IF;
  IF has_function_privilege('anon','public.record_payment_event(uuid,text,text,text,text,numeric,text,jsonb)','EXECUTE') THEN RAISE EXCEPTION 'anon received RPC EXECUTE'; END IF;
  IF NOT has_function_privilege('service_role','public.record_payment_event(uuid,text,text,text,text,numeric,text,jsonb)','EXECUTE') THEN RAISE EXCEPTION 'service_role missing RPC EXECUTE'; END IF;
END $$;

ROLLBACK;
