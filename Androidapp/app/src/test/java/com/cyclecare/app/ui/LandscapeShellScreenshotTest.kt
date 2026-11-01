package com.cyclecare.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.ui.Modifier
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.android.resources.ScreenOrientation
import com.cyclecare.app.AskFloatingBubble
import com.cyclecare.app.CycleCareBottomBar
import com.cyclecare.app.data.economy.UserEconomyTier
import com.cyclecare.app.ui.screens.HomeScreenContent
import com.cyclecare.app.ui.theme.CycleCareTheme
import com.cyclecare.app.ui.viewmodel.AppNavTab
import org.junit.Rule
import org.junit.Test

/** Landscape shell: checks responsive use of the wider viewport. */
class LandscapeShellScreenshotTest {
    @get:Rule
    val paparazzi = Paparazzi(deviceConfig = DeviceConfig.PIXEL_6.copy(orientation = ScreenOrientation.LANDSCAPE))

    @Test
    fun homeInsideShellLandscape() {
        paparazzi.snapshot {
            CycleCareTheme(darkTheme = false, dynamicColor = false) {
                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    bottomBar = { CycleCareBottomBar(currentTab = AppNavTab.TODAY, onSelectTab = {}) }
                ) { innerPadding ->
                    Box(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
                        HomeScreenContent(
                            uiState = homeBaseState(UserEconomyTier.FREE),
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
