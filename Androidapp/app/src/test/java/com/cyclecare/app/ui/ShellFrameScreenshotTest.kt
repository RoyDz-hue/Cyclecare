package com.cyclecare.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.ui.Modifier
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.cyclecare.app.AskFloatingBubble
import com.cyclecare.app.CycleCareBottomBar
import com.cyclecare.app.ui.screens.HomeScreenContent
import com.cyclecare.app.ui.theme.CycleCareTheme
import com.cyclecare.app.ui.viewmodel.AppNavTab
import org.junit.Rule
import org.junit.Test

/**
 * Actual-shell render: the same Scaffold + bottom navigation + floating Ask
 * bubble composition MainActivity uses, with the real Home frame inside.
 * Proves bottom-bar clearance and bubble placement instead of inferring them.
 */
class ShellFrameScreenshotTest {
    @get:Rule
    val paparazzi = Paparazzi(deviceConfig = DeviceConfig.PIXEL_6)

    @Test
    fun homeInsideRealShell() {
        paparazzi.snapshot {
            CycleCareTheme(darkTheme = false, dynamicColor = false) {
                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    bottomBar = {
                        CycleCareBottomBar(
                            currentTab = AppNavTab.TODAY,
                            onSelectTab = {}
                        )
                    }
                ) { innerPadding ->
                    Box(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
                        HomeScreenContent(
                            uiState = homeBaseState(com.cyclecare.app.data.economy.UserEconomyTier.FREE),
                            todayLog = null,
                            onOpenSettings = {},
                            onOpenNotificationCenter = {},
                            onDismissFloatingBanner = {},
                            onShowAuthSheet = {},
                            onOpenTrackForDate = {},
                            onSelectTab = {},
                            onQuickCheckIn = { _, _ -> },
                            onClearEconomyToast = {},
                            onOpenSomethingDifferent = {},
                            onOpenAsk = {},
                            onOpenVault = {}
                        )
                        AskFloatingBubble(onClick = {}, persistPosition = false)
                    }
                }
            }
        }
    }
}
