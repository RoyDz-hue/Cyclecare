-- ==============================================================================
-- CycleCare Migration: 20261031180000_economy_supply_engine.sql
-- Implements the approved 50/30/20 supply design (Oct 31, 2026):
--   10,000,000 paid / 6,000,000 rewards (5,000,000 claims + 500,000 starter +
--   500,000 gifts) / 4,000,000 reserve less past issuance, inside a permanent
--   20,000,000 lifetime minting cap.
-- Every coin mint now flows through one gate that clips awards to the remaining
-- line allowance and the remaining lifetime cap; caps are never overshot.
-- The opening count is a provisional recorded estimate from the surviving
-- records, never a recovered historical total and never proof that past
-- issuance fit inside the cap. Records are incomplete, so true historical
-- issuance is unknown; this figure is a planning assumption, fail-closed only
-- in the sense that the gate never claims historical compliance.
-- No member balance is reset, removed or frozen by this migration. Checkout
-- stays disabled; the paid reservation primitive is built for when it opens.
-- ==============================================================================

-- 1. Supply ledger tables

CREATE TABLE public.economy_supply_lines (
    line_key TEXT PRIMARY KEY,
    label TEXT NOT NULL,
    allowance NUMERIC NOT NULL CHECK (allowance >= 0),
    issued NUMERIC NOT NULL DEFAULT 0 CHECK (issued >= 0),
    reserved NUMERIC NOT NULL DEFAULT 0 CHECK (reserved >= 0)
);

CREATE TABLE public.economy_issuance (
    id SMALLINT PRIMARY KEY CHECK (id = 1),
    lifetime_cap NUMERIC NOT NULL CHECK (lifetime_cap > 0),
    opening_count NUMERIC NOT NULL CHECK (opening_count >= 0),
    opening_provisional BOOLEAN NOT NULL DEFAULT TRUE,
    total_minted NUMERIC NOT NULL CHECK (total_minted >= 0)
);

