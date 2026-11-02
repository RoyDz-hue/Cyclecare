//! requestV3Consent + journalExplicitSnapshot
//! (SupabaseSyncService.kt:31-57) with the AuthPreferences seams they call
//! (AuthPreferences.kt:168-194) and the `current` owner/consent gate
//! (SupabaseSyncService.kt:27).
//!
//! Production parity: conflictSafeProtocolReleased() = false
//! (SupabaseSyncService.kt:20), so the enabled=true arm ALWAYS fails with
//! ReleasePolicyClosed before any prefs mutation or network call; the live
//! production path is withdrawal (enabled=false), which stays available
//! while the release is closed. The enabled arm is ported 1:1 for parity
//! with the source and becomes reachable only if that constant ever flips.

use crate::sync_codec::{
    log_payload, profile_payload, settings_payload, CycleDayLog, CycleSettings, OnboardingProfile,
};
use crate::v3_outbox::OutboxEntry;
use crate::v3_pull::RevisionRow;
use crate::v3_sync::V3ConsentReceipt;

/// SupabaseSyncService.kt:20 — 1:1 parity constant. False in production.
pub const CONFLICT_SAFE_PROTOCOL_RELEASED: bool = false;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum V3ConsentError {
    /// error("owner_required") — no signed-in user id.
    OwnerRequired,
    /// check(conflictSafeProtocolReleased()) { "v3_release_policy_closed" }.
    /// The only possible enabled=true outcome in production parity.
    ReleasePolicyClosed,
    /// check(isCloudSyncAllowed && !hasPendingHealthSyncRevoke)
    /// { "explicit_current_consent_required" }.
    ExplicitCurrentConsentRequired,
    /// pauseHealthSyncFor returned null -> error("owner_changed"): the
    /// secure-storage / owner / session guard failed or the prefs commit
    /// failed (AuthPreferences.kt:168-181).
    OwnerChanged,
    /// transport.setConsent threw. Kotlin: the exception propagates; a failed
    /// revocation leaves the durable pending-revoke flag set (source comment).
    Transport,
    /// check(receipt.enabled == enabled && saveServerHealthConsentReceipt(...))
    /// { "stale_or_invalid_consent_receipt" }. Short-circuit parity: the save
    /// is NOT attempted when receipt.enabled != enabled.
    StaleOrInvalidConsentReceipt,
    /// journalExplicitSnapshot: check(current(owner, session, local, server))
    /// { "consent_changed" } inside the withTransaction.
    ConsentChanged,
    /// Math.addExact overflow parity (journal sequence or pause generation):
    /// ArithmeticException in Kotlin, typed error here — never wraparound,
    /// never a panic across the FFI boundary.
    RevisionOverflow,
    /// A DAO read/write threw inside the snapshot withTransaction: the whole
    /// snapshot journal rolls back and the exception leaves requestV3Consent.
    Db,
}

/// The AuthPreferences surface requestV3Consent reads/writes. Implementations
/// own the internal guards exactly as AuthPreferences.kt does: secure-storage
/// availability, owner/session match, single-editor-commit atomicity, the
/// OwnerDataGate wiped check, and commit() failure (-> null / false).
pub trait ConsentPrefs {
    fn user_id(&mut self) -> Option<String>;
    fn session_generation(&mut self) -> i64;
    fn is_cloud_sync_allowed(&mut self) -> bool;
    fn has_pending_health_sync_revoke(&mut self) -> bool;
    fn health_consent_generation(&mut self) -> i64;
    /// AuthPreferences.kt:168-181. Ok(None) = guard or commit failure
    /// (Kotlin returns null). Err = the addExact inside threw (Kotlin
    /// ArithmeticException propagates out of requestV3Consent).
    fn pause_health_sync_for(&mut self, owner: &str, session: i64)
        -> Result<Option<i64>, V3ConsentError>;
    /// AuthPreferences.kt:188-194; the bool is the SharedPreferences commit()
    /// result after all guards pass.
    fn save_server_health_consent_receipt(
        &mut self,
        owner: &str,
        session: i64,
        local: i64,
        server: i64,
        enabled: bool,
    ) -> bool;
}

pub trait V3ConsentTransport {
    /// HealthSyncV3Transport.setConsent (HealthSyncV3Transport.kt:21).
    fn set_consent(&mut self, enabled: bool, access_token: &str) -> Result<V3ConsentReceipt, ()>;
}

