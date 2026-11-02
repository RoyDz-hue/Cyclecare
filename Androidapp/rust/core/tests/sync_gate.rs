//! Sync preservation-gate tests over the full decision matrix.

use cyclecare_core::account_start::{decide, AccountStartDestination, LocalDataState};
use cyclecare_core::sync_gate::*;

const REMOTES: [Option<bool>; 3] = [None, Some(false), Some(true)];

#[test]
fn failed_local_read_is_unknown_and_blocks_everything() {
    let state = local_data_state(&LocalRead::Failed, Ok(false));
    assert_eq!(state, LocalDataState::Unknown);
    assert!(!local_valid(state, &LocalRead::Failed), "UNKNOWN guard wins over any completion flag");
    for &remote in &REMOTES {
        let d = decide(local_valid(state, &LocalRead::Failed), state, remote);
        assert!(!may_upload(d, state), "no upload on unknown local state (remote={remote:?})");
    }
}

#[test]
fn failed_conflict_read_is_unknown_and_blocks_upload() {
    let local = LocalRead::Ok { profile_completed: true, settings_onboarding_completed: true };
    let state = local_data_state(&local, Err(()));
    assert_eq!(state, LocalDataState::Unknown);
    let d = decide(local_valid(state, &local), state, Some(true));
    assert!(!may_upload(d, state));
}

#[test]
fn conflicted_valid_user_goes_home_but_upload_stays_gated() {
    let local = LocalRead::Ok { profile_completed: true, settings_onboarding_completed: true };
    let state = local_data_state(&local, Ok(true));
    assert_eq!(state, LocalDataState::Conflicted);
    let d = decide(local_valid(state, &local), state, None);
    assert_eq!(d, AccountStartDestination::Home, "conflict review is reachable from Home");
    assert!(!may_upload(d, state), "upload gated until the review resolves");
}

#[test]
fn clean_valid_home_allows_upload() {
    let local = LocalRead::Ok { profile_completed: true, settings_onboarding_completed: true };
    let state = local_data_state(&local, Ok(false));
    let d = decide(local_valid(state, &local), state, Some(true));
    assert_eq!(d, AccountStartDestination::Home);
    assert!(may_upload(d, state));
}

#[test]
fn upload_never_runs_for_onboarding_or_retry_destinations() {
    for &remote in &REMOTES {
        for state in [LocalDataState::Clean, LocalDataState::Conflicted, LocalDataState::Unknown] {
            for valid in [false, true] {
                let d = decide(valid, state, remote);
                if d != AccountStartDestination::Home {
                    assert!(!may_upload(d, state), "no upload for {d:?} (state={state:?}, valid={valid}, remote={remote:?})");
                }
            }
        }
    }
}

#[test]
fn manual_sync_precheck_is_fail_safe() {
    assert!(matches!(manual_sync_preaction(Ok(false)), ManualSyncPreaction::Proceed));
    assert!(matches!(manual_sync_preaction(Ok(true)), ManualSyncPreaction::BlockedReviewFirst));
    assert!(matches!(manual_sync_preaction(Err(())), ManualSyncPreaction::BlockedReviewFirst),
        "a failed conflict read must block the upload, never overwrite sync preferences");
}
