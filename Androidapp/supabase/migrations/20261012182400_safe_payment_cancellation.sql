-- Users can request the cancellation state, but cannot mark a provider payment
-- cancelled locally while it may still settle externally.
CREATE OR REPLACE FUNCTION public.cancel_my_transaction(p_external_reference TEXT)
RETURNS JSONB
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, public
AS $$
DECLARE
    v_tx RECORD;
BEGIN
    IF auth.uid() IS NULL THEN
        RETURN jsonb_build_object('success', FALSE, 'message', 'Authentication required');
    END IF;
    SELECT provider, status INTO v_tx
    FROM public.transactions
    WHERE external_reference = p_external_reference
      AND user_id = auth.uid()::TEXT
    FOR UPDATE;
    IF NOT FOUND THEN
        RETURN jsonb_build_object('success', FALSE, 'message', 'Transaction not found');
    END IF;
    IF v_tx.status <> 'pending' THEN
        RETURN jsonb_build_object('success', FALSE, 'message', 'Only a pending provider transaction can be checked');
    END IF;
    RETURN jsonb_build_object(
        'success', FALSE,
        'message', 'Cancellation must be confirmed by the payment provider; this request did not change the transaction.'
    );
END;
$$;
REVOKE ALL ON FUNCTION public.cancel_my_transaction(TEXT) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.cancel_my_transaction(TEXT) TO authenticated;
