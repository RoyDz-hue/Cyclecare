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

/// :1657-1672 completePasswordSetup result mapping: Success clears the
/// required flag and shows the message; VerificationRequired/Error only
/// update the message.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum PasswordSetupResult {
    Success,
    VerificationRequired,
    Error,
}

/// Returns (isPasswordSetupRequired, message field name) — message content
/// comes from the service result, not literals here.
pub fn password_setup_state(result: PasswordSetupResult) -> bool {
    // isPasswordSetupRequired after applying the result
    !matches!(result, PasswordSetupResult::Success)
}

/// :1431-1435 onForeground: autoSync only after initial auth completed.
pub fn on_foreground_autosync(has_completed_initial_auth: bool) -> bool {
    has_completed_initial_auth
}

/// :2153-2156 getPredictionForDate gate: no cycle anchor -> null prediction.
pub fn prediction_gate(has_cycle_anchor: bool) -> bool {
    has_cycle_anchor
}

/// Gift-resolution chain in refreshNotifications (:1820-1836, main 31b88f5).
/// `find_item` is EconomyCatalog.findItem (host delegate); `fallback_item` is
/// EconomyCatalog.ALL_ITEMS[1]; `unread_gift` is the first unread
/// GIFT-typed notification's (title, body). Kotlin semantics preserved:
/// when lastGiftName is non-blank the catalog lookup runs and ANY miss falls
/// back to ALL_ITEMS[1]; the notification-text branch runs ONLY when
/// lastGiftName was blank (giftItem is already non-null otherwise);
/// contains(..., ignoreCase = true) uses Java-style per-char folding.
pub fn resolve_gift_item(
    last_gift_name: Option<&str>,
    find_item: impl Fn(&str) -> Option<String>,
    fallback_item: impl Fn() -> String,
    unread_gift: Option<(&str, &str)>,
    all_items: &[(&str, &str)],
) -> Option<String> {
    if let Some(name) = last_gift_name {
        if !crate::kotlin_text::kotlin_is_blank(name) {
            return Some(find_item(name).unwrap_or_else(|| fallback_item()));
        }
    }
    if let Some((title, body)) = unread_gift {
        // :1832-1835 ALL_ITEMS.find returns the item itself (first match in
        // catalog order), not a name to re-resolve.
        let hit = all_items.iter().find(|(n, _)| {
            crate::kotlin_text::kotlin_contains_ignore_case(body, n)
                || crate::kotlin_text::kotlin_contains_ignore_case(title, n)
        });
        return Some(hit.map(|(_, item)| item.to_string()).unwrap_or_else(|| fallback_item()));
    }
    None
}

/// :1838-1846 state assembly applies ONLY when the verified fetch succeeds;
/// else notifications list alone is replaced (:1847-1849).
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum RefreshNotificationsOutcome {
    /// Verified profile Success: full assembly (list, profile, READY wallet,
    /// ban/suspension flags, notice, gift fallback to previous when None).
    VerifiedApplied,
    /// Non-success: notifications list only.
    ListOnly,
}

pub fn refresh_notifications_outcome(verified_success: bool) -> RefreshNotificationsOutcome {
    if verified_success {
        RefreshNotificationsOutcome::VerifiedApplied
    } else {
        RefreshNotificationsOutcome::ListOnly
    }
}

/// :1811 local-only / no-user early return.
pub fn refresh_notifications_entry(is_local_only_mode: bool, user_id_present: bool) -> bool {
    !is_local_only_mode && user_id_present
}

/// :1818 owner/session recheck after the fetches.
pub fn refresh_still_current(
    owner: &str,
    session: i64,
    current_owner: &str,
    current_session: i64,
) -> bool {
    owner == current_owner && session == current_session
}

/// Gift unread-notification detection (:1830): type equals "GIFT"
/// ignoreCase and not read.
pub fn is_unread_gift_notification(is_read: bool, notif_type: &str) -> bool {
    !is_read && crate::kotlin_text::kotlin_equals_ignore_case(notif_type, "GIFT")
}

// ---------------------------------------------------------------------------
// Composed sequences (injected effects). These drive the actual source
// control flow; the scalar helpers above remain their corpus-locked pieces.
// ---------------------------------------------------------------------------

use crate::account_start::{decide as account_start_decide, AccountStartDestination, LocalDataState};
use crate::reconnect_policy::Decision as TrailingDecision;
use crate::sync_gate::{local_data_state, local_valid, may_upload, LocalRead};

/// Host effects for runReconnectWorkOnce (:1376-1400).
pub trait ReconnectEffects {
    fn effective_user_id(&self) -> &str;
    fn session_generation(&self) -> i64;
    /// :1379 runCatching { reconcile }.getOrNull(); true iff Success.
    fn reconcile_pending_offline_claims(&mut self) -> bool;
    /// :1382 apply reconciled profile + wallet READY.
    fn apply_reconciled(&mut self);
    /// :1385 runCatching { fetchVerifiedProfile }.getOrNull(); true iff Success.
    fn fetch_verified_profile(&mut self) -> bool;
    /// :1388-1394 apply verified profile, READY, ban/suspension flags, notice.
    fn apply_verified(&mut self);
    /// :1398 runCatching { refreshNotifications() }.
    fn refresh_notifications_safely(&mut self);
}

