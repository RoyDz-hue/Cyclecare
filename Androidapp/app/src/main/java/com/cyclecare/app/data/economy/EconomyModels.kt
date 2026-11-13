package com.cyclecare.app.data.economy

import androidx.annotation.DrawableRes
import java.math.BigDecimal
import com.cyclecare.app.R

enum class UserEconomyTier(
    val title: String,
    val multiplier: Float,
    val perkDescription: String
) {
    FREE("Free", 1.0f, "Standard Cycle Mining (1.0×)"),
    SUPPORTER("Supporter", 2.0f, "2× check-in coins • Circle topic previews • Soft themes"),
    PREMIUM("Premium", 3.0f, "3× check-in coins • All Circle topic previews • Priority 3D artifacts")
}

enum class IdentityRank(
    val title: String,
    val minLifetimeCoins: Long,
    val auraColorHex: Long,
    val description: String
) {
    LUMEN("Lumen", 0L, 0xFFE0E0E0, "The nascent spark of conscious cycle attunement"),
    AURELIA("Aurelia", 150L, 0xFFF9A825, "Bathed in golden somatic awareness"),
    CELESTIA("Celestia", 500L, 0xFF8E24AA, "Aligned with the grand lunar and planetary tides"),
    SOLARA("Solara", 1200L, 0xFFFF6F00, "Radiant vitality and self-sovereign mastery"),
    NOCTURNE("Nocturne", 3000L, 0xFF1A237E, "Deep intuitive wisdom of the quiet phases"),
    FOUNDER("Founder", 7500L, 0xFFD4AF37, "Eternal patron of women's autonomous health")
}

data class EconomyProfile(
    val userId: String = "",
    val tier: UserEconomyTier = UserEconomyTier.FREE,
    val rank: IdentityRank = IdentityRank.LUMEN,
    val coinBalance: BigDecimal = BigDecimal.ZERO,
    val lifetimeEarned: BigDecimal = BigDecimal.ZERO,
    val lifetimeSpent: BigDecimal = BigDecimal.ZERO,
    val activeFrame: String? = null,
    val activeArtifact: String? = "cycle_coin",
    val activeBackground: String? = null,
    val activeAvatarArt: String? = null,
    val activeTheme: String = "SOOTHING_ROSE",
    val lastMinedTimestamp: Long = 0L,
    val lastMinedDateString: String? = null,
    val unlockedItemIds: Set<String> = setOf("cycle_coin"),
    val status: String = "ACTIVE", // ACTIVE, SUSPENDED, BANNED
    val suspensionReason: String? = null,
    val suspendedUntil: String? = null,
    val giftedArtifacts: List<String> = emptyList()
) {
    val miningMultiplier: Float get() = tier.multiplier
    val isBanned: Boolean get() = status.equals("BANNED", ignoreCase = true)
    val isSuspended: Boolean get() = status.equals("SUSPENDED", ignoreCase = true)
}

enum class ItemCategory {
    ARTIFACT,
    FRAME,
    BACKGROUND,
    THEME,
    AVATAR_ART,
    BADGE
}

data class EconomyItem(
    val id: String,
    val name: String,
    val category: ItemCategory,
    val price: Long,
    val description: String,
    val lore: String,
    @DrawableRes val drawableRes: Int,
    val auraColorHex: Long = 0xFFF5B82E,
    val isDefaultUnlocked: Boolean = false,
    val harmonicPitchHz: Float = 528f
)

