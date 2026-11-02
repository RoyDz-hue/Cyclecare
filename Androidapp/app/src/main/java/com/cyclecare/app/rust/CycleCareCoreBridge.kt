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


    // ---- Lifecycle contracts (core-ffi v5 / core v14) --------------------
    // Isolated spike wiring: NOTHING in the production flow calls these yet.
    // Every function is a pure decision/parse over caller-supplied snapshots
    // and server-response bodies; none of them reads or writes local state,
    // accounts, or the wallet. Each returns null on native unavailability or
    // failure so the caller keeps the production Kotlin path.

    data class StartupGateInputs(
        val isLocalOnlyMode: Boolean,
        val isSecureStorageAvailable: Boolean,
        val storedInitialAuthCompleted: Boolean,
        val profilePresent: Boolean,
        val settingsOnboardingCompleted: Boolean,
        val isPinEnabled: Boolean,
        val pinPresent: Boolean,
        val isFingerprintEnabled: Boolean,
        val userIdPresent: Boolean,
    )

    data class StartupGate(
        val hasCompletedInitialAuth: Boolean,
        val needsCloudProfileCheck: Boolean,
        val isLocked: Boolean,
        val showOnboarding: Boolean,
        val fetchWallet: Boolean,
    )

    /** CycleCareViewModel.init startup gate. null -> use the Kotlin gate. */
    fun startupGate(i: StartupGateInputs): StartupGate? {
        val ok = call("startup_gate", JSONObject()
            .put("local", i.isLocalOnlyMode)
            .put("secure", i.isSecureStorageAvailable)
            .put("stored", i.storedInitialAuthCompleted)
            .put("profile", i.profilePresent)
            .put("onboardingCompleted", i.settingsOnboardingCompleted)
            .put("pinEnabled", i.isPinEnabled)
            .put("pinPresent", i.pinPresent)
            .put("fingerprintEnabled", i.isFingerprintEnabled)
            .put("userIdPresent", i.userIdPresent))?.optJSONObject("ok") ?: return null
        return StartupGate(
            hasCompletedInitialAuth = ok.optBoolean("hasCompletedInitialAuth"),
            needsCloudProfileCheck = ok.optBoolean("needsCloudProfileCheck"),
            isLocked = ok.optBoolean("isLocked"),
            showOnboarding = ok.optBoolean("showOnboarding"),
            fetchWallet = ok.optBoolean("fetchWallet"),
        )
    }

    /** AuthPreferences.effectiveUserId (userId, else UUID v3 over device id). */
    fun effectiveUserId(userId: String?, deviceId: String): String? {
        val ok = call("effective_user_id", JSONObject()
            .put("userId", userId ?: JSONObject.NULL)
            .put("deviceId", deviceId)) ?: return null
        return ok.optString("ok").takeIf { it.isNotEmpty() }
    }

    /** ensure-my-account-setup RPC response check. */
    fun ensureSetupOk(body: String): Boolean? {
        val ok = call("ensure_setup_ok", JSONObject().put("body", body)) ?: return null
        return if (ok.has("ok")) ok.optBoolean("ok") else null
    }

    /** hasRemoteProfile: TRUE/FALSE/UNKNOWN (server said nothing decidable) or null (native unavailable). */
    enum class RemoteProfileAnswer { TRUE, FALSE, UNKNOWN }

    fun hasRemoteProfile(httpOk: Boolean, body: String, owner: String): RemoteProfileAnswer? {
        val ok = call("has_remote_profile", JSONObject()
            .put("httpOk", httpOk)
            .put("body", body)
            .put("owner", owner)) ?: return null
        if (!ok.has("ok")) return null
        if (ok.isNull("ok")) return RemoteProfileAnswer.UNKNOWN
        return if (ok.optBoolean("ok")) RemoteProfileAnswer.TRUE else RemoteProfileAnswer.FALSE
    }

    /** fetchVerifiedProfile parse/merge. Raw ok-payload passthrough (16 fields). */
    fun verifiedProfileParse(owner: String, econBody: String, profBody: String, nowIso: String): JSONObject? {
        return call("verified_profile_parse", JSONObject()
            .put("owner", owner)
            .put("econBody", econBody)
            .put("profBody", profBody)
            .put("nowIso", nowIso))?.optJSONObject("ok")
    }

    /** reconcilePendingOfflineClaims decision core. Raw ok-payload passthrough (outcome + fields). */
    fun reconcileOfflineClaim(
        httpOk: Boolean,
        body: String,
        currentBalance: String,
        currentEarned: String,
        currentRank: String,
        pendingDate: String,
    ): JSONObject? {
        return call("reconcile_offline_claim", JSONObject()
            .put("httpOk", httpOk)
            .put("body", body)
            .put("balance", currentBalance)
            .put("earned", currentEarned)
            .put("rank", currentRank)
            .put("pendingDate", pendingDate))?.optJSONObject("ok")
    }

    private external fun nativeCoreHandshake(): Int
    private external fun nativeCall(contract: String, payloadJson: String): String
}
