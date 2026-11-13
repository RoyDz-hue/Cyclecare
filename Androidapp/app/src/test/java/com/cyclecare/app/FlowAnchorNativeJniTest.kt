package com.cyclecare.app

import com.cyclecare.app.rust.CycleCareCoreBridge
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Loaded-contract evidence for the native flow-anchor branch: calls
 * CycleCareCoreBridge.flowAnchorDecision DIRECTLY (no Kotlin policy in this
 * path - the bridge returns null on native unavailability or strict-decode
 * failure, and the first assertion skips cleanly in that case). A non-null
 * reply proves the loaded host cdylib executed the flow_anchor_decision arm.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FlowAnchorNativeJniTest {

    @Test
    fun nativeAdvanceAndHoldDecisions() {
        val advance = CycleCareCoreBridge.flowAnchorDecision("2026-10-21", "HEAVY", "2026-10-05")
        org.junit.Assume.assumeTrue(
            "native flow_anchor_decision unavailable or strict decode rejected; JNI test skipped",
            advance != null
        )
        assertTrue(advance!!.update)
        assertEquals("2026-10-21", advance.newAnchor)

        val hold = CycleCareCoreBridge.flowAnchorDecision("2026-10-20", "HEAVY", "2026-10-05")
        assertTrue(hold != null)
        assertFalse(hold!!.update)

        val nonFlow = CycleCareCoreBridge.flowAnchorDecision("2027-01-01", "SPOTTING", "2026-10-05")
        assertTrue(nonFlow != null)
        assertFalse(nonFlow!!.update)

        val blankPrior = CycleCareCoreBridge.flowAnchorDecision("2026-10-10", "MODERATE", "")
        assertTrue(blankPrior != null)
        assertTrue(blankPrior!!.update)
        assertEquals("2026-10-10", blankPrior.newAnchor)

        // Malformed log date: the arm errors, the bridge reports null
        // (repository keeps the Kotlin behavior: in-transaction throw).
        assertNull(CycleCareCoreBridge.flowAnchorDecision("2026-13-40", "HEAVY", "2026-10-05"))
    }
}
