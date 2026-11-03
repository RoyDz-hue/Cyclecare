//! Source-faithful health-sync slice of AuthPreferences
//! (AuthPreferences.kt:138-194, :264, :356-361, :507-517, :522-531) over the
//! scratch SharedPreferences model. Every guard, key shape, single-editor
//! commit, and failure mode follows the source. NOT wired to the Android
//! app: the secure store (user id), OwnerDataGate (wiped), and WorkManager
//! cancellation are injected seams; synthetic scratch data only.

use crate::prefs_scratch::ScratchPrefs;
use crate::v3_consent::{ConsentPrefs, V3ConsentError};

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum AuthPrefsError {
    /// Math.addExact(generation, 1) overflowed -> ArithmeticException parity.
    GenerationOverflow,
}

pub struct HealthAuthPrefs<'a> {
    pub prefs: &'a mut ScratchPrefs,
    /// AuthPreferences.kt:20 — preferenceStore.secure.
    pub secure_available: bool,
    /// Secure-store user id (:356-361, readSecureString(KEY_USER_ID)). The
    /// Android secure store stays a separate seam; this is its read value.
    pub secure_user_id: Option<String>,
    /// effectiveUserId device fallback (:522-531): java
    /// UUID.nameUUIDFromBytes(deviceId) computed by host glue, injected.
    pub device_fallback_owner: String,
    /// OwnerDataGate.isWiped(context, owner) seam (:118, :189).
    pub owner_wiped: bool,
}

const KEY_CLOUD_SYNC_ALLOWED: &str = "cloud_sync_allowed";
const KEY_HEALTH_CONSENT_GENERATION: &str = "health_consent_generation";

impl HealthAuthPrefs<'_> {
    /// :356-358 (read side only; the setter lives in the auth slice and bumps
    /// session_generation_v3 via addExact on change).
    pub fn user_id(&self) -> Option<String> { self.secure_user_id.clone() }

    /// :522-531: signed-in id, else the host-computed device fallback.
    pub fn effective_user_id(&self) -> String {
        match &self.secure_user_id {
            Some(uid) if !uid.is_empty() => uid.clone(),
            _ => self.device_fallback_owner.clone(),
        }
    }

    /// :264
    pub fn session_generation(&self) -> i64 { self.prefs.get_long("session_generation_v3", 0) }

    /// :138-139
    pub fn is_cloud_sync_allowed(&self) -> bool {
        let key = format!("{KEY_CLOUD_SYNC_ALLOWED}_v2_{}", self.effective_user_id());
        self.prefs.get_bool(&key, false)
    }

    /// :140-156 — one editor commit makes the decision and generation advance
    /// visible together; revoking also marks the durable pending-revoke flag.
    /// (WorkManager cancellation is host glue, not modeled.) The commit()
    /// result is ignored exactly as in the source.
    pub fn set_is_cloud_sync_allowed(&mut self, value: bool) -> Result<(), AuthPrefsError> {
        let owner = self.effective_user_id();
        let key = format!("{KEY_CLOUD_SYNC_ALLOWED}_v2_{owner}");
        let generation_key = format!("{KEY_HEALTH_CONSENT_GENERATION}_{owner}");
        let previous = self.prefs.get_bool(&key, false);
        if previous != value {
            let generation = self.prefs.get_long(&generation_key, 0);
            let next = generation.checked_add(1).ok_or(AuthPrefsError::GenerationOverflow)?;
            let mut e = self.prefs.edit().put_bool(&key, value).put_long(&generation_key, next);
            if !value {
                e = e.put_bool(&format!("health_sync_pending_revoke_{owner}"), true);
            }
            e.commit();
        }
        Ok(())
    }

    /// :159-161
    pub fn health_consent_generation(&self) -> i64 {
        let key = format!("{KEY_HEALTH_CONSENT_GENERATION}_{}", self.effective_user_id());
        self.prefs.get_long(&key, 0)
    }
    pub fn set_health_consent_generation(&mut self, value: i64) {
        let key = format!("{KEY_HEALTH_CONSENT_GENERATION}_{}", self.effective_user_id());
        self.prefs.edit().put_long(&key, value).commit();
    }

    /// :163-164
    pub fn has_pending_health_sync_revoke(&self) -> bool {
        self.secure_available
            && self.prefs.get_bool(&format!("health_sync_pending_revoke_{}", self.effective_user_id()), false)
    }
    /// :165-167
    pub fn mark_pending_health_sync_revoke(&mut self) {
        if self.secure_available {
            let key = format!("health_sync_pending_revoke_{}", self.effective_user_id());
            self.prefs.edit().put_bool(&key, true).commit();
        }
    }

    /// :168-181. Ok(None) = guard failed or commit() failed (Kotlin null).
    /// Err = the addExact inside threw (Kotlin ArithmeticException).
    pub fn pause_health_sync_for(&mut self, owner: &str, session: i64) -> Result<Option<i64>, AuthPrefsError> {
        if !self.secure_available || self.user_id().as_deref() != Some(owner) || self.session_generation() != session {
            return Ok(None);
        }
        let allowed_key = format!("{KEY_CLOUD_SYNC_ALLOWED}_v2_{owner}");
        let generation_key = format!("{KEY_HEALTH_CONSENT_GENERATION}_{owner}");
        let previous_generation = self.prefs.get_long(&generation_key, 0);
        let generation = if self.prefs.get_bool(&allowed_key, false) {
            previous_generation.checked_add(1).ok_or(AuthPrefsError::GenerationOverflow)?
        } else {
            previous_generation
        };
        let committed = self
            .prefs
            .edit()
            .put_bool(&allowed_key, false)
            .put_long(&generation_key, generation)
            .put_bool(&format!("health_sync_pending_revoke_{owner}"), true)
            .remove(&format!("health_sync_receipt_server_{owner}"))
            .remove(&format!("health_sync_receipt_local_{owner}"))
            .remove(&format!("health_sync_receipt_session_{owner}"))
            .commit();
        if !committed {
            return Ok(None);
        }
        Ok(Some(generation))
    }

    /// :182-187 — all guards must pass or the receipt is treated as absent.
    pub fn server_health_consent_generation(&self) -> Option<i64> {
        let owner = self.effective_user_id();
        if !self.secure_available
            || !self.is_cloud_sync_allowed()
            || self.has_pending_health_sync_revoke()
            || self.prefs.get_long(&format!("health_sync_receipt_local_{owner}"), -1) != self.health_consent_generation()
            || self.prefs.get_long(&format!("health_sync_receipt_session_{owner}"), -1) != self.session_generation()
        {
            return None;
        }
        let v = self.prefs.get_long(&format!("health_sync_receipt_server_{owner}"), 0);
        if v > 0 { Some(v) } else { None }
    }

    /// :188-194 — the bool is the commit() result after all guards pass.
    pub fn save_server_health_consent_receipt(
        &mut self,
        owner: &str,
        session: i64,
        local_generation: i64,
        server_generation: i64,
        enabled: bool,
    ) -> bool {
        if !self.secure_available
            || self.user_id().as_deref() != Some(owner)
            || self.session_generation() != session
            || self.health_consent_generation() != local_generation
            || server_generation <= 0
            || (enabled && (!self.is_cloud_sync_allowed() || self.has_pending_health_sync_revoke() || self.owner_wiped))
        {
            return false;
        }
        let mut e = self.prefs.edit().put_bool(&format!("health_sync_pending_revoke_{owner}"), false);
        if enabled {
            e = e
                .put_long(&format!("health_sync_receipt_server_{owner}"), server_generation)
                .put_long(&format!("health_sync_receipt_local_{owner}"), local_generation)
                .put_long(&format!("health_sync_receipt_session_{owner}"), session);
        } else {
            e = e
                .remove(&format!("health_sync_receipt_server_{owner}"))
                .remove(&format!("health_sync_receipt_local_{owner}"))
                .remove(&format!("health_sync_receipt_session_{owner}"));
        }
        e.commit()
    }

    /// :507-517 — lastSync* use apply() (asynchronous persist, no result).
    pub fn set_last_sync_timestamp(&mut self, value: i64) {
        self.prefs.edit().put_long("last_sync_timestamp", value).apply();
    }
    pub fn set_last_sync_message(&mut self, value: &str) {
        self.prefs.edit().put_string("last_sync_message", value).apply();
    }
    pub fn set_last_sync_success(&mut self, value: bool) {
        self.prefs.edit().put_bool("last_sync_status", value).apply();
    }
    pub fn last_sync_timestamp(&self) -> i64 { self.prefs.get_long("last_sync_timestamp", 0) }
    pub fn last_sync_message(&self) -> String {
        self.prefs.get_string("last_sync_message").unwrap_or_else(|| "Never synced".into())
    }
    pub fn last_sync_success(&self) -> bool { self.prefs.get_bool("last_sync_status", false) }
}

