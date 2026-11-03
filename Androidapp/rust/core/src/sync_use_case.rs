//! Port of domain/sync/SyncUseCase.kt (main@31b88f5, 23 lines) - a thin
//! delegate with no logic of its own:
//!   executeSync()          -> syncService.syncAll()
//!   isCloudSyncAllowed()   -> authPreferences.isCloudSyncAllowed
//!   setCloudSyncAllowed(a) -> authPreferences.isCloudSyncAllowed = a
//! The setter routes through the AuthPreferences setter (AuthPreferences.kt
//! :140-156: decision + generation bump in ONE commit); Kotlin's var-setter
//! has no return value, but a generation overflow would throw
//! ArithmeticException through the use case, so the port propagates the
//! typed error instead of swallowing it.

use crate::auth_prefs_health::{AuthPrefsError, HealthAuthPrefs};
use crate::session_token::AccessTokenResolution;
use crate::v3_consent::V3ConsentTransport;
use crate::v3_sync_all::{sync_all, SyncResult};

/// executeSync(): one forward to syncAll. All dependencies are the ones
/// syncAll already takes; SyncUseCase adds nothing.
pub fn execute_sync(
    prefs: &mut HealthAuthPrefs,
    resolve_token: &mut dyn FnMut() -> AccessTokenResolution,
    consent_transport: &mut dyn V3ConsentTransport,
    pull: &mut dyn FnMut(&str, i64, i64, i64, &str) -> Result<i32, ()>,
    outbox: &mut dyn FnMut(&str) -> Result<i32, ()>,
    conflicts: &mut dyn FnMut(&str) -> Result<usize, ()>,
    current: &mut dyn FnMut() -> bool,
    now_ms: &mut dyn FnMut() -> i64,
    uuid: &mut dyn FnMut() -> String,
) -> SyncResult {
    sync_all(prefs, resolve_token, consent_transport, pull, outbox, conflicts, current, now_ms, uuid)
}

/// isCloudSyncAllowed(): one forward to the getter.
pub fn is_cloud_sync_allowed(prefs: &HealthAuthPrefs) -> bool {
    prefs.is_cloud_sync_allowed()
}

/// setCloudSyncAllowed(allowed): one forward to the setter. Error
/// propagates exactly like the Kotlin setter's Math.addExact throw.
pub fn set_cloud_sync_allowed(prefs: &mut HealthAuthPrefs, allowed: bool) -> Result<(), AuthPrefsError> {
    prefs.set_is_cloud_sync_allowed(allowed)
}
