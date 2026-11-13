package com.cyclecare.app.domain.economy

import com.cyclecare.app.data.economy.EconomyCatalog
import com.cyclecare.app.data.economy.EconomyProfile
import com.cyclecare.app.data.economy.EconomyResult
import com.cyclecare.app.data.economy.UserEconomyTier
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EarnCoinsUseCaseTest {

    private val defaultProfile = EconomyProfile(
        userId = "test-user-id",
        coinBalance = java.math.BigDecimal("100"),
        lifetimeEarned = java.math.BigDecimal("100"),
        lifetimeSpent = java.math.BigDecimal.ZERO,
        tier = UserEconomyTier.FREE
    )

    @Test
    fun claimDailyAttunementFailsGracefullyWhenServiceUnavailable() = runBlocking {
        val useCase = EarnCoinsUseCase(economyService = null, authPreferences = null)
        val result = useCase.claimDailyAttunement(defaultProfile)

        assertTrue(result is EconomyResult.Error)
        assertEquals("Service unavailable", (result as EconomyResult.Error).errorMessage)
    }

    @Test
    fun reconcilePendingOfflineClaimsReturnsCurrentProfileWhenServiceNull() = runBlocking {
        val useCase = EarnCoinsUseCase(economyService = null, authPreferences = null)
        val result = useCase.reconcilePendingOfflineClaims(defaultProfile)

        assertTrue(result is EconomyResult.Success)
        assertEquals(java.math.BigDecimal("100"), (result as EconomyResult.Success).data.coinBalance)
    }

    @Test
    fun purchaseArtifactFailsGracefullyWhenServiceOffline() = runBlocking {
        val useCase = EarnCoinsUseCase(economyService = null, authPreferences = null)
        val dummyItem = EconomyCatalog.ALL_ITEMS.first()
        val result = useCase.purchaseArtifact(defaultProfile, dummyItem)

        assertTrue(result is EconomyResult.Error)
        assertEquals("Economy service offline", (result as EconomyResult.Error).errorMessage)
    }

    @Test
    fun upgradeTierFailsGracefullyWhenServiceOffline() = runBlocking {
        val useCase = EarnCoinsUseCase(economyService = null, authPreferences = null)
        val result = useCase.upgradeTier(defaultProfile, UserEconomyTier.SUPPORTER)

        assertTrue(result is EconomyResult.Error)
        assertEquals("Economy service offline", (result as EconomyResult.Error).errorMessage)
    }
}
