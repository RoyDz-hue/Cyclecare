package com.cyclecare.bridge

import com.cyclecare.app.rust.CycleCareCoreBridge
import org.json.JSONObject

object RustSetupChecksConfig { const val ENABLED = false }

/** Wallet-setup and remote-profile JSON decisions through the Rust core, with the exact
 * production Kotlin expressions as fallback. These adapters decide ONLY over a response body
 * the caller already holds; they perform no network calls, grant no authorization, and change
 * no query. JSON semantics are AOSP org.json (last-wins duplicate keys, optString of explicit
 * null is the literal "null"); the Robolectric tests run the real AOSP classes at sdk 34. */
object RustSetupChecks {

    /** Production: SupabaseEconomyService wallet-setup verify
     * `runCatching { JSONObject(body).optBoolean("success", false) }.getOrDefault(false)`. */
    fun ensureSetupOk(body: String): Boolean {
        val kotlin = kotlinEnsureSetupOk(body)
        val native = nativeEnsureSetupOk(body) ?: return kotlin
        // Defense-parity shadow: native answer used only when it exactly equals the Kotlin oracle.
        return if (native == kotlin) native else kotlin
    }

    /** Production: SupabaseEconomyService.hasRemoteProfile body check. Null = inconclusive. */
    fun hasRemoteProfile(httpOk: Boolean, body: String, owner: String): Boolean? {
        // Source-preserving short-circuit: a failed HTTP status is inconclusive in both
        // implementations; skip the native parse entirely.
        if (!httpOk) return kotlinHasRemoteProfile(httpOk, body, owner)
        val kotlin = kotlinHasRemoteProfile(httpOk, body, owner)
        val native = nativeHasRemoteProfile(httpOk, body, owner) ?: return kotlin
        return if (native.answered && native.value == kotlin) native.value else kotlin
    }

    internal class Answer(val value: Boolean?) { val answered = true }

    internal fun kotlinEnsureSetupOk(body: String): Boolean =
        runCatching { JSONObject(body).optBoolean("success", false) }.getOrDefault(false)

    internal fun kotlinHasRemoteProfile(httpOk: Boolean, body: String, owner: String): Boolean? {
        if (!httpOk) return null
        return try {
            val state = JSONObject(body)
            if (state.optString("user_id") != owner || !state.has("exists")) null else state.getBoolean("exists")
        } catch (_: Exception) { null }
    }

    internal fun nativeEnsureSetupOk(body: String): Boolean? {
        val reply = CycleCareCoreBridge.call("ensure_setup_ok", JSONObject().put("body", body)) ?: return null
        val ok = reply.opt("ok") ?: return null
        return if (ok is Boolean) ok else null
    }

    /** Distinguishes "native unavailable/decode failure" (null) from "native answered None"
     * (Answer(null)) - the contract's inconclusive result is a real answer. */
    internal fun nativeHasRemoteProfile(httpOk: Boolean, body: String, owner: String): Answer? {
        return try {
            val reply = CycleCareCoreBridge.call("has_remote_profile", JSONObject()
                .put("httpOk", httpOk).put("body", body).put("owner", owner)) ?: return null
            if (!reply.has("ok")) return null
            when (val ok = reply.get("ok")) {
                JSONObject.NULL -> Answer(null)
                is Boolean -> Answer(ok)
                else -> null
            }
        } catch (_: org.json.JSONException) { null }
    }
}
