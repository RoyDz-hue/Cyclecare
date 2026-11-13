-- Add receipts atomically; preserve signatures, balances, grants and replay behavior. No backfill.
CREATE OR REPLACE FUNCTION public.apply_verified_payment(p_transaction_id uuid, p_expected_provider text, p_expected_amount numeric, p_expected_currency text, p_provider_reference text, p_provider_payload jsonb DEFAULT '{}'::jsonb)
 RETURNS jsonb
 LANGUAGE plpgsql
 SECURITY DEFINER
 SET search_path TO 'pg_catalog', 'public'
AS $function$
DECLARE
    v_tx RECORD;
    v_coins BIGINT;
    v_balance BIGINT;
    v_lifetime BIGINT;
    v_rank TEXT;
BEGIN
    IF auth.role() <> 'service_role' THEN RETURN jsonb_build_object('success', FALSE, 'message', 'Service role required'); END IF;
    SELECT * INTO v_tx FROM public.transactions WHERE id = p_transaction_id FOR UPDATE;
    IF NOT FOUND THEN RETURN jsonb_build_object('success', FALSE, 'message', 'Transaction not found'); END IF;
    IF v_tx.provider <> p_expected_provider OR v_tx.currency <> p_expected_currency OR v_tx.amount <> p_expected_amount THEN
        RETURN jsonb_build_object('success', FALSE, 'message', 'Transaction amount or provider mismatch');
    END IF;
    IF v_tx.status = 'success' THEN RETURN jsonb_build_object('success', TRUE, 'duplicate', TRUE, 'message', 'Already credited'); END IF;
    IF v_tx.status <> 'pending' THEN RETURN jsonb_build_object('success', FALSE, 'message', 'Transaction is not pending'); END IF;
    v_coins := NULLIF(v_tx.metadata->>'coins_to_credit', '')::BIGINT;
    IF v_coins IS NULL OR v_coins < 1 OR v_coins > 100000 THEN
        RETURN jsonb_build_object('success', FALSE, 'message', 'Invalid server package reward');
    END IF;
    INSERT INTO public.economy_profiles(user_id, coin_balance, lifetime_earned, lifetime_spent, tier, rank, unlocked_items)
    VALUES (v_tx.user_id, 120 + v_coins, 120 + v_coins, 0, 'FREE', 'LUMEN', '["cycle_coin"]'::JSONB)
    ON CONFLICT (user_id) DO UPDATE SET
        coin_balance = COALESCE(public.economy_profiles.coin_balance, 0) + v_coins,
        lifetime_earned = COALESCE(public.economy_profiles.lifetime_earned, 0) + v_coins,
        updated_at = NOW()
    RETURNING coin_balance, lifetime_earned INTO v_balance, v_lifetime;
    v_rank := CASE WHEN v_lifetime >= 7500 THEN 'FOUNDER' WHEN v_lifetime >= 3000 THEN 'NOCTURNE'
        WHEN v_lifetime >= 1200 THEN 'SOLARA' WHEN v_lifetime >= 500 THEN 'CELESTIA'
        WHEN v_lifetime >= 150 THEN 'AURELIA' ELSE 'LUMEN' END;
    UPDATE public.economy_profiles SET rank = v_rank, updated_at = NOW() WHERE user_id = v_tx.user_id;
    INSERT INTO public.coin_transactions(user_id,action,delta,balance_after,metadata)
    VALUES(v_tx.user_id,'PAYMENT_CREDIT',v_coins,v_balance,jsonb_build_object('source','apply_verified_payment','transaction_id',p_transaction_id,'provider',p_expected_provider,'provider_reference',p_provider_reference));
    UPDATE public.transactions SET status = 'success' , provider_reference = p_provider_reference,
        raw_payload = COALESCE(p_provider_payload, '{}'::JSONB), updated_at = NOW() WHERE id = p_transaction_id;
    RETURN jsonb_build_object('success', TRUE, 'new_balance', v_balance, 'lifetime_earned', v_lifetime, 'coins_credited', v_coins);
