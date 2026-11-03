//! Transition tests for the composed vm_sync drivers (CycleCareViewModel
//! :1376-1493, main 31b88f5). Scripted fakes record the exact effect call
//! order; assertions prove source sequencing end to end.

use cyclecare_core::reconnect_policy::Decision as TrailingDecision;
use cyclecare_core::vm_sync::{
    auto_sync_silently, execute_trailing, run_reconnect_work_once, schedule_trailing,
    AutoSyncEffects, LocalSnapshot, ReconnectEffects, SyncResult, TrailingEffects,
};

// --- runReconnectWorkOnce (:1376-1400) ------------------------------------

struct RecFx {
    owner: String,
    session: i64,
    reconcile_ok: bool,
    fetch_ok: bool,
    stale_after: &'static str, // "", "reconcile", "fetch"
    calls: Vec<String>,
}

impl ReconnectEffects for RecFx {
    fn effective_user_id(&self) -> &str {
        &self.owner
    }
    fn session_generation(&self) -> i64 {
        self.session
    }
    fn reconcile_pending_offline_claims(&mut self) -> bool {
        self.calls.push("reconcile".to_string());
        if self.stale_after == "reconcile" {
            self.owner = "other".to_string();
        }
        self.reconcile_ok
    }
    fn apply_reconciled(&mut self) {
        self.calls.push("apply_reconciled".to_string());
    }
    fn fetch_verified_profile(&mut self) -> bool {
        self.calls.push("fetch".to_string());
        if self.stale_after == "fetch" {
            self.owner = "other".to_string();
        }
        self.fetch_ok
    }
    fn apply_verified(&mut self) {
        self.calls.push("apply_verified".to_string());
    }
    fn refresh_notifications_safely(&mut self) {
        self.calls.push("refresh_notifications".to_string());
    }
}

#[test]
fn reconnect_happy_path_order_and_conjunction() {
    let mut fx = RecFx { owner: "o1".into(), session: 7, reconcile_ok: true, fetch_ok: true, stale_after: "", calls: vec![] };
    assert!(run_reconnect_work_once(&mut fx));
    assert_eq!(fx.calls, vec!["reconcile", "apply_reconciled", "fetch", "apply_verified", "refresh_notifications"]);
}

#[test]
fn reconcile_failure_skips_apply_but_still_fetches() {
    let mut fx = RecFx { owner: "o1".into(), session: 7, reconcile_ok: false, fetch_ok: true, stale_after: "", calls: vec![] };
    assert!(!run_reconnect_work_once(&mut fx)); // conjunction: false && true
    assert_eq!(fx.calls, vec!["reconcile", "fetch", "apply_verified", "refresh_notifications"]);
}

#[test]
fn fetch_failure_skips_apply_verified_and_returns_false() {
    let mut fx = RecFx { owner: "o1".into(), session: 7, reconcile_ok: true, fetch_ok: false, stale_after: "", calls: vec![] };
    assert!(!run_reconnect_work_once(&mut fx));
    assert_eq!(fx.calls, vec!["reconcile", "apply_reconciled", "fetch", "refresh_notifications"]);
}

#[test]
fn stale_after_reconcile_returns_true_and_stops() {
    // :1380 early return TRUE on owner change mid-run.
    let mut fx = RecFx { owner: "o1".into(), session: 7, reconcile_ok: true, fetch_ok: true, stale_after: "reconcile", calls: vec![] };
    assert!(run_reconnect_work_once(&mut fx));
    assert_eq!(fx.calls, vec!["reconcile"]);
}

#[test]
fn stale_after_fetch_returns_true_and_stops() {
    // :1386 early return TRUE; refreshNotifications never runs.
    let mut fx = RecFx { owner: "o1".into(), session: 7, reconcile_ok: true, fetch_ok: true, stale_after: "fetch", calls: vec![] };
    assert!(run_reconnect_work_once(&mut fx));
    assert_eq!(fx.calls, vec!["reconcile", "apply_reconciled", "fetch"]);
}

// --- schedule/launchTrailingReconnectWork (:1402-1428) --------------------

struct TrailFx {
    owner: String,
    job_active: bool,
    local_only: bool,
    user_id_present: bool,
    online: bool,
    now: i64,
    decision: TrailingDecision,
    calls: Vec<String>,
}

