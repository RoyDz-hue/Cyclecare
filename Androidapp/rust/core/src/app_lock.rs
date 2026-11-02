//! App-lock policy (launch gating + unlock decisions).
//! Port of CycleCareViewModel (ui/viewmodel, main@31b88f5): startup
//! isPinSet/isLocked (vm.kt:348), saveQuickUnlockPin's isAppLocked variant
//! (vm.kt:1594), unlockApp (vm.kt:1496-1506), unlockViaBiometric
//! (vm.kt:1508). All truth tables executed on the portable JVM (LockProbe.kt,
//! kotlinc 2.1.0 / JRE 21, 2026-11-02) and locked in app_lock_corpus.
//!
//! SAFETY-REVIEW QUIRKS (ported 1:1, labeled, not silently fixed):
//! - unlockApp FAILS OPEN when no PIN is stored: a wrong PIN with
//!   stored pin null/blank (e.g. insecure storage dropped the credential
//!   while pin_enabled stayed true) UNLOCKS the app.
//! - stored pin "  " (whitespace) counts as BLANK (isNullOrBlank), so it
//!   neither locks at startup nor blocks a failed verify.
//! - saveQuickUnlockPin computes isAppLocked as isPinEnabled ||
//!   isFingerprintEnabled WITHOUT the non-blank-pin check the startup path
//!   uses; the two predicates disagree when pin_enabled=true with a
//!   blank/absent stored PIN.
//! - unlockViaBiometric clears the lock unconditionally; the actual
//!   BiometricPrompt gate lives in BiometricHelper (Android-only, host-inert).

use crate::kotlin_text::kotlin_is_blank;

/// isPinSet: isPinEnabled && !pin.isNullOrBlank() (startup path).
pub fn is_pin_set(is_pin_enabled: bool, stored_pin: Option<&str>) -> bool {
    is_pin_enabled && !stored_pin.map(kotlin_is_blank).unwrap_or(true)
}

/// Startup lock decision: hasCompletedInitialAuth && (isPinSet || fingerprint).
pub fn is_locked_at_startup(
    has_completed_initial_auth: bool,
    is_pin_enabled: bool,
    stored_pin: Option<&str>,
    is_fingerprint_enabled: bool,
) -> bool {
    has_completed_initial_auth
        && (is_pin_set(is_pin_enabled, stored_pin) || is_fingerprint_enabled)
}

/// saveQuickUnlockPin variant: isPinEnabled || isFingerprintEnabled, with NO
/// blank-pin check (diverges from is_locked_at_startup when pin_enabled=true
/// but the stored PIN is blank/absent).
pub fn is_locked_after_quick_unlock_save(
    is_pin_enabled: bool,
    is_fingerprint_enabled: bool,
) -> bool {
    is_pin_enabled || is_fingerprint_enabled
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
