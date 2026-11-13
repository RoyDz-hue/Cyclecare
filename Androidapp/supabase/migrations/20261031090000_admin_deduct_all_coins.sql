-- ==============================================================================
-- CycleCare Migration: 20261031090000_admin_deduct_all_coins.sql
-- Bulk coin deduction from every member, mirroring admin_gift_all_coins:
-- one atomic transaction, idempotent by run key, approved-snapshot binding
-- (lock + count match, abort on drift).
-- Insufficient funds: STRICT all-or-nothing. Every approved member must have a
-- wallet covering the full per-member amount, otherwise the batch aborts before
-- any write and says how many members could not cover it. This keeps the
-- reviewed count/total exact. NOTE: individual deductions clamp to the available
-- balance instead (admin_apply_action); the divergence is a product decision
-- flagged to the owner.
-- ==============================================================================

CREATE OR REPLACE FUNCTION public.admin_deduct_all_coins(p_delta numeric, p_reason text DEFAULT NULL::text, p_run_key text DEFAULT NULL::text, p_recipients text[] DEFAULT NULL::text[])
 RETURNS jsonb
 LANGUAGE plpgsql
 SECURITY DEFINER
 SET search_path TO 'pg_catalog', 'public'
AS $function$
DECLARE
    v_reason TEXT;
    v_recipients INTEGER;
    v_prev_details JSONB;
    v_approved TEXT[];
    v_requested INTEGER;
    v_matched INTEGER;
    v_short INTEGER;