impl TrailingEffects for TrailFx {
    fn trailing_job_active(&self) -> bool {
        self.job_active
    }
    fn spawn_trailing(&mut self, delay_ms: i64, allow_reschedule: bool) {
        self.calls.push(format!("spawn({}, {})", delay_ms, allow_reschedule));
    }
    fn effective_user_id(&self) -> &str {
        &self.owner
    }
    fn is_local_only(&self) -> bool {
        self.local_only
    }
    fn user_id_present(&self) -> bool {
        self.user_id_present
    }
    fn is_online(&self) -> bool {
        self.online
    }
    fn now_ms(&self) -> i64 {
        self.now
    }
    fn decide_trailing(&mut self, _online: bool, _eligible: bool, _owner: &str, _now: i64) -> TrailingDecision {
        self.calls.push("decide".to_string());
        self.decision.clone()
    }
    fn run_work(&mut self) -> bool {
        self.calls.push("run_work".to_string());
        true
    }
}

fn trail(decision: TrailingDecision) -> TrailFx {
    TrailFx { owner: "o1".into(), job_active: false, local_only: false, user_id_present: true, online: true, now: 100, decision, calls: vec![] }
}

#[test]
fn schedule_coalesces_active_job() {
    let mut fx = trail(TrailingDecision::Skip);
    fx.job_active = true;
    assert!(!schedule_trailing(&mut fx, 5000, true));
    assert!(fx.calls.is_empty());
}

#[test]
fn schedule_launches_when_idle() {
    let mut fx = trail(TrailingDecision::Skip);
    assert!(schedule_trailing(&mut fx, 5000, true));
    assert_eq!(fx.calls, vec!["spawn(5000, true)"]);
}

#[test]
fn trailing_owner_mismatch_stops_before_any_check() {
    let mut fx = trail(TrailingDecision::Run);
    execute_trailing(&mut fx, "someone_else", true);
    assert!(fx.calls.is_empty());
}

#[test]
fn trailing_run_decision_runs_work() {
    let mut fx = trail(TrailingDecision::Run);
    execute_trailing(&mut fx, "o1", true);
    assert_eq!(fx.calls, vec!["decide", "run_work"]);
}

#[test]
fn trailing_throttled_reschedules_with_retry_after_ms_via_direct_reassignment() {
    // :1422-1425: the decision's retryAfterMs is preserved, allowReschedule=true,
    // and the job is reassigned DIRECTLY (no active-job check in this path).
    let mut fx = trail(TrailingDecision::Throttled { retry_after_ms: 1234 });
    execute_trailing(&mut fx, "o1", true);
    assert_eq!(fx.calls, vec!["decide", "spawn(1234, true)"]);
}

#[test]
fn trailing_throttled_without_reschedule_permission_does_nothing() {
    let mut fx = trail(TrailingDecision::Throttled { retry_after_ms: 1234 });
    execute_trailing(&mut fx, "o1", false);
    assert_eq!(fx.calls, vec!["decide"]);
}

#[test]
fn trailing_skip_does_nothing() {
    let mut fx = trail(TrailingDecision::Skip);
    execute_trailing(&mut fx, "o1", true);
    assert_eq!(fx.calls, vec!["decide"]);
}

// --- autoSyncSilently (:1439-1493) ----------------------------------------

struct AutoFx {
    local_only: bool,
    ui_onboarding_completed: bool,
    user_id_present: bool,
    owner: String,
    session: i64,
    checking: bool,
    check_failed: bool,
    reauth: bool,
    job_active: bool,
    local: Option<LocalSnapshot>,
    conflicts: Result<bool, ()>,
    remote: Option<bool>,
    sync_success: bool,
    sync_requires_auth: bool,
    stale_after_admin: bool,
    restored_reads_fail: bool,
    calls: Vec<String>,
    destination_flags: Option<(bool, bool, bool)>,
}

impl AutoFx {
    fn online(checking: bool, local: Option<LocalSnapshot>) -> Self {
        AutoFx {
            local_only: false,
            ui_onboarding_completed: false,
            user_id_present: true,
            owner: "o1".into(),
            session: 7,
            checking,
            check_failed: false,
            reauth: false,
            job_active: false,
            local,
            conflicts: Ok(false),
            remote: Some(true),
            sync_success: true,
            sync_requires_auth: false,
            stale_after_admin: false,
            restored_reads_fail: false,
            calls: vec![],
            destination_flags: None,
        }
    }
}

