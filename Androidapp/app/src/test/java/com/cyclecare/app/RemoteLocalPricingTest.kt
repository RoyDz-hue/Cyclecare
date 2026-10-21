package com.cyclecare.app

import com.cyclecare.app.data.economy.RemoteLocalPricing
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RemoteLocalPricingTest {

    private val sample = """
        {"currency":"UGX","packages":[
          {"id":"pkg_starter","title":"Starter Glow","coins":70,
           "display":{"currency":"UGX","amount":3699,"approx":true,"charge_currency":"USD","charge_amount":0.99}},
          {"id":"pkg_pocket","title":"Pocket Reserve","coins":160,
           "display":{"currency":"UGX","amount":7399,"approx":true,"charge_currency":"USD","charge_amount":1.99}}
        ]}
    """.trimIndent()

    @Test
    fun parsesLocalizedPrices() {
        val parsed = RemoteLocalPricing.parse(sample)
        assertEquals(2, parsed!!.size)
        val starter = parsed["pkg_starter"]!!
        assertEquals("UGX", starter.currency)
        assertEquals(3699.0, starter.amount, 0.001)
        assertTrue(starter.approx)
        assertEquals("USD", starter.chargeCurrency)
    }

    @Test
    fun displayTextMarksApproximateConversions() {
        val starter = RemoteLocalPricing.parse(sample)!!["pkg_starter"]!!
        assertEquals("~UGX 3,699", starter.displayText)
        assertEquals("Charged USD 0.99", starter.chargeText)
    }

    @Test
    fun exactKesChargeReadsCleanly() {
        val exact = RemoteLocalPricing.LocalizedPrice("KES", 50.0, false, "KES", 50.0)
        assertEquals("KES 50", exact.displayText)
        assertEquals("Charged KES 50 via M-Pesa", exact.chargeText)
    }

    @Test
    fun rejectsMalformedPayloads() {
        assertNull(RemoteLocalPricing.parse(null))
        assertNull(RemoteLocalPricing.parse(""))
        assertNull(RemoteLocalPricing.parse("{}"))
        assertNull(RemoteLocalPricing.parse("""{"packages":[]}"""))
        assertNull(RemoteLocalPricing.parse("""{"packages":[{"id":"x","display":{"currency":"UGX","amount":-5,"charge_currency":"USD","charge_amount":1}}]}"""))
        assertNull(RemoteLocalPricing.parse("not json"))
    }
}
