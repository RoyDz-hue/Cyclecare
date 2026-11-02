package com.cyclecare.app.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.cyclecare.app.ui.screens.Step2PrivacyFirst
import com.cyclecare.app.ui.screens.Step6LearnAboutMe
import com.cyclecare.app.ui.theme.CycleCareTheme
import org.junit.Rule
import org.junit.Test

/**
 * Dark AND light pixel coverage for the two onboarding steps Roy reported
 * with unreadable (black-on-dark) text: the privacy/backup step and the
 * learn-about-me step. Rendered inside the same Surface wrapper the app
 * shell uses, so LocalContentColor matches production.
 */
class OnboardingContrastScreenshotTest {

    @get:Rule
    val paparazzi = Paparazzi(deviceConfig = DeviceConfig.PIXEL_6)

    @Test
    fun privacyStepDark() {
        paparazzi.snapshot {
            CycleCareTheme(darkTheme = true, dynamicColor = false) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    Step2PrivacyFirst(
                        isAgeVerified = true,
                        onAgeVerifiedChange = {},
                        isTermsAgreed = true,
                        onTermsAgreedChange = {},
                        isCloudSyncAllowed = false,
                        onCloudSyncAllowedChange = {},
                        onOpenTerms = {},
                        onOpenPrivacy = {}
                    )
                }
            }
        }
    }

    @Test
    fun privacyStepLight() {
        paparazzi.snapshot {
            CycleCareTheme(darkTheme = false, dynamicColor = false) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    Step2PrivacyFirst(
                        isAgeVerified = true,
                        onAgeVerifiedChange = {},
                        isTermsAgreed = true,
                        onTermsAgreedChange = {},
                        isCloudSyncAllowed = false,
                        onCloudSyncAllowedChange = {},
                        onOpenTerms = {},
                        onOpenPrivacy = {}
                    )
                }
            }
        }
    }

    @Test
    fun learnAboutMeStepDark() {
        paparazzi.snapshot {
            CycleCareTheme(darkTheme = true, dynamicColor = false) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    Step6LearnAboutMe(
                        selectedSymptoms = setOf("Cramps", "Tiredness"),
                        onToggleSymptom = {},
                        showSymptomSection = true,
                        selectedEnergy = "Steady",
                        onSelectEnergy = {},
                        selectedSleep = "",
                        onSelectSleep = {}
                    )
                }
            }
        }
    }

    @Test
    fun learnAboutMeStepLight() {
        paparazzi.snapshot {
            CycleCareTheme(darkTheme = false, dynamicColor = false) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    Step6LearnAboutMe(
                        selectedSymptoms = setOf("Cramps", "Tiredness"),
                        onToggleSymptom = {},
                        showSymptomSection = true,
                        selectedEnergy = "Steady",
                        onSelectEnergy = {},
                        selectedSleep = "",
                        onSelectSleep = {}
                    )
                }
            }
        }
    }
}
