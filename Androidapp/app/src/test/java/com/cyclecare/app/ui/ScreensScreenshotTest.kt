package com.cyclecare.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.cyclecare.app.AskFloatingBubble
import com.cyclecare.app.CycleCareBottomBar
import com.cyclecare.app.data.economy.EconomyProfile
import com.cyclecare.app.data.economy.GuidedChatsRepository
import com.cyclecare.app.data.economy.UserEconomyTier
import com.cyclecare.app.ui.screens.AtelierScreen
import com.cyclecare.app.ui.screens.CareScreenContent
import com.cyclecare.app.ui.screens.CycleScreenContent
import com.cyclecare.app.ui.screens.GuidedChatsScreen
import com.cyclecare.app.ui.screens.HomeScreenContent
import com.cyclecare.app.ui.screens.TrackScreenContent
import com.cyclecare.app.ui.theme.CycleCareTheme
import com.cyclecare.app.ui.viewmodel.AppNavTab
import org.junit.Rule
import org.junit.Test

/** Renders of the non-Home main screens and additional shell states. */
class ScreensScreenshotTest {

    @get:Rule
    val paparazzi = Paparazzi(deviceConfig = DeviceConfig.PIXEL_6)

    private fun frame(pap: Paparazzi, dark: Boolean = false, content: @Composable () -> Unit) {
        pap.snapshot {
            CycleCareTheme(darkTheme = dark, dynamicColor = false) {
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                    content()
                }
            }
        }
    }

    private fun shell(pap: Paparazzi, tab: AppNavTab, content: @Composable () -> Unit) {
        pap.snapshot {
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

    private val freeState get() = homeBaseState(UserEconomyTier.FREE)

    @Test
    fun cycleCalendar() = frame(paparazzi) {
        CycleScreenContent(
            uiState = freeState,
            logsMap = emptyMap(),
            predictionForDate = { null },
            onSelectDate = {},
            onOpenTrackForDate = {}
        )
    }

    @Test
    fun trackDailyLog() = frame(paparazzi) {
        TrackScreenContent(
            uiState = freeState,
            currentLog = null,
            onSelectDate = {},
            onSaveDayLog = {}
        )
    }

    @Test
    fun careEntryState() = frame(paparazzi) {
        CareScreenContent(
            uiState = freeState,
            onConsultCare = {},
            onEditCareLog = {},
            onEnableAiProcessing = {},
            onRefreshAiManifest = {},
            onGenerateCareImage = { _, _ -> },
            onOpenVault = {},
            onClearChatHistory = {},
            onCancelCareLog = {},
            onConfirmCareLog = {}
        )
    }

    @Test
    fun hubAtelierInsideShell() = shell(paparazzi, AppNavTab.ATELIER) {
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
    fun spaceCirclesInsideShell() = shell(paparazzi, AppNavTab.SPACE) {
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


    @Test
    fun homeInsideShellScrolled() = shell(paparazzi, AppNavTab.TODAY) {
        HomeScreenContent(
            uiState = freeState,
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
            onOpenVault = {},
            listState = androidx.compose.foundation.lazy.LazyListState(firstVisibleItemIndex = 3)
        )
    }
}
