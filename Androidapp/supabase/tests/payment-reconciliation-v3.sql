-- Strict acceptance test: every negative case raises; no final-state print-only checks.
\set ON_ERROR_STOP on
BEGIN;
SET LOCAL ROLE service_role;
DO $$DECLARE r jsonb; iid uuid; tid uuid; b bigint; d bigint; BEGIN
 r:=public.get_payment_protocol_readiness(); IF r<>jsonb_build_object('schema_ready',true,'version',3) THEN RAISE EXCEPTION 'readiness failed'; END IF;
 r:=public.create_payment_intent('reconcile-owner','paypal','CC_reconcile-owner_v3',699,'USD',jsonb_build_object('package_id','pkg_sanctuary','coins_to_credit',850)); iid:=(r->>'payment_intent_id')::uuid; tid:=(r->>'transaction_id')::uuid;
 r:=public.create_payment_intent('reconcile-owner','paypal','CC_reconcile-owner_v3',699,'USD',jsonb_build_object('package_id','pkg_sanctuary','coins_to_credit',850)); IF r->>'decision'<>'duplicate' OR (r->>'payment_intent_id')::uuid<>iid THEN RAISE EXCEPTION 'idempotency failed'; END IF;
 PERFORM public.attach_provider_order(iid,'order-v3'); PERFORM public.attach_provider_order(iid,'order-v3');
 BEGIN PERFORM public.attach_provider_order(iid,'order-other'); RAISE EXCEPTION 'order replacement accepted'; EXCEPTION WHEN OTHERS THEN IF SQLERRM='order replacement accepted' THEN RAISE; END IF; END;
 r:=public.reconcile_verified_payment(iid,'capture','paypal','capture-v3','reconcile-owner',699,'USD','{}'); IF r->>'status'<>'success' THEN RAISE EXCEPTION 'capture failed'; END IF;
 r:=public.reconcile_verified_payment(iid,'refund','paypal','refund-v3','reconcile-owner',699,'USD','{}'); IF r->>'status'<>'refunded' THEN RAISE EXCEPTION 'refund failed'; END IF;
 r:=public.reconcile_verified_payment(iid,'capture','paypal','capture-v3','reconcile-owner',699,'USD','{}'); IF r->>'decision'<>'duplicate' OR r->>'status'<>'refunded' THEN RAISE EXCEPTION 'replay did not return terminal state'; END IF;
 BEGIN PERFORM public.reconcile_verified_payment(iid,'reversal','paypal','reversal-v3','reconcile-owner',699,'USD','{}'); RAISE EXCEPTION 'terminal reversal accepted'; EXCEPTION WHEN OTHERS THEN IF SQLERRM='terminal reversal accepted' THEN RAISE; END IF; END;
 BEGIN PERFORM public.reconcile_verified_payment(iid,'capture','paypal','wrong-owner','wrong-owner',699,'USD','{}'); RAISE EXCEPTION 'wrong owner accepted'; EXCEPTION WHEN OTHERS THEN IF SQLERRM='wrong owner accepted' THEN RAISE; END IF; END;
 SELECT coin_balance,payment_coin_debt INTO b,d FROM public.economy_profiles WHERE user_id='reconcile-owner'; IF d<>0 OR b<>120 THEN RAISE EXCEPTION 'refund accounting failed: %, %',b,d; END IF;
 IF (SELECT lifetime_earned FROM public.economy_profiles WHERE user_id='reconcile-owner')<>120 OR (SELECT rank FROM public.economy_profiles WHERE user_id='reconcile-owner')<>'LUMEN' THEN RAISE EXCEPTION 'refund did not restore baseline rank'; END IF;
END$$;
ROLLBACK;