impl AutoSyncEffects for AutoFx {
    fn is_local_only(&self) -> bool {
        self.local_only
    }
    fn user_id_present(&self) -> bool {
        self.user_id_present
    }
    fn effective_user_id(&self) -> &str {
        &self.owner
    }
    fn session_generation(&self) -> i64 {
        self.session
    }
    fn is_checking_cloud_profile(&self) -> bool {
        self.checking
    }
    fn cloud_profile_check_failed(&self) -> bool {
        self.check_failed
    }
    fn session_needs_reauthentication(&self) -> bool {
        self.reauth
    }
    fn ui_settings_onboarding_completed(&self) -> bool {
        self.ui_onboarding_completed
    }
    fn apply_local_only_return(&mut self, show_onboarding: bool) {
        self.calls.push(format!("local_only_return({})", show_onboarding));
    }
    fn refresh_wallet_now(&mut self, force: bool) {
        self.calls.push(format!("wallet({})", force));
    }
    fn account_refresh_job_active(&self) -> bool {
        self.job_active
    }
    fn set_checking_flags(&mut self) {
        self.calls.push("checking_flags".to_string());
    }
    fn read_local(&mut self) -> Option<LocalSnapshot> {
        self.calls.push("read_local".to_string());
        self.local.take()
    }
    fn pending_conflicts_nonempty(&mut self) -> Result<bool, ()> {
        self.calls.push("conflicts".to_string());
        self.conflicts
    }
    fn has_remote_profile(&mut self) -> Option<bool> {
        self.calls.push("remote_probe".to_string());
        self.remote
    }
    fn apply_destination_flags(&mut self, check_failed: bool, show_onboarding: bool, sync_gated: bool, _local: Option<&LocalSnapshot>) {
        self.calls.push("destination_flags".to_string());
        self.destination_flags = Some((check_failed, show_onboarding, sync_gated));
    }
    fn execute_sync(&mut self) -> SyncResult {
        self.calls.push("execute_sync".to_string());
        SyncResult { is_success: self.sync_success, requires_authentication: self.sync_requires_auth }
    }
    fn read_restored_after_sync(&mut self) -> Result<cyclecare_core::vm_sync::RestoredState, ()> {
        self.calls.push("restored_reads".to_string());
        if self.restored_reads_fail {
            return Err(());
        }
        if self.stale_after_admin {
            self.owner = "other".to_string();
        }
        Ok(cyclecare_core::vm_sync::RestoredState { settings_onboarding_completed: true, profile_present: true })
    }
    fn fetch_is_admin(&mut self) -> bool {
        self.calls.push("is_admin".to_string());
        if self.stale_after_admin {
            self.owner = "other".to_string();
        }
        false
    }
    fn apply_sync_result(&mut self, _r: &SyncResult, _restored: &cyclecare_core::vm_sync::RestoredState, _is_admin: bool, reauth: bool) {
        self.calls.push(format!("apply_result(reauth={})", reauth));
    }
    fn update_calculations(&mut self, _restored: &cyclecare_core::vm_sync::RestoredState) {
        self.calls.push("update_calculations".to_string());
    }
}

#[test]
fn autosync_local_only_uses_ui_state_no_storage_read() {
    // :1440-1444: showOnboarding from ALREADY-LOADED uiState.settings, no
    // repository read in this branch.
    let mut fx = AutoFx::online(false, None);
    fx.local_only = true;
    fx.ui_onboarding_completed = true;
    let out = auto_sync_silently(&mut fx, true);
    assert_eq!(out, Ok(cyclecare_core::vm_sync::AutoSyncOutcome::LocalOnlyReturn));
    assert_eq!(fx.calls, vec!["local_only_return(false)"]);
}

#[test]
fn autosync_local_only_incomplete_ui_settings_shows_onboarding() {
    let mut fx = AutoFx::online(false, None);
    fx.local_only = true;
    fx.ui_onboarding_completed = false;
    auto_sync_silently(&mut fx, true);
    assert_eq!(fx.calls, vec!["local_only_return(true)"]);
}

#[test]
fn autosync_wallet_then_coalesce_stops() {
    let mut fx = AutoFx::online(true, None);
    fx.job_active = true;
    auto_sync_silently(&mut fx, false);
    assert_eq!(fx.calls, vec!["wallet(false)"]);
}

#[test]
fn autosync_checking_with_complete_local_skips_remote_probe_and_syncs() {
    let snap = LocalSnapshot { profile_present: true, profile_completed: true, settings_onboarding_completed: true };
    let mut fx = AutoFx::online(true, Some(snap));
    let out = auto_sync_silently(&mut fx, true);
    assert_eq!(out, Ok(cyclecare_core::vm_sync::AutoSyncOutcome::Completed));
    assert_eq!(
        fx.calls,
        vec!["wallet(true)", "checking_flags", "read_local", "conflicts", "destination_flags", "execute_sync", "restored_reads", "is_admin", "apply_result(reauth=false)", "update_calculations"]
    );
    assert_eq!(fx.destination_flags, Some((false, false, false)));
}

