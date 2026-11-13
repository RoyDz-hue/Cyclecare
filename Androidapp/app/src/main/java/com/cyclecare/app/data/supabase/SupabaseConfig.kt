package com.cyclecare.app.data.supabase

import android.util.Base64
import com.cyclecare.app.BuildConfig
import java.nio.charset.StandardCharsets
import org.json.JSONObject

/** Validates the public client pair without ever falling back to production. */
object SupabaseConfig {
    const val PROJECT_REF = "apjqcocxtylsofgxhetb"
    const val DEFAULT_URL = "https://apjqcocxtylsofgxhetb.supabase.co"
    const val DEFAULT_PUBLISHABLE_KEY = "sb_publishable_ziAlyPmKSv7kQZjbpbIuwA_6er-NufM"

    internal data class ClientSettings(val url: String, val apiKey: String) {
        val isConfigured: Boolean get() = url.isNotBlank() && apiKey.isNotBlank()
    }

    private val clientSettings: ClientSettings
        get() {
            // Native-first: core supabase_config parity port of this exact
            // resolution (NEW IMPLEMENTATION wiring, 2026-11-13). A null
            // reply (native unavailable/malformed) keeps the Kotlin
            // resolution below, byte for byte.
            val native = com.cyclecare.app.rust.CycleCareCoreBridge.supabaseConfigResolveClientSettings(BuildConfig.SUPABASE_URL, BuildConfig.SUPABASE_ANON_KEY)
            if (native != null) return ClientSettings(native.url, native.apiKey)
            return resolveClientSettings(BuildConfig.SUPABASE_URL, BuildConfig.SUPABASE_ANON_KEY)
        }

    val url: String get() = clientSettings.url
    val anonKey: String get() = clientSettings.apiKey
    val isConfigured: Boolean get() = clientSettings.isConfigured

    /** Compatibility helper for callers that need a safe public-key default. */
    internal fun resolvePublicApiKey(value: String?): String =
        parsePublicApiKey(value) ?: DEFAULT_PUBLISHABLE_KEY

    /**
     * Blank/blank is the intentional production default. Any non-empty setting is
     * an explicit request and is disabled rather than silently redirected to prod.
     */
    internal fun resolveClientSettings(url: String?, apiKey: String?): ClientSettings {
        val rawUrl = url?.trim()?.removeSuffix("/").orEmpty()
        val rawKey = apiKey?.trim().orEmpty()
        // The secrets Gradle plugin injects the committed .env.android.example
        // template values when no real .env.android exists. Treat those as
        // absent so the production defaults apply instead of disabling the
        // client (which previously produced a relative Google OAuth URL).
        val candidateUrl = if (isTemplatePlaceholder(rawUrl)) "" else rawUrl
        val candidateKey = if (isTemplatePlaceholder(rawKey)) "" else rawKey
        if (candidateUrl.isBlank() && candidateKey.isBlank()) {
            return ClientSettings(DEFAULT_URL, DEFAULT_PUBLISHABLE_KEY)
        }
        val parsedKey = parsePublicApiKey(candidateKey) ?: return disabled()
        val endpoint = parseEndpoint(candidateUrl) ?: return disabled()
        // The production project's key is never accepted for another endpoint.
        if (endpoint != DEFAULT_URL && parsedKey == DEFAULT_PUBLISHABLE_KEY) return disabled()
        // Opaque publishable keys cannot be project-bound offline; never substitute
        // another key after a server rejection. Legacy JWTs do expose a project ref.
        if (parsedKey.startsWith("eyJ")) {
            val projectRef = legacyPayload(parsedKey)?.optString("ref", "").orEmpty()
            if (projectRef.isNotBlank() && endpoint != "https://$projectRef.supabase.co") return disabled()
        }
        return ClientSettings(endpoint, parsedKey)
    }

    private fun disabled() = ClientSettings("", "")

    private fun isTemplatePlaceholder(value: String): Boolean {
        val v = value.trim().lowercase()
        return v.contains("your_project_ref") || v.contains("your-project-ref") ||
            v.startsWith("replace_with") || v.startsWith("replace-with") ||
            v.contains("placeholder") || v.contains("_here")
    }

    private fun parseEndpoint(value: String): String? {
        val local = setOf("http://localhost:54321", "http://127.0.0.1:54321", "http://10.0.2.2:54321")
        if (local.any { it.equals(value, ignoreCase = true) }) return local.first { it.equals(value, ignoreCase = true) }
        val host = value.removePrefix("https://").takeIf { value.startsWith("https://") } ?: return null
        if (!host.matches(Regex("[a-z0-9][a-z0-9-]*\\.supabase\\.co", RegexOption.IGNORE_CASE))) return null
        return "https://${host.lowercase()}"
    }

    private fun parsePublicApiKey(value: String?): String? {
        val candidate = value?.trim().orEmpty()
        if (candidate.isBlank() || candidate.contains("replace", true) || candidate.contains("placeholder", true) ||
            candidate.contains("your-", true) || candidate.contains("your_", true) || candidate.contains("_here", true) ||
            candidate.startsWith("sb_secret_", true) || candidate.startsWith("service_role", true)) return null
        if (candidate.startsWith("sb_publishable_")) {
            return candidate.takeIf { it.removePrefix("sb_publishable_").matches(Regex("[A-Za-z0-9_-]{20,}")) }
        }
        val segments = candidate.split('.')
        if (!candidate.startsWith("eyJ") || segments.size != 3 || segments.any(String::isBlank)) return null
        return candidate.takeIf { legacyPayload(it)?.optString("role") == "anon" }
    }

    private fun legacyPayload(value: String): JSONObject? = try {
        val payload = Base64.decode(value.split('.')[1], Base64.URL_SAFE or Base64.NO_WRAP)
        JSONObject(String(payload, StandardCharsets.UTF_8))
    } catch (_: Exception) { null }

    val restUrl: String get() = "$url/rest/v1"
    val authUrl: String get() = "$url/auth/v1"
}