object EconomyCatalog {
    val ALL_ITEMS: List<EconomyItem> = listOf(
        EconomyItem(
            id = "cycle_coin",
            name = "Cycle Emblem",
            category = ItemCategory.ARTIFACT,
            price = 0L,
            description = "The foundational talisman of biological time and balance.",
            lore = "Forged with the sacred crescent, reminding you that energy waxes and wanes with grace.",
            drawableRes = R.drawable.cycle_coin,
            auraColorHex = 0xFFF5B82E,
            isDefaultUnlocked = true,
            harmonicPitchHz = 528f
        ),
        EconomyItem(
            id = "artifact_moon",
            name = "Luminescent Moon",
            category = ItemCategory.ARTIFACT,
            price = 250L,
            description = "Mother-of-pearl crescent bound in hand-polished gold.",
            lore = "Echoes the rhythm of restorative nocturnal tides. Anchors peace during follicular renewal.",
            drawableRes = R.drawable.artifact_moon,
            auraColorHex = 0xFFFFF8E1,
            harmonicPitchHz = 432f
        ),
        EconomyItem(
            id = "artifact_bloom",
            name = "Golden Blossom",
            category = ItemCategory.ARTIFACT,
            price = 450L,
            description = "Velvet satin petals cradling a diamond-gold stamen core.",
            lore = "Unfolds when vitality crests during ovulation. Symbolizes abundant creative power.",
            drawableRes = R.drawable.artifact_bloom,
            auraColorHex = 0xFFF8BBD0,
            harmonicPitchHz = 639f
        ),
        EconomyItem(
            id = "artifact_crystal",
            name = "Rose Obelisk",
            category = ItemCategory.ARTIFACT,
            price = 800L,
            description = "Prismatic rose quartz tower on an imperial gold crown.",
            lore = "Refracts somatic intuition. Clears emotional fog during the luteal descent.",
            drawableRes = R.drawable.artifact_crystal,
            auraColorHex = 0xFFF06292,
            harmonicPitchHz = 741f
        ),
        EconomyItem(
            id = "artifact_orbit",
            name = "Dual Eclipse Rings",
            category = ItemCategory.ARTIFACT,
            price = 1200L,
            description = "Harmonic interlocking tori of brushed gold and rose sheen.",
            lore = "Suspended in perpetual equilibrium. Marks the union of mind, breath, and biology.",
            drawableRes = R.drawable.artifact_orbit,
            auraColorHex = 0xFFFFD54F,
            harmonicPitchHz = 852f
        ),
        EconomyItem(
            id = "frame_elegant",
            name = "Imperial Pearl Halo",
            category = ItemCategory.FRAME,
            price = 350L,
            description = "Beveled gold profile adornment inlaid with natural pearls.",
            lore = "Encircles your presence across all guided spaces with quiet, dignified grace.",
            drawableRes = R.drawable.frame_elegant,
            auraColorHex = 0xFFE0A728,
            harmonicPitchHz = 963f
        ),
        EconomyItem(
            id = "avatar_aurora_3d",
            name = "Aurora Portrait",
            category = ItemCategory.AVATAR_ART,
            price = 600L,
            description = "A warm portrait in a cosy reading nook for your profile and circles.",
            lore = "For the quiet reader who recharges between pages.",
            drawableRes = R.drawable.avatar_aurora_3d,
            auraColorHex = 0xFF2A9D8F
        ),
        EconomyItem(
            id = "avatar_luna_3d",
            name = "Luna Portrait",
            category = ItemCategory.AVATAR_ART,
            price = 600L,
            description = "A soft portrait with long braids and bedroom plants.",
            lore = "For the calm spirit who blooms in her own space.",
            drawableRes = R.drawable.avatar_luna_3d,
            auraColorHex = 0xFF9575CD
        ),
        EconomyItem(
            id = "avatar_zuri_3d",
            name = "Zuri Portrait",
            category = ItemCategory.AVATAR_ART,
            price = 750L,
            description = "A golden kitchen portrait, included with Premium.",
            lore = "For the home chef whose warmth feeds everyone.",
            drawableRes = R.drawable.avatar_zuri_3d,
            auraColorHex = 0xFFD4A017
        ),
        EconomyItem(
            id = "avatar_nala_3d",
            name = "Nala Portrait",
            category = ItemCategory.AVATAR_ART,
            price = 750L,
            description = "A creative art-studio portrait with shoulder-length locs.",
            lore = "For the maker who turns feeling into colour.",
            drawableRes = R.drawable.avatar_nala_3d,
            auraColorHex = 0xFFBF5B34
        ),
        EconomyItem(
            id = "avatar_imani_3d",
            name = "Imani Portrait",
            category = ItemCategory.AVATAR_ART,
            price = 900L,
            description = "A thoughtful home-library portrait with a silk-press bob.",
            lore = "For the reader of rooms and of people.",
            drawableRes = R.drawable.avatar_imani_3d,
            auraColorHex = 0xFF6A3FA0
        ),
        EconomyItem(
            id = "avatar_amara_3d",
            name = "Amara Portrait",
            category = ItemCategory.AVATAR_ART,
            price = 900L,
            description = "A warm cafe portrait with a defined twist-out, included with Premium.",
            lore = "For the friend who makes every table feel like home.",
            drawableRes = R.drawable.avatar_amara_3d,
            auraColorHex = 0xFFC97B8A
        )
    )

