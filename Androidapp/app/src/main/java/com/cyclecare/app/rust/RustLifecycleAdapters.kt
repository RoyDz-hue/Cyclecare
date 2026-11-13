package com.cyclecare.app.rust

import com.cyclecare.app.security.AuthPreferences
import org.json.JSONObject

/**
 * Isolated call-site adapters for the reopen -> onboarding -> wallet slice of
 * an existing owner (core v14 / core-ffi v5).
 *
 * Scope guard: NOTHING in the production flow calls this file yet. Every
 * adapter either (a) returns the production Kotlin result when native is
 * unavailable or the identity snapshot is stale, or (b) returns null for
 * parse/decision passthroughs so the existing Kotlin parser stays the
 * fallback. No adapter reads or writes accounts, local data, or the wallet;
 * the transport (OkHttp calls) stays in SupabaseEconomyService - these only
 * decide over snapshots and response bodies the caller already holds.
 *
 * Identity discipline: each adapter captures effectiveUserId +
 * sessionGeneration once and runs the native call inside
 * [CycleCareCoreBridge.withIdentitySnapshot], mirroring the ViewModel's
 * stillOwns re-checks, so a mid-call account switch can never leak a native
 * result into the wrong owner's UI.
 */
object RustLifecycleAdapters {

    // -- Startup gate (ViewModel init, lines 334-340) -----------------------

    /**
     * Startup gate for the reopen path. Fallback reproduces the production
     * expressions verbatim so behavior is unchanged when native is absent.
     *
     * @param profilePresent       repository.getOnboardingProfile() != null
     * @param settingsOnboardingCompleted  settings.isOnboardingCompleted
     */
    fun startupGate(
        auth: AuthPreferences,
        profilePresent: Boolean,
        settingsOnboardingCompleted: Boolean,
    ): CycleCareCoreBridge.StartupGate {
        val owner = auth.effectiveUserId
        val session = auth.sessionGeneration
        return CycleCareCoreBridge.withIdentitySnapshot(
            owner = owner,
            sessionGeneration = session,
            currentOwner = { auth.effectiveUserId },
            currentSessionGeneration = { auth.sessionGeneration },
            fallback = { kotlinStartupGate(auth, profilePresent, settingsOnboardingCompleted) },
        ) {
            CycleCareCoreBridge.startupGate(
                CycleCareCoreBridge.StartupGateInputs(
                    isLocalOnlyMode = auth.isLocalOnlyMode,
                    isSecureStorageAvailable = auth.isSecureStorageAvailable,
                    storedInitialAuthCompleted = auth.hasCompletedInitialAuth,
                    profilePresent = profilePresent,
                    settingsOnboardingCompleted = settingsOnboardingCompleted,
                    isPinEnabled = auth.isPinEnabled,
                    pinPresent = !auth.pin.isNullOrBlank(),
                    isFingerprintEnabled = auth.isFingerprintEnabled,
                    userIdPresent = auth.userId != null,
                )
            ) ?: kotlinStartupGate(auth, profilePresent, settingsOnboardingCompleted)
        }
    }

    /** The production gate, verbatim from CycleCareViewModel init:334-340. */
    private fun kotlinStartupGate(
        auth: AuthPreferences,
        profilePresent: Boolean,
        settingsOnboardingCompleted: Boolean,
    ): CycleCareCoreBridge.StartupGate {
        val hasCompletedInitialAuth = auth.hasCompletedInitialAuth
        val needsCloudProfileCheck = hasCompletedInitialAuth && !auth.isLocalOnlyMode &&
            (!profilePresent || !settingsOnboardingCompleted)
        val isPinSet = auth.isPinEnabled && !auth.pin.isNullOrBlank()
        val isLocked = hasCompletedInitialAuth && (isPinSet || auth.isFingerprintEnabled)
        val showOnboarding = hasCompletedInitialAuth && !needsCloudProfileCheck &&
            (!profilePresent || !settingsOnboardingCompleted)
        return CycleCareCoreBridge.StartupGate(
            hasCompletedInitialAuth = hasCompletedInitialAuth,
            needsCloudProfileCheck = needsCloudProfileCheck,
            isLocked = isLocked,
            showOnboarding = showOnboarding,
            fetchWallet = hasCompletedInitialAuth && !auth.isLocalOnlyMode && auth.userId != null,
        )
    }

