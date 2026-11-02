//! Account-start routing policy.
//! Port of domain/sync/AccountStartPolicy.kt (main@31b88f5, corrected version).

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum AccountStartDestination {
    Home,
    Onboarding,
    Retry,
}

/// CLEAN: reads succeeded, no pending conflicts. CONFLICTED: unresolved sync
/// conflicts. UNKNOWN: a read failed - never treat as new user and never as
/// clean (a sync overwrite on unknown state could destroy local history).
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum LocalDataState {
    Clean,
    Conflicted,
    Unknown,
}

/// Unresolved sync conflicts never route to onboarding: rerunning setup would
/// overwrite personal answers and the conflict review lives in Settings,
/// unreachable while onboarding owns the shell. With valid local data the
/// user goes Home (sync stays gated until review resolves); without it the
/// safe destination is a non-destructive Retry.
pub fn decide(
    local_valid: bool,
    local_state: LocalDataState,
    remote_profile: Option<bool>,
) -> AccountStartDestination {
    if local_state == LocalDataState::Unknown {
        return AccountStartDestination::Retry;
    }
    if local_state == LocalDataState::Conflicted {
        return if local_valid {
            AccountStartDestination::Home
        } else {
            AccountStartDestination::Retry
        };
    }
    if local_valid {
        return AccountStartDestination::Home;
    }
    match remote_profile {
        Some(true) => AccountStartDestination::Home,
        Some(false) => AccountStartDestination::Onboarding,
        None => AccountStartDestination::Retry,
    }
}
