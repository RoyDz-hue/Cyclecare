-- Strict actual-Postgres payment settlement matrix. Disposable DB only.
\set ON_ERROR_STOP on
\set QUIET on

-- Catalog matches the independently asserted server COIN_PACKAGES contract.
BEGIN;
SET LOCAL ROLE ubuntu;
DO $$DECLARE n bigint; BEGIN
 SELECT coins INTO n FROM public.payment_catalog WHERE package_id='pkg_seed' AND usd_amount_minor=299;
 IF n IS NULL OR n<>250 THEN RAISE EXCEPTION 'pkg_seed catalog mismatch: %',n; END IF;
 SELECT coins INTO n FROM public.payment_catalog WHERE package_id='pkg_sanctuary' AND usd_amount_minor=699;
 IF n IS NULL OR n<>850 THEN RAISE EXCEPTION 'pkg_sanctuary catalog mismatch: %',n; END IF;
 SELECT coins INTO n FROM public.payment_catalog WHERE package_id='pkg_celestial' AND usd_amount_minor=1499;
 IF n IS NULL OR n<>2400 THEN RAISE EXCEPTION 'pkg_celestial catalog mismatch: %',n; END IF;
END$$;
ROLLBACK;

-- Create/replay and all immutable identity dimensions.
BEGIN; SET LOCAL ROLE service_role;
DO $$DECLARE r jsonb; iid uuid; x text:=replace(gen_random_uuid()::text,'-',''); tested int:=0; BEGIN
 r:=public.create_payment_intent('matrix-owner','paypal','matrix-'||x,699,'USD',jsonb_build_object('package_id','pkg_sanctuary','coins_to_credit',850)); iid:=(r->>'payment_intent_id')::uuid;
 r:=public.create_payment_intent('matrix-owner','paypal','matrix-'||x,699,'USD',jsonb_build_object('package_id','pkg_sanctuary','coins_to_credit',850)); IF r->>'decision'<> 'duplicate' OR (r->>'payment_intent_id')::uuid<>iid THEN RAISE EXCEPTION 'replay mismatch'; END IF;
 FOR r IN SELECT value FROM jsonb_array_elements(jsonb_build_array(jsonb_build_object('owner','other','provider','paypal','amount',699,'currency','USD','metadata',jsonb_build_object('package_id','pkg_sanctuary','coins_to_credit',850)), jsonb_build_object('owner','matrix-owner','provider','payhero','amount',699,'currency','USD','metadata',jsonb_build_object('package_id','pkg_sanctuary','coins_to_credit',850)), jsonb_build_object('owner','matrix-owner','provider','paypal','amount',299,'currency','USD','metadata',jsonb_build_object('package_id','pkg_sanctuary','coins_to_credit',850)), jsonb_build_object('owner','matrix-owner','provider','paypal','amount',699,'currency','KES','metadata',jsonb_build_object('package_id','pkg_sanctuary','coins_to_credit',850)), jsonb_build_object('owner','matrix-owner','provider','paypal','amount',699,'currency','USD','metadata',jsonb_build_object('package_id','pkg_celestial','coins_to_credit',2400)))) LOOP
 tested:=tested+1;
 BEGIN PERFORM public.create_payment_intent(r->>'owner',r->>'provider','matrix-'||x,(r->>'amount')::bigint,r->>'currency',r->'metadata'); RAISE EXCEPTION 'identity mutation accepted: %',r; EXCEPTION WHEN OTHERS THEN IF SQLERRM LIKE 'identity mutation accepted%' THEN RAISE; END IF; END; END LOOP;
 IF tested<>5 THEN RAISE EXCEPTION 'identity cases not all executed'; END IF;
END$$; ROLLBACK;

