//! syncAll (SupabaseSyncService.kt:114-143) against the REAL HealthAuthPrefs
//! implementation. SyncResult mirrors SupabaseModels.kt:73-82. Synthetic
//! seams only: token resolution (SupabaseSessionManager.resolveAccessToken is
//! wired by the host adapter over session_token::resolve_access_token),
//! transport, pull/outbox execution, and the conflicts DAO read.
//!
//! Production parity: with CONFLICT_SAFE_PROTOCOL_RELEASED = false the upload
//! body (pull + executeV3Outbox + lastSync writes) is UNREACHABLE — branch 4
//! returns first — and the only live server-touching route is the pending
//! WITHDRAWAL branch, which sends only a consent decision, never a health
//! payload (source comment :119). Withdrawal is a real account mutation in
//! production; this port is source parity exercised synthetically, not
//! authorization to execute it, and nothing here is wired to the app.

use crate::auth_prefs_health::HealthAuthPrefs;
use crate::session_token::AccessTokenResolution;
use crate::v3_consent::{
    request_v3_consent, testing::NoopSnapshotStore, V3ConsentTransport, CONFLICT_SAFE_PROTOCOL_RELEASED,
};

/// SupabaseModels.kt:73-82. `timestamp` defaults to construction time in the
/// source; callers pass now_ms() explicitly here.
#[derive(Debug, Clone, PartialEq, Default)]
pub struct SyncResult {
    pub is_success: bool,
    pub message: String,
    pub synced_cycles_count: i32,
    pub synced_logs_count: i32,
    pub profile_synced: bool,
    pub timestamp: i64,
    pub should_retry: bool,
    pub requires_authentication: bool,
}

fn result(now: i64, is_success: bool, message: &str) -> SyncResult {
    SyncResult { is_success, message: message.into(), timestamp: now, ..Default::default() }
}

/// The catch-all branch (:142): any exception inside the try lands here.
pub fn catch_result(now: i64) -> SyncResult {
    SyncResult {
        is_success: false,
        message: "Backup couldn't finish. Your changes stay saved on this device; please try again.".into(),
        should_retry: true,
        timestamp: now,
        ..Default::default()
    }
}

#[allow(clippy::too_many_arguments)]
pub fn sync_all(
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
    // :115-116
    if !prefs.is_cloud_sync_allowed() && !prefs.has_pending_health_sync_revoke() {
        return result(now_ms(), true, "Online backup is off, so nothing was sent.");
    }
    // :117
    let Some(owner) = prefs.user_id() else {
        return result(now_ms(), false, "Please sign in to back up your records.");
    };
    // :118
    if prefs.owner_wiped && !prefs.has_pending_health_sync_revoke() {
        return result(now_ms(), true, "This device's records were erased; online backup stays paused.");
    }
    // :120-121
    if !prefs.has_pending_health_sync_revoke()
        && (!prefs.is_cloud_sync_allowed() || !CONFLICT_SAFE_PROTOCOL_RELEASED)
    {
        return result(now_ms(), false, "Online backup is paused for now. Your records stay safe on this device.");
    }
    // :123-128
    let token = match resolve_token() {
        AccessTokenResolution::Valid(t) => t,
        AccessTokenResolution::Missing | AccessTokenResolution::ReauthenticationRequired => {
            return SyncResult {
                requires_authentication: true,
                ..result(now_ms(), false, "Please sign in again to finish.")
            };
        }
        AccessTokenResolution::TemporarilyUnavailable => {
            return SyncResult {
                should_retry: true,
                ..result(now_ms(), false, "Your secure session isn't available right now. Your records stay private on this device.")
            };
        }
    };
    // :129-142 — try { ... } catch (_: Exception) { ... }
    match sync_all_body(&owner, prefs, consent_transport, pull, outbox, conflicts, current, now_ms, uuid, &token) {
        Ok(r) => r,
        Err(()) => catch_result(now_ms()),
    }
}

/// The try-body (:130-141). Err(()) models any exception escaping into the
/// catch branch. The upload path below the withdrawal branch is reachable
/// only when CONFLICT_SAFE_PROTOCOL_RELEASED is true (branch 4 returns
/// otherwise); it is ported 1:1 and corpus-locked like the other
/// release-gated paths, and performs no I/O in production parity.
#[allow(clippy::too_many_arguments)]
pub fn sync_all_body(
    owner: &str,
    prefs: &mut HealthAuthPrefs,
    consent_transport: &mut dyn V3ConsentTransport,
    pull: &mut dyn FnMut(&str, i64, i64, i64, &str) -> Result<i32, ()>,
    outbox: &mut dyn FnMut(&str) -> Result<i32, ()>,
    conflicts: &mut dyn FnMut(&str) -> Result<usize, ()>,
    current: &mut dyn FnMut() -> bool,
    now_ms: &mut dyn FnMut() -> i64,
    uuid: &mut dyn FnMut() -> String,
    token: &str,
) -> Result<SyncResult, ()> {
    // :131-133 — a pending withdrawal sends only a consent decision.
    if prefs.has_pending_health_sync_revoke() {
        // includeExistingData defaults false; the snapshot store is never touched.
        request_v3_consent(
            false, consent_transport, token, false, prefs, &mut NoopSnapshotStore, current, uuid, now_ms,
        )
        .map_err(|_| ())?;
        return Ok(result(now_ms(), true, "Your choice was saved. Online backup is off."));
    }
    // :134-141 — release-gated upload path (unreachable in production parity).
    let local = prefs.health_consent_generation();
    let session = prefs.session_generation();
    let Some(server) = prefs.server_health_consent_generation() else {
        return Ok(result(now_ms(), false, "Please allow online backup in Settings first."));
    };
    let pulled = pull(owner, local, server, session, token)?;
    let uploaded = outbox(token)?;
    if !current() {
        return Ok(result(now_ms(), false, "Backup stopped because the account or your choices changed."));
    }
    let timestamp = now_ms();
    let conflict_count = conflicts(owner)?;
    prefs.set_last_sync_timestamp(timestamp);
    prefs.set_last_sync_success(conflict_count == 0);
    let message = if conflict_count > 0 {
        format!("{conflict_count} saved record(s) need your choice in Settings; nothing was overwritten.")
    } else {
        format!("Backup complete ({uploaded} sent, {pulled} received).")
    };
    prefs.set_last_sync_message(&message);
    Ok(SyncResult {
        is_success: conflict_count == 0,
        message,
        synced_logs_count: uploaded + pulled,
        timestamp,
        ..Default::default()
    })
}