/// :1376-1400 composed. Source semantics preserved INCLUDING the early
/// `return true` on owner/session staleness mid-run (:1380, :1386): a stale
/// run reports success-of-no-work, not failure.
pub fn run_reconnect_work_once<E: ReconnectEffects>(fx: &mut E) -> bool {
    let owner = fx.effective_user_id().to_string();
    let session = fx.session_generation();
    let reconciled_ok = fx.reconcile_pending_offline_claims();
    if owner != fx.effective_user_id() || session != fx.session_generation() {
        return true;
    }
    if reconciled_ok {
        fx.apply_reconciled();
    }
    let verified_ok = fx.fetch_verified_profile();
    if owner != fx.effective_user_id() || session != fx.session_generation() {
        return true;
    }
    if verified_ok {
        fx.apply_verified();
    }
    fx.refresh_notifications_safely();
    reconciled_ok && verified_ok
}

/// Host effects for the trailing reconnect job (:1402-1428).
pub trait TrailingEffects {
    fn trailing_job_active(&self) -> bool;
    /// Records the job reassignment (both the coalesced schedule AND the
    /// :1423-1424 direct reassignment inside the throttled arm).
    fn spawn_trailing(&mut self, delay_ms: i64, allow_reschedule: bool);
    fn effective_user_id(&self) -> &str;
    fn is_local_only(&self) -> bool;
    fn user_id_present(&self) -> bool;
    fn is_online(&self) -> bool;
    fn now_ms(&self) -> i64;
    /// The ported ReconnectWorkPolicy.decideTrailing (reconnect_policy.rs).
    fn decide_trailing(&mut self, online: bool, eligible: bool, owner: &str, now_ms: i64) -> TrailingDecision;
    fn run_work(&mut self) -> bool;
}

/// :1402-1405 coalesced schedule; an active job covers new requests.
/// Returns true when a new job was launched.
pub fn schedule_trailing<E: TrailingEffects>(fx: &mut E, delay_ms: i64, allow_reschedule: bool) -> bool {
    if fx.trailing_job_active() {
        return false;
    }
    fx.spawn_trailing(delay_ms, allow_reschedule);
    true
}

/// :1407-1428 the launched job body after the host's delay elapsed.
/// `owner_at_schedule` was captured at launch time (:1409).
pub fn execute_trailing<E: TrailingEffects>(fx: &mut E, owner_at_schedule: &str, allow_reschedule: bool) {
    if owner_at_schedule != fx.effective_user_id() {
        return; // :1412
    }
    let eligible = !fx.is_local_only() && fx.user_id_present(); // :1413
    let online = fx.is_online(); // :1414
    let now = fx.now_ms();
    match fx.decide_trailing(online, eligible, owner_at_schedule, now) {
        TrailingDecision::Run => {
            fx.run_work();
        }
        TrailingDecision::Throttled { retry_after_ms } => {
            // :1422-1425 DIRECT reassignment bypassing the active-job check,
            // with the decision's retryAfterMs and allowReschedule = true.
            if allow_reschedule {
                fx.spawn_trailing(retry_after_ms, true);
            }
        }
        TrailingDecision::Skip => {}
    }
}

/// What the composed autoSync read from local storage (:1453-1456).
pub struct LocalSnapshot {
    pub profile_present: bool,
    pub profile_completed: bool,
    pub settings_onboarding_completed: bool,
}

/// What executeSync restored, re-read from the repository (:1481-1483).
pub struct RestoredState {
    pub settings_onboarding_completed: bool,
    pub profile_present: bool,
}

