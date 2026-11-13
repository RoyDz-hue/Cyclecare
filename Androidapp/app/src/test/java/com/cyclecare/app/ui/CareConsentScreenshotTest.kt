package com.cyclecare.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.cyclecare.app.data.economy.UserEconomyTier
import com.cyclecare.app.domain.ai.AiProcessor
import com.cyclecare.app.domain.ai.AiProcessorManifest
import com.cyclecare.app.domain.ai.AiSubProcessor
import com.cyclecare.app.ui.screens.CareScreenContent
import com.cyclecare.app.ui.theme.CycleCareTheme
import com.cyclecare.app.ui.viewmodel.CycleUiState
import org.junit.Rule
import org.junit.Test

/** Golden renders of the Ask-tab expert-answers consent card states. */
class CareConsentScreenshotTest {

    @get:Rule
    val paparazzi = Paparazzi(deviceConfig = DeviceConfig.PIXEL_6)

    private fun render(state: CycleUiState) {
        paparazzi.snapshot {
            CycleCareTheme(darkTheme = false, dynamicColor = false) {
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                    CareScreenContent(
                        uiState = state,
                        onConsultCare = {},
                        onEditCareLog = {},
                        onEnableAiProcessing = { _ -> },
                        onRefreshAiManifest = {},
                        onGenerateCareImage = { _, _ -> },
                        onOpenVault = {},
                        onClearChatHistory = {},
                        onCancelCareLog = {},
                        onConfirmCareLog = {}
                    )
                }
            }
        }
    }

    private val testManifest = AiProcessorManifest(
        version = 3,
        accountSnapshot = "test-account",
        manifestRevision = "rev-test",
        providers = listOf(
            AiProcessor(
                id = "GEMINI",
                displayName = "Google Gemini",
                model = "gemini-2.5-flash",
                policyRevision = "p1",
                manifestFingerprint = "a".repeat(64),
                processors = listOf(
                    AiSubProcessor("google", "Google Cloud", "https://policies.google.com/privacy")
                ),
                eligible = true,
                unavailableReason = "",
                consentEnabled = true,
                consentRevision = 1
            ),
            AiProcessor(
                id = "OPENROUTER",
                displayName = "OpenRouter",
                model = "auto",
                policyRevision = "p1",
                manifestFingerprint = "b".repeat(64),
                processors = emptyList(),
                eligible = false,
                unavailableReason = "Not configured for this account",
                consentEnabled = true,
                consentRevision = 1
            )
        ),
        hybridReleased = false,
        hybridMaxAttempts = 0
    )

    @Test
    fun consentCardWithRecipientsAndDetailsToggle() =
        render(homeBaseState(UserEconomyTier.FREE).copy(aiProcessorManifest = testManifest))

    @Test
    fun consentCardLoadingManifest() =
        render(homeBaseState(UserEconomyTier.FREE).copy(isLoadingAiProcessorManifest = true))

    @Test
    fun consentCardUnavailable() =
        render(homeBaseState(UserEconomyTier.FREE))
}
