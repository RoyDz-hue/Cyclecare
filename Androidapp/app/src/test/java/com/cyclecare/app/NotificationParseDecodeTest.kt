package com.cyclecare.app

import com.cyclecare.app.rust.CycleCareCoreBridge
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Strict-decode guards for the notification_parse_v1 bridge wiring (NEW
 * IMPLEMENTATION, 2026-11-13): the decoded timestamp orders user-facing
 * notifications and the decoded priority can mark one urgent, so a
 * drifted, wrong-typed, or shaped-wrong reply is rejected (null -> the
 * caller keeps its verbatim Kotlin twin).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NotificationParseDecodeTest {

    private fun ts(body: String) = CycleCareCoreBridge.decodeNotificationTimestamp(JSONObject(body))
    private fun pr(body: String) = CycleCareCoreBridge.decodeNotificationPriority(JSONObject(body))

    @Test
    fun validTimestampDecodes() {
        assertEquals(1793434500000L, ts("""{"ok":{"version":1,"epoch_ms":1793434500000}}"""))
        // Negative values are legitimate pre-1970 parses, not drift.
        assertEquals(-1L, ts("""{"ok":{"version":1,"epoch_ms":-1}}"""))
    }

    @Test
    fun timestampDeclinesWrongTypesAndVersionDrift() {
        assertNull(ts("""{"ok":{"version":1,"epoch_ms":1793434500000.5}}"""))
        assertNull(ts("""{"ok":{"version":1,"epoch_ms":"1793434500000"}}"""))
        assertNull(ts("""{"ok":{"version":2,"epoch_ms":1793434500000}}"""))
        assertNull(ts("""{"ok":{"version":"1","epoch_ms":1793434500000}}"""))
        assertNull(ts("""{"ok":{"epoch_ms":1793434500000}}"""))
        assertNull(ts("""{"error":"native unavailable"}"""))
    }

    @Test
    fun timestampZeroVerdictDeclinesToTwin() {
        // 0 is the port's unparseable verdict: the twin's identical parse
        // produces the same 0, so the verdict is never consumed natively.
        assertNull(ts("""{"ok":{"version":1,"epoch_ms":0}}"""))
    }

    @Test
    fun validPriorityDecodes() {
        assertEquals("URGENT", pr("""{"ok":{"version":1,"priority":"URGENT"}}"""))
        assertEquals("HIGH", pr("""{"ok":{"version":1,"priority":"HIGH"}}"""))
        assertEquals("NORMAL", pr("""{"ok":{"version":1,"priority":"NORMAL"}}"""))
    }

    @Test
    fun priorityDeclinesOutsideExactVocabulary() {
        assertNull(pr("""{"ok":{"version":1,"priority":"urgent"}}"""))
        assertNull(pr("""{"ok":{"version":1,"priority":""}}"""))
        assertNull(pr("""{"ok":{"version":1,"priority":7}}"""))
        assertNull(pr("""{"ok":{"version":2,"priority":"HIGH"}}"""))
        assertNull(pr("""{"ok":{"version":1}}"""))
        assertNull(pr("""{"error":"native unavailable"}"""))
    }
}