-- Claim lifecycle, durable once-only claims, and immutable order.
BEGIN; SET LOCAL ROLE service_role;
DO $$DECLARE r jsonb; iid uuid; x text:=replace(gen_random_uuid()::text,'-',''); BEGIN
 r:=public.create_payment_intent('claim-'||x,'paypal','claim-'||x,699,'USD',jsonb_build_object('package_id','pkg_sanctuary','coins_to_credit',850)); iid:=(r->>'payment_intent_id')::uuid;
 r:=public.claim_payment_provider_action(iid,'create_order','req-create-'||x); IF r->>'may_submit'<>'true' THEN RAISE EXCEPTION 'first create claim denied'; END IF;
 r:=public.claim_payment_provider_action(iid,'create_order','req-create-'||x); IF r->>'may_submit'<>'false' THEN RAISE EXCEPTION 'duplicate create claim won'; END IF;
 BEGIN PERFORM public.claim_payment_provider_action(iid,'create_order','changed-'||x); RAISE EXCEPTION 'changed create request accepted'; EXCEPTION WHEN OTHERS THEN IF SQLERRM='changed create request accepted' THEN RAISE; END IF; END;
 BEGIN PERFORM public.claim_payment_provider_action(iid,'capture','cap-'||x); RAISE EXCEPTION 'capture without order accepted'; EXCEPTION WHEN OTHERS THEN IF SQLERRM='capture without order accepted' THEN RAISE; END IF; END;
 PERFORM public.attach_provider_order(iid,'order-'||x); PERFORM public.attach_provider_order(iid,'order-'||x);
 BEGIN PERFORM public.attach_provider_order(iid,'other-'||x); RAISE EXCEPTION 'order replacement accepted'; EXCEPTION WHEN OTHERS THEN IF SQLERRM='order replacement accepted' THEN RAISE; END IF; END;
 r:=public.claim_payment_provider_action(iid,'capture','cap-'||x); IF r->>'may_submit'<>'true' THEN RAISE EXCEPTION 'first capture claim denied'; END IF;
 r:=public.claim_payment_provider_action(iid,'capture','cap-'||x); IF r->>'may_submit'<>'false' THEN RAISE EXCEPTION 'duplicate capture claim won'; END IF;
 BEGIN PERFORM public.claim_payment_provider_action(iid,'capture','changed-cap-'||x); RAISE EXCEPTION 'changed capture request accepted'; EXCEPTION WHEN OTHERS THEN IF SQLERRM='changed capture request accepted' THEN RAISE; END IF; END;
 PERFORM public.reconcile_verified_payment(iid,'capture','paypal','capref-'||x,'claim-'||x,699,'USD','{}');
 BEGIN PERFORM public.claim_payment_provider_action(iid,'create_order','late-'||x); RAISE EXCEPTION 'terminal claim accepted'; EXCEPTION WHEN OTHERS THEN IF SQLERRM='terminal claim accepted' THEN RAISE; END IF; END;
END$$; ROLLBACK;

-- Atomic capture+refund and rollback of first event on invalid second event.
BEGIN; SET LOCAL ROLE service_role;
DO $$DECLARE r jsonb; iid uuid; ev jsonb; x text:=replace(gen_random_uuid()::text,'-',''); b bigint; cnt int; BEGIN
 r:=public.create_payment_intent('batch-'||x,'paypal','batch-'||x,699,'USD',jsonb_build_object('package_id','pkg_sanctuary','coins_to_credit',850)); iid:=(r->>'payment_intent_id')::uuid; PERFORM public.attach_provider_order(iid,'batch-order-'||x);
 ev:=jsonb_build_array(jsonb_build_object('event_type','capture','provider','paypal','provider_reference','batch-cap-'||x,'owner','batch-'||x,'amount_minor',699,'currency','USD'),jsonb_build_object('event_type','refund','provider','paypal','provider_reference','batch-ref-'||x,'owner','batch-'||x,'amount_minor',699,'currency','USD'));
 r:=public.reconcile_verified_payment_batch(iid,ev); IF r->>'events'<>'2' THEN RAISE EXCEPTION 'batch count wrong'; END IF; SELECT coin_balance INTO b FROM public.economy_profiles WHERE user_id='batch-'||x; IF b<>120 THEN RAISE EXCEPTION 'batch not net zero: %',b; END IF; SELECT count(*) INTO cnt FROM public.payment_settlement_events WHERE payment_intent_id=iid; IF cnt<>2 THEN RAISE EXCEPTION 'batch event rows %',cnt; END IF;
