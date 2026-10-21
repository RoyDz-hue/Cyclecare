package com.cyclecare.app.domain.diagnostics

import java.net.URI

/** An optional developer origin is never an authorization to collect diagnostics. */
object DebugBridgeConfiguration {
    fun baseUrl(isDebug: Boolean, configuredUrl: String): String? {
        if (!isDebug) return null
        val value = configuredUrl.trim()
        if (value.isEmpty() || value.length > 2048) return null
        val endpoint = try {
            URI(value)
        } catch (_: Exception) {
            return null
        }
        if (!endpoint.scheme.equals("https", ignoreCase = true) ||
            endpoint.host.isNullOrBlank() || endpoint.rawUserInfo != null ||
            endpoint.rawQuery != null || endpoint.rawFragment != null ||
            endpoint.rawPath.orEmpty() !in setOf("", "/") ||
            (endpoint.port != -1 && endpoint.port !in 1..65535)
        ) return null
        return endpoint.toASCIIString().removeSuffix("/")
    }
}