    // -- Effective user id --------------------------------------------------

    /** effectiveUserId with native confirmation; falls back to AuthPreferences. */
    fun effectiveUserId(auth: AuthPreferences): String {
        val owner = auth.effectiveUserId
        val session = auth.sessionGeneration
        return CycleCareCoreBridge.withIdentitySnapshot(
            owner = owner,
            sessionGeneration = session,
            currentOwner = { auth.effectiveUserId },
            currentSessionGeneration = { auth.sessionGeneration },
            fallback = { owner },
        ) {
            CycleCareCoreBridge.effectiveUserId(auth.userId, auth.deviceId) ?: owner
        }
    }

    // -- Wallet: verified profile parse (transport stays Kotlin) -------------

    /**
     * Parse the two fetched bodies (user_economy row, user_profiles row) into
     * the verified-profile field set. Null on native unavailability or stale
     * identity - caller keeps the existing Kotlin parse. No fetch, no write.
     */
    fun verifiedProfileParse(
        auth: AuthPreferences,
        economyBody: String,
        profileBody: String,
        nowIso: String,
    ): JSONObject? {
        val owner = auth.effectiveUserId
        val session = auth.sessionGeneration
        return CycleCareCoreBridge.withIdentitySnapshot(
            owner = owner,
            sessionGeneration = session,
            currentOwner = { auth.effectiveUserId },
            currentSessionGeneration = { auth.sessionGeneration },
            fallback = { null },
        ) {
            CycleCareCoreBridge.verifiedProfileParse(owner, economyBody, profileBody, nowIso)
        }
    }

    // -- Wallet: pending offline-claim reconcile decision --------------------

    /**
     * Decision core of SupabaseEconomyService.reconcilePendingOfflineClaims:
     * classifies the verify_and_mine_coins response the caller already
     * fetched. Null on native unavailability or stale identity - caller keeps
     * the Kotlin branch structure. No fetch, no preference writes.
     */
    fun reconcileOfflineClaim(
        auth: AuthPreferences,
        httpOk: Boolean,
        body: String,
        currentBalance: String,
        currentEarned: String,
        currentRank: String,
        pendingDate: String,
    ): JSONObject? {
        val owner = auth.effectiveUserId
        val session = auth.sessionGeneration
        return CycleCareCoreBridge.withIdentitySnapshot(
            owner = owner,
            sessionGeneration = session,
            currentOwner = { auth.effectiveUserId },
            currentSessionGeneration = { auth.sessionGeneration },
            fallback = { null },
        ) {
            CycleCareCoreBridge.reconcileOfflineClaim(
                httpOk, body, currentBalance, currentEarned, currentRank, pendingDate,
            )
        }
    }

    // -- Session snapshot (backing for the future session_token FFI) --------

    /**
     * Session snapshot the AuthPreferences-backed SessionStore would hand to
     * the native session_token resolver once resolve_access_token is exposed
     * over FFI. Not consumed by any contract yet.
     */
    data class SessionSnapshot(
        val owner: String,
        val sessionGeneration: Long,
        val accessToken: String?,
        val refreshToken: String?,
        val expiresAtSeconds: Long,
        val isLocalOnlyMode: Boolean,
    )

    fun sessionSnapshot(auth: AuthPreferences): SessionSnapshot = SessionSnapshot(
        owner = auth.effectiveUserId,
        sessionGeneration = auth.sessionGeneration,
        accessToken = auth.accessToken,
        refreshToken = auth.refreshToken,
        expiresAtSeconds = auth.sessionExpiresAtSeconds,
        isLocalOnlyMode = auth.isLocalOnlyMode,
    )
}