#[test]
fn autosync_checking_without_local_profile_probes_remote_first() {
    let snap = LocalSnapshot { profile_present: false, profile_completed: false, settings_onboarding_completed: false };
    let mut fx = AutoFx::online(true, Some(snap));
    auto_sync_silently(&mut fx, true);
    // probe happens before destination flags
    let probe = fx.calls.iter().position(|c| c == "remote_probe").unwrap();
    let flags = fx.calls.iter().position(|c| c == "destination_flags").unwrap();
    assert!(probe < flags);
}

#[test]
fn autosync_conflicted_reaches_home_but_upload_gated() {
    // Preservation gate: HOME destination with conflicts -> no executeSync.
    let snap = LocalSnapshot { profile_present: true, profile_completed: true, settings_onboarding_completed: true };
    let mut fx = AutoFx::online(true, Some(snap));
    fx.conflicts = Ok(true);
    auto_sync_silently(&mut fx, true);
    assert!(!fx.calls.contains(&"execute_sync".to_string()));
    assert_eq!(fx.destination_flags, Some((false, false, true)));
}

#[test]
fn autosync_onboarding_destination_gates_upload() {
    let snap = LocalSnapshot { profile_present: false, profile_completed: false, settings_onboarding_completed: false };
    let mut fx = AutoFx::online(true, Some(snap));
    fx.remote = Some(false);
    auto_sync_silently(&mut fx, true);
    assert!(!fx.calls.contains(&"execute_sync".to_string()));
    assert_eq!(fx.destination_flags, Some((false, true, false)));
}

#[test]
fn autosync_third_recheck_after_restored_reads_and_admin_blocks_apply() {
    // :1485: owner change during restored reads / isAdmin -> result discarded.
    let snap = LocalSnapshot { profile_present: true, profile_completed: true, settings_onboarding_completed: true };
    let mut fx = AutoFx::online(true, Some(snap));
    fx.stale_after_admin = true;
    let out = auto_sync_silently(&mut fx, true);
    assert_eq!(out, Ok(cyclecare_core::vm_sync::AutoSyncOutcome::StaleOwner));
    assert!(!fx.calls.iter().any(|c| c.starts_with("apply_result(")));
    assert!(!fx.calls.contains(&"update_calculations".to_string()));
    assert!(fx.calls.contains(&"restored_reads".to_string()));
}

#[test]
fn autosync_restored_read_failure_propagates_typed_fault() {
    // :1481-1483 unguarded repository read throws -> the failure PROPAGATES
    // (source: uncaught coroutine throw), it is NOT a silent early return;
    // the sync result is never applied, no third recheck, no updateCalculations.
    let snap = LocalSnapshot { profile_present: true, profile_completed: true, settings_onboarding_completed: true };
    let mut fx = AutoFx::online(true, Some(snap));
    fx.restored_reads_fail = true;
    let out = auto_sync_silently(&mut fx, true);
    assert_eq!(out, Err(cyclecare_core::vm_sync::AutoSyncFault::RestoredReadFailed));
    assert!(fx.calls.contains(&"execute_sync".to_string()));
    assert!(fx.calls.contains(&"restored_reads".to_string()));
    assert!(!fx.calls.contains(&"is_admin".to_string()));
    assert!(!fx.calls.iter().any(|c| c.starts_with("apply_result(")));
    assert!(!fx.calls.contains(&"update_calculations".to_string()));
}

#[test]
fn autosync_reauth_composition_reaches_apply_result() {
    let snap = LocalSnapshot { profile_present: true, profile_completed: true, settings_onboarding_completed: true };
    let mut fx = AutoFx::online(true, Some(snap));
    fx.sync_success = false;
    fx.sync_requires_auth = true;
    auto_sync_silently(&mut fx, true);
    assert!(fx.calls.iter().any(|c| c.starts_with("apply_result(")));
}

// --- refreshNotifications (:1810-1851) ------------------------------------

use cyclecare_core::vm_sync::{
    refresh_notifications, GiftDialogResolution, NotificationEntry, RefreshNotificationsEffects,
    VerifiedEconomyView,
};

