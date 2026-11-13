package com.cyclecare.app

import com.cyclecare.app.rust.CycleCareCoreBridge
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Strict-decode guard for the flow-anchor bridge: the repository persists
 * the reply's newAnchor, so the echo must be bound to the submitted date.
 * A drifted, wrong-typed, or shaped-wrong reply is rejected (null -> the
 * funnel falls back to the Kotlin policy) instead of being written.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FlowAnchorDecodeTest {

    @Test
    fun driftedEchoRejected() {
        val reply = JSONObject("""{"ok":{"update":true,"newAnchor":"2026-10-22"}}""")
        assertNull(CycleCareCoreBridge.decodeFlowAnchorReply(reply, "2026-10-21"))
    }

    @Test
    fun exactEchoAccepted() {
        val reply = JSONObject("""{"ok":{"update":true,"newAnchor":"2026-10-21"}}""")
        val d = CycleCareCoreBridge.decodeFlowAnchorReply(reply, "2026-10-21")
        assertEquals(true, d!!.update)
        assertEquals("2026-10-21", d.newAnchor)
    }

    @Test
    fun wrongTypedEchoRejected() {
        val reply = JSONObject("""{"ok":{"update":true,"newAnchor":20261021}}""")
        assertNull(CycleCareCoreBridge.decodeFlowAnchorReply(reply, "2026-10-21"))
    }

    @Test
    fun nonBooleanUpdateRejected() {
        val reply = JSONObject("""{"ok":{"update":"true","newAnchor":"2026-10-21"}}""")
        assertNull(CycleCareCoreBridge.decodeFlowAnchorReply(reply, "2026-10-21"))
    }

    @Test
    fun missingOkRejected() {
        assertNull(CycleCareCoreBridge.decodeFlowAnchorReply(JSONObject("""{"error":"x"}"""), "2026-10-21"))
    }

    @Test
    fun holdNeedsNoAnchor() {
        val reply = JSONObject("""{"ok":{"update":false}}""")
        val d = CycleCareCoreBridge.decodeFlowAnchorReply(reply, "2026-10-21")
        assertFalse(d!!.update)
        assertNull(d.newAnchor)
    }
}
