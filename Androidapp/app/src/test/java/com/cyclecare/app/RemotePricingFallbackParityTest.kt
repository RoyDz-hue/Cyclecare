package com.cyclecare.app

import com.cyclecare.app.data.economy.RemoteLocalPricing
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Regression pins for the Kotlin fallback twin of the pricing_parse_v1
 * wiring (NEW IMPLEMENTATION, 2026-11-13): the twin is the verbatim
 * pre-wiring RemoteLocalPricing parse, run here against the REAL AOSP
 * org.json classes at sdk 34. Reflection reaches the private twin; the
 * production parse() entry point stays untouched.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RemotePricingFallbackParityTest {

    private fun twin(raw: String?): Map<String, RemoteLocalPricing.LocalizedPrice>? {
        val m = RemoteLocalPricing::class.java.getDeclaredMethod("kotlinParsePricing", String::class.java)
        m.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        return m.invoke(RemoteLocalPricing, raw) as Map<String, RemoteLocalPricing.LocalizedPrice>?
    }

    @Test
    fun validPayloadParses() {
        val raw = """{"currency":"UGX","packages":[
          {"id":"pkg_starter","title":"Starter Glow","coins":70,
           "display":{"currency":"UGX","amount":3699,"approx":true,"charge_currency":"USD","charge_amount":0.99}}]}"""
        val parsed = twin(raw)!!
        val price = parsed["pkg_starter"]!!
        assertEquals("UGX", price.currency)
        assertEquals(3699.0, price.amount, 0.0)
        assertTrue(price.approx)
        assertEquals("USD", price.chargeCurrency)
        assertEquals(0.99, price.chargeAmount, 0.0)
    }

    @Test
    fun malformedAndBlankAreNull() {
        assertNull(twin(null))
        assertNull(twin(""))
        assertNull(twin("   "))
        assertNull(twin("not json"))
        // Valid JSON but not an object: JSONObject(raw) throws -> catch.
        assertNull(twin("[]"))
        // packages missing or not an array -> null.
        assertNull(twin("""{"currency":"UGX"}"""))
        assertNull(twin("""{"packages":{}}"""))
        // Empty packages -> null.
        assertNull(twin("""{"packages":[]}"""))
    }

    @Test
    fun invalidRowsRejectWholePayload() {
        // Non-object row: getJSONObject(i) throws -> catch -> null.
        assertNull(twin("""{"packages":["x"]}"""))
        // Missing display -> null.
        assertNull(twin("""{"packages":[{"id":"p"}]}"""))
        // Blank id, wrong-length currency, NaN/non-positive amounts all
        // reject the WHOLE payload, not just the row.
        assertNull(twin("""{"packages":[{"id":" ","display":{"currency":"UGX","amount":1,"charge_currency":"USD","charge_amount":1}}]}"""))
        assertNull(twin("""{"packages":[{"id":"p","display":{"currency":"UG","amount":1,"charge_currency":"USD","charge_amount":1}}]}"""))
        assertNull(twin("""{"packages":[{"id":"p","display":{"currency":"UGX","amount":-1,"charge_currency":"USD","charge_amount":1}}]}"""))
        assertNull(twin("""{"packages":[{"id":"p","display":{"currency":"UGX","amount":1,"charge_currency":"US","charge_amount":1}}]}"""))
        assertNull(twin("""{"packages":[{"id":"p","display":{"currency":"UGX","amount":1,"charge_currency":"USD","charge_amount":0}}]}"""))
    }

    @Test
    fun optSemanticsPreserved() {
        // AOSP optBoolean accepts the STRING "true"; optDouble parses a
        // numeric STRING; last duplicate key wins.
        val raw = """{"packages":[
          {"id":"p1","display":{"currency":"UGX","amount":"12.50","approx":"true","charge_currency":"USD","charge_amount":"0.99"}},
          {"id":"p1","display":{"currency":"KES","amount":50,"approx":false,"charge_currency":"KES","charge_amount":50}}]}"""
        val parsed = twin(raw)!!
        assertEquals(1, parsed.size) // last-wins duplicate id
        val price = parsed["p1"]!!
        assertEquals("KES", price.currency)
        assertFalse(price.approx)
        val first = """{"packages":[{"id":"p1","display":{"currency":"UGX","amount":"12.50","approx":"true","charge_currency":"USD","charge_amount":"0.99"}}]}"""
        val p1 = twin(first)!!["p1"]!!
        assertEquals(12.50, p1.amount, 0.0)
        assertTrue(p1.approx)
        assertEquals(0.99, p1.chargeAmount, 0.0)
    }

    @Test
    fun numericStringAmountAcceptedButJunkRejected() {
        assertNull(twin("""{"packages":[{"id":"p","display":{"currency":"UGX","amount":"abc","charge_currency":"USD","charge_amount":1}}]}"""))
        // optDouble on a Boolean falls back to NaN -> rejected.
        assertNull(twin("""{"packages":[{"id":"p","display":{"currency":"UGX","amount":true,"charge_currency":"USD","charge_amount":1}}]}"""))
    }
}
