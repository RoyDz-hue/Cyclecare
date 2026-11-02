package com.cyclecare.app.rust

import android.util.Log
import com.cyclecare.app.ui.screens.isCompletePin as kotlinIsCompletePin
import org.json.JSONObject

/**
 * Transitional JNI bridge to the Rust cyclecare-core cdylib (core-ffi crate,
 * JSON dispatcher). The Kotlin host stays authoritative: when the native
 * library is absent or a call fails, every contract falls back to the
 * production Kotlin implementation, so app behavior never depends on the
 * spike artifact.
 *
 * Native contract (rust/HOST-INTEGRATION.md):
 *   nativeCoreHandshake(): Int                 -> always 42
 *   nativeCall(contract, payloadJson): String  -> {"ok": ...} or {"error": ...}
 *
 * Identity-dependent contracts MUST go through [withIdentitySnapshot]: the
 * caller passes the owner + sessionGeneration captured before its async
 * boundary; a stale snapshot discards the native result and uses the Kotlin
 * fallback. This mirrors CycleCareViewModel's stillOwns re-checks.
 */
object CycleCareCoreBridge {

    private const val TAG = "CycleCareCoreBridge"
    private const val LIB_NAME = "cyclecare_core"

    enum class NativeState { NOT_ATTEMPTED, LOADED, UNAVAILABLE }

    @Volatile
    private var nativeState = NativeState.NOT_ATTEMPTED

    @Synchronized
    private fun ensureLoaded(): NativeState {
        if (nativeState != NativeState.NOT_ATTEMPTED) return nativeState
        nativeState = runCatching {
            System.loadLibrary(LIB_NAME)
            if (nativeCoreHandshake() == 42) NativeState.LOADED else NativeState.UNAVAILABLE
        }.getOrElse {
            Log.d(TAG, "native core unavailable, Kotlin fallback active: ${it.javaClass.simpleName}")
            NativeState.UNAVAILABLE
        }
        return nativeState
    }

    /** Raw dispatcher call; null on any failure (caller falls back to Kotlin). */
    fun call(contract: String, payload: JSONObject): JSONObject? {
        if (ensureLoaded() != NativeState.LOADED) return null
        return runCatching {
            val reply = JSONObject(nativeCall(contract, payload.toString()))
            if (reply.has("ok")) reply else null
        }.getOrNull()
    }

    /** Quick-unlock PIN shape (identity-free). Pure; no user data leaves the device. */
    fun isCompletePin(pin: String): Boolean {
        val ok = call("is_complete_pin", JSONObject().put("pin", pin))
        if (ok != null) return ok.optBoolean("ok", false)
        return kotlinIsCompletePin(pin)
    }

    /**
     * Guard for future identity-dependent native contracts: runs [nativeCall]
     * only while the owner/session snapshot taken before the async boundary is
     * still current; otherwise returns [fallback]. No identity-dependent
     * contract is wired yet - first use must come with its own review.
     */
    inline fun <T> withIdentitySnapshot(
        owner: String?,
        sessionGeneration: Long,
        currentOwner: () -> String?,
        currentSessionGeneration: () -> Long,
        fallback: () -> T,
        nativeCall: () -> T,
    ): T {
        if (owner != currentOwner() || sessionGeneration != currentSessionGeneration()) return fallback()
        val result = nativeCall()
        return if (owner == currentOwner() && sessionGeneration == currentSessionGeneration()) result else fallback()
    }

    private external fun nativeCoreHandshake(): Int
    private external fun nativeCall(contract: String, payloadJson: String): String
}
