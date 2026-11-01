package com.cyclecare.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.cyclecare.app.AskFloatingBubble
import com.cyclecare.app.CycleCareBottomBar
import com.cyclecare.app.data.economy.EconomyProfile
import com.cyclecare.app.data.economy.GuidedChatsRepository
import com.cyclecare.app.ui.screens.AtelierScreen
import com.cyclecare.app.ui.screens.GuidedChatsScreen
import com.cyclecare.app.ui.theme.CycleCareTheme
import com.cyclecare.app.ui.viewmodel.AppNavTab
import org.junit.Rule
import org.junit.Test

/**
 * Short/narrow viewport renders inside the REAL app shell (Scaffold + bottom
 * navigation + floating Ask bubble): every page must own its full canvas - no
 * visible parent band, no dead gap - even when vertical space is tight.
 */
class CompactShellScreenshotTest {

    @get:Rule
    val paparazzi = Paparazzi(
        deviceConfig = DeviceConfig.PIXEL_6.copy(screenWidth = 900, screenHeight = 1600)
    )

    private fun shell(tab: AppNavTab, content: @Composable () -> Unit) {
        paparazzi.snapshot {
            CycleCareTheme(darkTheme = false, dynamicColor = false) {
                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    bottomBar = { CycleCareBottomBar(currentTab = tab, onSelectTab = {}) }
                ) { innerPadding ->
                    Box(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
                        content()
                        AskFloatingBubble(onClick = {}, persistPosition = false)
                    }
                }
            }
        }
    }

    @Test
    fun hubCompactViewport() = shell(AppNavTab.ATELIER) {
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

    @Test
    fun spaceCompactViewport() = shell(AppNavTab.SPACE) {
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
