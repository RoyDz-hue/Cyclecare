package com.cyclecare.app

import com.cyclecare.app.rust.CycleCareCoreBridge
import com.cyclecare.app.util.ValidationResult
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Strict-decode guards for the payment_phone_v1 bridge wiring (NEW
 * IMPLEMENTATION, 2026-11-13): the decoded verdict gates a payment request
 * and the sanitized number goes into the STK payload, so a drifted,
 * wrong-typed, or shaped-wrong reply is rejected (null -> the caller keeps
 * its verbatim Kotlin twins) instead of becoming a payment decision.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PaymentPhoneDecodeTest {

    @Test
    fun validateVerdictsDecode() {
        val valid = CycleCareCoreBridge.decodePaymentPhoneValidate(
            JSONObject("""{"ok":{"version":1,"valid":true,"message":null}}"""),
        )
        assertTrue(valid is ValidationResult.Valid)

        val invalid = CycleCareCoreBridge.decodePaymentPhoneValidate(
            JSONObject("""{"ok":{"version":1,"valid":false,"message":"Phone number is required."}}"""),
        )
        assertEquals(ValidationResult.Invalid("Phone number is required."), invalid)
    }

    @Test
    fun validateDriftDeclines() {
        // valid=true carrying a message.
        assertNull(CycleCareCoreBridge.decodePaymentPhoneValidate(
            JSONObject("""{"ok":{"version":1,"valid":true,"message":"Phone number is required."}}"""),
        ))
        // valid=false with an unknown message (the four verbatim messages only).
        assertNull(CycleCareCoreBridge.decodePaymentPhoneValidate(
            JSONObject("""{"ok":{"version":1,"valid":false,"message":"Bad phone."}}"""),
        ))
        assertNull(CycleCareCoreBridge.decodePaymentPhoneValidate(
            JSONObject("""{"ok":{"version":1,"valid":false,"message":null}}"""),
        ))
        // Wrong types / shapes.
        assertNull(CycleCareCoreBridge.decodePaymentPhoneValidate(JSONObject("""{"ok":{"version":1,"valid":"true"}}""")))
        assertNull(CycleCareCoreBridge.decodePaymentPhoneValidate(JSONObject("""{"ok":{"version":2,"valid":true}}""")))
        assertNull(CycleCareCoreBridge.decodePaymentPhoneValidate(JSONObject("""{"ok":{"version":"1","valid":true}}""")))
        assertNull(CycleCareCoreBridge.decodePaymentPhoneValidate(JSONObject("""{"valid":true}""")))
        assertNull(CycleCareCoreBridge.decodePaymentPhoneValidate(JSONObject("""{"ok":"true"}""")))
    }

    @Test
    fun sanitizeDecodesAndDeclines() {
        assertEquals(
            "254712345678",
            CycleCareCoreBridge.decodePaymentPhoneSanitize(
                JSONObject("""{"ok":{"version":1,"sanitized":"254712345678"}}"""),
            ),
        )
        assertNull(CycleCareCoreBridge.decodePaymentPhoneSanitize(JSONObject("""{"ok":{"version":1,"sanitized":254712345678}}""")))
        assertNull(CycleCareCoreBridge.decodePaymentPhoneSanitize(JSONObject("""{"ok":{"version":1}}""")))
        assertNull(CycleCareCoreBridge.decodePaymentPhoneSanitize(JSONObject("""{"ok":{"version":2,"sanitized":"254712345678"}}""")))
        assertNull(CycleCareCoreBridge.decodePaymentPhoneSanitize(JSONObject("""{"sanitized":"254712345678"}""")))
    }
}
