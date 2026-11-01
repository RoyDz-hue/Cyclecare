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
 * Narrow and narrow+short viewports inside the REAL app shell (Scaffold +
 * bottom navigation + floating Ask bubble). Configs are explicit pixels at
 * PIXEL_6 density (420dpi, so dp = px * 160/420):
 *  - NARROW_SHORT: 714x1250 px = 272x476 dp (narrower than Roy's phone, short)
 *  - ROY_PHONE:    714x1599 px = 272x610 dp (matches Roy's screenshot aspect)
 * Every page must own its full canvas - no visible parent band, no dead gap.
 */
class CompactShellScreenshotTest {

    companion object {
        private val NARROW_SHORT = DeviceConfig.PIXEL_6.copy(screenWidth = 714, screenHeight = 1250)
        private val ROY_PHONE = DeviceConfig.PIXEL_6.copy(screenWidth = 714, screenHeight = 1599)
    }

    @get:Rule
    val paparazzi = Paparazzi()

    private fun shell(device: DeviceConfig, name: String, tab: AppNavTab, content: @Composable () -> Unit) {
        paparazzi.unsafeUpdateConfig(deviceConfig = device)
        paparazzi.snapshot(name = name) {
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

    @Composable
    private fun hub() = AtelierScreen(
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

    @Composable
    private fun space() = GuidedChatsScreen(
        profile = EconomyProfile(),
        repository = GuidedChatsRepository(),
        onNavigateBack = {},
        onUpgradeTierClicked = {},
        isAdmin = false,
        communityGuidesService = null,
        embedded = true
    )

    @Test
    fun hubNarrowShortShell() = shell(NARROW_SHORT, "hubNarrowShortShell", AppNavTab.ATELIER) { hub() }

    @Test
    fun spaceNarrowShortShell() = shell(NARROW_SHORT, "spaceNarrowShortShell", AppNavTab.SPACE) { space() }

    @Test
    fun hubRoyPhoneShell() = shell(ROY_PHONE, "hubRoyPhoneShell", AppNavTab.ATELIER) { hub() }

    @Test
    fun spaceRoyPhoneShell() = shell(ROY_PHONE, "spaceRoyPhoneShell", AppNavTab.SPACE) { space() }
}
