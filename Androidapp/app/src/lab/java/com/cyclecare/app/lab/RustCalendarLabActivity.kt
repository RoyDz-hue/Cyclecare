package com.cyclecare.app.lab

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import com.cyclecare.app.data.local.CycleSettings
import com.cyclecare.app.ui.theme.CycleCareTheme
import com.cyclecare.app.ui.viewmodel.CycleUiState
import com.cyclecare.bridge.CycleScreenRustContent
import java.time.LocalDate

/** Nonexported, nonlauncher, lab-only host. All state is synthetic and memory
 * only. Never constructs MainActivity, ViewModel, repository, auth, prefs,
 * Room, SQLCipher, Keystore, transport or a real-data file path.
 * Recreate/process restart intentionally resets to the same synthetic fixture.
 */
class RustCalendarLabActivity : ComponentActivity() {
    @Volatile var nativeStatus: String = "loading"
        private set
    @Volatile var selectedFixtureDate: LocalDate = LocalDate.of(2024, 2, 14)
        private set
    @Volatile internal var acceptedResult: com.cyclecare.bridge.CycleMonthResult? = null
        private set
    private var fixtureState = mutableStateOf(fixture())
    private var fixtureEpoch = mutableStateOf(1L)

    /** Test-only, memory-only owner/settings supersession. No external input. */
    fun supersedeFixture() {
        fixtureEpoch.value += 1L
        // Only epoch changes: owner/settings/month stay identical.

    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            var snapshot by fixtureState
            var status by remember { mutableStateOf("loading") }
            val producer = remember { Any() }
            CycleCareTheme(darkTheme = false) {
                Column {
                    Text("Synthetic calendar lab - estimates, not medical advice")
                    Text("Calculation: $status")
                    CycleScreenRustContent(
                        uiState = snapshot, logsMap = emptyMap(), producer = producer,
                        epoch = fixtureEpoch.value, requireNative = true,
                        fallbackMonth = { _, _ -> error("Lab must never use Kotlin fallback") },
                        fallbackDate = { _, _ -> error("Lab must never use Kotlin fallback") },
                        onSelectDate = { snapshot = snapshot.copy(selectedDate = it); selectedFixtureDate = it },
                        onOpenTrackForDate = {}, // read-only: no daily-log editor
                        onNativeState = { status = it; nativeStatus = it },
                        onAcceptedResult = { acceptedResult = it }
                    )
                }
            }
        }
    }

    companion object {
        internal fun fixture(): CycleUiState = CycleUiState(
            currentDate = LocalDate.of(2024, 2, 14), selectedDate = LocalDate.of(2024, 2, 14),
            settings = CycleSettings(lastPeriodStartDate = "2024-02-01", averageCycleLength = 28,
                periodDuration = 5, lutealPhaseDays = 14),
            onboardingProfile = com.cyclecare.app.data.local.OnboardingProfile(
                approximatePeriodChoice = "EXACT", cycleLengthOption = "28 days", completedAtTimestamp = 0L),
            hasCycleAnchor = true, localDataOwnerKey = "synthetic-calendar-owner", showOnboarding = false
        )
    }
}