END$$; ROLLBACK;
BEGIN; SET LOCAL ROLE service_role;
DO $$DECLARE r jsonb; iid uuid; x text:=replace(gen_random_uuid()::text,'-',''); BEGIN
 r:=public.create_payment_intent('rollback-'||x,'paypal','rollback-'||x,699,'USD',jsonb_build_object('package_id','pkg_sanctuary','coins_to_credit',850)); iid:=(r->>'payment_intent_id')::uuid; PERFORM public.attach_provider_order(iid,'rollback-order-'||x);
 BEGIN PERFORM public.reconcile_verified_payment_batch(iid,jsonb_build_array(jsonb_build_object('event_type','capture','provider','paypal','provider_reference','rb-cap-'||x,'owner','rollback-'||x,'amount_minor',699,'currency','USD'),jsonb_build_object('event_type','refund','provider','paypal','provider_reference','rb-ref-'||x,'owner','wrong','amount_minor',699,'currency','USD'))); RAISE EXCEPTION 'invalid second event committed'; EXCEPTION WHEN OTHERS THEN IF SQLERRM='invalid second event committed' THEN RAISE; END IF; END;
 IF EXISTS(SELECT 1 FROM public.payment_settlement_events WHERE payment_intent_id=iid) OR EXISTS(SELECT 1 FROM public.payment_economy_ledger WHERE payment_intent_id=iid) THEN RAISE EXCEPTION 'batch rollback leaked rows'; END IF;
END$$; ROLLBACK;

-- Reversal debt, paid credit and legacy daily-reward increment repay debt without money collection.
BEGIN; SET LOCAL ROLE service_role;
DO $$DECLARE r jsonb; iid uuid; x text:=replace(gen_random_uuid()::text,'-',''); b bigint; d bigint; le bigint; rk text; BEGIN
 r:=public.create_payment_intent('debt-'||x,'paypal','debt-'||x,699,'USD',jsonb_build_object('package_id','pkg_sanctuary','coins_to_credit',850)); iid:=(r->>'payment_intent_id')::uuid; PERFORM public.attach_provider_order(iid,'debt-order-'||x); PERFORM public.reconcile_verified_payment(iid,'capture','paypal','debt-cap-'||x,'debt-'||x,699,'USD','{}'); UPDATE public.economy_profiles SET coin_balance=0,lifetime_spent=850 WHERE user_id='debt-'||x; PERFORM public.reconcile_verified_payment(iid,'refund','paypal','debt-ref-'||x,'debt-'||x,699,'USD','{}'); SELECT coin_balance,payment_coin_debt,lifetime_earned,rank INTO b,d,le,rk FROM public.economy_profiles WHERE user_id='debt-'||x; IF b<>0 OR d<>850 OR le<>120 OR rk<>'LUMEN' THEN RAISE EXCEPTION 'unexpected debt baseline %,%,%,%',b,d,le,rk; END IF;
 UPDATE public.economy_profiles SET coin_balance=coin_balance+100 WHERE user_id='debt-'||x; SELECT coin_balance,payment_coin_debt INTO b,d FROM public.economy_profiles WHERE user_id='debt-'||x; IF b<>0 OR d<>750 THEN RAISE EXCEPTION 'legacy repayment failed %, %',b,d; END IF;
END$$; ROLLBACK;

-- Service role has RPC execute but no direct DML on financial evidence/ledger; owner RLS sees only self.
BEGIN; SET LOCAL ROLE service_role;
DO $$BEGIN
 BEGIN INSERT INTO public.payment_settlement_events(payment_intent_id,provider,provider_reference,event_type,owner_id,amount,currency) VALUES(gen_random_uuid(),'paypal','deny','capture','deny',1,'USD'); RAISE EXCEPTION 'service insert evidence allowed'; EXCEPTION WHEN insufficient_privilege THEN NULL; END;
 BEGIN UPDATE public.payment_economy_ledger SET coin_delta=1; RAISE EXCEPTION 'service update ledger allowed'; EXCEPTION WHEN insufficient_privilege THEN NULL; END;
 BEGIN DELETE FROM public.payment_economy_ledger; RAISE EXCEPTION 'service delete ledger allowed'; EXCEPTION WHEN insufficient_privilege THEN NULL; END;
END$$; ROLLBACK;

\echo STRICT_MATRIX_PASS
