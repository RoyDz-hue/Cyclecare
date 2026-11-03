//! Port of the remaining ViewModel sync/payment composition logic
//! (main 31b88f5): the wiring AROUND the already-ported domain policies
//! (account_start.rs, reconnect_policy.rs, sync_gate.rs). Coroutine jobs,
//! repository IO and service calls stay host glue.
//! Line citations grep-verified against main 31b88f5.

/// :1269-1271 state cleared by cancelActivePayment plus its two literals.
pub const PAYMENT_CANCELLED_MESSAGE: &str = "Payment request cancelled.";
pub const MPESA_CANCELLED_MESSAGE: &str = "M-Pesa transaction cancelled.";

/// :1286-1289 manual sync blocked by pending conflicts.
pub const MANUAL_SYNC_BLOCKED_MESSAGE: &str =
    "Review the pending sync choices in Settings first. Nothing was uploaded.";

/// :1277 triggerManualSync reentry guard.
pub fn manual_sync_reentry_guard(is_syncing: bool) -> bool {
    !is_syncing
}

/// :1296-1297 / :1484-1485 sessionNeedsReauthentication after a sync: cleared
/// on success, else sticky-OR with requiresAuthentication.
pub fn reauth_after_sync(
    is_success: bool,
    requires_authentication: bool,
    previous: bool,
) -> bool {
    if is_success {
        false
    } else {
        requires_authentication || previous
    }
}

/// :1440-1444 autoSyncSilently local-only early return: not checking, and
/// onboarding shown iff settings say incomplete.
pub fn autosync_local_only_return(settings_onboarding_completed: bool) -> (bool, bool) {
    // (isCheckingCloudProfile, showOnboarding)
    (false, !settings_onboarding_completed)
}

/// :1450 the `checking` flag.
pub fn autosync_checking(is_checking_cloud_profile: bool, cloud_profile_check_failed: bool) -> bool {
    is_checking_cloud_profile || cloud_profile_check_failed
}

/// :1447 account-refresh coalescing: an active job covers new entrants.
pub fn account_refresh_coalesce(job_active: bool) -> bool {
    !job_active
}

/// :1461-1462 the remote hasRemoteProfile probe runs only while checking AND
/// local profile/onboarding is incomplete; otherwise `remote` defaults true.
pub fn needs_remote_profile_probe(
    checking: bool,
    local_profile_present: bool,
    settings_onboarding_completed: bool,
) -> bool {
    checking && (!local_profile_present || !settings_onboarding_completed)
}

/// :1465-1470 destination-driven UI flags applied when `checking` was true.
/// Returns (cloudProfileCheckFailed, showOnboarding, syncGatedForConflicts).
pub fn destination_ui_flags(
    destination_is_retry: bool,
    destination_is_onboarding: bool,
    local_state_conflicted: bool,
) -> (bool, bool, bool) {
    (
        destination_is_retry,
        destination_is_onboarding,
        local_state_conflicted,
    )
}

/// :1412-1413 trailing reconnect eligibility.
pub fn trailing_eligible(is_local_only_mode: bool, user_id_present: bool) -> bool {
    !is_local_only_mode && user_id_present
}

/// :1402-1404 trailing-job coalescing: an active trailing job covers new
/// schedule requests.
pub fn trailing_schedule_coalesce(trailing_job_active: bool) -> bool {
    !trailing_job_active
}

/// :1410 owner captured at schedule time must still match after the delay.
pub fn trailing_owner_still_current(owner_at_schedule: &str, current_owner: &str) -> bool {
    owner_at_schedule == current_owner
}

/// :1420-1426 decision dispatch for the trailing run.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum TrailingAction {
    /// Decision.Run -> runReconnectWorkOnce()
    Run,
    /// Decision.Throttled + allowReschedule -> reschedule with retryAfterMs
    /// and allowReschedule = true.
    Reschedule,
    /// Decision.Throttled without allowReschedule, or Decision.Skip.
    Nothing,
}

pub fn trailing_dispatch(
    decision_is_run: bool,
    decision_is_throttled: bool,
    allow_reschedule: bool,
) -> TrailingAction {
    if decision_is_run {
        TrailingAction::Run
    } else if decision_is_throttled && allow_reschedule {
        TrailingAction::Reschedule
    } else {
        TrailingAction::Nothing
    }
}

/// :1399-1400 runReconnectWorkOnce success: BOTH the offline-claim reconcile
/// and the verified-profile fetch must be Success.
pub fn reconnect_success(reconciled_ok: bool, verified_ok: bool) -> bool {
    reconciled_ok && verified_ok
}

/// :1431-1435 onForeground: autoSync only after initial auth completed.
pub fn on_foreground_autosync(has_completed_initial_auth: bool) -> bool {
    has_completed_initial_auth
}
