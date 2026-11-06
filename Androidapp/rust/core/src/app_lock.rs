//! App-lock policy (launch gating + unlock decisions).
//! Port of CycleCareViewModel (ui/viewmodel, main@31b88f5): startup
//! isPinSet/isLocked (vm.kt:335-348), uiState.isPinEnabled merge
//! (vm.kt:365), startLocalOnly isAppLocked (vm.kt:1594), lockApp
//! (vm.kt:1890-1895), unlockApp (vm.kt:1496-1506), unlockViaBiometric
//! (vm.kt:1508). All truth tables executed on the portable JVM
//! (LockProbe.kt + LockProbe2.kt, kotlinc 2.1.0 / JRE 21, 2026-11-02/03)
//! and locked in app_lock_corpus.
//!
//! FOUR DISTINCT PREDICATES exist in source (do not merge them):
//! 1. Startup lock: hasCompletedInitialAuth && (isPinSet || pref fingerprint),
//!    isPinSet = pref pin_enabled && stored pin non-blank (PREF-ONLY; the
//!    settings flag does NOT lock at startup).
//! 2. uiState.isPinEnabled = pref pin_enabled || settings.isAppLockEnabled
//!    (display/state merge).
//! 3. lockApp (manual re-lock): uiState.isPinEnabled || uiState.fingerprint
//!    || settings.isAppLockEnabled - the ONLY predicate consulting the
//!    settings flag for locking, and it uses the MERGED uiState value.
//! 4. startLocalOnly: pref pin_enabled || pref fingerprint, NO blank-pin
//!    check (diverges from startup when pin_enabled=true with blank PIN).
//! saveQuickUnlockPin does NOT set isAppLocked (it enables the pref, the
//! settings flag via updateSettings, and uiState.isPinEnabled only).
//!
//! SAFETY-REVIEW QUIRKS (ported 1:1, labeled, not silently fixed):
//! - unlockApp FAILS OPEN when no PIN is stored: a wrong PIN with stored pin
//!   null/blank (e.g. insecure storage dropped the credential while
//!   pin_enabled stayed true) UNLOCKS the app.
//! - A whitespace-only stored PIN counts as blank: it neither locks at
//!   startup nor blocks a failed verify.
//! - unlockViaBiometric clears the lock unconditionally; the actual
//!   BiometricPrompt gate lives in BiometricHelper (Android-only, host-inert).
//! - settings.isAppLockEnabled can lock via lockApp while the startup path
//!   would NOT lock (settings flag ignored there) - a settings-only lock
//!   state is reachable only through the manual lockApp call.

use crate::kotlin_text::kotlin_is_blank;

/// isPinSet: isPinEnabled && !pin.isNullOrBlank() (startup path, pref-only).
pub fn is_pin_set(is_pin_enabled: bool, stored_pin: Option<&str>) -> bool {
    is_pin_enabled && !stored_pin.map(kotlin_is_blank).unwrap_or(true)
}

/// Startup lock decision (vm.kt:335-348):
/// hasCompletedInitialAuth && (isPinSet || pref fingerprint). Pref-only;
/// settings.isAppLockEnabled does NOT participate at startup.
pub fn is_locked_at_startup(
    has_completed_initial_auth: bool,
    is_pin_enabled: bool,
    stored_pin: Option<&str>,
    is_fingerprint_enabled: bool,
) -> bool {
    has_completed_initial_auth
        && (is_pin_set(is_pin_enabled, stored_pin) || is_fingerprint_enabled)
}

/// uiState.isPinEnabled (vm.kt:365): pref pin_enabled || settings flag.
pub fn ui_pin_enabled(pref_pin_enabled: bool, settings_app_lock_enabled: bool) -> bool {
    pref_pin_enabled || settings_app_lock_enabled
}

/// lockApp (vm.kt:1890-1895): the manual re-lock predicate, the only one
/// consulting settings.isAppLockEnabled. Returns the new isAppLocked value
/// (source sets locked only when the predicate holds; otherwise unchanged).
pub fn lock_app(ui_pin_enabled: bool, ui_fingerprint_enabled: bool, settings_app_lock_enabled: bool) -> bool {
    ui_pin_enabled || ui_fingerprint_enabled || settings_app_lock_enabled
}

/// startLocalOnly (vm.kt:1594): pref pin_enabled || pref fingerprint, with
/// NO blank-pin check (diverges from is_locked_at_startup when
/// pin_enabled=true but the stored PIN is blank/absent).
pub fn is_locked_after_start_local_only(
    pref_pin_enabled: bool,
    pref_fingerprint_enabled: bool,
) -> bool {
    pref_pin_enabled || pref_fingerprint_enabled
}

/// unlockApp: a successful verify unlocks; a failed verify STILL unlocks when
/// the stored PIN is null/blank (fail-open quirk, safety-review labeled);
/// otherwise stays locked.
pub fn unlock_app(pin_verified: bool, stored_pin: Option<&str>) -> bool {
    if pin_verified {
        return true;
    }
    if stored_pin.map(kotlin_is_blank).unwrap_or(true) {
        return true;
    }
    false
}

/// unlockViaBiometric: clears the lock unconditionally once the platform
/// prompt succeeds (prompt itself is Android-only, host-inert).
pub fn unlock_via_biometric() -> bool {
    true
}
