package com.example.domain.economy

import com.example.data.economy.CoinPurchasePackage
import com.example.data.economy.EconomyItem
import com.example.data.economy.EconomyProfile
import com.example.data.economy.EconomyResult
import com.example.data.economy.SupabaseEconomyService
import com.example.data.economy.UserEconomyTier
import com.example.security.AuthPreferences

@Deprecated("Use EarnCoinsUseCase instead per spec §41", ReplaceWith("EarnCoinsUseCase"))
class MiningUseCase(
    economyService: SupabaseEconomyService? = null,
    authPreferences: AuthPreferences? = null
) {
    private val delegate = EarnCoinsUseCase(economyService, authPreferences)

    suspend fun mineDailyAttunement(
        currentProfile: EconomyProfile,
        baseReward: Long = 30L
    ): EconomyResult<EconomyProfile> = delegate.claimDailyAttunement(currentProfile, baseReward)

    suspend fun reconcilePendingOfflineClaims(
        currentProfile: EconomyProfile
    ): EconomyResult<EconomyProfile> = delegate.reconcilePendingOfflineClaims(currentProfile)

    suspend fun transferCoins(
        currentProfile: EconomyProfile,
        recipientEmail: String,
        amount: Long
    ): EconomyResult<EconomyProfile> = delegate.transferCoins(currentProfile, recipientEmail, amount)

    suspend fun purchaseArtifact(
        currentProfile: EconomyProfile,
        item: EconomyItem
    ): EconomyResult<EconomyProfile> = delegate.purchaseArtifact(currentProfile, item)

    suspend fun equipItem(
        currentProfile: EconomyProfile,
        item: EconomyItem
    ): EconomyResult<EconomyProfile> = delegate.equipItem(currentProfile, item)

    suspend fun purchasePackage(
        currentProfile: EconomyProfile,
        pkg: CoinPurchasePackage
    ): EconomyResult<EconomyProfile> = delegate.purchasePackage(currentProfile, pkg)

    suspend fun upgradeTier(
        currentProfile: EconomyProfile,
        tier: UserEconomyTier
    ): EconomyResult<EconomyProfile> = delegate.upgradeTier(currentProfile, tier)

    suspend fun reviveStreak(
        currentProfile: EconomyProfile
    ): EconomyResult<EconomyProfile> = EconomyResult.Error("Streak mechanics are deprecated per spec §48. Your daily rewards are always available in the Atelier.")
}
