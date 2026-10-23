package com.cyclecare.app

import com.cyclecare.app.domain.sync.ReconnectWorkPolicy
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReconnectWorkPolicyTest {

    @Test
    fun initialOnlineEmissionIsNotAReconnect() {
        val s = ReconnectWorkPolicy.State()
        assertFalse(ReconnectWorkPolicy.shouldRun(s, isConnected = true, isEligible = true, currentOwner = "a", nowMs = 1_000))
    }

    @Test
    fun offlineToOnlineTransitionRunsOnce() {
        val s = ReconnectWorkPolicy.State()
        ReconnectWorkPolicy.shouldRun(s, false, true, "a", 1_000)
        assertTrue(ReconnectWorkPolicy.shouldRun(s, true, true, "a", 2_000))
    }

    @Test
    fun flappyReconnectsWithinIntervalAreThrottled() {
        val s = ReconnectWorkPolicy.State()
        ReconnectWorkPolicy.shouldRun(s, false, true, "a", 1_000)
        assertTrue(ReconnectWorkPolicy.shouldRun(s, true, true, "a", 2_000))
        ReconnectWorkPolicy.shouldRun(s, false, true, "a", 3_000)
        assertFalse(ReconnectWorkPolicy.shouldRun(s, true, true, "a", 4_000))
        ReconnectWorkPolicy.shouldRun(s, false, true, "a", 5_000)
        assertFalse(ReconnectWorkPolicy.shouldRun(s, true, true, "a", 10_000))
    }

    @Test
    fun reconnectAfterIntervalRunsAgain() {
        val s = ReconnectWorkPolicy.State()
        ReconnectWorkPolicy.shouldRun(s, false, true, "a", 1_000)
        assertTrue(ReconnectWorkPolicy.shouldRun(s, true, true, "a", 2_000))
        ReconnectWorkPolicy.shouldRun(s, false, true, "a", 3_000)
        assertTrue(ReconnectWorkPolicy.shouldRun(s, true, true, "a", 2_000 + ReconnectWorkPolicy.MIN_INTERVAL_MS))
    }

    @Test
    fun repeatedOnlineEmissionsWithoutTransitionNeverRun() {
        val s = ReconnectWorkPolicy.State()
        repeat(5) { i ->
            assertFalse(ReconnectWorkPolicy.shouldRun(s, true, true, "a", 10_000L * (i + 1)))
        }
    }

    @Test
    fun accountChangeResetsThrottle() {
        val s = ReconnectWorkPolicy.State()
        ReconnectWorkPolicy.shouldRun(s, false, true, "a", 1_000)
        assertTrue(ReconnectWorkPolicy.shouldRun(s, true, true, "a", 2_000))
        ReconnectWorkPolicy.shouldRun(s, false, true, "a", 3_000)
        // New owner reconnecting immediately must not inherit the old throttle.
        assertTrue(ReconnectWorkPolicy.shouldRun(s, true, true, "b", 4_000))
    }

    @Test
    fun ineligibleReconnectDoesNotRunOrConsumeThrottle() {
        val s = ReconnectWorkPolicy.State()
        ReconnectWorkPolicy.shouldRun(s, false, true, "a", 1_000)
        assertFalse(ReconnectWorkPolicy.shouldRun(s, true, isEligible = false, currentOwner = "a", nowMs = 2_000))
        // Eligibility returning later within the interval still allows the first run.
        ReconnectWorkPolicy.shouldRun(s, false, true, "a", 3_000)
        assertTrue(ReconnectWorkPolicy.shouldRun(s, true, isEligible = true, currentOwner = "a", nowMs = 4_000))
    }
}
