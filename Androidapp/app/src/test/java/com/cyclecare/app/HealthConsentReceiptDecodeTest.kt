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
 * Strict-decode guards for the health-consent receipt bridge wiring
 * (NEW IMPLEMENTATION, 2026-11-13): the read reply feeds sync CAS and the
 * save verdict gates a real SharedPreferences mutation, so a drifted,
 * wrong-typed, or shaped-wrong reply is rejected (null -> the caller keeps
 * the Kotlin guard) instead of becoming an authoritative verdict.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class HealthConsentReceiptDecodeTest {

    @Test
    fun readReceiptAccepted() {
        val reply = JSONObject("""{"ok":{"version":1,"receipt":7}}""")
        assertEquals(7L, CycleCareCoreBridge.decodeHealthConsentReceiptRead(reply)!!.receipt)
    }

    @Test
    fun readAbsentReceiptAccepted() {
        val reply = JSONObject("""{"ok":{"version":1,"receipt":null}}""")
        val d = CycleCareCoreBridge.decodeHealthConsentReceiptRead(reply)
        assertNull(d!!.receipt)
    }

    @Test
    fun readStringReceiptRejected() {
        val reply = JSONObject("""{"ok":{"version":1,"receipt":"7"}}""")
        assertNull(CycleCareCoreBridge.decodeHealthConsentReceiptRead(reply))
    }

    @Test
    fun readZeroReceiptRejected() {
        // The source shape is takeIf { it > 0 }; a zero echo is malformed.
        val reply = JSONObject("""{"ok":{"version":1,"receipt":0}}""")
        assertNull(CycleCareCoreBridge.decodeHealthConsentReceiptRead(reply))
    }

    @Test
    fun readNegativeReceiptRejected() {
        val reply = JSONObject("""{"ok":{"version":1,"receipt":-3}}""")
        assertNull(CycleCareCoreBridge.decodeHealthConsentReceiptRead(reply))
    }

    @Test
    fun readMissingReceiptKeyRejected() {
        val reply = JSONObject("""{"ok":{"version":1}}""")
        assertNull(CycleCareCoreBridge.decodeHealthConsentReceiptRead(reply))
    }

    @Test
    fun readWrongVersionRejected() {
        assertNull(CycleCareCoreBridge.decodeHealthConsentReceiptRead(JSONObject("""{"ok":{"version":2,"receipt":7}}""")))
        assertNull(CycleCareCoreBridge.decodeHealthConsentReceiptRead(JSONObject("""{"ok":{"version":"1","receipt":7}}""")))
        assertNull(CycleCareCoreBridge.decodeHealthConsentReceiptRead(JSONObject("""{"ok":{"receipt":7}}""")))
    }

    @Test
    fun readMissingOkRejected() {
        assertNull(CycleCareCoreBridge.decodeHealthConsentReceiptRead(JSONObject("""{"error":"x"}""")))
    }

    @Test
    fun saveAllowedAccepted() {
        assertTrue(CycleCareCoreBridge.decodeHealthConsentReceiptSaveAllowed(JSONObject("""{"ok":{"version":1,"allowed":true}}"""))!!)
        assertFalse(CycleCareCoreBridge.decodeHealthConsentReceiptSaveAllowed(JSONObject("""{"ok":{"version":1,"allowed":false}}"""))!!)
    }

    @Test
    fun saveStringAllowedRejected() {
        // optBoolean would coerce this into an authoritative verdict that
        // could skip or force a real receipt write; types are checked first.
        assertNull(CycleCareCoreBridge.decodeHealthConsentReceiptSaveAllowed(JSONObject("""{"ok":{"version":1,"allowed":"false"}}""")))
    }

    @Test
    fun saveMissingAllowedRejected() {
        assertNull(CycleCareCoreBridge.decodeHealthConsentReceiptSaveAllowed(JSONObject("""{"ok":{"version":1}}""")))
    }

    @Test
    fun saveWrongVersionRejected() {
        assertNull(CycleCareCoreBridge.decodeHealthConsentReceiptSaveAllowed(JSONObject("""{"ok":{"version":2,"allowed":true}}""")))
    }

    @Test
    fun saveMissingOkRejected() {
        assertNull(CycleCareCoreBridge.decodeHealthConsentReceiptSaveAllowed(JSONObject("""{"error":"x"}""")))
    }
}
