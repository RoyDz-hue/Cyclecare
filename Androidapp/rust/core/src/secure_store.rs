//! Secure-prefs storage contract (AuthPreferences storage layer,
//! security/AuthPreferences.kt, main@31b88f5). Semantics executed on the
//! portable JVM over map stand-ins (StoreProbe.kt, kotlinc 2.1.0 / JRE 21,
//! 2026-11-02) and locked in secure_store_corpus. SharedPreferences
//! editor/commit/clear mechanics are adapter obligations, documented here.
//!
//! PARITY (source-verbatim) section:
//! - read_secure_string: insecure store reads EVERY credential as null -
//!   "absent configured value" and "unavailable secure storage" are
//!   INDISTINGUISHABLE to callers (this conflation is what makes the
//!   app_lock fail-open reachable on storage loss; see app_lock.rs).
//! - write_secure_string: insecure store REMOVES the key on any write;
//!   secure store removes on blank/whitespace values.
//! - Boolean gates (pin_enabled etc.): insecure reads false, writes dropped.
//!   EXCEPTION: hasCompletedInitialAuth is NOT a plain gate - it is
//!   isLocalOnlyMode || (secure && stored); that variant lives in
//!   lifecycle::has_completed_initial_auth, already corpus-locked.
//! - Fallback sanitization: when EncryptedSharedPreferences construction
//!   fails, the plaintext legacy store has exactly these 14 credential keys
//!   REMOVED (never keep credentials in the plaintext fallback).
//! - migrate_legacy: copies legacy entries whose keys are absent in secure
//!   (existing secure wins), typed putString/putBoolean/putInt/putLong/
//!   putFloat ONLY (a Double is dropped), then clears legacy; exceptions
//!   swallowed (best-effort, silent).
//!
//! PROPOSED HARDENING (NOT source parity, NOT wired into any parity path;
//!   recorded separately per security-review hold): credential presence as a
//!   tri-state distinguishing StorageUnavailable from AbsentConfigured, and
//!   biometric unlock bound to the platform callback result. Adopting either
//!   changes migration behavior vs the current source and needs an explicit
//!   safety decision.

use std::collections::BTreeMap;

/// Typed preference value, mirroring the five migrated SharedPreferences
/// types (Double is intentionally absent: the source drops it).
#[derive(Debug, Clone, PartialEq)]
pub enum PrefsValue {
    Str(String),
    Bool(bool),
    Int(i32),
    Long(i64),
    Float(f32),
}

pub type Prefs = BTreeMap<String, PrefsValue>;

/// The exact 14 credential keys the plaintext fallback removes, in source
/// order (createEncryptedOrFallbackPrefs catch branch).
pub const FALLBACK_SANITIZE_KEYS: [&str; 14] = [
    "user_id",
    "user_email",
    "access_token",
    "refresh_token",
    "session_expires_at",
    "pending_email",
    "google_pkce_verifier",
    "pending_auth_action",
    "pending_guest_email_link",
    "needs_password_setup",
    "quick_unlock_pin",
    "pin_enabled",
    "fingerprint_enabled",
    "quick_unlock_asked",
];

// ---------- PARITY (source-verbatim) ----------

/// readSecureString: secure ? stored string : null. Insecure storage reads
/// every credential as absent.
pub fn read_secure_string(secure: bool, prefs: &Prefs, key: &str) -> Option<String> {
    if !secure {
        return None;
    }
    match prefs.get(key) {
        Some(PrefsValue::Str(s)) => Some(s.clone()),
        _ => None,
    }
}

/// writeSecureString: secure && non-blank -> put; otherwise REMOVE the key
/// (insecure store drops credentials on write; blank values remove).
pub fn write_secure_string(secure: bool, prefs: &mut Prefs, key: &str, value: Option<&str>) {
    if secure && !value.map(crate::kotlin_text::kotlin_is_blank).unwrap_or(true) {
        prefs.insert(key.to_string(), PrefsValue::Str(value.unwrap().to_string()));
    } else {
        prefs.remove(key);
    }
}

/// Boolean gate read (isPinEnabled shape): secure && stored bool.
pub fn read_gate(secure: bool, prefs: &Prefs, key: &str) -> bool {
    secure && matches!(prefs.get(key), Some(PrefsValue::Bool(true)))
}

/// Boolean gate write: insecure store DROPS the write entirely.
pub fn write_gate(secure: bool, prefs: &mut Prefs, key: &str, value: bool) {
    if secure {
        prefs.insert(key.to_string(), PrefsValue::Bool(value));
    }
}

/// Fallback sanitization: remove exactly the 14 credential keys from the
/// plaintext legacy store; everything else survives.
pub fn fallback_sanitize(plaintext_legacy: &mut Prefs) {
    for k in FALLBACK_SANITIZE_KEYS {
        plaintext_legacy.remove(k);
    }
}

/// migrateLegacyPrefs: copy-if-absent with typed puts (String/Boolean/Int/
/// Long/Float only), then clear legacy. Existing secure values win.
pub fn migrate_legacy(legacy: &mut Prefs, secure: &mut Prefs) {
    if !legacy.is_empty() {
        for (key, value) in legacy.iter() {
            if !secure.contains_key(key) {
                secure.insert(key.clone(), value.clone());
            }
        }
        legacy.clear();
    }
}

// ---------- PROPOSED HARDENING (not source parity, not wired) ----------

/// Tri-state credential presence. The SOURCE conflates the first variant
/// with AbsentConfigured via readSecureString -> null; this resolver keeps
/// them apart for a future reviewed safety change.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum CredentialPresence {
    /// Secure storage unavailable (or corrupt): presence is UNKNOWN. Source
    /// parity treats this as absent (fail-open in app_lock); a hardened
    /// reading must not.
    StorageUnavailable,
    /// Secure storage available and the key is absent or blank.
    AbsentConfigured,
    /// Secure storage available and a non-blank value is stored.
    Present,
}

pub fn credential_presence(secure: bool, prefs: &Prefs, key: &str) -> CredentialPresence {
    if !secure {
        return CredentialPresence::StorageUnavailable;
    }
    match read_secure_string(true, prefs, key) {
        Some(s) if !crate::kotlin_text::kotlin_is_blank(&s) => CredentialPresence::Present,
        _ => CredentialPresence::AbsentConfigured,
    }
}

/// Proposed biometric unlock bound to the platform callback result. Source
/// parity (app_lock::unlock_via_biometric) clears unconditionally because
/// the BiometricPrompt gate lives in Android-only BiometricHelper; this
/// variant makes the dependency explicit for a host/FFI adapter.
pub fn unlock_via_biometric_bound(prompt_succeeded: bool) -> bool {
    prompt_succeeded
}
