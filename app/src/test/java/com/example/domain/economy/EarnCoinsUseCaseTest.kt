package com.example.domain.economy

import com.example.data.economy.EconomyCatalog
import com.example.data.economy.EconomyProfile
import com.example.data.economy.EconomyResult
import com.example.data.economy.UserEconomyTier
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EarnCoinsUseCaseTest {

    private val defaultProfile = EconomyProfile(
        userId = "test-user-id",
        coinBalance = 100L,
        lifetimeEarned = 100L,
        lifetimeSpent = 0L,
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
        assertEquals(100L, (result as EconomyResult.Success).data.coinBalance)
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
