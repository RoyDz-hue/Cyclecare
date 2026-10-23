package com.cyclecare.app

import com.cyclecare.app.domain.sync.ReconnectWorkPolicy
import com.cyclecare.app.domain.sync.ReconnectWorkPolicy.Decision
import org.junit.Assert.assertEquals
import org.junit.Test

class ReconnectWorkPolicyTest {

    private val min = ReconnectWorkPolicy.MIN_INTERVAL_MS

    @Test
    fun initialOnlineEmissionIsNotAReconnect() {
        val s = ReconnectWorkPolicy.State()
        assertEquals(Decision.Skip, ReconnectWorkPolicy.decide(s, true, true, "a", 1_000))
    }

    @Test
    fun startsOfflineFirstTransitionRuns() {
        val s = ReconnectWorkPolicy.State()
        // App started offline: the first emission reports offline, not a transition.
        assertEquals(Decision.Skip, ReconnectWorkPolicy.decide(s, false, true, "a", 1_000))
        // The first genuine offline -> online edge runs, even inside any window.
        assertEquals(Decision.Run, ReconnectWorkPolicy.decide(s, true, true, "a", 2_000))
    }

    @Test
    fun offlineToOnlineTransitionRunsOnce() {
        val s = ReconnectWorkPolicy.State()
        ReconnectWorkPolicy.decide(s, false, true, "a", 1_000)
        assertEquals(Decision.Run, ReconnectWorkPolicy.decide(s, true, true, "a", 2_000))
    }

    @Test
    fun flappyReconnectsWithinIntervalAreThrottledNotRun() {
        val s = ReconnectWorkPolicy.State()
        ReconnectWorkPolicy.decide(s, false, true, "a", 1_000)
        assertEquals(Decision.Run, ReconnectWorkPolicy.decide(s, true, true, "a", 2_000))
        ReconnectWorkPolicy.decide(s, false, true, "a", 3_000)
        assertEquals(Decision.Throttled(min - 2_000), ReconnectWorkPolicy.decide(s, true, true, "a", 4_000))
        ReconnectWorkPolicy.decide(s, false, true, "a", 5_000)
        assertEquals(Decision.Throttled(min - 8_000), ReconnectWorkPolicy.decide(s, true, true, "a", 10_000))
    }

    @Test
    fun throttledDecisionCarriesExactRemainingCooldown() {
        val s = ReconnectWorkPolicy.State()
        ReconnectWorkPolicy.decide(s, false, true, "a", 1_000)
        assertEquals(Decision.Run, ReconnectWorkPolicy.decide(s, true, true, "a", 2_000))
        ReconnectWorkPolicy.decide(s, false, true, "a", 30_000)
        // Ran at 2_000; at 32_000 the remaining wait is min - 30_000.
        assertEquals(Decision.Throttled(min - 30_000), ReconnectWorkPolicy.decide(s, true, true, "a", 32_000))
    }

    @Test
    fun reconnectAtExactIntervalBoundaryRunsAgain() {
        val s = ReconnectWorkPolicy.State()
        ReconnectWorkPolicy.decide(s, false, true, "a", 1_000)
        assertEquals(Decision.Run, ReconnectWorkPolicy.decide(s, true, true, "a", 2_000))
        ReconnectWorkPolicy.decide(s, false, true, "a", 3_000)
        assertEquals(Decision.Run, ReconnectWorkPolicy.decide(s, true, true, "a", 2_000 + min))
    }

    @Test
    fun repeatedOnlineEmissionsWithoutTransitionNeverRun() {
        val s = ReconnectWorkPolicy.State()
        repeat(5) { i ->
            assertEquals(Decision.Skip, ReconnectWorkPolicy.decide(s, true, true, "a", 10_000L * (i + 1)))
        }
    }