CREATE TABLE public.economy_supply_movements (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    line_key TEXT NOT NULL,
    delta NUMERIC NOT NULL,
    user_id TEXT,
    action TEXT NOT NULL,
    metadata JSONB NOT NULL DEFAULT '{}'::JSONB,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- Treasury holds coins recovered from member spending. Recovered coins were
-- minted once already, so treasury movements never touch total_minted.
CREATE TABLE public.economy_treasury (
    id SMALLINT PRIMARY KEY CHECK (id = 1),
    balance NUMERIC NOT NULL DEFAULT 0 CHECK (balance >= 0),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- Holds for paid packages before money is taken (used when checkout opens).
CREATE TABLE public.economy_paid_reservations (
    reservation_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id TEXT NOT NULL,
    coins NUMERIC NOT NULL CHECK (coins > 0 AND scale(coins) <= 3),
    status TEXT NOT NULL DEFAULT 'HELD' CHECK (status IN ('HELD', 'FULFILLED', 'RELEASED', 'EXPIRED')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    expires_at TIMESTAMPTZ NOT NULL,
    metadata JSONB NOT NULL DEFAULT '{}'::JSONB
);

ALTER TABLE public.economy_supply_lines ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.economy_issuance ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.economy_supply_movements ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.economy_treasury ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.economy_paid_reservations ENABLE ROW LEVEL SECURITY;
-- No member policies: supply accounting is reachable only through the
-- security-definer functions below.

-- Opening count: provisional recorded estimate only. SUM(lifetime_earned) is
-- what the surviving records show as earned; it double-counts peer transfers
-- and never reverses for removals, but missing or corrected records mean the
-- true historical minting total is UNKNOWN and could be higher or lower. This
-- figure is a planning assumption, not a recovered historical total, and must
-- not be presented as proof that past issuance stayed inside the cap. The cap
-- is enforced from deployment forward; historical compliance is unprovable.
INSERT INTO public.economy_issuance (id, lifetime_cap, opening_count, opening_provisional, total_minted)
SELECT 1, 20000000, COALESCE(SUM(COALESCE(lifetime_earned, 0)), 0), TRUE,
       COALESCE(SUM(COALESCE(lifetime_earned, 0)), 0)
FROM public.economy_profiles;

INSERT INTO public.economy_supply_lines (line_key, label, allowance, issued) VALUES
    ('claims',  'Daily claims (rewards pool)',        5000000,  0),
    ('starter', 'Starter grants (rewards pool)',       500000,  0),
    ('gifts',   'Gifts and promotions (rewards pool)', 500000,  0),
    ('paid',    'Paid packages and coin purchases',  10000000,  0),
    ('reserve', 'Reserve, less past issuance',
        GREATEST(0, 4000000 - (SELECT opening_count FROM public.economy_issuance WHERE id = 1)), 0);

INSERT INTO public.economy_treasury (id, balance) VALUES (1, 0);

-- 2. The minting gate: every new coin passes through here

CREATE FUNCTION public.mint_from_supply_line(
    p_line_key TEXT,
    p_user_id TEXT,
    p_amount NUMERIC,
    p_action TEXT,
    p_metadata JSONB DEFAULT '{}'::JSONB
) RETURNS NUMERIC
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, public
AS $$
DECLARE
    v_cap NUMERIC;
    v_total NUMERIC;
    v_allowance NUMERIC;
    v_issued NUMERIC;
    v_reserved NUMERIC;
    v_clip NUMERIC;
BEGIN
    IF p_amount IS NULL OR p_amount <= 0 OR scale(p_amount) > 3 THEN
        RAISE EXCEPTION 'Invalid mint amount';
    END IF;
    -- Lock order is always issuance row first, then the line row.
    SELECT lifetime_cap, total_minted INTO v_cap, v_total
      FROM public.economy_issuance WHERE id = 1 FOR UPDATE;
    IF NOT FOUND THEN RAISE EXCEPTION 'Economy issuance ledger is not initialized'; END IF;
    SELECT allowance, issued, reserved INTO v_allowance, v_issued, v_reserved
      FROM public.economy_supply_lines WHERE line_key = p_line_key FOR UPDATE;
    IF NOT FOUND THEN RAISE EXCEPTION 'Unknown supply line: %', p_line_key; END IF;
    -- Clip, never overshoot: an award that would cross its line allowance or
    -- the lifetime cap is reduced to what remains.
    v_clip := LEAST(p_amount, v_allowance - v_issued - v_reserved, v_cap - v_total);
    IF v_clip <= 0 THEN RETURN 0; END IF;
    UPDATE public.economy_supply_lines SET issued = issued + v_clip WHERE line_key = p_line_key;
    UPDATE public.economy_issuance SET total_minted = total_minted + v_clip WHERE id = 1;
    INSERT INTO public.economy_supply_movements (line_key, delta, user_id, action, metadata)
    VALUES (p_line_key, v_clip, p_user_id, p_action, COALESCE(p_metadata, '{}'::JSONB));
    RETURN v_clip;
END;
$$;
REVOKE ALL ON FUNCTION public.mint_from_supply_line(TEXT, TEXT, NUMERIC, TEXT, JSONB) FROM PUBLIC, anon, authenticated;

-- 3. Claims schedule: milestone step-downs with a 0.1/day floor
-- Base rate halves each time half of what remains in the claims line is issued
-- (2,500,000, then 3,750,000, then 4,375,000, ... issued). Rates round half
-- away from zero to 3 decimals, matching the decimal precision boundary. Claims
-- stop when the base rate falls below 0.1 a day.

CREATE FUNCTION public.current_claim_schedule()
RETURNS JSONB
LANGUAGE plpgsql
STABLE
SECURITY DEFINER
SET search_path = pg_catalog, public
AS $$
DECLARE
    v_issued NUMERIC;
    v_k INTEGER := 0;
    v_next NUMERIC;
    v_rate NUMERIC;
BEGIN
    SELECT issued INTO v_issued FROM public.economy_supply_lines WHERE line_key = 'claims';
    v_issued := COALESCE(v_issued, 0);
    LOOP
        v_next := 5000000 * (1 - pow(2::NUMERIC, -(v_k + 1)));
        EXIT WHEN v_issued < v_next OR v_k >= 9;
        v_k := v_k + 1;
    END LOOP;
    v_rate := ROUND(30 / pow(2::NUMERIC, v_k), 3);
    RETURN jsonb_build_object(
        'step', v_k,
        'base_rate', v_rate,
        'price_factor', v_rate / 30,
        'claims_issued', v_issued,
        'claims_open', v_rate >= 0.1
    );
END;
$$;
REVOKE ALL ON FUNCTION public.current_claim_schedule() FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.current_claim_schedule() TO authenticated;

-- 4. Daily claim: draws from the claims line at the current step rate

CREATE OR REPLACE FUNCTION public.verify_and_mine_coins(
    p_user_id TEXT,
    p_timezone_name TEXT DEFAULT 'UTC'
) RETURNS JSONB
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, public
AS $$
DECLARE
    v_profile RECORD;
    v_timezone TEXT;
    v_today DATE;
    v_multiplier NUMERIC := 1.0;
    v_schedule JSONB;
    v_base NUMERIC;
    v_awarded NUMERIC;
    v_starter NUMERIC;
    v_new_balance NUMERIC;
    v_new_lifetime NUMERIC;
    v_new_rank TEXT;
BEGIN
    IF auth.uid() IS NULL OR auth.uid()::TEXT <> p_user_id THEN
        RETURN jsonb_build_object('success', FALSE, 'message', 'Not authorized');
    END IF;
    PERFORM pg_advisory_xact_lock(hashtext('economy_wallet:' || p_user_id));
    IF NOT EXISTS (SELECT 1 FROM public.cyclecare_user_timezones WHERE user_id = p_user_id) THEN
        IF NOT EXISTS (SELECT 1 FROM pg_timezone_names WHERE name = p_timezone_name) THEN
            RETURN jsonb_build_object('success', FALSE, 'message', 'Invalid timezone');
        END IF;
        INSERT INTO public.cyclecare_user_timezones(user_id, timezone_name)
        VALUES (p_user_id, p_timezone_name)
        ON CONFLICT (user_id) DO NOTHING;
    END IF;
    SELECT timezone_name INTO v_timezone FROM public.cyclecare_user_timezones WHERE user_id = p_user_id;
    v_today := (NOW() AT TIME ZONE v_timezone)::DATE;

    -- First touch: create the wallet with a starter grant drawn from the
    -- starter line, clipped to what that line still holds.
    IF NOT EXISTS (SELECT 1 FROM public.economy_profiles WHERE user_id = p_user_id) THEN
        v_starter := public.mint_from_supply_line('starter', p_user_id, 120, 'STARTER_GRANT',
            jsonb_build_object('source', 'verify_and_mine_coins'));
        INSERT INTO public.economy_profiles (
            user_id, coin_balance, lifetime_earned, lifetime_spent, tier, rank, last_mined_date, unlocked_items
        ) VALUES (p_user_id, v_starter, v_starter, 0, 'FREE', 'LUMEN', NULL, '["cycle_coin"]'::JSONB)
        ON CONFLICT (user_id) DO NOTHING;
        IF v_starter > 0 THEN
            INSERT INTO public.coin_transactions(user_id, action, delta, balance_after, metadata)
            VALUES (p_user_id, 'STARTER_GRANT', v_starter, v_starter,
                jsonb_build_object('source', 'verify_and_mine_coins', 'budget_line', 'starter'));
        END IF;
    END IF;
    SELECT * INTO v_profile FROM public.economy_profiles WHERE user_id = p_user_id FOR UPDATE;

    IF v_profile.last_mined_date = v_today THEN
        RETURN jsonb_build_object('success', FALSE, 'message', 'You have already claimed your daily reward today. Returns at midnight.');
    END IF;

    v_schedule := public.current_claim_schedule();
    IF (v_schedule->>'claims_open')::BOOLEAN IS NOT TRUE THEN
        RETURN jsonb_build_object('success', FALSE, 'message', 'The daily rewards pool is resting right now. Coins you already have are safe.');
    END IF;

    CASE UPPER(COALESCE(v_profile.tier, 'FREE'))
        WHEN 'SUPPORTER' THEN v_multiplier := 2.0;
        WHEN 'PREMIUM' THEN v_multiplier := 3.0;
        ELSE v_multiplier := 1.0;
    END CASE;
    v_base := (v_schedule->>'base_rate')::NUMERIC;
    v_awarded := ROUND(v_base * v_multiplier, 3);
    v_awarded := public.mint_from_supply_line('claims', p_user_id, v_awarded, 'DAILY_ATTUNEMENT',
        jsonb_build_object('date', v_today, 'multiplier', v_multiplier, 'step', (v_schedule->>'step')::INTEGER));
    IF v_awarded <= 0 THEN
        RETURN jsonb_build_object('success', FALSE, 'message', 'The daily rewards pool is resting right now. Coins you already have are safe.');
    END IF;
    v_new_balance := COALESCE(v_profile.coin_balance, 0) + v_awarded;
    v_new_lifetime := COALESCE(v_profile.lifetime_earned, 0) + v_awarded;
    v_new_rank := CASE
        WHEN v_new_lifetime >= 7500 THEN 'FOUNDER'
        WHEN v_new_lifetime >= 3000 THEN 'NOCTURNE'
        WHEN v_new_lifetime >= 1200 THEN 'SOLARA'
        WHEN v_new_lifetime >= 500 THEN 'CELESTIA'
        WHEN v_new_lifetime >= 150 THEN 'AURELIA'
        ELSE 'LUMEN' END;

    INSERT INTO public.mining_records(user_id, claimed_date, amount_earned)
    VALUES (p_user_id, v_today, v_awarded);

    INSERT INTO public.coin_transactions(user_id, action, delta, balance_after, metadata)
    VALUES (p_user_id, 'DAILY_ATTUNEMENT', v_awarded, v_new_balance,
        jsonb_build_object('date', v_today, 'multiplier', v_multiplier, 'budget_line', 'claims'));

    UPDATE public.economy_profiles
    SET coin_balance = v_new_balance,
        lifetime_earned = v_new_lifetime,
        rank = v_new_rank,
        last_mined_date = v_today,
        updated_at = NOW()
    WHERE user_id = p_user_id;

    RETURN jsonb_build_object(
        'success', TRUE, 'message', 'Daily reward claimed successfully',
        'new_balance', v_new_balance,
        'lifetime_earned', v_new_lifetime,
        'new_rank', v_new_rank,
        'awarded', v_awarded,
        'awarded_coins', v_awarded,
        'mined_date', v_today
    );
END;
$$;
REVOKE ALL ON FUNCTION public.verify_and_mine_coins(TEXT, TEXT) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.verify_and_mine_coins(TEXT, TEXT) TO authenticated;

-- 5. Wallet creation on first open: starter grant through the starter line

CREATE OR REPLACE FUNCTION public.ensure_my_economy_profile()
RETURNS JSONB
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, public
AS $$
DECLARE
    v_user_id TEXT := auth.uid()::TEXT;
    v_starter NUMERIC;
BEGIN
    IF v_user_id IS NULL THEN
        RETURN jsonb_build_object('success', FALSE, 'message', 'Authentication required');
    END IF;
    PERFORM pg_advisory_xact_lock(hashtext('economy_wallet:' || v_user_id));
    IF NOT EXISTS (SELECT 1 FROM public.economy_profiles WHERE user_id = v_user_id) THEN
        v_starter := public.mint_from_supply_line('starter', v_user_id, 120, 'STARTER_GRANT',
            jsonb_build_object('source', 'ensure_my_economy_profile'));
        INSERT INTO public.economy_profiles (user_id, coin_balance, lifetime_earned, lifetime_spent, tier, rank, unlocked_items)
        VALUES (v_user_id, v_starter, v_starter, 0, 'FREE', 'LUMEN', '["cycle_coin"]'::JSONB)
        ON CONFLICT (user_id) DO NOTHING;
        IF v_starter > 0 THEN
            INSERT INTO public.coin_transactions(user_id, action, delta, balance_after, metadata)
            VALUES (v_user_id, 'STARTER_GRANT', v_starter, v_starter,
                jsonb_build_object('source', 'ensure_my_economy_profile', 'budget_line', 'starter'));
        END IF;
    END IF;
    RETURN jsonb_build_object('success', TRUE);
END;
$$;
REVOKE ALL ON FUNCTION public.ensure_my_economy_profile() FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.ensure_my_economy_profile() TO authenticated;

-- 6. Peer transfers: a brand-new recipient's starter grant comes from the
-- starter line; the transferred amount itself is not minted.

CREATE OR REPLACE FUNCTION public.transfer_coins_atomic(p_sender_id text, p_recipient_email text, p_amount numeric)
 RETURNS jsonb
 LANGUAGE plpgsql
 SECURITY DEFINER
 SET search_path TO 'pg_catalog', 'public'
AS $function$
DECLARE
    v_sender_balance NUMERIC;
    v_recipient_id TEXT;
    v_recipient_balance NUMERIC;
    v_starter NUMERIC := 0;
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
    IF scale(p_amount) > 3 THEN
        RETURN jsonb_build_object('success', FALSE, 'message', 'Cycle Coins support up to 3 decimal places');
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
    PERFORM pg_advisory_xact_lock(hashtext('economy_wallet:' || v_recipient_id));
    IF NOT EXISTS (SELECT 1 FROM public.economy_profiles WHERE user_id = v_recipient_id) THEN
        v_starter := public.mint_from_supply_line('starter', v_recipient_id, 120, 'STARTER_GRANT',
            jsonb_build_object('source', 'transfer_coins_atomic', 'transfer_id', v_transfer_id));
        INSERT INTO public.economy_profiles(user_id, coin_balance, lifetime_earned, lifetime_spent, unlocked_items)
        VALUES (v_recipient_id, v_starter + p_amount, v_starter + p_amount, 0, '["cycle_coin"]'::JSONB)
        RETURNING coin_balance INTO v_recipient_balance;
        IF v_starter > 0 THEN
            INSERT INTO public.coin_transactions(user_id, action, delta, balance_after, metadata)
            VALUES (v_recipient_id, 'STARTER_GRANT', v_starter, v_starter,
                jsonb_build_object('source', 'transfer_coins_atomic', 'transfer_id', v_transfer_id, 'budget_line', 'starter'));
        END IF;
    ELSE
        UPDATE public.economy_profiles SET coin_balance = public.economy_profiles.coin_balance + p_amount,
            lifetime_earned = public.economy_profiles.lifetime_earned + p_amount, updated_at = NOW()
        WHERE user_id = v_recipient_id RETURNING coin_balance INTO v_recipient_balance;
    END IF;
    INSERT INTO public.coin_transactions(user_id,action,delta,balance_after,metadata) VALUES
    (p_sender_id,'TRANSFER_SENT',-p_amount,v_sender_balance-p_amount,jsonb_build_object('source','transfer_coins_atomic','transfer_id',v_transfer_id,'sender_id',p_sender_id,'receiver_id',v_recipient_id)),
    (v_recipient_id,'TRANSFER_RECEIVED',p_amount,v_recipient_balance,jsonb_build_object('source','transfer_coins_atomic','transfer_id',v_transfer_id,'sender_id',p_sender_id,'receiver_id',v_recipient_id));
    INSERT INTO public.user_notifications(user_id, target_identifier, title, body, type, priority, sender)
    VALUES (v_recipient_id, v_recipient_id, 'Cycle Coins Received!',
        'You have received ' || trim_scale(p_amount) || ' Cycle Coins from a peer.', 'GIFT', 'HIGH', 'CYCLECARE_TRANSFER');
    RETURN jsonb_build_object('success', TRUE, 'message', 'Transfer completed',
        'transferred_amount', p_amount, 'remaining_balance', v_sender_balance - p_amount);
END;
$function$;
REVOKE ALL ON FUNCTION public.transfer_coins_atomic(TEXT, TEXT, NUMERIC) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.transfer_coins_atomic(TEXT, TEXT, NUMERIC) TO authenticated;

-- 7. Verified payments: credit from the paid line, fail closed when it cannot
-- cover the purchase (no partial fulfillment of a paid package). Checkout stays
-- disabled; when it opens it must hold a reservation before taking money.

CREATE OR REPLACE FUNCTION public.apply_verified_payment(p_transaction_id uuid, p_expected_provider text, p_expected_amount numeric, p_expected_currency text, p_provider_reference text, p_provider_payload jsonb DEFAULT '{}'::jsonb)
 RETURNS jsonb
 LANGUAGE plpgsql
 SECURITY DEFINER
 SET search_path TO 'pg_catalog', 'public'
AS $function$
DECLARE
    v_tx RECORD;
    v_coins NUMERIC;
    v_minted NUMERIC;
    v_starter NUMERIC := 0;
    v_balance NUMERIC;
    v_lifetime NUMERIC;
    v_rank TEXT;
    v_reservation UUID;
BEGIN
    IF auth.role() <> 'service_role' THEN RETURN jsonb_build_object('success', FALSE, 'message', 'Service role required'); END IF;
    SELECT * INTO v_tx FROM public.transactions WHERE id = p_transaction_id FOR UPDATE;
    IF NOT FOUND THEN RETURN jsonb_build_object('success', FALSE, 'message', 'Transaction not found'); END IF;
    IF v_tx.provider <> p_expected_provider OR v_tx.currency <> p_expected_currency OR v_tx.amount <> p_expected_amount THEN
        RETURN jsonb_build_object('success', FALSE, 'message', 'Transaction amount or provider mismatch');
    END IF;
    IF v_tx.status = 'success' THEN RETURN jsonb_build_object('success', TRUE, 'duplicate', TRUE, 'message', 'Already credited'); END IF;
    IF v_tx.status <> 'pending' THEN RETURN jsonb_build_object('success', FALSE, 'message', 'Transaction is not pending'); END IF;
    v_coins := NULLIF(v_tx.metadata->>'coins_to_credit', '')::NUMERIC;
    IF v_coins IS NULL OR v_coins < 1 OR v_coins > 100000 OR scale(v_coins) > 3 THEN
        RETURN jsonb_build_object('success', FALSE, 'message', 'Invalid server package reward');
    END IF;
    PERFORM pg_advisory_xact_lock(hashtext('economy_wallet:' || v_tx.user_id));
    -- Consume the checkout reservation first: its held coins are released back
    -- to the gate so this exact fulfillment can draw them, and the hold cannot
    -- be used twice. An expired or missing hold simply does not apply.
    v_reservation := NULLIF(v_tx.metadata->>'reservation_id', '')::UUID;
    IF v_reservation IS NOT NULL THEN
        UPDATE public.economy_paid_reservations SET status = 'EXPIRED'
        WHERE reservation_id = v_reservation AND status = 'HELD' AND expires_at < NOW();
        UPDATE public.economy_supply_lines l SET reserved = GREATEST(0, l.reserved - r.coins)
        FROM public.economy_paid_reservations r
        WHERE r.reservation_id = v_reservation AND r.status = 'HELD' AND r.user_id = v_tx.user_id
          AND l.line_key = 'paid';
        UPDATE public.economy_paid_reservations SET status = 'FULFILLED'
        WHERE reservation_id = v_reservation AND status = 'HELD' AND user_id = v_tx.user_id;
    END IF;
    -- Fail closed: a paid package is never partially fulfilled.
    v_minted := public.mint_from_supply_line('paid', v_tx.user_id, v_coins, 'PAYMENT_CREDIT',
        jsonb_build_object('source', 'apply_verified_payment', 'transaction_id', p_transaction_id, 'provider', p_expected_provider));
    IF v_minted < v_coins THEN
        -- Fail closed without stranding the charge: nothing is credited, the
        -- transaction stays pending for a verified credit-or-refund recovery,
        -- and the block is audited so operations can act on it.
        INSERT INTO public.admin_audit_logs(admin_user_id, admin_action, target_user_id, details, performed_by)
        VALUES (NULL, 'PAYMENT_CREDIT_BLOCKED', v_tx.user_id,
            jsonb_build_object('transaction_id', p_transaction_id, 'provider', p_expected_provider,
                'coins_requested', v_coins, 'reason', 'paid_line_or_cap_exhausted'), 'apply_verified_payment');
        RETURN jsonb_build_object('success', FALSE, 'credit_blocked', TRUE,
            'message', 'Coin delivery is delayed. No coins were credited; the payment stays pending for credit or refund.');
    END IF;
    IF NOT EXISTS (SELECT 1 FROM public.economy_profiles WHERE user_id = v_tx.user_id) THEN
        v_starter := public.mint_from_supply_line('starter', v_tx.user_id, 120, 'STARTER_GRANT',
            jsonb_build_object('source', 'apply_verified_payment', 'transaction_id', p_transaction_id));
        INSERT INTO public.economy_profiles(user_id, coin_balance, lifetime_earned, lifetime_spent, tier, rank, unlocked_items)
        VALUES (v_tx.user_id, v_starter + v_minted, v_starter + v_minted, 0, 'FREE', 'LUMEN', '["cycle_coin"]'::JSONB);
        IF v_starter > 0 THEN
            INSERT INTO public.coin_transactions(user_id, action, delta, balance_after, metadata)
            VALUES (v_tx.user_id, 'STARTER_GRANT', v_starter, v_starter,
                jsonb_build_object('source', 'apply_verified_payment', 'transaction_id', p_transaction_id, 'budget_line', 'starter'));
        END IF;
    ELSE
        UPDATE public.economy_profiles SET
            coin_balance = COALESCE(public.economy_profiles.coin_balance, 0) + v_minted,
            lifetime_earned = COALESCE(public.economy_profiles.lifetime_earned, 0) + v_minted,
            updated_at = NOW()
        WHERE user_id = v_tx.user_id;
    END IF;
    SELECT coin_balance, lifetime_earned INTO v_balance, v_lifetime FROM public.economy_profiles WHERE user_id = v_tx.user_id;
    v_rank := CASE WHEN v_lifetime >= 7500 THEN 'FOUNDER' WHEN v_lifetime >= 3000 THEN 'NOCTURNE'
        WHEN v_lifetime >= 1200 THEN 'SOLARA' WHEN v_lifetime >= 500 THEN 'CELESTIA'
        WHEN v_lifetime >= 150 THEN 'AURELIA' ELSE 'LUMEN' END;
    UPDATE public.economy_profiles SET rank = v_rank, updated_at = NOW() WHERE user_id = v_tx.user_id;
    INSERT INTO public.coin_transactions(user_id,action,delta,balance_after,metadata)
    VALUES(v_tx.user_id,'PAYMENT_CREDIT',v_minted,v_balance,jsonb_build_object('source','apply_verified_payment','transaction_id',p_transaction_id,'provider',p_expected_provider,'provider_reference',p_provider_reference,'budget_line','paid'));
    UPDATE public.transactions SET status = 'success' , provider_reference = p_provider_reference,
        raw_payload = COALESCE(p_provider_payload, '{}'::JSONB), updated_at = NOW() WHERE id = p_transaction_id;
    RETURN jsonb_build_object('success', TRUE, 'new_balance', v_balance, 'lifetime_earned', v_lifetime, 'coins_credited', v_minted);
END;
$function$
;

-- 8. Cosmetic purchases: prices step down with the claims schedule, and coins
-- spent route to the treasury instead of being destroyed (recirculation).
-- Already-burned coins stay burned; only new spending is rerouted.

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
    v_price NUMERIC;
    v_factor NUMERIC;
    v_treasury_balance NUMERIC;
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
    -- Price pairing: when claim rates halve, coin prices halve with them, so
    -- the time it takes to earn an item stays fair for later members.
    v_factor := (public.current_claim_schedule()->>'price_factor')::NUMERIC;
    v_price := ROUND(v_item.price_coins * v_factor, 3);
    IF v_price <= 0 THEN RETURN jsonb_build_object('success', FALSE, 'message', 'Item is not available'); END IF;
    IF COALESCE(v_profile.coin_balance, 0) < v_price THEN
        RETURN jsonb_build_object('success', FALSE, 'message', 'Insufficient Cycle Coins');
    END IF;
    UPDATE public.economy_profiles SET coin_balance = COALESCE(coin_balance, 0) - v_price,
        lifetime_spent = COALESCE(lifetime_spent, 0) + v_price,
        unlocked_items = v_unlocked || jsonb_build_array(p_item_id),
        active_artifact = CASE WHEN v_item.kind = 'ARTIFACT' THEN p_item_id ELSE active_artifact END,
        active_frame = CASE WHEN v_item.kind = 'FRAME' THEN p_item_id ELSE active_frame END,
        active_theme = CASE WHEN v_item.kind = 'THEME' THEN p_item_id ELSE active_theme END,
        active_avatar_art = CASE WHEN v_item.kind = 'AVATAR_ART' THEN p_item_id ELSE active_avatar_art END,
        active_badge = CASE WHEN v_item.kind = 'BADGE' THEN p_item_id ELSE active_badge END,
        updated_at = NOW()
    WHERE user_id = v_user_id;
    INSERT INTO public.coin_transactions(user_id,action,delta,balance_after,metadata)
    VALUES(v_user_id,'PURCHASE',-v_price,v_profile.coin_balance-v_price,
      jsonb_build_object('source','purchase_economy_item','item_id',p_item_id,'paid_price_coins',v_price,'list_price_coins',v_item.price_coins,'price_factor',v_factor));
    -- Recirculation: the spent coins land in the treasury, not the void.
    UPDATE public.economy_treasury SET balance = balance + v_price, updated_at = NOW()
    WHERE id = 1 RETURNING balance INTO v_treasury_balance;
    INSERT INTO public.coin_transactions(user_id,action,delta,balance_after,metadata)
    VALUES('CYCLECARE_TREASURY','TREASURY_CREDIT',v_price,v_treasury_balance,
      jsonb_build_object('source','purchase_economy_item','item_id',p_item_id,'payer',v_user_id));
    INSERT INTO public.economy_supply_movements(line_key, delta, user_id, action, metadata)
    VALUES('treasury', v_price, v_user_id, 'TREASURY_IN', jsonb_build_object('item_id', p_item_id, 'list_price_coins', v_item.price_coins));
    RETURN jsonb_build_object('success', TRUE, 'message', 'Item unlocked', 'item_id', p_item_id, 'paid_price_coins', v_price);
END;
$function$
;

-- 9. Treasury draw: funds rewards from recovered coins. Recovered coins are
-- finite; the reward budget must stay at or below actual spend inflows plus an
-- approved treasury draw. No earning rate is guaranteed forever.

CREATE FUNCTION public.treasury_fund_reward(p_user_id TEXT, p_amount NUMERIC, p_reason TEXT DEFAULT NULL)
RETURNS JSONB
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, public
AS $$
DECLARE
    v_treasury NUMERIC;
    v_balance NUMERIC;
BEGIN
    IF NOT public.is_cyclecare_admin() THEN RAISE EXCEPTION 'Administrator access required'; END IF;
    IF p_amount IS NULL OR p_amount <= 0 OR p_amount > 1000000 OR scale(p_amount) > 3 THEN
        RAISE EXCEPTION 'Treasury draw must be between 0 and 1,000,000 coins with up to 3 decimal places';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM public.economy_profiles WHERE user_id = p_user_id) THEN
        RAISE EXCEPTION 'Wallet not found for this account';
    END IF;
    SELECT balance INTO v_treasury FROM public.economy_treasury WHERE id = 1 FOR UPDATE;
    IF v_treasury IS NULL OR v_treasury < p_amount THEN
        RAISE EXCEPTION 'The treasury holds % coins; this draw needs %', v_treasury, p_amount;
    END IF;
    UPDATE public.economy_treasury SET balance = balance - p_amount, updated_at = NOW() WHERE id = 1;
    UPDATE public.economy_profiles SET coin_balance = COALESCE(coin_balance, 0) + p_amount,
        lifetime_earned = COALESCE(lifetime_earned, 0) + p_amount, updated_at = NOW()
    WHERE user_id = p_user_id RETURNING coin_balance INTO v_balance;
    INSERT INTO public.coin_transactions(user_id,action,delta,balance_after,metadata) VALUES
        ('CYCLECARE_TREASURY','TREASURY_DEBIT',-p_amount,v_treasury-p_amount,jsonb_build_object('source','treasury_fund_reward','receiver_id',p_user_id,'reason',p_reason)),
        (p_user_id,'TREASURY_REWARD',p_amount,v_balance,jsonb_build_object('source','treasury_fund_reward','reason',p_reason));
    INSERT INTO public.economy_supply_movements(line_key, delta, user_id, action, metadata)
    VALUES('treasury', -p_amount, p_user_id, 'TREASURY_DRAW', jsonb_build_object('reason', p_reason, 'admin_user_id', auth.uid()::TEXT));
    INSERT INTO public.admin_audit_logs(admin_user_id, admin_action, target_user_id, details, performed_by)
    VALUES (auth.uid(), 'ADMIN_TREASURY_FUND_REWARD', p_user_id,
        jsonb_build_object('amount', p_amount, 'reason', p_reason), auth.uid()::TEXT);
    RETURN jsonb_build_object('success', TRUE, 'amount', p_amount, 'new_balance', v_balance,
        'treasury_balance', v_treasury - p_amount);
END;
$$;
REVOKE ALL ON FUNCTION public.treasury_fund_reward(TEXT, NUMERIC, TEXT) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.treasury_fund_reward(TEXT, NUMERIC, TEXT) TO authenticated;

-- 10. Paid reservations: hold supply before taking money (for checkout, which
-- remains disabled until its payment safety review passes).

CREATE FUNCTION public.reserve_paid_coins(p_user_id TEXT, p_coins NUMERIC, p_hold_seconds INTEGER DEFAULT 900, p_metadata JSONB DEFAULT '{}'::JSONB)
RETURNS JSONB
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, public
AS $$
DECLARE
    v_available NUMERIC;
    v_held NUMERIC;
    v_id UUID;
BEGIN
    IF auth.role() <> 'service_role' THEN RETURN jsonb_build_object('success', FALSE, 'message', 'Service role required'); END IF;
    IF p_coins IS NULL OR p_coins < 1 OR p_coins > 100000 OR scale(p_coins) > 3 THEN
        RETURN jsonb_build_object('success', FALSE, 'message', 'Invalid reservation amount');
    END IF;
    p_hold_seconds := GREATEST(60, LEAST(COALESCE(p_hold_seconds, 900), 3600));
    PERFORM 1 FROM public.economy_issuance WHERE id = 1 FOR UPDATE;
    PERFORM 1 FROM public.economy_supply_lines WHERE line_key = 'paid' FOR UPDATE;
    -- Lazy expiry sweep: holds past their time release themselves here.
    UPDATE public.economy_paid_reservations SET status = 'EXPIRED' WHERE status = 'HELD' AND expires_at < NOW();
    SELECT COALESCE(SUM(coins), 0) INTO v_held FROM public.economy_paid_reservations WHERE status = 'HELD';
    UPDATE public.economy_supply_lines SET reserved = v_held WHERE line_key = 'paid';
    SELECT allowance - issued - reserved INTO v_available FROM public.economy_supply_lines WHERE line_key = 'paid';
    IF v_available < p_coins THEN
        RETURN jsonb_build_object('success', FALSE, 'message', 'Paid coin supply is temporarily unavailable');
    END IF;
    INSERT INTO public.economy_paid_reservations(user_id, coins, expires_at, metadata)
    VALUES (p_user_id, p_coins, NOW() + (p_hold_seconds * INTERVAL '1 second'), COALESCE(p_metadata, '{}'::JSONB))
    RETURNING reservation_id INTO v_id;
    UPDATE public.economy_supply_lines SET reserved = reserved + p_coins WHERE line_key = 'paid';
    RETURN jsonb_build_object('success', TRUE, 'reservation_id', v_id, 'coins', p_coins, 'expires_in_seconds', p_hold_seconds);
END;
$$;
REVOKE ALL ON FUNCTION public.reserve_paid_coins(TEXT, NUMERIC, INTEGER, JSONB) FROM PUBLIC, anon, authenticated;
GRANT EXECUTE ON FUNCTION public.reserve_paid_coins(TEXT, NUMERIC, INTEGER, JSONB) TO service_role;

CREATE FUNCTION public.release_paid_reservation(p_reservation_id UUID)
RETURNS JSONB
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, public
AS $$
DECLARE
    v_res RECORD;
BEGIN
    IF auth.role() <> 'service_role' THEN RETURN jsonb_build_object('success', FALSE, 'message', 'Service role required'); END IF;
    SELECT * INTO v_res FROM public.economy_paid_reservations WHERE reservation_id = p_reservation_id FOR UPDATE;
    IF NOT FOUND THEN RETURN jsonb_build_object('success', FALSE, 'message', 'Reservation not found'); END IF;
    IF v_res.status <> 'HELD' THEN RETURN jsonb_build_object('success', TRUE, 'duplicate', TRUE, 'status', v_res.status); END IF;
    UPDATE public.economy_paid_reservations SET status = 'RELEASED' WHERE reservation_id = p_reservation_id;
    UPDATE public.economy_supply_lines SET reserved = GREATEST(0, reserved - v_res.coins) WHERE line_key = 'paid';
    RETURN jsonb_build_object('success', TRUE, 'status', 'RELEASED');
END;
$$;
REVOKE ALL ON FUNCTION public.release_paid_reservation(UUID) FROM PUBLIC, anon, authenticated;
GRANT EXECUTE ON FUNCTION public.release_paid_reservation(UUID) TO service_role;

-- 11. Bulk gifts draw from the gifts line, all-or-nothing within the budget

CREATE OR REPLACE FUNCTION public.admin_gift_all_coins(
    p_delta NUMERIC,
    p_reason TEXT DEFAULT NULL,
    p_run_key TEXT DEFAULT NULL,
    p_recipients TEXT[] DEFAULT NULL
) RETURNS JSONB
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, public
AS $$
DECLARE
    v_reason TEXT;
    v_recipients INTEGER;
    v_prev_details JSONB;
    v_needed NUMERIC;
    v_available NUMERIC;
BEGIN
    IF NOT public.is_cyclecare_admin() THEN RAISE EXCEPTION 'Administrator access required'; END IF;
    IF p_delta IS NULL OR p_delta < 1 OR p_delta > 10000 THEN
        RAISE EXCEPTION 'Gift must be between 1 and 10,000 coins for each member';
    END IF;
    IF scale(p_delta) > 3 THEN
        RAISE EXCEPTION 'Cycle Coins support up to 3 decimal places';
    END IF;
    IF p_run_key IS NULL OR length(TRIM(p_run_key)) = 0 OR length(p_run_key) > 120 THEN
        RAISE EXCEPTION 'A gift run key is required';
    END IF;
    IF p_recipients IS NOT NULL AND cardinality(p_recipients) = 0 THEN
        RAISE EXCEPTION 'The recipient list is empty';
    END IF;

    PERFORM pg_advisory_xact_lock(hashtext('admin_gift_all_coins:' || p_run_key));
    SELECT details INTO v_prev_details FROM public.admin_audit_logs
     WHERE admin_action = 'ADMIN_GIFT_ALL_COINS' AND details->>'run_key' = p_run_key
     ORDER BY created_at ASC LIMIT 1;
    IF FOUND THEN
        RETURN jsonb_build_object('success', TRUE, 'duplicate', TRUE,
            'recipients', COALESCE((v_prev_details->>'recipients')::INTEGER, 0),
            'total_coins', COALESCE((v_prev_details->>'total_coins')::NUMERIC, 0),
            'reason', v_prev_details->>'reason');
    END IF;

    v_reason := NULLIF(LEFT(TRIM(COALESCE(p_reason, '')), 500), '');

    -- Budget check first: the run fits inside the gifts line and the lifetime
    -- cap or it does not happen at all (no silent partial runs).
    SELECT COUNT(*) INTO v_recipients
      FROM public.profiles p
     WHERE p_recipients IS NULL
        OR COALESCE(NULLIF(p.user_id::TEXT, ''), NULLIF(p.device_id, ''), p.id::TEXT) = ANY(p_recipients);
    v_needed := v_recipients * p_delta;
    PERFORM 1 FROM public.economy_issuance WHERE id = 1 FOR UPDATE;
    SELECT LEAST(allowance - issued - reserved,
                 (SELECT lifetime_cap - total_minted FROM public.economy_issuance WHERE id = 1))
      INTO v_available
      FROM public.economy_supply_lines WHERE line_key = 'gifts' FOR UPDATE;
    IF v_needed > v_available THEN
        RAISE EXCEPTION 'The gifts and promotions budget has % coins available; this run needs %',
            trim_scale(v_available), trim_scale(v_needed);
    END IF;

    WITH source AS (
        SELECT COALESCE(NULLIF(p.user_id::TEXT, ''), NULLIF(p.device_id, ''), p.id::TEXT) AS uid
        FROM public.profiles p
        WHERE p_recipients IS NULL
           OR COALESCE(NULLIF(p.user_id::TEXT, ''), NULLIF(p.device_id, ''), p.id::TEXT) = ANY(p_recipients)
    ),
    upserted AS (
        INSERT INTO public.economy_profiles(user_id, coin_balance, lifetime_earned, lifetime_spent, unlocked_items)
        SELECT uid, p_delta, p_delta, 0, '["cycle_coin"]'::JSONB
        FROM source
        ON CONFLICT (user_id) DO UPDATE
        SET coin_balance = COALESCE(economy_profiles.coin_balance, 0) + EXCLUDED.coin_balance,
            lifetime_earned = COALESCE(economy_profiles.lifetime_earned, 0) + EXCLUDED.coin_balance,
            updated_at = NOW()
        RETURNING user_id, coin_balance
    ),
    ledger AS (
        INSERT INTO public.coin_transactions(user_id, action, delta, balance_after, metadata)
        SELECT user_id, 'ADMIN_GIFT_ALL', p_delta, coin_balance,
               jsonb_build_object('run_key', p_run_key, 'reason', v_reason, 'admin_user_id', auth.uid()::TEXT, 'budget_line', 'gifts')
        FROM upserted
        RETURNING user_id
    ),
    notices AS (
        INSERT INTO public.user_notifications(user_id, target_identifier, title, body, type, priority, sender)
        SELECT user_id, user_id, 'Cycle Coins Received!',
               'You have received ' || trim_scale(p_delta) || ' Cycle Coins from CycleCare Administration.',
               'REWARD', 'HIGH', 'CYCLECARE_OFFICIAL'
        FROM upserted
        RETURNING user_id
    )
    SELECT count(*) INTO v_recipients FROM upserted;

    UPDATE public.economy_supply_lines SET issued = issued + v_recipients * p_delta WHERE line_key = 'gifts';
    UPDATE public.economy_issuance SET total_minted = total_minted + v_recipients * p_delta WHERE id = 1;
    INSERT INTO public.economy_supply_movements(line_key, delta, user_id, action, metadata)
    VALUES ('gifts', v_recipients * p_delta, NULL, 'ADMIN_GIFT_ALL',
        jsonb_build_object('run_key', p_run_key, 'recipients', v_recipients, 'admin_user_id', auth.uid()::TEXT));

    INSERT INTO public.admin_audit_logs(admin_user_id, admin_action, target_user_id, details, performed_by)
    VALUES (auth.uid(), 'ADMIN_GIFT_ALL_COINS', '*',
        jsonb_build_object('run_key', p_run_key, 'delta_per_user', p_delta, 'recipients', v_recipients,
            'total_coins', v_recipients * p_delta, 'reason', v_reason,
            'snapshot', p_recipients IS NOT NULL), auth.uid()::TEXT);

    RETURN jsonb_build_object('success', TRUE, 'duplicate', FALSE, 'recipients', v_recipients,
        'total_coins', v_recipients * p_delta, 'reason', v_reason);
END;
$$;
REVOKE ALL ON FUNCTION public.admin_gift_all_coins(NUMERIC, TEXT, TEXT, TEXT[]) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.admin_gift_all_coins(NUMERIC, TEXT, TEXT, TEXT[]) TO authenticated;

-- 12. Admin single-wallet coin adjustments: positive adjustments draw from the
-- gifts line (clipped to what remains); debits stay as they are.

CREATE OR REPLACE FUNCTION public.admin_apply_action(p_action text, p_target_user_id text, p_payload jsonb DEFAULT '{}'::jsonb)
 RETURNS jsonb
 LANGUAGE plpgsql
 SECURITY DEFINER
 SET search_path TO 'pg_catalog', 'public'
AS $function$
DECLARE
    v_action TEXT := UPPER(TRIM(p_action));
    v_input_target TEXT := NULLIF(TRIM(p_target_user_id), '');
    v_target TEXT;
    v_status TEXT;
    v_reason TEXT;
    v_until TIMESTAMPTZ;
    v_delta_numeric NUMERIC;
    v_delta NUMERIC;
    v_minted NUMERIC;
    v_balance NUMERIC;
    v_deducted NUMERIC;
    v_percent NUMERIC;
    v_artifact TEXT;
    v_rarity TEXT;
    v_artifacts JSONB;
    v_title TEXT;
    v_body TEXT;
    v_kind TEXT;
    v_tier TEXT;
    v_message_id UUID;
    v_rows INTEGER;
    v_payload_details JSONB;
BEGIN
    IF NOT public.is_cyclecare_admin() THEN RAISE EXCEPTION 'Administrator access required'; END IF;
    IF v_input_target IS NULL OR length(v_input_target) > 200 THEN RAISE EXCEPTION 'A valid target account is required'; END IF;
    IF p_payload IS NULL OR jsonb_typeof(p_payload) <> 'object' THEN RAISE EXCEPTION 'Invalid action payload'; END IF;
    SELECT COALESCE(NULLIF(user_id::TEXT, ''), NULLIF(device_id, ''), id::TEXT)
      INTO v_target
      FROM public.profiles
     WHERE user_id::TEXT = v_input_target OR device_id = v_input_target OR id::TEXT = v_input_target
     ORDER BY CASE WHEN user_id::TEXT = v_input_target THEN 0 ELSE 1 END
     LIMIT 1;
    IF v_target IS NULL THEN RAISE EXCEPTION 'Target CycleCare profile was not found'; END IF;

    IF v_action = 'STATUS' THEN
        v_status := UPPER(COALESCE(p_payload->>'status', ''));
        IF v_status NOT IN ('ACTIVE', 'SUSPENDED', 'BANNED') THEN RAISE EXCEPTION 'Invalid account status'; END IF;
        v_reason := NULLIF(LEFT(TRIM(COALESCE(p_payload->>'reason', '')), 500), '');
        v_until := NULL;
        IF v_status = 'SUSPENDED' THEN
            IF COALESCE((p_payload->>'duration_hours')::INTEGER, 0) NOT BETWEEN 1 AND 8760 THEN
                RAISE EXCEPTION 'Suspension duration must be between 1 hour and 1 year';
            END IF;
            v_until := NOW() + ((p_payload->>'duration_hours')::INTEGER * INTERVAL '1 hour');
        END IF;
        UPDATE public.profiles SET status = v_status, suspension_reason = v_reason,
            suspended_until = v_until, updated_at = NOW()
        WHERE user_id::TEXT = v_target OR device_id = v_target OR id::TEXT = v_target;
        GET DIAGNOSTICS v_rows = ROW_COUNT;
        UPDATE public.economy_profiles SET status = v_status, suspension_reason = v_reason,
            suspended_until = v_until, updated_at = NOW() WHERE user_id = v_target;
        INSERT INTO public.user_notifications(user_id, target_identifier, title, body, type, priority, sender)
        VALUES (v_target, v_target,
            CASE WHEN v_status = 'BANNED' THEN 'Account Notice: Account Banned' ELSE 'Account Notice: Account Status Updated' END,
            CASE WHEN v_reason IS NULL THEN 'Your account status has been updated to ' || v_status || '.' ELSE 'Admin notice: ' || v_reason END,
            'SECURITY', 'HIGH', 'CYCLECARE_OFFICIAL');
        v_payload_details := jsonb_build_object('status', v_status, 'reason', v_reason, 'suspended_until', v_until, 'profiles_updated', v_rows);

    ELSIF v_action = 'TIER' THEN
        v_tier := UPPER(COALESCE(p_payload->>'tier', ''));
        IF v_tier NOT IN ('FREE', 'SUPPORTER', 'PREMIUM') THEN RAISE EXCEPTION 'Invalid tier'; END IF;
        UPDATE public.economy_profiles SET tier = v_tier, updated_at = NOW() WHERE user_id = v_target;
        IF NOT FOUND THEN RAISE EXCEPTION 'Wallet not found for this account'; END IF;
        INSERT INTO public.user_notifications(user_id, target_identifier, title, body, type, priority, sender)
        VALUES (v_target, v_target, 'Membership Updated',
            'Your CycleCare membership is now ' || v_tier || '.', 'SYSTEM', 'HIGH', 'CYCLECARE_OFFICIAL');
        v_payload_details := jsonb_build_object('tier', v_tier);

    ELSIF v_action = 'COINS' THEN
        v_delta_numeric := COALESCE((p_payload->>'delta')::NUMERIC, 0);
        IF v_delta_numeric = 0 OR scale(v_delta_numeric) > 3 OR ABS(v_delta_numeric) > 1000000 THEN
            RAISE EXCEPTION 'Coin adjustment must be a non-zero amount with up to 3 decimal places, up to 1,000,000';
        END IF;
        v_delta := v_delta_numeric;
        IF v_delta > 0 THEN
            v_minted := public.mint_from_supply_line('gifts', v_target, v_delta, 'ADMIN_CREDIT',
                jsonb_build_object('source', 'admin_apply_action'));
            IF v_minted <= 0 THEN RAISE EXCEPTION 'The gifts and promotions budget is exhausted'; END IF;
            v_delta := v_minted;
        END IF;
        SELECT coin_balance INTO v_balance FROM public.economy_profiles WHERE user_id = v_target FOR UPDATE;
        IF NOT FOUND THEN
            IF v_delta < 0 THEN RAISE EXCEPTION 'Cannot debit a user with no wallet'; END IF;
            INSERT INTO public.economy_profiles(user_id, coin_balance, lifetime_earned, lifetime_spent, unlocked_items)
            VALUES (v_target, v_delta, v_delta, 0, '["cycle_coin"]'::JSONB);
            v_balance := v_delta;
        ELSE
            IF v_delta < 0 THEN v_delta := -LEAST(COALESCE(v_balance, 0), ABS(v_delta)); END IF;
            UPDATE public.economy_profiles SET coin_balance = COALESCE(coin_balance, 0) + v_delta,
                lifetime_earned = COALESCE(lifetime_earned, 0) + GREATEST(v_delta, 0), updated_at = NOW()
            WHERE user_id = v_target RETURNING coin_balance INTO v_balance;
        END IF;
        v_reason := NULLIF(LEFT(TRIM(COALESCE(p_payload->>'reason', '')), 500), '');
        INSERT INTO public.user_notifications(user_id, target_identifier, title, body, type, priority, sender)
        VALUES (v_target, v_target, CASE WHEN v_delta >= 0 THEN 'Cycle Coins Received!' ELSE 'Account Balance Adjustment' END,
            CASE WHEN v_delta >= 0 THEN 'You have received ' || trim_scale(v_delta) || ' Cycle Coins from CycleCare Administration.'
                 ELSE 'Your balance was adjusted by ' || trim_scale(v_delta) || ' Cycle Coins. ' || COALESCE(v_reason, '') END,
            CASE WHEN v_delta >= 0 THEN 'REWARD' ELSE 'PENALTY' END, 'HIGH', 'CYCLECARE_OFFICIAL');
        INSERT INTO public.coin_transactions(user_id, action, delta, balance_after, metadata)
        VALUES (v_target, CASE WHEN v_delta >= 0 THEN 'ADMIN_CREDIT' ELSE 'ADMIN_DEBIT' END,
            v_delta, v_balance, jsonb_build_object('reason', v_reason, 'source', 'admin_apply_action', 'budget_line', CASE WHEN v_delta >= 0 THEN 'gifts' ELSE NULL END));
        v_payload_details := jsonb_build_object('delta', v_delta, 'new_balance', v_balance, 'reason', v_reason);

    ELSIF v_action = 'GIFT' THEN
        v_artifact := NULLIF(LEFT(TRIM(COALESCE(p_payload->>'artifact_name', '')), 120), '');
        v_rarity := NULLIF(LEFT(TRIM(COALESCE(p_payload->>'artifact_rarity', 'Rare')), 40), '');
        IF v_artifact NOT IN ('Luminescent Moon', 'Golden Blossom', 'Rose Obelisk', 'Dual Eclipse', 'Celestial Tiara') THEN
            RAISE EXCEPTION 'Artifact is not in the approved catalog';
        END IF;
        SELECT artifacts INTO v_artifacts FROM public.economy_profiles WHERE user_id = v_target FOR UPDATE;
        IF NOT FOUND THEN
            v_artifacts := jsonb_build_array(jsonb_build_object('name', v_artifact, 'rarity', COALESCE(v_rarity, 'Rare'),
                'giftedAt', NOW(), 'giftedBy', 'CycleCare Administration'));
            INSERT INTO public.economy_profiles(user_id, artifacts, unlocked_items) VALUES (v_target, v_artifacts, '["cycle_coin"]'::JSONB);
        ELSE
            v_artifacts := CASE WHEN jsonb_typeof(COALESCE(v_artifacts, '[]'::JSONB)) = 'array' THEN COALESCE(v_artifacts, '[]'::JSONB) ELSE '[]'::JSONB END
                || jsonb_build_array(jsonb_build_object('name', v_artifact, 'rarity', COALESCE(v_rarity, 'Rare'),
                    'giftedAt', NOW(), 'giftedBy', 'CycleCare Administration'));
            UPDATE public.economy_profiles SET artifacts = v_artifacts, updated_at = NOW() WHERE user_id = v_target;
        END IF;
        INSERT INTO public.user_notifications(user_id, target_identifier, title, body, type, priority, sender)
        VALUES (v_target, v_target, 'Exclusive Gift: ' || v_artifact,
            'CycleCare Administration has gifted you a collectible artifact: ' || v_artifact || '!', 'GIFT', 'HIGH', 'CYCLECARE_OFFICIAL');
        v_payload_details := jsonb_build_object('artifact', v_artifact, 'rarity', v_rarity);

    ELSIF v_action = 'PENALTY' THEN
        v_percent := COALESCE((p_payload->>'percent')::NUMERIC, 0);
        IF v_percent <= 0 OR v_percent > 100 THEN RAISE EXCEPTION 'Penalty must be between 1 and 100 percent'; END IF;
        SELECT coin_balance INTO v_balance FROM public.economy_profiles WHERE user_id = v_target FOR UPDATE;
        IF NOT FOUND THEN RAISE EXCEPTION 'Wallet not found'; END IF;
        v_deducted := FLOOR(COALESCE(v_balance, 0) * v_percent / 100);
        UPDATE public.economy_profiles SET coin_balance = GREATEST(0, COALESCE(coin_balance, 0) - v_deducted),
            last_penalized_at = NOW(), updated_at = NOW() WHERE user_id = v_target RETURNING coin_balance INTO v_balance;
        INSERT INTO public.user_notifications(user_id, target_identifier, title, body, type, priority, sender)
        VALUES (v_target, v_target, 'Account Inactivity / Policy Notice',
            'An inactivity adjustment of ' || trim_scale(v_deducted) || ' coins (' || trim_scale(v_percent) || '%) was applied.', 'WARNING', 'NORMAL', 'CYCLECARE_OFFICIAL');
        v_payload_details := jsonb_build_object('deducted', v_deducted, 'new_balance', v_balance, 'percent', v_percent);

    ELSIF v_action = 'CUSTOM_MSG' THEN
        v_title := NULLIF(LEFT(TRIM(COALESCE(p_payload->>'title', '')), 160), '');
        v_body := NULLIF(LEFT(TRIM(COALESCE(p_payload->>'body', '')), 4000), '');
        v_kind := UPPER(COALESCE(p_payload->>'type', 'SYSTEM'));
        IF v_title IS NULL OR v_body IS NULL THEN RAISE EXCEPTION 'Message title and body are required'; END IF;
        IF v_kind NOT IN ('SYSTEM','SECURITY','REWARD','GIFT','WARNING','ALERT','SYSTEM_ANNOUNCEMENT','CARE_TIP','PROMO') THEN
            RAISE EXCEPTION 'Invalid notification type';
        END IF;
        INSERT INTO public.in_house_messages(title, message, message_type, target_audience, target_user_id, sent_by)
        VALUES (v_title, v_body, v_kind, 'INDIVIDUAL', v_target, auth.uid()::TEXT) RETURNING id INTO v_message_id;
        INSERT INTO public.user_notifications(message_id, user_id, target_identifier, title, body, type, priority, sender)
        VALUES (v_message_id, v_target, v_target, v_title, v_body, v_kind, 'HIGH', 'CYCLECARE_OFFICIAL');
        v_payload_details := jsonb_build_object('title', v_title, 'message_id', v_message_id);
    ELSE
        RAISE EXCEPTION 'Unsupported admin action';
    END IF;

    INSERT INTO public.admin_audit_logs(admin_user_id, admin_action, target_user_id, details, performed_by)
    VALUES (auth.uid(), 'ADMIN_' || v_action, v_target, COALESCE(v_payload_details, '{}'::JSONB), auth.uid()::TEXT);
    RETURN jsonb_build_object('success', TRUE, 'action', v_action, 'target_user_id', v_target, 'details', v_payload_details);
END;
$function$;

-- 13. Retire the dead multiplier config: earning rates follow the published
-- supply schedule with milestone step-downs; this legacy key drives nothing.
-- Checkout provider flags cannot be switched on while the safety review block
-- stands, and the stored flags now match the enforced block.

CREATE OR REPLACE FUNCTION public.admin_update_app_config(p_key text, p_value jsonb)
 RETURNS jsonb
 LANGUAGE plpgsql
 SECURITY DEFINER
 SET search_path TO 'pg_catalog', 'public'
AS $function$
DECLARE
    v_key TEXT := LOWER(TRIM(COALESCE(p_key, '')));
    v_pkg RECORD;
    v_ids TEXT[];
    v_num INTEGER;
BEGIN
    IF NOT public.is_cyclecare_admin() THEN RAISE EXCEPTION 'Administrator access required'; END IF;
    IF v_key = '' OR length(v_key) > 60 THEN RAISE EXCEPTION 'A valid config key is required'; END IF;
    IF p_value IS NULL THEN RAISE EXCEPTION 'A config value is required'; END IF;

    IF v_key = 'coin_packages' THEN
        IF jsonb_typeof(p_value) <> 'array' OR jsonb_array_length(p_value) NOT BETWEEN 1 AND 20 THEN
            RAISE EXCEPTION 'Coin packages must be a list of 1 to 20 offers'; END IF;
        FOR v_pkg IN SELECT * FROM jsonb_array_elements(p_value) LOOP
            IF jsonb_typeof(v_pkg) <> 'object' THEN RAISE EXCEPTION 'Every package must be an object'; END IF;
            IF COALESCE(v_pkg->>'id','') !~ '^[a-z0-9_]{3,40}$' THEN RAISE EXCEPTION 'Every package needs a lowercase id like pkg_starter'; END IF;
            IF LENGTH(COALESCE(v_pkg->>'title','')) NOT BETWEEN 1 AND 60 THEN RAISE EXCEPTION 'Every package needs a title'; END IF;
            IF COALESCE((v_pkg->>'coins')::NUMERIC, -1) < 1 OR (v_pkg->>'coins')::NUMERIC > 1000000 OR (v_pkg->>'coins')::NUMERIC <> TRUNC((v_pkg->>'coins')::NUMERIC) THEN
                RAISE EXCEPTION 'Package % must grant between 1 and 1,000,000 whole coins', v_pkg->>'id'; END IF;
            IF COALESCE(COALESCE(v_pkg->>'bonusCoins', v_pkg->>'bonus_coins')::NUMERIC, 0) < 0 OR COALESCE(COALESCE(v_pkg->>'bonusCoins', v_pkg->>'bonus_coins')::NUMERIC, 0) > 1000000 THEN
                RAISE EXCEPTION 'Package % bonus must be between 0 and 1,000,000', v_pkg->>'id'; END IF;
            IF COALESCE(COALESCE(v_pkg->>'priceKes', v_pkg->>'price_kes')::NUMERIC, -1) < 0 OR COALESCE(COALESCE(v_pkg->>'priceKes', v_pkg->>'price_kes')::NUMERIC) > 1000000 THEN
                RAISE EXCEPTION 'Package % KES price cannot be negative', v_pkg->>'id'; END IF;
            IF COALESCE(COALESCE(v_pkg->>'priceUsd', v_pkg->>'price_usd')::NUMERIC, -1) < 0 OR COALESCE(COALESCE(v_pkg->>'priceUsd', v_pkg->>'price_usd')::NUMERIC) > 10000 THEN
                RAISE EXCEPTION 'Package % USD price cannot be negative', v_pkg->>'id'; END IF;
            IF COALESCE(COALESCE(v_pkg->>'priceKes', v_pkg->>'price_kes')::NUMERIC, 0) = 0 AND COALESCE(COALESCE(v_pkg->>'priceUsd', v_pkg->>'price_usd')::NUMERIC, 0) = 0 THEN
                RAISE EXCEPTION 'Package % needs a price in KES or USD', v_pkg->>'id'; END IF;
        END LOOP;
        SELECT array_agg(DISTINCT elem->>'id'), COUNT(DISTINCT elem->>'id') INTO v_ids, v_num FROM jsonb_array_elements(p_value) elem;
        IF v_num <> jsonb_array_length(p_value) THEN RAISE EXCEPTION 'Package ids must be unique'; END IF;
    ELSIF v_key = 'economy_multipliers' THEN
        RAISE EXCEPTION 'Earning rates now follow the published supply schedule with milestone step-downs; the legacy multiplier config no longer drives earnings and is retired';
    ELSIF v_key = 'payment_gateway' THEN
        IF jsonb_typeof(p_value) <> 'object' THEN RAISE EXCEPTION 'Payment gateway settings must be an object'; END IF;
        IF p_value ? 'minDepositKes' AND (COALESCE((p_value->>'minDepositKes')::NUMERIC, -1) < 0 OR (p_value->>'minDepositKes')::NUMERIC > 1000000) THEN
            RAISE EXCEPTION 'Minimum deposit must be between 0 and 1,000,000 KES'; END IF;
        IF p_value ? 'activeMpesaProvider' AND UPPER(p_value->>'activeMpesaProvider') <> 'PAYHERO' THEN
            RAISE EXCEPTION 'Only PAYHERO is an approved M-Pesa provider'; END IF;
        IF COALESCE((p_value->>'mpesaEnabled')::BOOLEAN, FALSE) IS TRUE OR COALESCE((p_value->>'okxEnabled')::BOOLEAN, FALSE) IS TRUE THEN
            RAISE EXCEPTION 'Checkout providers stay disabled until the payment safety review passes';
        END IF;
    ELSIF v_key = 'ai_config' THEN
        IF jsonb_typeof(p_value) <> 'object' THEN RAISE EXCEPTION 'AI settings must be an object'; END IF;
        IF p_value ? 'temperature' AND ((p_value->>'temperature')::NUMERIC < 0 OR (p_value->>'temperature')::NUMERIC > 2) THEN
            RAISE EXCEPTION 'Temperature must be between 0 and 2'; END IF;
        IF p_value ? 'model' AND LENGTH(p_value->>'model') > 100 THEN RAISE EXCEPTION 'Model name is too long'; END IF;
    ELSE
        RAISE EXCEPTION 'This configuration key is not editable from the admin panel';
    END IF;

    INSERT INTO public.app_config(key, value, updated_at) VALUES (v_key, p_value, NOW())
    ON CONFLICT (key) DO UPDATE SET value = EXCLUDED.value, updated_at = NOW();

    INSERT INTO public.admin_audit_logs(admin_user_id, admin_action, target_user_id, details, performed_by)
    VALUES (auth.uid(), 'ADMIN_UPDATE_APP_CONFIG', v_key, jsonb_build_object('key', v_key, 'value', p_value), auth.uid()::TEXT);
    RETURN jsonb_build_object('success', TRUE, 'key', v_key, 'value', p_value);
END;
$function$;

-- Align the stored payment flags with the enforced block: the app blocks every
-- checkout provider until the payment safety review passes; the config now says
-- the same thing.
UPDATE public.app_config
SET value = value || '{"mpesaEnabled": false, "okxEnabled": false}'::jsonb,
    updated_at = NOW()
WHERE key = 'payment_gateway';

-- PostgREST picks up the new tables and signatures.
NOTIFY pgrst, 'reload schema';
