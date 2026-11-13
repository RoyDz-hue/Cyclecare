package com.cyclecare.app.rust

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView

/**
 * Connected-screen wiring for the Rust-authored AppLockScreen.
 *
 * RustAppLockConfig.ENABLED is a compile-time spike switch, default OFF:
 * the Compose AppLockScreen remains the shipping lock UI until the Rust
 * screen has compile + runtime proof on device. Even when ENABLED, any
 * WebView preparation failure renders the Compose fallback - the user is
 * never left without a working lock screen.
 *
 * Auth semantics are unchanged: the bridge calls the SAME callbacks
 * MainActivity passes to the Compose screen (viewModel.unlockApp etc.),
 * so unlock, biometric, and forgot-credentials behavior is identical
 * whichever rendering is active.
 */
object RustAppLockConfig {
    const val ENABLED = false
}

@Composable
fun RustAppLockScreen(
    isFingerprintEnabled: () -> Boolean,
    onUnlock: (String) -> Boolean,
    onTriggerBiometric: () -> Unit,
    onForgotCredentials: () -> Unit,
    fallback: @Composable () -> Unit,
) {
    if (!RustAppLockConfig.ENABLED) {
        fallback()
        return
    }
    val context = LocalContext.current
    val webView = remember {
        runCatching {
            AppLockWebViewHost.createWebView(
                context = context,
                bridge = CycleCareAppLockBridge(
                    isFingerprintEnabled = isFingerprintEnabled,
                    onUnlock = onUnlock,
                    onTriggerBiometric = onTriggerBiometric,
                    onForgotCredentials = onForgotCredentials,
                ),
            ).apply { loadUrl(AppLockWebViewHost.url()) }
        }.getOrNull()
    }
    if (webView == null) {
        fallback()
    } else {
        AndroidView(factory = { webView })
    }
}
