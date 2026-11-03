package com.cyclecare.bridge

import com.cyclecare.app.domain.sync.ReconnectWorkPolicy
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RustReconnectPolicyTest {

    private fun reply(kind: String, retry: Long? = null, wasConnected: Boolean = true, lastRunAtMs: Long = 0L, owner: String? = null): JSONObject {
        val ok = JSONObject().put("kind", kind)
        if (retry != null) ok.put("retryAfterMs", retry)
        return JSONObject().put("ok", ok).put("state", JSONObject()
            .put("wasConnected", wasConnected).put("lastRunAtMs", lastRunAtMs)
            .put("owner", if (owner == null) JSONObject.NULL else owner))
    }

    // --- strict decode ---

    @Test fun decodeRun() {
        val d = RustReconnectPolicy.decode(reply("Run", wasConnected = true, lastRunAtMs = 51040L, owner = "o1"))!!
        assertEquals(ReconnectWorkPolicy.Decision.Run, d.decision)
    }

    @Test fun decodeSkip() {
        val d = RustReconnectPolicy.decode(reply("Skip", wasConnected = false, lastRunAtMs = 7L, owner = null))!!
        assertEquals(ReconnectWorkPolicy.Decision.Skip, d.decision)
        assertEquals(false, d.post.wasConnected)
        assertEquals(7L, d.post.lastRunAtMs)
        assertNull(d.post.owner)
    }

    @Test fun decodeThrottled() {
        val d = RustReconnectPolicy.decode(reply("Throttled", retry = 8961L))!!
        assertEquals(ReconnectWorkPolicy.Decision.Throttled(8961L), d.decision)
    }

    @Test fun decodeRejectsUnknownKind() = assertNull(RustReconnectPolicy.decode(reply("run")))
    @Test fun decodeRejectsMissingState() = assertNull(RustReconnectPolicy.decode(JSONObject().put("ok", JSONObject().put("kind", "Run"))))
    @Test fun decodeRejectsNonStringOwner() = assertNull(RustReconnectPolicy.decode(
        reply("Run").apply { getJSONObject("state").put("owner", 42) }))
    @Test fun decodeRejectsThrottledWithoutRetry() = assertNull(RustReconnectPolicy.decode(reply("Throttled")))
    @Test fun decodeRejectsStateInsideOkOnly() {
        // state must be a sibling of ok, matching the FFI reply shape
        val bad = JSONObject().put("ok", JSONObject().put("kind", "Run")
            .put("state", JSONObject().put("wasConnected", true).put("lastRunAtMs", 1L).put("owner", JSONObject.NULL)))
        assertNull(RustReconnectPolicy.decode(bad))
    }

    // --- fallback semantics: native library absent in host tests, so step() must be
    // exactly the production Kotlin policy, including its state mutations ---

    @Test fun fallbackDecideRunOnTransition() {
        val st = ReconnectWorkPolicy.State(wasConnected = false, lastRunAtMs = 0L, owner = "o1")
        val d = RustReconnectPolicy.decide(st, isConnected = true, isEligible = true, currentOwner = "o1", nowMs = 51040L)
        assertEquals(ReconnectWorkPolicy.Decision.Run, d)
        assertEquals(true, st.wasConnected)
        assertEquals(51040L, st.lastRunAtMs)
        assertEquals("o1", st.owner)
    }

    @Test fun fallbackDecideSkipsRepeatedOnlineAndStillStampsWasConnected() {
        val st = ReconnectWorkPolicy.State(wasConnected = true, lastRunAtMs = 0L, owner = "o1")
        val d = RustReconnectPolicy.decide(st, isConnected = true, isEligible = true, currentOwner = "o1", nowMs = 51040L)
        assertEquals(ReconnectWorkPolicy.Decision.Skip, d)
        assertEquals(0L, st.lastRunAtMs)
    }

    @Test fun fallbackDecideOfflineFlipsWasConnected() {
        val st = ReconnectWorkPolicy.State(wasConnected = true, lastRunAtMs = 50000L, owner = "o1")
        val d = RustReconnectPolicy.decide(st, isConnected = false, isEligible = true, currentOwner = "o1", nowMs = 51040L)
        assertEquals(ReconnectWorkPolicy.Decision.Skip, d)
        assertEquals(false, st.wasConnected)
        assertEquals(50000L, st.lastRunAtMs)
    }

    @Test fun fallbackOwnerChangeResetsInterval() {
        val st = ReconnectWorkPolicy.State(wasConnected = false, lastRunAtMs = 50000L, owner = "o1")
        val d = RustReconnectPolicy.decide(st, isConnected = true, isEligible = true, currentOwner = "o2", nowMs = 51040L)
        assertEquals(ReconnectWorkPolicy.Decision.Run, d)
        assertEquals("o2", st.owner)
        assertEquals(51040L, st.lastRunAtMs)
    }

    @Test fun fallbackThrottleBoundary() {
        // lastRunAtMs=50000, now=109999 -> elapsed 59999 < 60000 -> Throttled(1)
        val st = ReconnectWorkPolicy.State(wasConnected = false, lastRunAtMs = 50000L, owner = "o1")
        val d = RustReconnectPolicy.decide(st, true, true, "o1", 109999L)
        assertEquals(ReconnectWorkPolicy.Decision.Throttled(1L), d)
        assertEquals(50000L, st.lastRunAtMs)
        // now=110000 -> elapsed 60000 -> Run
        val st2 = ReconnectWorkPolicy.State(wasConnected = false, lastRunAtMs = 50000L, owner = "o1")
        assertEquals(ReconnectWorkPolicy.Decision.Run, RustReconnectPolicy.decide(st2, true, true, "o1", 110000L))
        assertEquals(110000L, st2.lastRunAtMs)
    }

    @Test fun fallbackTrailingSkipsOfflineWithoutMutation() {
        val st = ReconnectWorkPolicy.State(wasConnected = true, lastRunAtMs = 50000L, owner = "o1")
        val d = RustReconnectPolicy.decideTrailing(st, isConnected = false, isEligible = true, currentOwner = "o1", nowMs = 120000L)
        assertEquals(ReconnectWorkPolicy.Decision.Skip, d)
        assertEquals(true, st.wasConnected)
        assertEquals(50000L, st.lastRunAtMs)
        assertEquals("o1", st.owner)
    }

    @Test fun fallbackTrailingSkipsStaleOwnerWithoutMutation() {
        val st = ReconnectWorkPolicy.State(wasConnected = true, lastRunAtMs = 50000L, owner = "o1")
        val d = RustReconnectPolicy.decideTrailing(st, true, true, "o2", 120000L)
        assertEquals(ReconnectWorkPolicy.Decision.Skip, d)
        assertEquals("o1", st.owner)
        assertEquals(50000L, st.lastRunAtMs)
    }

    @Test fun fallbackTrailingRunsAfterCooldown() {
        val st = ReconnectWorkPolicy.State(wasConnected = true, lastRunAtMs = 50000L, owner = "o1")
        val d = RustReconnectPolicy.decideTrailing(st, true, true, "o1", 120000L)
        assertEquals(ReconnectWorkPolicy.Decision.Run, d)
        assertEquals(120000L, st.lastRunAtMs)
    }

    @Test fun flagRemainsDisabled() {
        assertTrue(!RustReconnectPolicyConfig.ENABLED)
    }
}
