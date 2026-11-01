package com.cyclecare.app.ui

import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import com.cyclecare.app.data.economy.UserEconomyTier
import com.cyclecare.app.domain.ai.AiProcessor
import com.cyclecare.app.domain.ai.AiProcessorManifest
import com.cyclecare.app.domain.ai.AiSubProcessor
import com.cyclecare.app.ui.screens.CareScreenContent
import com.cyclecare.app.ui.theme.CycleCareTheme
import java.io.File
import java.io.FileOutputStream
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode

/**
 * Opens the consent card's "Details & privacy policies" toggle and captures the
 * expanded render (recipient policy links). Interactive complement to the
 * Paparazzi goldens, which cannot flip internal toggle state.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h800dp")
@LooperMode(LooperMode.Mode.INSTRUMENTATION_DETACHED)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CareConsentExpandedCaptureTest {

    @get:Rule
    val compose = createComposeRule()

    private val manifest = AiProcessorManifest(
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
            )
        ),
        hybridReleased = false,
        hybridMaxAttempts = 0
    )

    @Test
    fun expandedDetailsAreReachableAndCaptured() {
        org.robolectric.util.ReflectionHelpers.callStaticMethod<Void>(
            org.robolectric.shadows.ShadowTrace::class.java,
            "setAppTracingAllowed",
            org.robolectric.util.ReflectionHelpers.ClassParameter.from(Boolean::class.javaPrimitiveType!!, false)
        )
        compose.setContent {
            CycleCareTheme(darkTheme = false, dynamicColor = false) {
                CareScreenContent(
                    uiState = homeBaseState(UserEconomyTier.FREE).copy(aiProcessorManifest = manifest),
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
        compose.onNodeWithTag("care_ai_consent_details").assertIsDisplayed().performClick()
        compose.onNodeWithTag("care_ai_policy_GEMINI_google").assertIsDisplayed()
        val out = File("build/outputs/captures/care-consent-expanded.png")
        out.parentFile?.mkdirs()
        compose.waitForIdle()
        FileOutputStream(out).use { stream ->
            compose.onRoot().captureToImage().asAndroidBitmap()
                .compress(Bitmap.CompressFormat.PNG, 100, stream)
        }
        check(out.length() > 0) { "expanded capture is empty" }
    }
}
