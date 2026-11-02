//! Exhaustive property tests for the account-start policy.
//! Mirrors AccountStartPolicyTest's contract, checked over the FULL input
//! space (2 x 3 x 3 = 18 combinations), not just sampled cases.

use cyclecare_core::account_start::{decide, AccountStartDestination, LocalDataState};
use cyclecare_core::startup_route::{route, ShellRoute, StartupInput};

const BOOLS: [bool; 2] = [false, true];
const STATES: [LocalDataState; 3] =
    [LocalDataState::Clean, LocalDataState::Conflicted, LocalDataState::Unknown];
const REMOTES: [Option<bool>; 3] = [None, Some(false), Some(true)];

#[test]
fn conflicted_never_routes_to_onboarding() {
    for &local_valid in &BOOLS {
        for &remote in &REMOTES {
            let d = decide(local_valid, LocalDataState::Conflicted, remote);
            assert_ne!(
                d,
                AccountStartDestination::Onboarding,
                "CONFLICTED must never route to onboarding (valid={local_valid}, remote={remote:?})"
            );
        }
    }
}

#[test]
fn unknown_read_is_always_retry_never_destructive() {
    for &local_valid in &BOOLS {
        for &remote in &REMOTES {
            assert_eq!(
                decide(local_valid, LocalDataState::Unknown, remote),
                AccountStartDestination::Retry,
                "UNKNOWN must be RETRY (valid={local_valid}, remote={remote:?})"
            );
        }
    }
}

#[test]
fn onboarding_only_for_confirmed_new_remote_user_with_no_local_data() {
    for &local_valid in &BOOLS {
        for &state in &STATES {
            for &remote in &REMOTES {
                let d = decide(local_valid, state, remote);
                if d == AccountStartDestination::Onboarding {
                    assert!(
                        !local_valid,
                        "onboarding with valid local data is forbidden (state={state:?}, remote={remote:?})"
                    );
                    assert_eq!(
                        state,
                        LocalDataState::Clean,
                        "onboarding requires a CLEAN read (remote={remote:?})"
                    );
                    assert_eq!(
                        remote,
                        Some(false),
                        "onboarding requires a server-confirmed absent profile (state={state:?})"
                    );
                }
            }
        }
    }
}

#[test]
fn valid_local_data_means_home_unless_read_unknown() {
    for &state in &STATES {
        for &remote in &REMOTES {
            let d = decide(true, state, remote);
            match state {
                LocalDataState::Unknown => assert_eq!(d, AccountStartDestination::Retry),
                _ => assert_eq!(
                    d,
                    AccountStartDestination::Home,
                    "valid local data goes home (state={state:?}, remote={remote:?})"
                ),
            }
        }
    }
}

#[test]
fn existing_remote_profile_never_sees_onboarding() {
    for &local_valid in &BOOLS {
        for &state in &STATES {
            let d = decide(local_valid, state, Some(true));
            assert_ne!(
                d,
                AccountStartDestination::Onboarding,
                "server-confirmed existing profile must never route to onboarding (valid={local_valid}, state={state:?})"
            );
        }
    }
}

#[test]
fn shell_gate_order_is_fixed() {
    let base = StartupInput {
        has_completed_initial_auth: true,
        is_checking_cloud_profile: false,
        cloud_profile_check_failed: false,
        is_app_locked: false,
        local_valid: true,
        local_state: LocalDataState::Clean,
        remote_profile: Some(true),
    };
    assert_eq!(route(&base), ShellRoute::Home);
    assert_eq!(
        route(&StartupInput { has_completed_initial_auth: false, ..base }),
        ShellRoute::WelcomeAuth,
        "auth gate wins over everything"
    );
    assert_eq!(
        route(&StartupInput { is_checking_cloud_profile: true, ..base }),
        ShellRoute::Splash
    );
    assert_eq!(
        route(&StartupInput { cloud_profile_check_failed: true, ..base }),
        ShellRoute::Retry
    );
    assert_eq!(
        route(&StartupInput { is_app_locked: true, ..base }),
        ShellRoute::AppLock
    );
    // A conflicted-but-valid returning user reaches Home even when the remote
    // read failed: the vc6 bug (CONFLICTED -> ONBOARDING) stays dead.
    assert_eq!(
        route(&StartupInput {
            local_state: LocalDataState::Conflicted,
            remote_profile: None,
            ..base
        }),
        ShellRoute::Home
    );
}

#[test]
fn locked_or_unauthenticated_never_leaks_home_contents() {
    for &auth_done in &BOOLS {
        for &locked in &BOOLS {
            for &state in &STATES {
                for &remote in &REMOTES {
                    let r = route(&StartupInput {
                        has_completed_initial_auth: auth_done,
                        is_checking_cloud_profile: false,
                        cloud_profile_check_failed: false,
                        is_app_locked: locked,
                        local_valid: true,
                        local_state: state,
                        remote_profile: remote,
                    });
                    if !auth_done {
                        assert_eq!(r, ShellRoute::WelcomeAuth);
                    } else if locked {
                        assert_eq!(r, ShellRoute::AppLock);
                    }
                }
            }
        }
    }
}
