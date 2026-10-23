package com.cyclecare.app.domain.sync

/**
 * Decides when reconnect-triggered work (offline-claim reconcile, verified
 * profile fetch, notification refresh) may run.
 *
 * Rules:
 * - Only on an offline -> online TRANSITION; repeated "online" emissions
 *   (e.g. capability changes) and the initial state emit are not reconnects.
 * - At most once per [MIN_INTERVAL_MS]; flappy networks must not cause
 *   unbounded refetching.
 * - An owner (account) change resets the interval: the new account has never
 *   had reconnect work and must not inherit the previous account's throttle.
 *
 * This throttles redundant work only; startup work and manual refreshes have
 * their own paths and are unaffected.
 */
object ReconnectWorkPolicy {

    const val MIN_INTERVAL_MS: Long = 60_000L

    class State(
        var wasConnected: Boolean = true,
        var lastRunAtMs: Long = 0L,
        var owner: String? = null
    )

    fun shouldRun(
        state: State,
        isConnected: Boolean,
        isEligible: Boolean,
        currentOwner: String?,
        nowMs: Long
    ): Boolean {
        val justReconnected = isConnected && !state.wasConnected
        state.wasConnected = isConnected
        if (currentOwner != state.owner) {
            state.owner = currentOwner
            state.lastRunAtMs = 0L
        }
        if (!justReconnected || !isEligible) return false
        // lastRunAtMs == 0 means this owner has never had reconnect work.
        if (state.lastRunAtMs > 0L && nowMs - state.lastRunAtMs < MIN_INTERVAL_MS) return false
        state.lastRunAtMs = nowMs
        return true
    }
}
