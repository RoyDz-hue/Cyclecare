package com.cyclecare.app.rust

import android.annotation.SuppressLint
import android.content.Context
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebViewAssetLoader

/**
 * Hosts the Rust-authored AppLockScreen dist (Dioxus wasm) inside a WebView.
 * NOT yet wired into MainActivity - embedding decision belongs to the
 * migration owner flow. Local-assets only: every request resolves through
 * WebViewAssetLoader against app assets; anything else is denied.
 *
 * Contract with the Rust screen: window.cyclecareBridge (see
 * CycleCareAppLockBridge) must be registered BEFORE the page loads, and the
 * page must never run without it - the Compose AppLockScreen remains the
 * fallback rendering when this host cannot be prepared.
 */
object AppLockWebViewHost {

    private const val ORIGIN = "https://appassets.androidplatform.net"
    private const val ASSETS_PATH = "/rust/applock/"

    fun createLoader(context: Context): WebViewAssetLoader =
        WebViewAssetLoader.Builder()
            .addPathHandler(ASSETS_PATH, WebViewAssetLoader.AssetsPathHandler(context))
            .build()

    @SuppressLint("SetJavaScriptEnabled", "AddJavascriptInterface")
    fun createWebView(
        context: Context,
        bridge: CycleCareAppLockBridge,
        loader: WebViewAssetLoader = createLoader(context),
    ): WebView {
        val webView = WebView(context)
        webView.settings.javaScriptEnabled = true // required for wasm
        webView.settings.domStorageEnabled = false // lock screen persists nothing
        webView.settings.allowFileAccess = false
        webView.settings.allowContentAccess = false
        webView.settings.mediaPlaybackRequiresUserGesture = true
        webView.addJavascriptInterface(bridge, "cyclecareBridge")
        webView.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(
                view: WebView,
                request: WebResourceRequest,
            ): WebResourceResponse? {
                val url = request.url
                // Navigation allowlist: same https origin, assets path only.
                if (url.scheme != "https" || url.host != "appassets.androidplatform.net" ||
                    !url.path.orEmpty().startsWith(ASSETS_PATH)
                ) {
                    return WebResourceResponse("text/plain", "utf-8", 403, "Denied", emptyMap(), null)
                }
                return loader.shouldInterceptRequest(url)
            }

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean = true
            // No navigation away from the embedded page, ever.
        }
        return webView
    }

    fun url(): String = "$ORIGIN$ASSETS_PATH/index.html"
}
