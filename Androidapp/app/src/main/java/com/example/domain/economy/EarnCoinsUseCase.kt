package com.example.domain.economy

import com.example.data.economy.CoinPurchasePackage
import com.example.data.economy.EconomyItem
import com.example.data.economy.EconomyProfile
import com.example.data.economy.EconomyResult
import com.example.data.economy.ItemCategory
import com.example.data.economy.SupabaseEconomyService
import com.example.data.economy.UserEconomyTier
import com.example.security.AuthPreferences

class EarnCoinsUseCase(
    private val economyService: SupabaseEconomyService? = null,
    private val authPreferences: AuthPreferences? = null
) {
    suspend fun claimDailyAttunement(
        currentProfile: EconomyProfile,
        baseReward: Long = 30L
    ): EconomyResult<EconomyProfile> {
        val service = economyService ?: return EconomyResult.Error("Service unavailable")
        val userId = authPreferences?.effectiveUserId ?: "local_user"
        return service.verifyAndMineCoins(
            userId = userId,
            currentProfile = currentProfile,
            baseReward = baseReward
        )
    }

    suspend fun reconcilePendingOfflineClaims(
        currentProfile: EconomyProfile
    ): EconomyResult<EconomyProfile> {
        val service = economyService ?: return EconomyResult.Success(currentProfile)
        val userId = authPreferences?.effectiveUserId ?: "local_user"
        return service.reconcilePendingOfflineClaims(userId, currentProfile)
    }

    suspend fun transferCoins(
        currentProfile: EconomyProfile,
        recipientEmail: String,
        amount: Long
    ): EconomyResult<EconomyProfile> {
        return economyService?.transferCoins(currentProfile, recipientEmail, amount)
            ?: EconomyResult.Error("Economy service offline")
    }

    suspend fun purchaseArtifact(
        currentProfile: EconomyProfile,
        item: EconomyItem
    ): EconomyResult<EconomyProfile> {
        return economyService?.purchaseArtifact(currentProfile, item)
            ?: EconomyResult.Error("Economy service offline")
    }

    suspend fun equipItem(
        currentProfile: EconomyProfile,
        item: EconomyItem
    ): EconomyResult<EconomyProfile> = economyService?.equipItem(currentProfile, item)
        ?: EconomyResult.Error("Economy service offline")

    suspend fun purchasePackage(
        currentProfile: EconomyProfile,
        pkg: CoinPurchasePackage
    ): EconomyResult<EconomyProfile> {
        return economyService?.purchaseCoinPackage(currentProfile, pkg)
            ?: EconomyResult.Error("Economy service offline")
    }

    suspend fun upgradeTier(
        currentProfile: EconomyProfile,
        tier: UserEconomyTier
    ): EconomyResult<EconomyProfile> {
        return economyService?.upgradeTier(currentProfile, tier)
            ?: EconomyResult.Error("Economy service offline")
    }

    // Spec §48 strictly forbids artificial streak mechanics and streak revival.
    // Daily rewards remain available organically in the Atelier without streaks.
}
