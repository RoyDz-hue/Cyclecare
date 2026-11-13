package com.cyclecare.app

import com.cyclecare.app.data.payment.PaymentStatus
import com.cyclecare.app.rust.CycleCareCoreBridge
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Strict-decode guards for the payment_capture_status_v1 bridge wiring (NEW
 * IMPLEMENTATION, 2026-11-13): the decoded status drives whether a capture
 * is treated as settled money, so a drifted, wrong-typed, or shaped-wrong
 * reply is rejected (null -> the caller keeps its verbatim Kotlin twin)
 * instead of becoming a payment verdict.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PaymentCaptureStatusDecodeTest {

    @Test
    fun knownStatusesDecode() {
        val cases = mapOf(
            "PENDING" to PaymentStatus.PENDING,
            "COMPLETED" to PaymentStatus.COMPLETED,
            "FAILED" to PaymentStatus.FAILED,
            "CANCELLED" to PaymentStatus.CANCELLED,
            "REFUNDED" to PaymentStatus.REFUNDED,
            "REVERSED" to PaymentStatus.REVERSED,
            "AMBIGUOUS" to PaymentStatus.AMBIGUOUS,
        )
        for ((wire, expected) in cases) {
            val reply = JSONObject("""{"ok":{"version":1,"status":"$wire"}}""")
            assertEquals(wire, expected, CycleCareCoreBridge.decodePaymentCaptureStatus(reply))
        }
    }

    @Test
    fun unknownOrMissingStatusDeclines() {
        assertNull(CycleCareCoreBridge.decodePaymentCaptureStatus(JSONObject("""{"ok":{"version":1,"status":"SUCCESSFUL"}}""")))
        assertNull(CycleCareCoreBridge.decodePaymentCaptureStatus(JSONObject("""{"ok":{"version":1,"status":1}}""")))
        assertNull(CycleCareCoreBridge.decodePaymentCaptureStatus(JSONObject("""{"ok":{"version":1}}""")))
        assertNull(CycleCareCoreBridge.decodePaymentCaptureStatus(JSONObject("""{"ok":{"version":1,"status":null}}""")))
    }

    @Test
    fun wrongShapeDeclines() {
        // Missing ok.
        assertNull(CycleCareCoreBridge.decodePaymentCaptureStatus(JSONObject("""{"status":"COMPLETED"}""")))
        // ok not an object.
        assertNull(CycleCareCoreBridge.decodePaymentCaptureStatus(JSONObject("""{"ok":"COMPLETED"}""")))
        // Wrong version.
        assertNull(CycleCareCoreBridge.decodePaymentCaptureStatus(JSONObject("""{"ok":{"version":2,"status":"COMPLETED"}}""")))
        // Version not an Int.
        assertNull(CycleCareCoreBridge.decodePaymentCaptureStatus(JSONObject("""{"ok":{"version":"1","status":"COMPLETED"}}""")))
    }
}
