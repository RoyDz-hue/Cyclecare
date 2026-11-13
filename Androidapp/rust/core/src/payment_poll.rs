//! Port of domain/payment/PaymentUseCase.pollPaymentStatus (main@31b88f5):
//! the payment-polling decision core - owner/session identity guard on every
//! step, wall-clock-bound loop (timeout coerceIn 0..300s), terminal-status
//! vocabulary, success -> verified-wallet recheck with owner match, and the
//! verbatim user-facing copy. Transport (checkStatus/refreshWallet), the
//! nano-clock, and delay are injected, exactly like the Kotlin constructor
//! seams. The pass-through methods (processCard/processPaypal/processCrypto/
//! initiateMpesa/cancelTransaction) are transport delegates - host glue, not
//! ported here.
//!
//! Locked by PaymentParity (parity/ai/careai, results_payment.tsv, p01-p16)
//! executed against the VERBATIM PaymentUseCase.kt with its own injected
//! checkStatus/refreshWallet seams and a no-op delay stub (delays recorded:
//! min(3000, remainingMillis) per pending poll).

/// Live snapshot of the auth state the guard re-reads at every step.
#[derive(Debug, Clone, PartialEq)]
pub struct PollAuthState {
    pub user_id: Option<String>,
    pub session_generation: i64,
    pub is_local_only: bool,
}

#[derive(Debug, Clone, PartialEq)]
pub enum PaymentPollResult {
    Success { profile_user_id: String, message: String },
    Failed { status: String, message: String },
    Timeout { message: String },
}

pub const TERMINAL_STATUSES: [&str; 7] =
    ["success", "failed", "cancelled", "expired", "auth_required", "refunded", "reversed"];

pub const ACCOUNT_CHANGED_MESSAGE: &str =
    "Your checkout account or secure session changed. Sign in to the original account and check this payment again.";
const VERIFIED_MESSAGE: &str =
    "Payment verified. Your latest wallet balance is shown; credits may first repay reversed purchases.";
const REFRESH_FAILED_MESSAGE: &str =
    "Payment was confirmed, but your balance could not be refreshed. Refresh your wallet or check this payment again; please do not pay again.";
const NOT_CONFIRMED_MESSAGE: &str =
    "Payment is not confirmed yet. Check this payment again before retrying; do not assume it failed.";

fn account_changed() -> PaymentPollResult {
    PaymentPollResult::Failed {
        status: "auth_required".to_string(),
        message: ACCOUNT_CHANGED_MESSAGE.to_string(),
    }
}

/// timeoutSeconds.coerceIn(0L, 300L) * 1_000_000_000L
pub fn max_poll_duration_nanos(timeout_seconds: i64) -> i64 {
    timeout_seconds.clamp(0, 300) * 1_000_000_000
}