/// Lets the ported requestV3Consent run against this real implementation.
impl ConsentPrefs for HealthAuthPrefs<'_> {
    fn user_id(&mut self) -> Option<String> { HealthAuthPrefs::user_id(self) }
    fn session_generation(&mut self) -> i64 { HealthAuthPrefs::session_generation(self) }
    fn is_cloud_sync_allowed(&mut self) -> bool { HealthAuthPrefs::is_cloud_sync_allowed(self) }
    fn has_pending_health_sync_revoke(&mut self) -> bool { HealthAuthPrefs::has_pending_health_sync_revoke(self) }
    fn health_consent_generation(&mut self) -> i64 { HealthAuthPrefs::health_consent_generation(self) }
    fn pause_health_sync_for(&mut self, owner: &str, session: i64) -> Result<Option<i64>, V3ConsentError> {
        HealthAuthPrefs::pause_health_sync_for(self, owner, session)
            .map_err(|_| V3ConsentError::RevisionOverflow)
    }
    fn save_server_health_consent_receipt(&mut self, owner: &str, session: i64, local: i64, server: i64, enabled: bool) -> bool {
        HealthAuthPrefs::save_server_health_consent_receipt(self, owner, session, local, server, enabled)
    }
}

/// SupabaseSyncService.kt:27 - current(owner, session, local, server),
/// verbatim and COMPLETE: every term is re-read live from the preferences
/// on each evaluation, exactly like the Kotlin property getters. This is
/// the real gate the injected `current` closures stand in for; wire it at
/// the call site as `|| current_gate(&prefs, owner, session, local, server)`.
/// serverHealthConsentGeneration is nullable (:182-187); the Kotlin
/// `== server` is true only when a receipt exists and matches.
pub fn current_gate(
    prefs: &HealthAuthPrefs,
    owner: &str,
    session: i64,
    local: i64,
    server: i64,
) -> bool {
    prefs.user_id().as_deref() == Some(owner)
        && prefs.session_generation() == session
        && prefs.health_consent_generation() == local
        && prefs.server_health_consent_generation() == Some(server)
        && prefs.is_cloud_sync_allowed()
        && !prefs.owner_wiped
}
