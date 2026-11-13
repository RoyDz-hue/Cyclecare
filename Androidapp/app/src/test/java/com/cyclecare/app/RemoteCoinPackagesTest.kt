package com.cyclecare.app

import com.cyclecare.app.data.economy.RemoteCoinPackages
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RemoteCoinPackagesTest {

    private val liveShape = """
        [
          {"id":"pkg_starter","tag":"Hero Value","coins":70,"title":"Starter Glow","isHero":true,"priceKes":50,"priceUsd":0.99,"bonusCoins":10},
          {"id":"pkg_pocket","tag":"Most Popular","coins":160,"title":"Pocket Reserve","priceKes":100,"priceUsd":1.99,"bonusCoins":30}
        ]
    """.trimIndent()

    @Test
    fun parsesTheLiveAppConfigShape() {
        val parsed = RemoteCoinPackages.parse(liveShape)
        assertEquals(2, parsed!!.size)
        assertEquals("pkg_starter", parsed[0].id)
        assertEquals(70L, parsed[0].coins)
        assertEquals(50L, parsed[0].priceKes)
        assertEquals("$0.99", parsed[0].priceUsd)
        assertEquals("Hero Value", parsed[0].tag)
    }

    @Test
    fun rejectsMalformedPayloadsSoTheStoreFallsBack() {
        assertNull(RemoteCoinPackages.parse(null))
        assertNull(RemoteCoinPackages.parse(""))
        assertNull(RemoteCoinPackages.parse("not json"))
        assertNull(RemoteCoinPackages.parse("[]"))
        // negative price
        assertNull(RemoteCoinPackages.parse("""[{"id":"pkg_bad","title":"Bad","coins":10,"bonusCoins":0,"priceKes":-5,"priceUsd":1}]"""))
        // free package
        assertNull(RemoteCoinPackages.parse("""[{"id":"pkg_free","title":"Free","coins":10,"bonusCoins":0,"priceKes":0,"priceUsd":0}]"""))
        // duplicate ids
        assertNull(RemoteCoinPackages.parse("""[{"id":"pkg_a","title":"A","coins":10,"bonusCoins":0,"priceKes":1,"priceUsd":1},{"id":"pkg_a","title":"B","coins":10,"bonusCoins":0,"priceKes":1,"priceUsd":1}]"""))
        // bad id shape
        assertNull(RemoteCoinPackages.parse("""[{"id":"Pkg Bad","title":"A","coins":10,"bonusCoins":0,"priceKes":1,"priceUsd":1}]"""))
    }

    @Test
    fun usdFormattingKeepsTwoDecimalsForWholeAmounts() {
        val parsed = RemoteCoinPackages.parse("""[{"id":"pkg_bloom","title":"Bloom","coins":260,"bonusCoins":50,"priceKes":150,"priceUsd":3}]""")
        assertEquals("$3.00", parsed!![0].priceUsd)
    }
}