/// The full poll loop. `auth_state` is re-invoked for every sameAccount()
/// check (production re-reads AuthPreferences); `nano_clock` is invoked at
/// exactly the System.nanoTime() call sites; `record_delay` observes each
/// min(3000, remainingMillis) sleep (host sleeps).
pub fn poll_payment_status(
    auth_state: &dyn Fn() -> PollAuthState,
    current_profile_user_id: &str,
    timeout_seconds: i64,
    mut check_status: impl FnMut() -> String,
    mut refresh_wallet: impl FnMut(&str) -> Option<String>,
    mut nano_clock: impl FnMut() -> i64,
    mut record_delay: impl FnMut(i64),
) -> PaymentPollResult {
    let owner = match auth_state().user_id {
        Some(o) => o,
        None => return account_changed(),
    };
    let session = auth_state().session_generation;
    let same_account = |owner: &str, session: i64| {
        let s = auth_state();
        !s.is_local_only && s.user_id.as_deref() == Some(owner) && s.session_generation == session
    };
    if !same_account(&owner, session) || current_profile_user_id != owner {
        return account_changed();
    }
    let start_time = nano_clock();
    let max_duration = max_poll_duration_nanos(timeout_seconds);
    let mut final_status = "pending".to_string();
    while nano_clock() - start_time < max_duration {
        if !same_account(&owner, session) {
            return account_changed();
        }
        final_status = check_status();
        if !same_account(&owner, session) {
            return account_changed();
        }
        if TERMINAL_STATUSES.contains(&final_status.as_str()) {
            break;
        }
        let remaining_millis = (max_duration - (nano_clock() - start_time)) / 1_000_000;
        if remaining_millis > 0 {
            record_delay(remaining_millis.min(3000));
        }
    }
    if !same_account(&owner, session) {
        return account_changed();
    }
    match final_status.as_str() {
        "success" => {
            let verified = refresh_wallet(&owner);
            if !same_account(&owner, session) {
                return account_changed();
            }
            match verified {
                Some(uid) if uid == owner => PaymentPollResult::Success {
                    profile_user_id: uid,
                    message: VERIFIED_MESSAGE.to_string(),
                },
                _ => PaymentPollResult::Timeout {
                    message: REFRESH_FAILED_MESSAGE.to_string(),
                },
            }
        }
        // Kotlin uppercase(): full Unicode mapping; statuses are ASCII.
        "failed" | "cancelled" | "expired" => PaymentPollResult::Failed {
            status: final_status.clone(),
            message: format!(
                "Payment status: {}. Transaction was not completed.",
                final_status.to_uppercase()
            ),
        },
        "refunded" | "reversed" => PaymentPollResult::Failed {
            status: final_status.clone(),
            message: format!(
                "Payment status: {}. Purchased credit may have been reversed. Refresh your wallet before spending or starting another purchase.",
                final_status.to_uppercase()
            ),
        },
        "auth_required" => account_changed(),
        _ => PaymentPollResult::Timeout {
            message: NOT_CONFIRMED_MESSAGE.to_string(),
        },
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::cell::Cell;
    use std::collections::VecDeque;

    fn stable_auth() -> impl Fn() -> PollAuthState {
        || PollAuthState {
            user_id: Some("owner-1".to_string()),
            session_generation: 7,
            is_local_only: false,
        }
    }

    /// Clock that advances 1s per read (delay is a no-op, as in the probe).
    fn clock() -> impl FnMut() -> i64 {
        let t = Cell::new(0i64);
        move || {
            let v = t.get();
            t.set(v + 1_000_000_000);
            v
        }
    }

    fn no_delays() -> impl FnMut(i64) {
        |_| {}
    }

    fn ok_refresh() -> impl FnMut(&str) -> Option<String> {
        |_| Some("owner-1".to_string())
    }

    fn statuses(v: Vec<&str>) -> impl FnMut() -> String {
        let mut q: VecDeque<String> = v.into_iter().map(String::from).collect();
        move || q.pop_front().unwrap_or_else(|| "pending".to_string())
    }

    #[test]
    fn poll_rows_locked_to_jvm() {
        // p01: immediate success, verified owner match
        assert_eq!(
            poll_payment_status(&stable_auth(), "owner-1", 30, statuses(vec!["success"]), ok_refresh(), clock(), no_delays()),
            PaymentPollResult::Success { profile_user_id: "owner-1".into(), message: VERIFIED_MESSAGE.into() }
        );
        // p02: success but refresh errors
        assert_eq!(
            poll_payment_status(&stable_auth(), "owner-1", 30, statuses(vec!["success"]), |_| None, clock(), no_delays()),
            PaymentPollResult::Timeout { message: REFRESH_FAILED_MESSAGE.into() }
        );
        // p03: success but verified userId mismatch
        assert_eq!(
            poll_payment_status(&stable_auth(), "owner-1", 30, statuses(vec!["success"]), |_| Some("other".to_string()), clock(), no_delays()),
            PaymentPollResult::Timeout { message: REFRESH_FAILED_MESSAGE.into() }
        );
        // p04/p15/p16: failed / cancelled / expired
        for (st, up) in [("failed", "FAILED"), ("cancelled", "CANCELLED"), ("expired", "EXPIRED")] {
            assert_eq!(
                poll_payment_status(&stable_auth(), "owner-1", 30, statuses(vec![st]), ok_refresh(), clock(), no_delays()),
                PaymentPollResult::Failed { status: st.into(), message: format!("Payment status: {up}. Transaction was not completed.") }
            );
        }
        // p05/p14: refunded / reversed
        for (st, up) in [("refunded", "REFUNDED"), ("reversed", "REVERSED")] {
            assert_eq!(
                poll_payment_status(&stable_auth(), "owner-1", 30, statuses(vec![st]), ok_refresh(), clock(), no_delays()),
                PaymentPollResult::Failed { status: st.into(), message: format!("Payment status: {up}. Purchased credit may have been reversed. Refresh your wallet before spending or starting another purchase.") }
            );
        }
        // p06: auth_required from the gateway
        assert_eq!(
            poll_payment_status(&stable_auth(), "owner-1", 30, statuses(vec!["auth_required"]), ok_refresh(), clock(), no_delays()),
            account_changed()
        );
        // p07: zero timeout -> loop never runs -> "pending" -> not-confirmed timeout
        assert_eq!(
            poll_payment_status(&stable_auth(), "owner-1", 0, statuses(vec!["processing"]), ok_refresh(), clock(), no_delays()),
            PaymentPollResult::Timeout { message: NOT_CONFIRMED_MESSAGE.into() }
        );
        // p13: negative timeout coerced to 0
        assert_eq!(
            poll_payment_status(&stable_auth(), "owner-1", -5, statuses(vec!["success"]), ok_refresh(), clock(), no_delays()),
            PaymentPollResult::Timeout { message: NOT_CONFIRMED_MESSAGE.into() }
        );
        // p08: timeout coerced to 300; pending, pending, success; two 3000ms delays
        let delays = std::rc::Rc::new(std::cell::RefCell::new(Vec::new()));
        let d2 = delays.clone();
        assert_eq!(
            poll_payment_status(&stable_auth(), "owner-1", 999, statuses(vec!["pending", "pending", "success"]), ok_refresh(), clock(), move |d| d2.borrow_mut().push(d)),
            PaymentPollResult::Success { profile_user_id: "owner-1".into(), message: VERIFIED_MESSAGE.into() }
        );
        assert_eq!(*delays.borrow(), vec![3000, 3000]);
        // p09: account changes mid-poll (after checkStatus returns)
        let flipped = Cell::new(false);
        let auth = || PollAuthState {
            user_id: Some(if flipped.get() { "someone-else" } else { "owner-1" }.to_string()),
            session_generation: 7,
            is_local_only: false,
        };
        assert_eq!(
            poll_payment_status(&auth, "owner-1", 30, {
                let f = &flipped;
                move || { f.set(true); "pending".to_string() }
            }, ok_refresh(), clock(), no_delays()),
            account_changed()
        );
        // p10: profile userId != owner at entry
        assert_eq!(
            poll_payment_status(&stable_auth(), "other", 30, statuses(vec!["success"]), ok_refresh(), clock(), no_delays()),
            account_changed()
        );
        // p11: userId null at entry
        let null_auth = || PollAuthState { user_id: None, session_generation: 7, is_local_only: false };
        assert_eq!(
            poll_payment_status(&null_auth, "owner-1", 30, statuses(vec!["success"]), ok_refresh(), clock(), no_delays()),
            account_changed()
        );
        // p12: local-only mode at entry
        let local_auth = || PollAuthState { user_id: Some("owner-1".to_string()), session_generation: 7, is_local_only: true };
        assert_eq!(
            poll_payment_status(&local_auth, "owner-1", 30, statuses(vec!["success"]), ok_refresh(), clock(), no_delays()),
            account_changed()
        );
        assert_eq!(max_poll_duration_nanos(999), 300_000_000_000);
        assert_eq!(max_poll_duration_nanos(-5), 0);
    }
}