/// requestV3Consent (SupabaseSyncService.kt:32-44).
/// `current` is the live owner/consent gate closure (SupabaseSyncService.kt:27);
/// `uuid` is the runtime UUID provider (fresh ids demonstrate provider use
/// only); `now_ms` is System.currentTimeMillis.
#[allow(clippy::too_many_arguments)]
pub fn request_v3_consent(
    enabled: bool,
    transport: &mut dyn V3ConsentTransport,
    access_token: &str,
    include_existing_data: bool,
    prefs: &mut dyn ConsentPrefs,
    journal_store: &mut dyn V3SnapshotStore,
    current: &mut dyn FnMut() -> bool,
    uuid: &mut dyn FnMut() -> String,
    now_ms: &mut dyn FnMut() -> i64,
) -> Result<V3ConsentReceipt, V3ConsentError> {
    let owner = prefs.user_id().ok_or(V3ConsentError::OwnerRequired)?;
    let session = prefs.session_generation();
    if enabled {
        if !CONFLICT_SAFE_PROTOCOL_RELEASED {
            return Err(V3ConsentError::ReleasePolicyClosed);
        }
        // Unreachable in production parity (the constant above is false);
        // kept 1:1 with the source check ordering.
        if !(prefs.is_cloud_sync_allowed() && !prefs.has_pending_health_sync_revoke()) {
            return Err(V3ConsentError::ExplicitCurrentConsentRequired);
        }
    }
    let local = if enabled {
        prefs.health_consent_generation()
    } else {
        prefs
            .pause_health_sync_for(&owner, session)?
            .ok_or(V3ConsentError::OwnerChanged)?
    };
    let receipt = transport
        .set_consent(enabled, access_token)
        .map_err(|_| V3ConsentError::Transport)?;
    // && short-circuit parity: save is skipped when receipt.enabled != enabled.
    if !(receipt.enabled == enabled
        && prefs.save_server_health_consent_receipt(&owner, session, local, receipt.generation, enabled))
    {
        return Err(V3ConsentError::StaleOrInvalidConsentReceipt);
    }
    // Historical health data is never automatically enrolled by background
    // sync — only by this explicit opt-in flag (source comment, :42).
    if enabled && include_existing_data {
        journal_explicit_snapshot(&owner, session, local, receipt.generation, journal_store, current, uuid, now_ms)?;
    }
    Ok(receipt)
}

/// The DAO surface journalExplicitSnapshot uses inside its ONE
/// withTransaction (SupabaseSyncService.kt:45-57). All writes fallible: any
/// Err aborts the transaction and the whole snapshot rolls back.
pub trait V3SnapshotTx {
    fn revision(&mut self, owner: &str, id: &str) -> Result<Option<RevisionRow>, V3ConsentError>;
    /// removeReviewedPending: DELETE FROM health_sync_outbox WHERE ownerId
    /// AND recordId AND revision <= throughLocalRevision (HealthSyncLocal.kt:91-92).
    fn remove_reviewed_pending(&mut self, owner: &str, id: &str, through_local_revision: i64) -> Result<(), V3ConsentError>;
    fn put_revision(&mut self, owner: &str, id: &str, rev: i64, hash: Option<String>, tombstone: bool, now: i64, server_rev: i64) -> Result<(), V3ConsentError>;
    fn enqueue(&mut self, entry: OutboxEntry) -> Result<(), V3ConsentError>;
    /// cycleDao().getAllLogsList() — DAO ordering preserved.
    fn all_logs(&mut self) -> Result<Vec<CycleDayLog>, V3ConsentError>;
    fn settings(&mut self) -> Result<Option<CycleSettings>, V3ConsentError>;
    fn profile(&mut self) -> Result<Option<OnboardingProfile>, V3ConsentError>;
}

pub trait V3SnapshotStore {
    /// ONE atomic transaction for the whole snapshot (Kotlin withTransaction):
    /// Err from the body rolls back every journal write.
    fn transaction(&mut self, body: &mut dyn FnMut(&mut dyn V3SnapshotTx) -> Result<(), V3ConsentError>) -> Result<(), V3ConsentError>;
}

/// journalExplicitSnapshot (SupabaseSyncService.kt:45-57). Reachable only
/// from the enabled=true + includeExistingData arm, which production parity
/// gates shut (ReleasePolicyClosed); ported 1:1 so the wiring is ready and
/// corpus-locked for the day the release constant flips.
pub fn journal_explicit_snapshot(
    owner: &str,
    session: i64,
    local: i64,
    server: i64,
    store: &mut dyn V3SnapshotStore,
    current: &mut dyn FnMut() -> bool,
    uuid: &mut dyn FnMut() -> String,
    now_ms: &mut dyn FnMut() -> i64,
) -> Result<(), V3ConsentError> {
    let _ = (session, server); // carried by the `current` closure, as in v3_pull
    store.transaction(&mut |tx: &mut dyn V3SnapshotTx| -> Result<(), V3ConsentError> {
        if !current() {
            return Err(V3ConsentError::ConsentChanged);
        }
        let mut journal = |tx: &mut dyn V3SnapshotTx, id: &str, payload: String| -> Result<(), V3ConsentError> {
            let prior = tx.revision(owner, id)?;
            let prior_local = prior.as_ref().map(|p| p.revision).unwrap_or(0);
            let prior_server = prior.as_ref().map(|p| p.server_revision).unwrap_or(0);
            let sequence = prior_local.checked_add(1).ok_or(V3ConsentError::RevisionOverflow)?;
            tx.remove_reviewed_pending(owner, id, prior_local)?;
            let h = crate::sync_codec::hash(Some(&payload));
            tx.put_revision(owner, id, sequence, Some(h.clone()), false, now_ms(), prior_server)?;
            tx.enqueue(OutboxEntry {
                owner: owner.to_string(),
                operation_id: uuid(),
                record_id: id.to_string(),
                kind: "UPSERT".to_string(),
                base_revision: prior_server,
                revision: sequence,
                payload_json: Some(payload),
                payload_hash: h,
                consent_generation: local,
                attempts: 0,       // HealthSyncOutboxEntry entity default
                next_attempt_at: 0, // HealthSyncOutboxEntry entity default
            })?;
            Ok(())
        };
        // Source order: all day logs (DAO order), then settings, then profile.
        for log in tx.all_logs()? {
            journal(tx, &log.date.clone(), log_payload(&log))?;
        }
        if let Some(s) = tx.settings()? {
            journal(tx, "settings", settings_payload(&s))?;
        }
        if let Some(p) = tx.profile()? {
            journal(tx, "profile", profile_payload(&p))?;
        }
        Ok(())
    })
}
