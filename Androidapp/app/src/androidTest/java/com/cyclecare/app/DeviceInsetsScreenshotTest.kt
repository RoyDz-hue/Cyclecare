package com.cyclecare.app

import android.graphics.Bitmap
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.platform.app.InstrumentationRegistry
import com.cyclecare.app.data.economy.EconomyProfile
import com.cyclecare.app.data.economy.GuidedChatsRepository
import com.cyclecare.app.ui.screens.CareScreenContent
import com.cyclecare.app.ui.screens.GuidedChatsScreen
import com.cyclecare.app.ui.theme.CycleCareTheme
import com.cyclecare.app.ui.viewmodel.CycleUiState
import org.junit.Rule
import org.junit.Test
import java.io.File

/**
 * Device-grade inset verification. Renders the PRODUCTION screen composables
 * in a real edge-to-edge activity on an emulator, so the status bar, nav bar
 * and IME deliver real nonzero WindowInsets (Paparazzi and Robolectric both
 * report zero). Captures full-display screenshots (system bars included) for
 * pixel review. Dark theme matches the device state Roy reported from.
 */
class DeviceInsetsScreenshotTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private fun matchProductionWindow() {
        compose.activityRule.scenario.onActivity { activity ->
            activity.enableEdgeToEdge()
            activity.window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }
    }

    private fun save(name: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val shot = instrumentation.uiAutomation.takeScreenshot() ?: error("uiAutomation screenshot unavailable")
        val dir = instrumentation.targetContext.getExternalFilesDir(null)!!
        File(dir, "$name.png").outputStream().use { shot.compress(Bitmap.CompressFormat.PNG, 100, it) }
        shot.recycle()
    }

    private fun settle(ms: Long = 1200) {
        compose.waitForIdle()
        Thread.sleep(ms)
        compose.waitForIdle()
    }

    @Test
    fun guidedHubAndRoomDarkModeInsets() {
        matchProductionWindow()
        compose.setContent {
            CycleCareTheme(darkTheme = true, dynamicColor = false) {
                GuidedChatsScreen(
                    profile = EconomyProfile(),
                    repository = GuidedChatsRepository(),
                    onNavigateBack = {},
                    onUpgradeTierClicked = {}
                )
            }
        }
        settle()
        save("01-hub-dark-closed")

        compose.onNodeWithTag("guided_channel_circle_luteal_rest").performClick()
        settle()
        save("02-room-dark-closed")

        compose.onNodeWithText("Message the circle", substring = true).performClick()
        compose.onNodeWithText("Message the circle", substring = true).performTextInput("Drafting a thought")
        settle(1600)
        save("03-room-dark-keyboard-open")
    }

    @Test
    fun askOverlayDarkModeInsets() {
        matchProductionWindow()
        compose.setContent {
            CycleCareTheme(darkTheme = true, dynamicColor = false) {
                CareScreenContent(
                    uiState = CycleUiState(),
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
        }
        settle()
        save("04-ask-dark-closed")

        compose.onNodeWithTag("care_query_input").performClick()
        compose.onNodeWithTag("care_query_input").performTextInput("How can I ease period cramps?")
        settle(1600)
        save("05-ask-dark-keyboard-open")
    }
}
