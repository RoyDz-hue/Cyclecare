//! Cold-start shell routing.
//! Mirrors the gate order in MainActivity.CycleCareApp (main@31b88f5):
//! (1) initial auth incomplete -> WelcomeAuth; (2) cloud profile check running
//! -> Splash; (3) cloud profile check failed -> Retry; (4) app locked ->
//! AppLock; (5) onboarding needed -> Onboarding; else Home.
//! The onboarding decision itself delegates to account_start::decide.

use crate::account_start::{decide, AccountStartDestination, LocalDataState};

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum ShellRoute {
    WelcomeAuth,
    Splash,
    Retry,
    AppLock,
    Onboarding,
    Home,
}

#[derive(Debug, Clone, Copy)]
pub struct StartupInput {
    pub has_completed_initial_auth: bool,
    pub is_checking_cloud_profile: bool,
    pub cloud_profile_check_failed: bool,
    pub is_app_locked: bool,
    /// Local data layer verdicts.
    pub local_valid: bool,
    pub local_state: LocalDataState,
    /// Server-side profile existence: Some(true/false), None = unknown (read failed).
    pub remote_profile: Option<bool>,
}

pub fn route(input: &StartupInput) -> ShellRoute {
    if !input.has_completed_initial_auth {
        return ShellRoute::WelcomeAuth;
    }
    if input.is_checking_cloud_profile {
        return ShellRoute::Splash;
    }
    if input.cloud_profile_check_failed {
        return ShellRoute::Retry;
    }
    if input.is_app_locked {
        return ShellRoute::AppLock;
    }
    match decide(input.local_valid, input.local_state, input.remote_profile) {
        AccountStartDestination::Home => ShellRoute::Home,
        AccountStartDestination::Onboarding => ShellRoute::Onboarding,
        AccountStartDestination::Retry => ShellRoute::Retry,
    }
}
