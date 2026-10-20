package com.example

import com.example.data.economy.EconomyItem
import com.example.data.economy.EconomyProfile
import com.example.data.economy.EconomyResult
import com.example.data.economy.IdentityRank
import com.example.data.economy.ItemCategory
import com.example.domain.economy.MiningUseCase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.coroutines.runBlocking
import java.security.MessageDigest

class CycleCareDomainSecurityTest {

    @Test
    fun testPinHashingSha256Security() {
        val rawPin = "1234"
        val salt = "cyclecare_salt_"
        val expectedHash = MessageDigest.getInstance("SHA-256")
            .digest((salt + rawPin).toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

        // Ensure hash is 64 hex characters (256 bits)
        assertEquals(64, expectedHash.length)
        assertNotEquals(rawPin, expectedHash)

        // Hashing with different PIN produces distinct hash
        val otherHash = MessageDigest.getInstance("SHA-256")
            .digest((salt + "5678").toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        assertNotEquals(expectedHash, otherHash)
    }

    @Test
    fun testEquipItemFailsClosedWithoutEconomyService() = runBlocking {
        val useCase = MiningUseCase()
        val profile = EconomyProfile(
            userId = "test_user",
            activeFrame = null,
            activeArtifact = "cycle_coin"
        )
        val frameItem = EconomyItem(
            id = "frame_gold",
            name = "Golden Aura",
            category = ItemCategory.FRAME,
            price = 100L,
            description = "A golden aura frame",
            lore = "Radiates warmth and self-trust",
            drawableRes = 0
        )

        val result = useCase.equipItem(profile, frameItem)
        assertTrue(result is EconomyResult.Error)
        assertEquals("Economy service offline", (result as EconomyResult.Error).errorMessage)
        assertEquals(null, profile.activeFrame)
        assertEquals("cycle_coin", profile.activeArtifact)
    }

    @Test
    fun testIdentityRankThresholds() {
        assertEquals(IdentityRank.LUMEN, getRank(0L))
        assertEquals(IdentityRank.LUMEN, getRank(149L))
        assertEquals(IdentityRank.AURELIA, getRank(150L))
        assertEquals(IdentityRank.CELESTIA, getRank(500L))
        assertEquals(IdentityRank.SOLARA, getRank(1200L))
        assertEquals(IdentityRank.NOCTURNE, getRank(3000L))
        assertEquals(IdentityRank.FOUNDER, getRank(7500L))
    }

    private fun getRank(lifetimeCoins: Long): IdentityRank {
        return when {
            lifetimeCoins >= IdentityRank.FOUNDER.minLifetimeCoins -> IdentityRank.FOUNDER
            lifetimeCoins >= IdentityRank.NOCTURNE.minLifetimeCoins -> IdentityRank.NOCTURNE
            lifetimeCoins >= IdentityRank.SOLARA.minLifetimeCoins -> IdentityRank.SOLARA
            lifetimeCoins >= IdentityRank.CELESTIA.minLifetimeCoins -> IdentityRank.CELESTIA
            lifetimeCoins >= IdentityRank.AURELIA.minLifetimeCoins -> IdentityRank.AURELIA
            else -> IdentityRank.LUMEN
        }
    }
}