    /**
     * NEW IMPLEMENTATION (2026-11-13): native-first via
     * economy_catalog_find_item_v1. Pure lookup: the trimmed query crosses
     * JNI; only the matched item id returns (drawableRes and the rest of
     * EconomyItem never cross - the UI keeps its own mapping). The native
     * side declines non-ASCII queries, because Kotlin equals(ignoreCase)
     * is Unicode case folding while the port is ASCII-exact; a no-match
     * verdict also decodes to a decline. Any null reply runs the verbatim
     * pre-wiring body, kept as the private twin [kotlinFindItem], whose
     * own null is the source's no-match result. A native id that is not
     * in ALL_ITEMS (table drift) also falls through to the twin.
     */
    fun findItem(idOrName: String?): EconomyItem? {
        val clean = idOrName?.trim().orEmpty()
        if (clean.isEmpty()) return null
        com.cyclecare.app.rust.CycleCareCoreBridge.economyCatalogFindItemId(clean)?.let { nativeId ->
            ALL_ITEMS.find { it.id == nativeId }?.let { return it }
        }
        return kotlinFindItem(idOrName)
    }

    /** Verbatim pre-wiring body of [findItem]. */
    private fun kotlinFindItem(idOrName: String?): EconomyItem? {
        val clean = idOrName?.trim().orEmpty()
        if (clean.isEmpty()) return null
        return ALL_ITEMS.find {
            it.id.equals(clean, ignoreCase = true) ||
                it.name.equals(clean, ignoreCase = true) ||
                (it.id == "artifact_bloom" && (clean.equals("Golden Blossom", ignoreCase = true) || clean.equals("Blooming Lotus", ignoreCase = true))) ||
                (it.id == "artifact_crystal" && (clean.equals("Rose Obelisk", ignoreCase = true) || clean.equals("Rose Quartz Crystal", ignoreCase = true))) ||
                (it.id == "artifact_orbit" && (clean.equals("Dual Eclipse", ignoreCase = true) || clean.equals("Dual Eclipse Rings", ignoreCase = true) || clean.equals("Celestial Orbit", ignoreCase = true))) ||
                (it.id == "frame_elegant" && (clean.equals("Celestial Tiara", ignoreCase = true) || clean.equals("Imperial Pearl Halo", ignoreCase = true) || clean.equals("Golden Pearl Frame", ignoreCase = true)))
        }
    }
}

enum class TransactionType {
    DAILY_MINING,
    ACTIVITY_LOGGING,
    P2P_TRANSFER_SENT,
    P2P_TRANSFER_RECEIVED,
    COIN_PURCHASE,
    SINK_UNLOCK
}

data class CoinTransaction(
    val id: String,
    val amount: BigDecimal,
    val type: TransactionType,
    val description: String,
    val timestamp: Long,
    val counterpartEmail: String? = null
)

data class CoinPurchasePackage(
    val id: String,
    val title: String,
    val coins: Long,
    val bonusCoins: Long,
    val priceUsd: String,
    val priceKes: Long = 0L,
    val tag: String? = null,
    val description: String
)

object DefaultCoinPackages {
    val PACKAGES = listOf(
        CoinPurchasePackage(
            id = "pkg_seed",
            title = "Bloom Seed",
            coins = 250L,
            bonusCoins = 0L,
            priceUsd = "$2.99",
            priceKes = 389L,
            description = "Essential stepping stone for profile adornments."
        ),
        CoinPurchasePackage(
            id = "pkg_sanctuary",
            title = "Sanctuary Vault",
            coins = 750L,
            bonusCoins = 100L,
            priceUsd = "$6.99",
            priceKes = 909L,
            tag = "Most Cherished",
            description = "Includes 100 bonus coins + unlocks immediate Lumen status."
        ),
        CoinPurchasePackage(
            id = "pkg_celestial",
            title = "Celestial Treasury",
            coins = 2000L,
            bonusCoins = 400L,
            priceUsd = "$14.99",
            priceKes = 1949L,
            tag = "Grand Value",
            description = "Sovereign reserve. Unlocks prestigious 3D artifacts and custom frames."
        )
    )
}

data class GuidedChatChannel(
    val id: String,
    val title: String,
    val topicGuide: String,
    val minTier: UserEconomyTier,
    /** Set when this circle is backed by a server chat_topics row. */
    val serverTopicId: String? = null
)