END;
$function$
;
CREATE OR REPLACE FUNCTION public.purchase_economy_item(p_item_id text)
 RETURNS jsonb
 LANGUAGE plpgsql
 SECURITY DEFINER
 SET search_path TO 'pg_catalog', 'public'
AS $function$
DECLARE
    v_user_id TEXT := auth.uid()::TEXT;
    v_item RECORD;
    v_profile RECORD;
    v_unlocked JSONB;
    v_is_premium BOOLEAN;
BEGIN
    IF v_user_id IS NULL THEN RETURN jsonb_build_object('success', FALSE, 'message', 'Authentication required'); END IF;
    IF public.is_cyclecare_account_restricted(v_user_id) THEN
        RETURN jsonb_build_object('success', FALSE, 'message', 'This account is suspended or banned');
    END IF;
    SELECT * INTO v_item FROM public.cosmetic_catalog WHERE item_id = p_item_id AND is_active = TRUE;
    IF NOT FOUND OR v_item.price_coins <= 0 THEN RETURN jsonb_build_object('success', FALSE, 'message', 'Item is not available'); END IF;
    SELECT * INTO v_profile FROM public.economy_profiles WHERE user_id = v_user_id FOR UPDATE;
    IF NOT FOUND THEN RETURN jsonb_build_object('success', FALSE, 'message', 'Economy profile not found'); END IF;
    v_unlocked := CASE
        WHEN jsonb_typeof(v_profile.unlocked_items) = 'array' THEN v_profile.unlocked_items
        ELSE '["cycle_coin"]'::JSONB
    END;
    IF v_unlocked ? p_item_id THEN
        RETURN jsonb_build_object('success', FALSE, 'message', 'Item already unlocked');
    END IF;
    v_is_premium := UPPER(COALESCE(v_profile.tier, 'FREE')) = 'PREMIUM';
    IF v_item.premium_included AND v_is_premium THEN
        UPDATE public.economy_profiles SET unlocked_items = v_unlocked || jsonb_build_array(p_item_id), updated_at = NOW()
        WHERE user_id = v_user_id;
        RETURN jsonb_build_object('success', TRUE, 'message', 'Included with Premium - item unlocked', 'item_id', p_item_id);
    END IF;
    IF COALESCE(v_profile.coin_balance, 0) < v_item.price_coins THEN
        RETURN jsonb_build_object('success', FALSE, 'message', 'Insufficient Cycle Coins');
    END IF;
    UPDATE public.economy_profiles SET coin_balance = COALESCE(coin_balance, 0) - v_item.price_coins,
        lifetime_spent = COALESCE(lifetime_spent, 0) + v_item.price_coins,
        unlocked_items = v_unlocked || jsonb_build_array(p_item_id),
        active_artifact = CASE WHEN v_item.kind = 'ARTIFACT' THEN p_item_id ELSE active_artifact END,
        active_frame = CASE WHEN v_item.kind = 'FRAME' THEN p_item_id ELSE active_frame END,
        active_theme = CASE WHEN v_item.kind = 'THEME' THEN p_item_id ELSE active_theme END,
        active_avatar_art = CASE WHEN v_item.kind = 'AVATAR_ART' THEN p_item_id ELSE active_avatar_art END,
        active_badge = CASE WHEN v_item.kind = 'BADGE' THEN p_item_id ELSE active_badge END,
        updated_at = NOW()
    WHERE user_id = v_user_id;
    INSERT INTO public.coin_transactions(user_id,action,delta,balance_after,metadata)
    VALUES(v_user_id,'PURCHASE',-v_item.price_coins,v_profile.coin_balance-v_item.price_coins,
      jsonb_build_object('source','purchase_economy_item','item_id',p_item_id,'paid_price_coins',v_item.price_coins));
    RETURN jsonb_build_object('success', TRUE, 'message', 'Item unlocked', 'item_id', p_item_id);
