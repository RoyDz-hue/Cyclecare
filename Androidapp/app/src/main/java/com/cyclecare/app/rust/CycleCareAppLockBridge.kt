package com.cyclecare.app.rust

import android.webkit.JavascriptInterface

/**
 * JavascriptInterface seam for the Rust-authored AppLockScreen
 * (window.cyclecareBridge). Every method maps 1:1 onto the PRODUCTION
 * callbacks MainActivity passes to the Compose AppLockScreen - the Rust
 * screen is a second rendering of the same contract, not a new auth path.
 *
 * unlock() vocabulary (single string, lowercase): "unlocked" when
 * production onUnlock returns true, "rejected" when it returns false,
 * "unavailable" on any technical failure. A technical failure must never
 * surface as "Incorrect PIN" and must never count as an auth attempt
 * (production verifyPin currently counts nothing; the seam stays honest
 * if rate-limiting is ever added).
 */
class CycleCareAppLockBridge(
    private val isFingerprintEnabled: () -> Boolean,
    // Production: viewModel.unlockApp(pin) - PBKDF2 verify, unlock on
    // success, legacy-format migration on first success. The JS side must
    // invoke unlock() EXACTLY once per user attempt.
    private val onUnlock: (String) -> Boolean,
    // Production: BiometricHelper.showBiometricPrompt via MainActivity.
    private val onTriggerBiometric: () -> Unit,
    // Production: viewModel.setShowAuthSheet(true).
    private val onForgotCredentials: () -> Unit,
) {
    @JavascriptInterface
    fun unlock(pin: String): String = try {
        if (onUnlock(pin)) "unlocked" else "rejected"
    } catch (t: Throwable) {
        // Technical failure: distinct from rejected credentials, no detail leak.
        "unavailable"
    }

    @JavascriptInterface
    fun isBiometricEnabled(): Boolean = runCatching { isFingerprintEnabled() }.getOrDefault(false)

    @JavascriptInterface
    fun triggerBiometric() {
        runCatching { onTriggerBiometric() }
    }

    @JavascriptInterface
    fun forgotCredentials() {
        runCatching { onForgotCredentials() }
    }
}