struct RefFx {
    local_only: bool,
    user_id_present: bool,
    owner: String,
    session: i64,
    verified: Option<VerifiedEconomyView>,
    notifications: Vec<NotificationEntry>,
    flip_owner_at_fetch: bool,
    calls: Vec<String>,
    applied_gift: Option<GiftDialogResolution>,
}

impl RefFx {
    fn new(verified: Option<VerifiedEconomyView>, notifications: Vec<NotificationEntry>) -> Self {
        RefFx {
            local_only: false,
            user_id_present: true,
            owner: "o1".into(),
            session: 7,
            verified,
            notifications,
            flip_owner_at_fetch: false,
            calls: vec![],
            applied_gift: None,
        }
    }
}

fn notif(is_read: bool, type_: &str, title: &str, body: &str) -> NotificationEntry {
    NotificationEntry { is_read, type_: type_.into(), title: title.into(), body: body.into() }
}

fn econ(gifts: Vec<&str>) -> VerifiedEconomyView {
    VerifiedEconomyView {
        econ_ref: "ECON".into(),
        gifted_artifacts: gifts.into_iter().map(|s| s.to_string()).collect(),
        is_banned: false,
        is_suspended: false,
        suspension_reason: None,
    }
}

impl RefreshNotificationsEffects for RefFx {
    fn is_local_only(&self) -> bool {
        self.local_only
    }
    fn user_id_present(&self) -> bool {
        self.user_id_present
    }
    fn effective_user_id(&self) -> &str {
        &self.owner
    }
    fn session_generation(&self) -> i64 {
        self.session
    }
    fn fetch_notifications(&mut self, _user: &str) -> Vec<NotificationEntry> {
        self.calls.push("fetch_notifications".to_string());
        std::mem::take(&mut self.notifications)
    }
    fn fetch_verified_profile(&mut self, _user: &str) -> Option<VerifiedEconomyView> {
        self.calls.push("fetch_verified".to_string());
        if self.flip_owner_at_fetch {
            self.owner = "other".to_string();
        }
        self.verified.take()
    }
    fn apply_refresh_success(
        &mut self,
        _list: Vec<NotificationEntry>,
        _econ_ref: &str,
        _b: bool,
        _s: bool,
        _r: Option<&str>,
        gift: GiftDialogResolution,
    ) {
        self.calls.push("apply_success".to_string());
        self.applied_gift = Some(gift);
    }
    fn apply_refresh_notifications_only(&mut self, _list: Vec<NotificationEntry>) {
        self.calls.push("apply_notifications_only".to_string());
    }
}

#[test]
fn refresh_local_only_never_fetches() {
    let mut fx = RefFx::new(None, vec![]);
    fx.local_only = true;
    assert!(refresh_notifications(&mut fx));
    assert!(fx.calls.is_empty());
}

#[test]
fn refresh_fetch_order_notifications_then_verified() {
    let mut fx = RefFx::new(None, vec![]);
    assert!(refresh_notifications(&mut fx));
    assert_eq!(fx.calls, vec!["fetch_notifications", "fetch_verified", "apply_notifications_only"]);
}

#[test]
fn refresh_stale_owner_discards_everything() {
    // :1819 recheck: owner changed during the verified fetch -> return@launch.
    let mut fx = RefFx::new(Some(econ(vec![])), vec![]);
    fx.flip_owner_at_fetch = true;
    assert!(!refresh_notifications(&mut fx));
    assert_eq!(fx.calls, vec!["fetch_notifications", "fetch_verified"]);
}

#[test]
fn refresh_gift_from_last_gifted_artifact_name() {
    let mut fx = RefFx::new(Some(econ(vec!["Golden Blossom"])), vec![]);
    assert!(refresh_notifications(&mut fx));
    assert_eq!(fx.applied_gift, Some(GiftDialogResolution::Resolved("artifact_bloom".to_string())));
}

#[test]
fn refresh_gift_from_unread_gift_notification_body() {
    let mut fx = RefFx::new(
        Some(econ(vec![])),
        vec![notif(false, "GIFT", "A gift", "You received the Luminescent Moon")],
    );
    assert!(refresh_notifications(&mut fx));
    assert_eq!(fx.applied_gift, Some(GiftDialogResolution::Resolved("artifact_moon".to_string())));
}

#[test]
fn refresh_no_gift_signal_keeps_existing_dialog_item() {
    let mut fx = RefFx::new(Some(econ(vec![])), vec![notif(true, "SYSTEM", "hi", "plain")]);
    assert!(refresh_notifications(&mut fx));
    assert_eq!(fx.applied_gift, Some(GiftDialogResolution::KeepExisting));
}