BEGIN
    IF NOT public.is_cyclecare_admin() THEN RAISE EXCEPTION 'Administrator access required'; END IF;
    IF p_delta IS NULL OR p_delta < 1 OR p_delta > 10000 THEN
        RAISE EXCEPTION 'Deduction must be between 1 and 10,000 coins for each member';
    END IF;
    IF scale(p_delta) > 3 THEN
        RAISE EXCEPTION 'Cycle Coins support up to 3 decimal places';
    END IF;
    IF p_run_key IS NULL OR length(TRIM(p_run_key)) = 0 OR length(p_run_key) > 120 THEN
        RAISE EXCEPTION 'A deduction run key is required';
    END IF;
    IF p_recipients IS NOT NULL AND cardinality(p_recipients) = 0 THEN
        RAISE EXCEPTION 'The recipient list is empty';
    END IF;

    PERFORM pg_advisory_xact_lock(hashtext('admin_deduct_all_coins:' || p_run_key));
    SELECT details INTO v_prev_details FROM public.admin_audit_logs
     WHERE admin_action = 'ADMIN_DEDUCT_ALL_COINS' AND details->>'run_key' = p_run_key
     ORDER BY created_at ASC LIMIT 1;
    IF FOUND THEN
        RETURN jsonb_build_object('success', TRUE, 'duplicate', TRUE,
            'recipients', COALESCE((v_prev_details->>'recipients')::INTEGER, 0),
            'total_coins', COALESCE((v_prev_details->>'total_coins')::NUMERIC, 0),
            'reason', v_prev_details->>'reason');
    END IF;

    v_reason := NULLIF(LEFT(TRIM(COALESCE(p_reason, '')), 500), '');

    -- Atomic approved-set validation: lock the approved profile rows, then
    -- require the full approved set to still exist. Any drift aborts first.
    IF p_recipients IS NOT NULL THEN
        SELECT ARRAY(SELECT DISTINCT x FROM unnest(p_recipients) AS u(x)) INTO v_approved;
        v_requested := cardinality(v_approved);
        SELECT count(DISTINCT uid) INTO v_matched FROM (
            SELECT COALESCE(NULLIF(p.user_id::TEXT, ''), NULLIF(p.device_id, ''), p.id::TEXT) AS uid
            FROM public.profiles p
            WHERE COALESCE(NULLIF(p.user_id::TEXT, ''), NULLIF(p.device_id, ''), p.id::TEXT) = ANY(v_approved)
            FOR UPDATE OF p
        ) locked;
        IF v_matched <> v_requested THEN
            RAISE EXCEPTION 'The approved member set changed after review. No coins were moved; prepare a fresh plan.';
        END IF;
    END IF;

    WITH source AS (
        SELECT DISTINCT COALESCE(NULLIF(p.user_id::TEXT, ''), NULLIF(p.device_id, ''), p.id::TEXT) AS uid
        FROM public.profiles p
        WHERE p_recipients IS NULL
           OR COALESCE(NULLIF(p.user_id::TEXT, ''), NULLIF(p.device_id, ''), p.id::TEXT) = ANY(v_approved)
    ),
    wallets AS (
        SELECT s.uid, e.coin_balance
        FROM source s
        LEFT JOIN public.economy_profiles e ON e.user_id = s.uid
        FOR UPDATE OF e
    ),
    short AS (
        SELECT count(*) AS n FROM wallets WHERE COALESCE(coin_balance, 0) < p_delta
    )
    SELECT n INTO v_short FROM short;
    IF v_short > 0 THEN
        RAISE EXCEPTION '% member(s) cannot cover a % coin deduction each. No coins were moved; deduct a smaller amount or name members individually.', v_short, trim_scale(p_delta);
    END IF;

    WITH source AS (
        SELECT DISTINCT COALESCE(NULLIF(p.user_id::TEXT, ''), NULLIF(p.device_id, ''), p.id::TEXT) AS uid
        FROM public.profiles p
        WHERE p_recipients IS NULL
           OR COALESCE(NULLIF(p.user_id::TEXT, ''), NULLIF(p.device_id, ''), p.id::TEXT) = ANY(v_approved)
    ),
    updated AS (
        UPDATE public.economy_profiles e
        SET coin_balance = e.coin_balance - p_delta, updated_at = NOW()
        FROM source s
        WHERE e.user_id = s.uid
        RETURNING e.user_id, e.coin_balance
    ),
    ledger AS (
        INSERT INTO public.coin_transactions(user_id, action, delta, balance_after, metadata)
        SELECT user_id, 'ADMIN_DEDUCT_ALL', -p_delta, coin_balance,
               jsonb_build_object('run_key', p_run_key, 'reason', v_reason, 'admin_user_id', auth.uid()::TEXT)
        FROM updated
        RETURNING user_id
    ),
    notices AS (
        INSERT INTO public.user_notifications(user_id, target_identifier, title, body, type, priority, sender)
        SELECT user_id, user_id, 'Account Balance Adjustment',
               trim_scale(p_delta) || ' Cycle Coins were deducted from your balance by CycleCare Administration.',
               'REWARD', 'HIGH', 'CYCLECARE_OFFICIAL'
        FROM updated
        RETURNING user_id
    )
    SELECT count(*) INTO v_recipients FROM updated;

    INSERT INTO public.admin_audit_logs(admin_user_id, admin_action, target_user_id, details, performed_by)
    VALUES (auth.uid(), 'ADMIN_DEDUCT_ALL_COINS', '*',
        jsonb_build_object('run_key', p_run_key, 'delta_per_user', -p_delta, 'recipients', v_recipients,
            'total_coins', v_recipients * p_delta, 'reason', v_reason,
            'snapshot', p_recipients IS NOT NULL), auth.uid()::TEXT);

    RETURN jsonb_build_object('success', TRUE, 'duplicate', FALSE, 'recipients', v_recipients,
        'total_coins', v_recipients * p_delta, 'reason', v_reason);
END;
$function$;

-- Register the tool for the Admin Command agent (same RISKY class as bulk gifts).
INSERT INTO public.ai_tool_registry (tool_name, agent, kind, limits, is_active)
VALUES ('deduct_all_coins', 'admin_command', 'RISKY', '{}'::jsonb, TRUE)
ON CONFLICT (tool_name) DO UPDATE SET kind = EXCLUDED.kind, is_active = EXCLUDED.is_active;
