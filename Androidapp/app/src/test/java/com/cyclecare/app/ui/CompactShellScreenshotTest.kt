package com.cyclecare.app.ui

import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.cyclecare.app.data.economy.EconomyProfile
import com.cyclecare.app.data.economy.GuidedChatsRepository
import com.cyclecare.app.ui.screens.AtelierScreen
import com.cyclecare.app.ui.screens.GuidedChatsScreen
import org.junit.Rule
import org.junit.Test

/**
 * Short/narrow viewport renders: each page must own its full canvas - no
 * visible parent band, no dead gap - even when vertical space is tight.
 */
class CompactShellScreenshotTest {

    @get:Rule
    val paparazzi = Paparazzi(
        deviceConfig = DeviceConfig.PIXEL_6.copy(screenWidth = 900, screenHeight = 1600)
    )

    @Test
    fun hubCompactViewport() {
        paparazzi.snapshot {
            AtelierScreen(
                profile = EconomyProfile(),
                onClaimCoins = {},
                onTransferCoins = { _, _ -> },
                onUnlockItem = {},
                onEquipItem = {},
                onPurchasePackage = {},
                onUpgradeTier = {},
                guidedChatsRepository = GuidedChatsRepository(),
                onNavigateBack = {},
                isAdmin = false,
                communityGuidesService = null,
                embedded = true
            )
        }
    }

    @Test
    fun spaceCompactViewport() {
        paparazzi.snapshot {
            GuidedChatsScreen(
                profile = EconomyProfile(),
                repository = GuidedChatsRepository(),
                onNavigateBack = {},
                onUpgradeTierClicked = {},
                isAdmin = false,
                communityGuidesService = null,
                embedded = true
            )
        }
    }
}
