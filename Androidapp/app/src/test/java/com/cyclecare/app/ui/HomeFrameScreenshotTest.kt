package com.cyclecare.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.android.resources.ScreenOrientation
import com.cyclecare.app.data.model.CyclePhase
import com.cyclecare.app.data.model.DayPrediction
import com.cyclecare.app.data.model.TodayAttentionItem
import com.cyclecare.app.data.notification.UserNotification
import com.cyclecare.app.ui.screens.HomeScreenContent
import com.cyclecare.app.ui.theme.CycleCareTheme
import com.cyclecare.app.ui.viewmodel.CycleUiState
import org.junit.Rule
import org.junit.Test
import java.time.LocalDate

/**
 * Screen-level render proof for the consolidated Home frame. Uses the pure
 * state-and-events [HomeScreenContent] so the real hierarchy (urgent banner,
 * top bar, cycle snapshot, check-in, rewards entry) renders without a
 * ViewModel or repository, on the same scaffold background the app shell uses.
 */
private val testToday: LocalDate = LocalDate.of(2026, 11, 1)

private fun homeBaseState(tier: com.cyclecare.app.data.economy.UserEconomyTier) = CycleUiState(
    currentDate = testToday,
    selectedDate = testToday,
    hasCycleAnchor = true,
    daysUntilNextPeriod = 6,
    daysUntilOvulation = 1,
    todayPrediction = DayPrediction(
        date = testToday,
        dayOfCycle = 22,
        phase = CyclePhase.LUTEAL,
        isPeriod = false,
        isFertile = false,
        isOvulation = false,
        fertilityConfidence = "Calendar Estimate",
        pregnancyChance = "Low"
    ),
    todayAttentionItem = TodayAttentionItem(
        id = "a1",
        category = "Cycle Status",
        headline = "Your period is likely about 6 days away",
        message = "This is a calendar estimate from your logged cycle.",
        whyThisAppeared = "Based on your last period start date and average cycle length.",
        careSuggestion = "Log any new symptoms so the estimate stays accurate.",
        isUrgent = false
    ),
    economyProfile = com.cyclecare.app.data.economy.EconomyProfile(tier = tier),
    notifications = listOf(
        UserNotification(
            id = "n1",
            title = "CycleCare update",
            body = "A small improvement to your daily insights is live.",
            type = "SYSTEM",
            priority = "NORMAL",
            isRead = true
        )
    )
)

private val urgentSecurityState = homeBaseState(com.cyclecare.app.data.economy.UserEconomyTier.FREE).copy(
    notifications = listOf(
        UserNotification(
            id = "n-urgent",
            title = "New sign-in on your account",
            body = "If this wasn't you, open your inbox to secure your account.",
            type = "SECURITY",
            priority = "URGENT",
            isRead = false
        )
    )
)

private val premiumCalmState = homeBaseState(com.cyclecare.app.data.economy.UserEconomyTier.PREMIUM)

private fun Paparazzi.renderHome(state: CycleUiState, dark: Boolean) {
    snapshot {
        CycleCareTheme(darkTheme = dark, dynamicColor = false) {
            Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                HomeScreenContent(
                    uiState = state,
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
            }
        }
    }
}

class HomeFrameScreenshotTest {
    @get:Rule
    val paparazzi = Paparazzi(deviceConfig = DeviceConfig.PIXEL_6)

    @Test
    fun homeFreeTierWithUrgentBanner() = paparazzi.renderHome(urgentSecurityState, dark = false)

    @Test
    fun homePremiumTierCalm() = paparazzi.renderHome(premiumCalmState, dark = false)

    @Test
    fun homeDarkTheme() = paparazzi.renderHome(premiumCalmState, dark = true)
}

/** Same frame at 1.3x font scale: catches text overflow and clipped chips. */
class HomeFrameLargeFontScreenshotTest {
    @get:Rule
    val paparazzi = Paparazzi(deviceConfig = DeviceConfig.PIXEL_6.copy(fontScale = 1.3f))

    @Test
    fun homeLargeFont() = paparazzi.renderHome(urgentSecurityState, dark = false)
}

/** Same frame in landscape: catches fixed-width assumptions and wasted height. */
class HomeFrameLandscapeScreenshotTest {
    @get:Rule
    val paparazzi = Paparazzi(deviceConfig = DeviceConfig.PIXEL_6.copy(orientation = ScreenOrientation.LANDSCAPE))

    @Test
    fun homeLandscape() = paparazzi.renderHome(premiumCalmState, dark = false)
}
