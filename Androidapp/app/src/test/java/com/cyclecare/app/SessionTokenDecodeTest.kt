package com.cyclecare.app

import com.cyclecare.app.rust.CycleCareCoreBridge
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Strict-decode guards for the session-token bridge wiring (NEW
 * IMPLEMENTATION, 2026-11-13): the verdicts gate token renewal on the
 * production auth path, so a drifted, wrong-typed, or shaped-wrong reply
 * is rejected (null -> the caller keeps the Kotlin check) instead of
 * becoming an auth decision.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SessionTokenDecodeTest {

    @Test
    fun expiryAccepted() {
        val reply = JSONObject("""{"ok":{"version":1,"expiry":1767225600}}""")
        assertEquals(1767225600L, CycleCareCoreBridge.decodeSessionTokenExpiry(reply))
    }

    @Test
    fun expiryZeroAccepted() {
        // 0 mirrors the source catch-all and must survive decoding.
        val reply = JSONObject("""{"ok":{"version":1,"expiry":0}}""")
        assertEquals(0L, CycleCareCoreBridge.decodeSessionTokenExpiry(reply))
    }

    @Test
    fun expiryStringRejected() {
        assertNull(CycleCareCoreBridge.decodeSessionTokenExpiry(JSONObject("""{"ok":{"version":1,"expiry":"1767225600"}}""")))
    }

    @Test
    fun expiryNegativeRejected() {
        assertNull(CycleCareCoreBridge.decodeSessionTokenExpiry(JSONObject("""{"ok":{"version":1,"expiry":-5}}""")))
    }

    @Test
    fun expiryWrongVersionRejected() {
        assertNull(CycleCareCoreBridge.decodeSessionTokenExpiry(JSONObject("""{"ok":{"version":2,"expiry":0}}""")))
        assertNull(CycleCareCoreBridge.decodeSessionTokenExpiry(JSONObject("""{"error":"x"}""")))
    }

    @Test
    fun validAccepted() {
        assertTrue(CycleCareCoreBridge.decodeSessionTokenValid(JSONObject("""{"ok":{"version":1,"valid":true}}"""))!!)
        assertFalse(CycleCareCoreBridge.decodeSessionTokenValid(JSONObject("""{"ok":{"version":1,"valid":false}}"""))!!)
    }

    @Test
    fun validStringRejected() {
        // optBoolean would coerce this into an auth verdict; types first.
        assertNull(CycleCareCoreBridge.decodeSessionTokenValid(JSONObject("""{"ok":{"version":1,"valid":"true"}}""")))
    }

    @Test
    fun validMissingRejected() {
        assertNull(CycleCareCoreBridge.decodeSessionTokenValid(JSONObject("""{"ok":{"version":1}}""")))
        assertNull(CycleCareCoreBridge.decodeSessionTokenValid(JSONObject("""{"error":"x"}""")))
    }
}
