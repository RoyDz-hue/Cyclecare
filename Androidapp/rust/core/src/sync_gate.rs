//! Sync preservation gates.
//! Port of the gating semantics in CycleCareViewModel.autoSyncSilently and
//! triggerManualSync (main@31b88f5). The gates write NO persisted sync
//! preferences: they set in-memory UI flags only, and a failed conflict read
//! is treated as "pending" (fail-safe, blocks the upload) without touching
//! isCloudSyncAllowed or any stored setting.

use crate::account_start::{AccountStartDestination, LocalDataState};

/// Outcome of reading local settings + onboarding profile.
pub enum LocalRead {
    /// Reads succeeded; both completion flags reported.
    Ok { profile_completed: bool, settings_onboarding_completed: bool },
    /// A read failed: UNKNOWN - never "damaged, re-onboard" and never
    /// "clean, safe to sync over".
    Failed,
}

/// Port of the localState computation in autoSyncSilently.
pub fn local_data_state(local: &LocalRead, conflicts_read: Result<bool, ()>) -> LocalDataState {
    match (local, conflicts_read) {
        (LocalRead::Failed, _) | (_, Err(())) => LocalDataState::Unknown,
        (_, Ok(true)) => LocalDataState::Conflicted,
        _ => LocalDataState::Clean,
    }
}

/// Port of the localValid computation. Note the UNKNOWN guard wins over
/// completion flags: unreadable state is never "valid".
pub fn local_valid(state: LocalDataState, local: &LocalRead) -> bool {
    if state == LocalDataState::Unknown {
        return false;
    }
    match local {
        LocalRead::Ok { profile_completed, settings_onboarding_completed } => {
            *profile_completed && *settings_onboarding_completed
        }
        LocalRead::Failed => false,
    }
}

/// Preservation gate: sync uploads run only when the start decision is HOME
/// AND no conflicts are unresolved. Conflicted users still reach HOME (and
/// the Settings conflict review) when local data is valid; only the upload is
/// gated.
pub fn may_upload(destination: AccountStartDestination, state: LocalDataState) -> bool {
    destination == AccountStartDestination::Home && state != LocalDataState::Conflicted
}

pub enum ManualSyncPreaction {
    /// Blocked: review pending choices first; nothing uploaded.
    BlockedReviewFirst,
    Proceed,
}

/// Port of triggerManualSync's precheck. A failed conflict read blocks the
/// upload (fail-safe) and writes no persisted preference.
pub fn manual_sync_preaction(conflicts_read: Result<bool, ()>) -> ManualSyncPreaction {
    match conflicts_read {
        Ok(false) => ManualSyncPreaction::Proceed,
        Ok(true) | Err(()) => ManualSyncPreaction::BlockedReviewFirst,
    }
}