END;
$function$
;
CREATE OR REPLACE FUNCTION public.transfer_coins_atomic(p_sender_id text, p_recipient_email text, p_amount bigint)
 RETURNS jsonb
 LANGUAGE plpgsql
 SECURITY DEFINER
 SET search_path TO 'pg_catalog', 'public'
AS $function$
DECLARE
    v_sender_balance BIGINT;
    v_recipient_id TEXT;
    v_recipient_balance BIGINT;
    v_transfer_id UUID := gen_random_uuid();
    v_email TEXT := LOWER(TRIM(p_recipient_email));
BEGIN
    IF auth.uid() IS NULL OR auth.uid()::TEXT <> p_sender_id THEN
        RETURN jsonb_build_object('success', FALSE, 'message', 'Not authorized');
    END IF;
    IF public.is_cyclecare_account_restricted(p_sender_id) THEN
        RETURN jsonb_build_object('success', FALSE, 'message', 'This account is suspended or banned');
    END IF;
    IF p_amount IS NULL OR p_amount <= 0 OR p_amount > 1000000 THEN
        RETURN jsonb_build_object('success', FALSE, 'message', 'Invalid transfer amount');
    END IF;
    SELECT coin_balance INTO v_sender_balance FROM public.economy_profiles WHERE user_id = p_sender_id FOR UPDATE;
    IF v_sender_balance IS NULL OR v_sender_balance < p_amount THEN
        RETURN jsonb_build_object('success', FALSE, 'message', 'Insufficient coin balance');
    END IF;
    SELECT id::TEXT INTO v_recipient_id FROM auth.users WHERE LOWER(email) = v_email LIMIT 1;
    IF v_recipient_id IS NULL OR v_recipient_id = p_sender_id THEN
        RETURN jsonb_build_object('success', FALSE, 'message', 'Recipient account is invalid');
    END IF;
    IF public.is_cyclecare_account_restricted(v_recipient_id) THEN
        RETURN jsonb_build_object('success', FALSE, 'message', 'Recipient account cannot receive transfers');
    END IF;
    UPDATE public.economy_profiles SET coin_balance = coin_balance - p_amount,
        lifetime_spent = lifetime_spent + p_amount, updated_at = NOW() WHERE user_id = p_sender_id;
    INSERT INTO public.economy_profiles(user_id, coin_balance, lifetime_earned, lifetime_spent, unlocked_items)
    VALUES (v_recipient_id, 120 + p_amount, 120 + p_amount, 0, '["cycle_coin"]'::JSONB)
    ON CONFLICT (user_id) DO UPDATE SET coin_balance = public.economy_profiles.coin_balance + p_amount,
        lifetime_earned = public.economy_profiles.lifetime_earned + p_amount, updated_at = NOW() RETURNING coin_balance INTO v_recipient_balance;
    INSERT INTO public.coin_transactions(user_id,action,delta,balance_after,metadata) VALUES
    (p_sender_id,'TRANSFER_SENT',-p_amount,v_sender_balance-p_amount,jsonb_build_object('source','transfer_coins_atomic','transfer_id',v_transfer_id,'sender_id',p_sender_id,'receiver_id',v_recipient_id)),
    (v_recipient_id,'TRANSFER_RECEIVED',p_amount,v_recipient_balance,jsonb_build_object('source','transfer_coins_atomic','transfer_id',v_transfer_id,'sender_id',p_sender_id,'receiver_id',v_recipient_id));
    INSERT INTO public.user_notifications(user_id, target_identifier, title, body, type, priority, sender)
    VALUES (v_recipient_id, v_recipient_id, 'Cycle Coins Received!',
        'You have received ' || p_amount || ' Cycle Coins from a peer.', 'GIFT', 'HIGH', 'CYCLECARE_TRANSFER');
    RETURN jsonb_build_object('success', TRUE, 'message', 'Transfer completed',
        'transferred_amount', p_amount, 'remaining_balance', v_sender_balance - p_amount);
END;
$function$
;