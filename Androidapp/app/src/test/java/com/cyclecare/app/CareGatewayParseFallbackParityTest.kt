package com.cyclecare.app

import com.cyclecare.app.domain.ai.CareAiService
import com.cyclecare.app.rust.CycleCareCoreBridge
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Fallback-parity pins for the care_gateway_parse_v1 twin (NEW
 * IMPLEMENTATION, 2026-11-13): the twin is the verbatim pre-wiring gateway
 * validation block in CareAiService, reached by reflection and run against
 * the REAL AOSP org.json classes at sdk 34. Written, NOT executed in this
 * workspace.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CareGatewayParseFallbackParityTest {

    private fun twin(raw: String): CycleCareCoreBridge.GatewayCareFields? {
        val m = CareAiService::class.java.getDeclaredMethod("kotlinGatewayParse", String::class.java)
        m.isAccessible = true
        return m.invoke(CareAiService, raw) as CycleCareCoreBridge.GatewayCareFields?
    }

    private fun body(summary: String) =
        """{"success":true,"provider":"cohere","data":{"summary":$summary,"whatText":"w","understandText":"u","careText":"c","watchText":"wa","escalateText":"e"}}"""

    @Test
    fun validPayloadDecodesWithNormalizedSource() {
        val parsed = twin(body(""""s""""))!!
        assertEquals("s", parsed.summary)
        assertEquals("w", parsed.whatText)
        assertEquals("u", parsed.understandText)
        assertEquals("c", parsed.careText)
        assertEquals("wa", parsed.watchText)
        assertEquals("e", parsed.escalateText)
        // Provider/routing detail never surfaces: always the one label.
        assertEquals("CycleCare Assistant", parsed.source)
    }

    @Test
    fun successGateVariants() {
        // String forms of success parse (AOSP parseBoolean, case-insensitive).
        assertEquals("s", twin(body(""""s"""").replace("\"success\":true", "\"success\":\"true\""))!!.summary)
        assertEquals("s", twin(body(""""s"""").replace("\"success\":true", "\"success\":\"TRUE\""))!!.summary)
        // Missing/false success -> null (fall-through).
        assertNull(twin(body(""""s"""").replace("\"success\":true,", "")))
        assertNull(twin(body(""""s"""").replace("\"success\":true", "\"success\":false")))
        // success as a NUMBER is not optBoolean-true.
        assertNull(twin(body(""""s"""").replace("\"success\":true", "\"success\":1")))
    }

    @Test
    fun dataAndFieldValidation() {
        // data not an object.
        assertNull(twin("""{"success":true,"data":"x"}"""))
        assertNull(twin("""{"success":true,"data":null}"""))
        // Blank field rejected; numeric field rejected (opt is String, no
        // optString coercion).
        assertNull(twin(body(""" " """")))
        assertNull(twin(body("5")))
        // UTF-16 length bound: 4000 passes, 4001 fails (astral chars count 2).
        assertEquals("y".repeat(4000), twin(body("\"${"y".repeat(4000)}\""))!!.summary)
        assertNull(twin(body("\"${"y".repeat(4001)}\"")))
    }

    @Test
    fun sourceSelectionChain() {
        // provider blank -> falls back to source; both normalize anyway.
        val viaProvider = twin(body(""""s""""))!!
        assertEquals("CycleCare Assistant", viaProvider.source)
        val viaSource = twin(body(""""s"""").replace("\"provider\":\"cohere\"", "\"provider\":\"\",\"source\":\"x\""))!!
        assertEquals("CycleCare Assistant", viaSource.source)
    }
}
