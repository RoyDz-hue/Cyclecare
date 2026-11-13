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
 * Strict-decode guards for the pricing_parse_v1 bridge wiring (NEW
 * IMPLEMENTATION, 2026-11-13): the decoded prices are what the store shows
 * and what the checkout truth line states, so a drifted, wrong-typed, or
 * shaped-wrong reply is rejected (null -> the caller keeps its verbatim
 * Kotlin parse) instead of becoming a displayed price.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RemotePricingParseDecodeTest {

    @Test
    fun packagesAccepted() {
        val reply = JSONObject("""{"ok":{"version":1,"packages":{"pkg_starter":{"currency":"UGX","amount":3699,"approx":true,"chargeCurrency":"USD","chargeAmount":0.99}}}}""")
        val parsed = CycleCareCoreBridge.decodeRemotePricingParse(reply)
        val price = parsed!!["pkg_starter"]!!
        assertEquals("UGX", price.currency)
        assertEquals(3699.0, price.amount, 0.0)
        assertTrue(price.approx)
        assertEquals("USD", price.chargeCurrency)
        assertEquals(0.99, price.chargeAmount, 0.0)
    }

    @Test
    fun packagesNullMeansNativeParseFailedAndDeclines() {
        // The native-side parse failure verdict declines to the Kotlin parse.
        val reply = JSONObject("""{"ok":{"version":1,"packages":null}}""")
        assertNull(CycleCareCoreBridge.decodeRemotePricingParse(reply))
    }

    @Test
    fun wrongShapesRejected() {
        assertNull(CycleCareCoreBridge.decodeRemotePricingParse(JSONObject("""{"ok":{"version":2,"packages":{}}}""")))
        assertNull(CycleCareCoreBridge.decodeRemotePricingParse(JSONObject("""{"error":"x"}""")))
        assertNull(CycleCareCoreBridge.decodeRemotePricingParse(JSONObject("""{"ok":{"version":1,"packages":[]}}""")))
        // Wrong-typed or missing fields reject, never coerce.
        assertNull(CycleCareCoreBridge.decodeRemotePricingParse(JSONObject("""{"ok":{"version":1,"packages":{"p":{"currency":"UGX","amount":"3699","approx":true,"chargeCurrency":"USD","chargeAmount":0.99}}}}""")))
        assertNull(CycleCareCoreBridge.decodeRemotePricingParse(JSONObject("""{"ok":{"version":1,"packages":{"p":{"currency":"UGX","amount":3699,"approx":"true","chargeCurrency":"USD","chargeAmount":0.99}}}}""")))
        assertNull(CycleCareCoreBridge.decodeRemotePricingParse(JSONObject("""{"ok":{"version":1,"packages":{"p":{"currency":"UGX","amount":3699,"approx":true,"chargeCurrency":"USD"}}}}""")))
        assertNull(CycleCareCoreBridge.decodeRemotePricingParse(JSONObject("""{"ok":{"version":1,"packages":{"p":null}}}""")))
    }

    @Test
    fun emptyPackagesMapAccepted() {
        // A parsed-but-empty map is a real native answer (the port returns
        // None for empty, so this shape is defensive; decode must still be
        // strict but not reject a well-formed empty object).
        val reply = JSONObject("""{"ok":{"version":1,"packages":{}}}""")
        assertTrue(CycleCareCoreBridge.decodeRemotePricingParse(reply)!!.isEmpty())
    }

    @Test
    fun booleanApproxFalseRoundTrips() {
        val reply = JSONObject("""{"ok":{"version":1,"packages":{"p":{"currency":"KES","amount":50.0,"approx":false,"chargeCurrency":"KES","chargeAmount":50.0}}}}""")
        assertFalse(CycleCareCoreBridge.decodeRemotePricingParse(reply)!!["p"]!!.approx)
    }
}