    @Test
    fun accountChangeResetsThrottle() {
        val s = ReconnectWorkPolicy.State()
        ReconnectWorkPolicy.decide(s, false, true, "a", 1_000)
        assertEquals(Decision.Run, ReconnectWorkPolicy.decide(s, true, true, "a", 2_000))
        ReconnectWorkPolicy.decide(s, false, true, "a", 3_000)
        // New owner reconnecting immediately must not inherit the old throttle.
        assertEquals(Decision.Run, ReconnectWorkPolicy.decide(s, true, true, "b", 4_000))
    }

    @Test
    fun logoutAndLoginSameOwnerResetsThrottle() {
        val s = ReconnectWorkPolicy.State()
        ReconnectWorkPolicy.decide(s, false, true, "a", 1_000)
        assertEquals(Decision.Run, ReconnectWorkPolicy.decide(s, true, true, "a", 2_000))
        // Logout: emissions now carry no owner and are ineligible.
        ReconnectWorkPolicy.decide(s, false, false, null, 3_000)
        assertEquals(Decision.Skip, ReconnectWorkPolicy.decide(s, true, false, null, 4_000))
        // Login again as the SAME owner: owner changed (a -> null -> a), so the
        // throttle reset and the first reconnect after login runs immediately.
        ReconnectWorkPolicy.decide(s, false, true, "a", 5_000)
        assertEquals(Decision.Run, ReconnectWorkPolicy.decide(s, true, true, "a", 6_000))
    }

    @Test
    fun ineligibleReconnectDoesNotRunOrConsumeThrottle() {
        val s = ReconnectWorkPolicy.State()
        ReconnectWorkPolicy.decide(s, false, true, "a", 1_000)
        assertEquals(Decision.Skip, ReconnectWorkPolicy.decide(s, true, false, "a", 2_000))
        // Eligibility returning later within the interval still allows the first run.
        ReconnectWorkPolicy.decide(s, false, true, "a", 3_000)
        assertEquals(Decision.Run, ReconnectWorkPolicy.decide(s, true, true, "a", 4_000))
    }

    @Test
    fun trailingRunFiresAfterCooldownAndStampsTheSlot() {
        val s = ReconnectWorkPolicy.State()
        ReconnectWorkPolicy.decide(s, false, true, "a", 1_000)
        assertEquals(Decision.Run, ReconnectWorkPolicy.decide(s, true, true, "a", 2_000))
        ReconnectWorkPolicy.decide(s, false, true, "a", 3_000)
        assertEquals(Decision.Throttled(min - 2_000), ReconnectWorkPolicy.decide(s, true, true, "a", 4_000))
        // Trailing fires early: still inside the cooldown, defers again.
        assertEquals(Decision.Throttled(min - 30_000),
            ReconnectWorkPolicy.decideTrailing(s, true, true, "a", 32_000))
        // Trailing fires at cooldown expiry: runs even though no new transition.
        assertEquals(Decision.Run, ReconnectWorkPolicy.decideTrailing(s, true, true, "a", 2_000 + min))
        // The trailing run stamped the slot: a flap right after is throttled.
        ReconnectWorkPolicy.decide(s, false, true, "a", 3_000 + min)
        assertEquals(Decision.Throttled(min - 1_000),
            ReconnectWorkPolicy.decide(s, true, true, "a", 3_000 + min))
    }

    @Test
    fun trailingSkippedWhenOwnerChangedOfflineOrIneligible() {
        val s = ReconnectWorkPolicy.State()
        ReconnectWorkPolicy.decide(s, false, true, "a", 1_000)
        assertEquals(Decision.Run, ReconnectWorkPolicy.decide(s, true, true, "a", 2_000))
        // Owner changed since the run was scheduled: never run stale work.
        assertEquals(Decision.Skip, ReconnectWorkPolicy.decideTrailing(s, true, true, "b", 2_000 + min))
        // Went back offline before the trailing fired.
        assertEquals(Decision.Skip, ReconnectWorkPolicy.decideTrailing(s, false, true, "a", 2_000 + min))
        // Signed out / local-only before the trailing fired.
        assertEquals(Decision.Skip, ReconnectWorkPolicy.decideTrailing(s, true, false, "a", 2_000 + min))
    }
}
