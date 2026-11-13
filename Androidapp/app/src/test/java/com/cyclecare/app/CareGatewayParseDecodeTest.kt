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
 * Strict-decode guards for the care_gateway_parse_v1 bridge wiring (NEW
 * IMPLEMENTATION, 2026-11-13): the decoded fields become user-facing care
 * guidance, so a drifted, wrong-typed, or shaped-wrong reply is rejected
 * (null -> the caller keeps its verbatim Kotlin twin).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CareGatewayParseDecodeTest {

    private fun validReply() = JSONObject(
        """{"ok":{"version":1,"fields":{"summary":"s","whatText":"w","understandText":"u","careText":"c","watchText":"wa","escalateText":"e","source":"CycleCare Assistant"}}}""",
    )

    @Test
    fun validFieldsDecode() {
        val parsed = CycleCareCoreBridge.decodeCareGatewayParse(validReply())!!
        assertEquals("s", parsed.summary)
        assertEquals("w", parsed.whatText)
        assertEquals("u", parsed.understandText)
        assertEquals("c", parsed.careText)
        assertEquals("wa", parsed.watchText)
        assertEquals("e", parsed.escalateText)
        assertEquals("CycleCare Assistant", parsed.source)
    }

    @Test
    fun nativeInvalidVerdictDeclinesToTwin() {
        // fields:null is the port's validation-failure verdict: the twin's
        // identical validation produces the source's fall-through.
        assertNull(CycleCareCoreBridge.decodeCareGatewayParse(JSONObject("""{"ok":{"version":1,"fields":null}}""")))
        assertNull(CycleCareCoreBridge.decodeCareGatewayParse(JSONObject("""{"ok":{"version":1}}""")))
    }

    @Test
    fun driftDeclines() {
        // A non-String field.
        assertNull(CycleCareCoreBridge.decodeCareGatewayParse(JSONObject(
            """{"ok":{"version":1,"fields":{"summary":5,"whatText":"w","understandText":"u","careText":"c","watchText":"wa","escalateText":"e","source":"CycleCare Assistant"}}}""",
        )))
        // A missing field.
        assertNull(CycleCareCoreBridge.decodeCareGatewayParse(JSONObject(
            """{"ok":{"version":1,"fields":{"summary":"s","whatText":"w","understandText":"u","careText":"c","watchText":"wa","source":"CycleCare Assistant"}}}""",
        )))
        // Source drift: anything but the single normalized constant.
        assertNull(CycleCareCoreBridge.decodeCareGatewayParse(JSONObject(
            """{"ok":{"version":1,"fields":{"summary":"s","whatText":"w","understandText":"u","careText":"c","watchText":"wa","escalateText":"e","source":"cohere"}}}""",
        )))
        // Shape drift.
        assertNull(CycleCareCoreBridge.decodeCareGatewayParse(JSONObject("""{"ok":{"version":2,"fields":{}}}""")))
        assertNull(CycleCareCoreBridge.decodeCareGatewayParse(JSONObject("""{"ok":{"version":"1","fields":{}}}""")))
        assertNull(CycleCareCoreBridge.decodeCareGatewayParse(JSONObject("""{"fields":{}}""")))
        assertNull(CycleCareCoreBridge.decodeCareGatewayParse(JSONObject("""{"ok":"x"}""")))
    }
}
