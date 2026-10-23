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
 * - A throttled transition is deferred, not dropped: the caller schedules one
 *   trailing run for when the cooldown expires ([Decision.Throttled] carries
 *   the remaining wait; [decideTrailing] re-validates it at fire time).
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

    sealed interface Decision {
        /** Run the reconnect work now. */
        data object Run : Decision
        /** Do nothing: not a transition, not eligible, offline, or stale owner. */
        data object Skip : Decision
        /**
         * A real transition arrived inside the cooldown. The work is deferred,
         * not dropped: schedule one trailing run after [retryAfterMs].
         */
        data class Throttled(val retryAfterMs: Long) : Decision
    }

    /** Evaluate a connectivity emission. */
    fun decide(
        state: State,
        isConnected: Boolean,
        isEligible: Boolean,
        currentOwner: String?,
        nowMs: Long
    ): Decision {
        val justReconnected = isConnected && !state.wasConnected
        state.wasConnected = isConnected
        if (currentOwner != state.owner) {
            state.owner = currentOwner
            state.lastRunAtMs = 0L
        }
        if (!justReconnected || !isEligible) return Decision.Skip
        throttle(state, nowMs)?.let { return it }
        state.lastRunAtMs = nowMs
        return Decision.Run
    }

    /**
     * Evaluate a deferred trailing run for an earlier throttled transition.
     * No fresh transition is required, but the run only fires for the SAME
     * owner, while online and eligible, and only once the cooldown has
     * expired. If newer work already ran (stamping [State.lastRunAtMs]), this
     * reports the remaining cooldown instead of running twice.
     */
    fun decideTrailing(
        state: State,
        isConnected: Boolean,
        isEligible: Boolean,
        currentOwner: String?,
        nowMs: Long
    ): Decision {
        if (!isConnected || !isEligible) return Decision.Skip
        if (currentOwner != state.owner) return Decision.Skip
        throttle(state, nowMs)?.let { return it }
        state.lastRunAtMs = nowMs
        return Decision.Run
    }

    private fun throttle(state: State, nowMs: Long): Decision.Throttled? {
        // lastRunAtMs == 0 means this owner has never had reconnect work.
        if (state.lastRunAtMs <= 0L) return null
        val elapsed = nowMs - state.lastRunAtMs
        if (elapsed >= MIN_INTERVAL_MS) return null
        return Decision.Throttled(MIN_INTERVAL_MS - elapsed)
    }
}
