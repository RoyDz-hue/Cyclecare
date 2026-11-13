package com.cyclecare.app

import com.cyclecare.app.data.payment.PaymentStatus
import com.cyclecare.app.data.payment.PaymentStatusVerifier
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Fallback-parity pins for the payment_capture_status_v1 twin (NEW
 * IMPLEMENTATION, 2026-11-13): the twin IS the original
 * PaymentStatusVerifier.captureStatus, exercised here against the real
 * org.json (Robolectric sdk=34) so every behavior the native port must
 * reproduce is pinned from source. Written, NOT executed in this workspace.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PaymentCaptureStatusFallbackParityTest {

    private fun twin(httpSuccessful: Boolean, body: String): PaymentStatus =
        PaymentStatusVerifier.captureStatus(httpSuccessful, body)

    @Test
    fun httpFailureAndMalformedBodyAreAmbiguous() {
        assertEquals(PaymentStatus.AMBIGUOUS, twin(false, """{"status":"success","success":true}"""))
        assertEquals(PaymentStatus.AMBIGUOUS, twin(false, ""))
        assertEquals(PaymentStatus.AMBIGUOUS, twin(true, ""))
        assertEquals(PaymentStatus.AMBIGUOUS, twin(true, "not json"))
        // Non-object roots: JSONObject(body) throws -> AMBIGUOUS.
        assertEquals(PaymentStatus.AMBIGUOUS, twin(true, """[1,2]"""))
        assertEquals(PaymentStatus.AMBIGUOUS, twin(true, "null"))
    }

    @Test
    fun refundedAndReversedWinOverEverything() {
        assertEquals(PaymentStatus.REFUNDED, twin(true, """{"status":"refunded","success":true}"""))
        assertEquals(PaymentStatus.REVERSED, twin(true, """{"status":"reversed","success":true}"""))
        // Branch order: refunded/reversed are checked before the success combo
        // and before the failed vocabulary.
        assertEquals(PaymentStatus.REFUNDED, twin(true, """{"status":"refunded","settled":true}"""))
    }

    @Test
    fun statusTrimAndCaseAreKotlinSemantics() {
        // Kotlin trim() strips Unicode whitespace (incl. NBSP, U+00A0
        // escaped explicitly below); lowercase().
        assertEquals(PaymentStatus.REFUNDED, twin(true, "{\"status\":\"\u00A0refunded\u00A0\"}"))
        assertEquals(PaymentStatus.REVERSED, twin(true, """{"status":" Reversed\t"}"""))
    }

    @Test
    fun completedRequiresOptBooleanSuccess() {
        assertEquals(PaymentStatus.COMPLETED, twin(true, """{"status":"success","success":true}"""))
        assertEquals(PaymentStatus.COMPLETED, twin(true, """{"status":"settled","success":true}"""))
        assertEquals(PaymentStatus.COMPLETED, twin(true, """{"status":"anything","success":true,"settled":true}"""))
        // optBoolean accepts the STRING "true" (case-insensitive).
        assertEquals(PaymentStatus.COMPLETED, twin(true, """{"status":"success","success":"true"}"""))
        assertEquals(PaymentStatus.COMPLETED, twin(true, """{"status":"anything","success":"TRUE","settled":"true"}"""))
        // Numbers are NOT optBoolean-true: JSON.toBoolean(1) is null ->
        // fallback false (executed-oracle verified), so the combo fails.
        assertEquals(PaymentStatus.AMBIGUOUS, twin(true, """{"status":"anything","success":"TRUE","settled":1}"""))
        // Without success=true there is no COMPLETED.
        assertEquals(PaymentStatus.AMBIGUOUS, twin(true, """{"status":"success"}"""))
        assertEquals(PaymentStatus.AMBIGUOUS, twin(true, """{"status":"success","success":1}"""))
        assertEquals(PaymentStatus.AMBIGUOUS, twin(true, """{"status":"anything","success":true}"""))
    }

    @Test
    fun failedVocabularyAndDefault() {
        for (s in listOf("failed", "cancelled", "canceled", "expired")) {
            assertEquals(s, PaymentStatus.FAILED, twin(true, """{"status":"$s"}"""))
        }
        assertEquals(PaymentStatus.AMBIGUOUS, twin(true, """{"status":"pending"}"""))
        assertEquals(PaymentStatus.AMBIGUOUS, twin(true, """{}"""))
    }

    @Test
    fun optStringCoercionsAndDuplicateKeys() {
        // Numeric status -> org.json optString coercion. AOSP android-14
        // JSON.toString is String.valueOf for non-strings: a Double status
        // renders "200.0" (NOT numberToString's stripped "200"); either way
        // no vocabulary branch matches.
        assertEquals(PaymentStatus.AMBIGUOUS, twin(true, """{"status":200}"""))
        assertEquals(PaymentStatus.AMBIGUOUS, twin(true, """{"status":200.0}"""))
        // Duplicate key: JSONObject.put is last-wins.
        assertEquals(PaymentStatus.REFUNDED, twin(true, """{"status":"pending","status":"refunded"}"""))
        // JSONObject.NULL status: String.valueOf(JSONObject.NULL) is the
        // literal "null", which matches no branch -> AMBIGUOUS.
        assertEquals(PaymentStatus.AMBIGUOUS, twin(true, """{"status":null}"""))
        // A nested object status coerces to its toString, never "refunded".
        assertEquals(PaymentStatus.AMBIGUOUS, twin(true, """{"status":{"x":1}}"""))
    }
}
