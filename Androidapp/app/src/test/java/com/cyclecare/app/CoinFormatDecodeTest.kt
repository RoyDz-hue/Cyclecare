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
 * Strict-decode guards for the coin_format_v1 bridge wiring (NEW
 * IMPLEMENTATION, 2026-11-13): the decoded string lands on user-facing
 * balance surfaces, so a drifted, wrong-typed, or shaped-wrong reply is
 * rejected (null -> the caller keeps its verbatim Kotlin twin).
 * Written, NOT executed in this workspace.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CoinFormatDecodeTest {

    @Test
    fun validReplyDecodes() {
        val reply = JSONObject("""{"ok":{"version":1,"formatted":"1,234.5"}}""")
        assertEquals("1,234.5", CycleCareCoreBridge.decodeCoinFormat(reply))
    }

    @Test
    fun driftDeclines() {
        assertNull(CycleCareCoreBridge.decodeCoinFormat(JSONObject("""{"ok":{"version":1,"formatted":1234.5}}""")))
        assertNull(CycleCareCoreBridge.decodeCoinFormat(JSONObject("""{"ok":{"version":1}}""")))
        assertNull(CycleCareCoreBridge.decodeCoinFormat(JSONObject("""{"ok":{"version":1,"formatted":null}}""")))
        assertNull(CycleCareCoreBridge.decodeCoinFormat(JSONObject("""{"ok":{"version":2,"formatted":"5"}}""")))
        assertNull(CycleCareCoreBridge.decodeCoinFormat(JSONObject("""{"ok":{"version":"1","formatted":"5"}}""")))
        assertNull(CycleCareCoreBridge.decodeCoinFormat(JSONObject("""{"formatted":"5"}""")))
        assertNull(CycleCareCoreBridge.decodeCoinFormat(JSONObject("""{"ok":"5"}""")))
    }
}
