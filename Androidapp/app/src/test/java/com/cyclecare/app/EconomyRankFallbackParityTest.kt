package com.cyclecare.app

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.cyclecare.app.data.economy.IdentityRank
import com.cyclecare.app.data.economy.SupabaseEconomyService
import com.cyclecare.app.security.AuthPreferences
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.math.BigDecimal
import java.util.UUID

/**
 * Fallback-parity pins for the economy_rank_v1 twin (NEW IMPLEMENTATION,
 * 2026-11-13): the twin is the verbatim pre-wiring
 * calculateRankForLifetimeCoins, reached by reflection on a
 * Robolectric-built service. Written, NOT executed in this workspace.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EconomyRankFallbackParityTest {

    private fun service(): SupabaseEconomyService {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = AuthPreferences(
            context,
            context.getSharedPreferences("rank-twin-${UUID.randomUUID()}", Context.MODE_PRIVATE),
            true,
        )
        return SupabaseEconomyService(prefs)
    }

    private fun twin(lifetimeCoins: String): IdentityRank {
        val m = SupabaseEconomyService::class.java.getDeclaredMethod(
            "kotlinRankForLifetimeCoins", BigDecimal::class.java,
        )
        m.isAccessible = true
        return m.invoke(service(), BigDecimal(lifetimeCoins)) as IdentityRank
    }

    @Test
    fun thresholdsAndBoundaries() {
        assertEquals(IdentityRank.LUMEN, twin("0"))
        assertEquals(IdentityRank.LUMEN, twin("149.999"))
        assertEquals(IdentityRank.AURELIA, twin("150"))
        // BigDecimal.compareTo is scale-insensitive.
        assertEquals(IdentityRank.AURELIA, twin("150.00"))
        assertEquals(IdentityRank.AURELIA, twin("499.9"))
        assertEquals(IdentityRank.CELESTIA, twin("500"))
        assertEquals(IdentityRank.CELESTIA, twin("1199.99"))
        assertEquals(IdentityRank.SOLARA, twin("1200"))
        assertEquals(IdentityRank.SOLARA, twin("2999"))
        assertEquals(IdentityRank.NOCTURNE, twin("3000"))
        assertEquals(IdentityRank.NOCTURNE, twin("7499.999"))
        assertEquals(IdentityRank.FOUNDER, twin("7500"))
        assertEquals(IdentityRank.FOUNDER, twin("1000000"))
        assertEquals(IdentityRank.LUMEN, twin("-5"))
    }
}
