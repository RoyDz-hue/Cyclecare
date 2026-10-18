package com.example.data.supabase

import com.example.BuildConfig

object SupabaseConfig {
    const val PROJECT_REF = "apjqcocxtylsofgxhetb"
    const val DEFAULT_URL = "https://apjqcocxtylsofgxhetb.supabase.co"
    const val DEFAULT_PUBLISHABLE_KEY = "sb_publishable_ziAlyPmKSv7kQZjbpbIuwA_6er-NufM"

    internal data class ClientSettings(val url: String, val apiKey: String)

    private val clientSettings: ClientSettings
        get() = try {
            resolveClientSettings(BuildConfig.SUPABASE_URL, BuildConfig.SUPABASE_ANON_KEY)
        } catch (_: Throwable) {
            ClientSettings(DEFAULT_URL, DEFAULT_PUBLISHABLE_KEY)
        }

    val url: String
        get() = clientSettings.url

    val anonKey: String
        get() = clientSettings.apiKey

    internal fun resolvePublicApiKey(value: String?): String {
        val candidate = value?.trim().orEmpty()
        val placeholder = listOf("replace", "placeholder", "your-", "your_", "example", "_here")
            .any { candidate.contains(it, ignoreCase = true) }
        val validPublishable = !placeholder && candidate.startsWith("sb_publishable_") &&
            candidate.removePrefix("sb_publishable_").matches(Regex("[A-Za-z0-9_-]{20,}"))
        val segments = candidate.split('.')
        val validLegacyKey = !placeholder && candidate.startsWith("eyJ") && segments.size == 3 && segments.all(String::isNotBlank)
        return if (validPublishable || validLegacyKey) candidate else DEFAULT_PUBLISHABLE_KEY
    }

    internal fun resolveClientSettings(url: String?, apiKey: String?): ClientSettings {
        val candidateUrl = url?.trim()?.removeSuffix("/").orEmpty()
        val candidateKey = apiKey?.trim().orEmpty()
        val resolvedKey = resolvePublicApiKey(candidateKey)
        val keyIsValid = candidateKey.isNotEmpty() && candidateKey == resolvedKey
        if (candidateUrl.equals(DEFAULT_URL, ignoreCase = true)) {
            return ClientSettings(DEFAULT_URL, if (keyIsValid) resolvedKey else DEFAULT_PUBLISHABLE_KEY)
        }
        val localDevelopmentUrls = setOf(
            "http://localhost:54321",
            "http://127.0.0.1:54321",
            "http://10.0.2.2:54321"
        )
        val localUrl = localDevelopmentUrls.firstOrNull { candidateUrl.equals(it, ignoreCase = true) }
        if (localUrl != null && keyIsValid && resolvedKey != DEFAULT_PUBLISHABLE_KEY) {
            return ClientSettings(localUrl, resolvedKey)
        }
        return ClientSettings(DEFAULT_URL, DEFAULT_PUBLISHABLE_KEY)
    }

    val isConfigured: Boolean get() = anonKey.isNotBlank()

    val restUrl: String get() = "$url/rest/v1"
    val authUrl: String get() = "$url/auth/v1"
}
