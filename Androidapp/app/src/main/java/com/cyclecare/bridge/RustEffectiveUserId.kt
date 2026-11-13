package com.cyclecare.bridge

import com.cyclecare.app.rust.CycleCareCoreBridge
import org.json.JSONObject
import java.util.UUID

object RustEffectiveUserIdConfig { const val ENABLED = false }

/** Effective user id through the Rust core, with the exact AuthPreferences.getEffectiveUserId
 * Kotlin getter as fallback. IDENTITY-LOAD-BEARING: no normalization, no change to owner
 * partition keys; the native answer is used only when it exactly equals the Kotlin getter's
 * output for the same inputs. Callsite wiring of AuthPreferences is intentionally NOT done in
 * this slice (wide blast radius) - this file plus tests only.
 *
 * JNI boundary: unpaired UTF-16 surrogates cannot be transported to native faithfully (Java's
 * own toByteArray replaces them with '?' while JNI carries them as CESU-8), so any input
 * containing an unpaired surrogate goes straight to the exact Kotlin path. */
object RustEffectiveUserId {

    fun effectiveUserId(userId: String?, deviceId: String): String {
        if (hasUnpairedSurrogate(userId) || hasUnpairedSurrogate(deviceId)) {
            return kotlinEffectiveUserId(userId, deviceId)
        }
        val kotlin = kotlinEffectiveUserId(userId, deviceId)
        val native = nativeOrNull(userId, deviceId) ?: return kotlin
        // Defense-parity shadow: native answer used only when EXACTLY equal to the Kotlin getter.
        return if (native == kotlin) native else kotlin
    }

    /** Verbatim AuthPreferences.kt effectiveUserId getter body. */
    internal fun kotlinEffectiveUserId(userId: String?, deviceId: String): String =
        if (!userId.isNullOrBlank()) userId
        else try { UUID.nameUUIDFromBytes(deviceId.toByteArray()).toString() } catch (e: Exception) { deviceId }

    internal fun hasUnpairedSurrogate(s: String?): Boolean {
        if (s == null) return false
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c.isHighSurrogate()) {
                if (i + 1 >= s.length || !s[i + 1].isLowSurrogate()) return true
                i += 2
            } else if (c.isLowSurrogate()) {
                return true
            } else i++
        }
        return false
    }

    internal fun nativeOrNull(userId: String?, deviceId: String): String? {
        val reply = CycleCareCoreBridge.call("effective_user_id", JSONObject()
            .put("userId", if (userId == null) JSONObject.NULL else userId)
            .put("deviceId", deviceId)) ?: return null
        val ok = reply.opt("ok") ?: return null
        return if (ok is String) ok else null
    }
}
