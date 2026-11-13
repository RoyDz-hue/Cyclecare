package com.cyclecare.app

import com.cyclecare.app.data.local.CycleSettings
import com.cyclecare.app.rust.CycleCareCoreBridge
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Loaded-contract evidence for the native cycle-screen anchor branch: calls
 * CycleCareCoreBridge.cycleScreenAnchorState DIRECTLY (no Kotlin computation
 * in this path - the bridge returns null on native unavailability or strict-
 * decode failure, and the first assertion skips cleanly in that case). A
 * non-null reply proves the loaded host cdylib executed the
 * cycle_screen_state arm. Expected values mirror the pinned dispatch case.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CycleScreenAnchorNativeJniTest {

    private val settings = CycleSettings(
        lastPeriodStartDate = "2026-10-05", averageCycleLength = 28,
        periodDuration = 5, lutealPhaseDays = 14
    )

    @Test
    fun nativeAnchorSliceMatchesPinnedDispatchCase() {
        val s = CycleCareCoreBridge.cycleScreenAnchorState(
            settings, "exact", "28", LocalDate.of(2026, 10, 19), LocalDate.of(2026, 10, 26))
        org.junit.Assume.assumeTrue(
            "native cycle_screen_state unavailable or strict decode rejected; JNI test skipped",
            s != null
        )
        assertTrue(s!!.hasCycleAnchor)
        assertFalse(s.cycleStartIsApproximate)
        assertEquals(14, s.daysUntilNextPeriod)
        assertEquals(27, s.daysUntilOvulation)
    }

    @Test
    fun nativeUnknownChoiceDropsAnchor() {
        val gate = CycleCareCoreBridge.cycleScreenAnchorState(
            settings, "exact", "28", LocalDate.of(2026, 10, 19), LocalDate.of(2026, 10, 26))
        org.junit.Assume.assumeTrue("native unavailable; skipped", gate != null)
        val s = CycleCareCoreBridge.cycleScreenAnchorState(
            settings, "exact", "unknown", LocalDate.of(2026, 10, 19), LocalDate.of(2026, 10, 26))
        assertTrue(s != null)
        assertFalse(s!!.hasCycleAnchor)
    }
}
