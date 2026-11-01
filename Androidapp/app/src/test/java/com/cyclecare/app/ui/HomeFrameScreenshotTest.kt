package com.cyclecare.app.ui

import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
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
 * ViewModel or repository.
 */
class HomeFrameScreenshotTest {
    @get:Rule
    val paparazzi = Paparazzi(deviceConfig = DeviceConfig.PIXEL_6)

    private val today: LocalDate = LocalDate.of(2026, 11, 1)

    private fun baseState(tier: com.cyclecare.app.data.economy.UserEconomyTier) = CycleUiState(
        currentDate = today,
        selectedDate = today,
        hasCycleAnchor = true,
        daysUntilNextPeriod = 6,
        daysUntilOvulation = 1,
        todayPrediction = DayPrediction(
            date = today,
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

    @Test
    fun homeFreeTierWithUrgentBanner() {
        val state = baseState(com.cyclecare.app.data.economy.UserEconomyTier.FREE).copy(
            notifications = listOf(
                UserNotification(
                    id = "n-urgent",
                    title = "Please check in",
                    body = "We noticed a missed day. A quick check-in keeps your estimates accurate.",
                    type = "CARE",
                    priority = "URGENT",
                    isRead = false
                )
            )
        )
        paparazzi.snapshot {
            CycleCareTheme(darkTheme = false, dynamicColor = false) {
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
                    onOpenVault = {},
                    onAskCare = {}
                )
            }
        }
    }

    @Test
    fun homePremiumTierCalm() {
        val state = baseState(com.cyclecare.app.data.economy.UserEconomyTier.PREMIUM)
        paparazzi.snapshot {
            CycleCareTheme(darkTheme = false, dynamicColor = false) {
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
                    onOpenVault = {},
                    onAskCare = {}
                )
            }
        }
    }
}