/// Host effects for autoSyncSilently (:1439-1494).
pub trait AutoSyncEffects {
    fn is_local_only(&self) -> bool;
    fn user_id_present(&self) -> bool;
    fn effective_user_id(&self) -> &str;
    fn session_generation(&self) -> i64;
    fn is_checking_cloud_profile(&self) -> bool;
    fn cloud_profile_check_failed(&self) -> bool;
    fn session_needs_reauthentication(&self) -> bool;
    /// :1441-1443 local-only early return flags. The source reads
    /// uiState.settings.isOnboardingCompleted - ALREADY-LOADED UI STATE, no
    /// storage read - so this is a plain bool, never a fallible read.
    fn ui_settings_onboarding_completed(&self) -> bool;
    /// :1441-1443 local-only early return flags.
    fn apply_local_only_return(&mut self, show_onboarding: bool);
    /// :1446 refreshWalletNow(force).
    fn refresh_wallet_now(&mut self, force: bool);
    /// :1447 coalescing.
    fn account_refresh_job_active(&self) -> bool;
    /// :1451 checking -> flags on.
    fn set_checking_flags(&mut self);
    /// :1453 runCatching local reads; None = read failed (UNKNOWN).
    fn read_local(&mut self) -> Option<LocalSnapshot>;
    /// :1457 runCatching conflicts read; Err = failed (UNKNOWN, fail-safe).
    fn pending_conflicts_nonempty(&mut self) -> Result<bool, ()>;
    /// :1461-1463 withTimeoutOrNull(20s) hasRemoteProfile; only invoked when
    /// needs_remote_profile_probe says so. None = timeout (source nullable).
    fn has_remote_profile(&mut self) -> Option<bool>;
    /// :1465-1470 destination-driven flags (checking branch only). The
    /// source also writes settings=localSettings (CycleSettings() default on
    /// read failure) and onboardingProfile=localProfile (null on failure)
    /// into the same uiState copy; the host gets the snapshot as-is.
    fn apply_destination_flags(&mut self, check_failed: bool, show_onboarding: bool, sync_gated: bool, local: Option<&LocalSnapshot>);
    /// :1479 executeSync.
    fn execute_sync(&mut self) -> SyncResult;
    /// :1481-1483 restored repository reads AFTER a passed second recheck.
    fn read_restored_after_sync(&mut self) -> RestoredState;
    /// :1484 runCatching { isCycleCareAdmin() }.getOrDefault(false).
    fn fetch_is_admin(&mut self) -> bool;
    /// :1485-1491 apply result + restored reads + admin + reauth composition.
    fn apply_sync_result(&mut self, result: &SyncResult, restored: &RestoredState, is_admin: bool, session_needs_reauthentication: bool);
    /// :1493 updateCalculations(restoredSettings, currentDate, selectedDate).
    fn update_calculations(&mut self, restored: &RestoredState);
}

pub struct SyncResult {
    pub is_success: bool,
    pub requires_authentication: bool,
}

/// :1439-1494 composed. Preservation gates from sync_gate.rs: upload only
/// when the destination is HOME and no conflicts are unresolved; conflicted
/// users still reach HOME, only the upload is gated.
pub fn auto_sync_silently<E: AutoSyncEffects>(fx: &mut E, force_wallet_refresh: bool) {
    if fx.is_local_only() || !fx.user_id_present() {
        // :1440-1444 - UI state only, no storage read.
        fx.apply_local_only_return(!fx.ui_settings_onboarding_completed());
        return;
    }
    fx.refresh_wallet_now(force_wallet_refresh); // :1446
    if fx.account_refresh_job_active() {
        return; // :1447
    }
    let owner = fx.effective_user_id().to_string();
    let session = fx.session_generation();
    let checking = fx.is_checking_cloud_profile() || fx.cloud_profile_check_failed(); // :1450
    if checking {
        fx.set_checking_flags(); // :1451
    }
    let local = fx.read_local(); // :1453
    let conflicts = fx.pending_conflicts_nonempty(); // :1457
    let local_read = match &local {
        Some(snap) => LocalRead::Ok {
            profile_completed: snap.profile_completed,
            settings_onboarding_completed: snap.settings_onboarding_completed,
        },
        None => LocalRead::Failed,
    };
    let state = local_data_state(&local_read, conflicts); // :1458-1464
    let valid = local_valid(state, &local_read); // :1465-1466 (source lines :1466-1467)
    let remote: Option<bool> = if checking
        && !local
            .as_ref()
            .map(|s| s.profile_present && s.settings_onboarding_completed)
            .unwrap_or(false)
    {
        fx.has_remote_profile() // :1461-1463, null on timeout
    } else {
        Some(true)
    };
    if owner != fx.effective_user_id() || session != fx.session_generation() {
        return; // :1464
    }
    let destination = account_start_decide(valid, state, remote); // :1465
    if checking {
        fx.apply_destination_flags(
            destination == AccountStartDestination::Retry,
            destination == AccountStartDestination::Onboarding,
            state == LocalDataState::Conflicted,
            local.as_ref(),
        ); // :1466-1470
    }
    if !may_upload(destination, state) {
        return; // :1477-1478
    }
    let result = fx.execute_sync(); // :1479
    if owner != fx.effective_user_id() || session != fx.session_generation() {
        return; // :1480 second recheck
    }
    let restored = fx.read_restored_after_sync(); // :1481-1483
    let is_admin = fx.fetch_is_admin(); // :1484
    if owner != fx.effective_user_id() || session != fx.session_generation() {
        return; // :1485 THIRD recheck, after repository reads + isAdmin,
                // BEFORE applying the sync result. Modeled explicitly.
    }
    let reauth = reauth_after_sync(result.is_success, result.requires_authentication, fx.session_needs_reauthentication());
    fx.apply_sync_result(&result, &restored, is_admin, reauth); // :1486-1491
    fx.update_calculations(&restored); // :1493
}
