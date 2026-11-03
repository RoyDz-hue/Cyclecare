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
import com.cyclecare.bridge.acceptFor

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

    private var previousMarch: com.cyclecare.bridge.CycleMonthResult? = null

    /** All checks stay in the app's own R8 graph. Only primitives/String cross
     * instrumentation boundary; no production model or kotlin helper ABI. */
    fun nativeReady(): Boolean = nativeStatus == "native" && acceptedResult?.nativeAnswered == true
    fun monthValue(): Int = acceptedResult?.request?.month?.monthValue ?: -1
    fun epochValue(): Long = acceptedResult?.request?.epoch ?: -1L
    fun selectedMarchFirst(): Boolean = selectedFixtureDate == LocalDate.of(2024,3,1)
    fun checkInitialMonth(): String {
        val result = acceptedResult ?: return "No native calendar result"
        if (!result.nativeAnswered || !result.computedOffMain || result.elapsedNanos <= 0L)
            return "Native answer/thread/timing assertion failed"
        if (result.request.owner != "synthetic-calendar-owner" ||
            result.request.month != java.time.YearMonth.of(2024,2) || result.request.epoch != 1L)
            return "Unexpected initial owner/month/epoch"
        if (result.predictions.size != 29) return "Unexpected native month size"
        val expected = (1..29).associate { day ->
            val date = java.time.LocalDate.of(2024,2,day)
            date.toString() to checkNotNull(CalendarKotlinOracle.calculateDayPrediction(date, fixture().settings))
        }
        if (RustCalendarLabProof.canonicalDays(expected) != RustCalendarLabProof.canonicalDays(result.predictions))
            return "Canonical eight-field initial month differs"
        for ((date, prediction) in result.predictions) {
            if (CalendarKotlinOracle.calculateDayPrediction(LocalDate.parse(date), fixture().settings) != prediction)
                return "Native calendar differs from original Kotlin predictor"
        }
        return ""
    }
    fun captureMarchAndSupersede(): Boolean {
        val result = acceptedResult ?: return false
        if (result.request.owner != "synthetic-calendar-owner" || result.request.epoch != 1L ||
            result.request.month != java.time.YearMonth.of(2024,3) || result.predictions.size != 31 ||
            !result.nativeAnswered || !result.computedOffMain || result.elapsedNanos <= 0L) return false
        previousMarch = result
        supersedeFixture()
        return true
    }
    fun checkEpochOnlyRejection(): String {
        val old = previousMarch ?: return "Missing previous March payload"
        val now = acceptedResult ?: return "Missing current native payload"
        if (old.request.inputs != now.request.inputs || old.request.month != now.request.month ||
            old.request.owner != now.request.owner || old.request.epoch == now.request.epoch)
            return "Epoch-only supersession was confounded"
        if (now.request.epoch != 2L || now.predictions.size != 31 || !now.nativeAnswered ||
            !now.computedOffMain || now.elapsedNanos <= 0L) return "Current accepted result invalid"
        if (old.acceptFor(now.request) != null) return "Stale epoch accepted"
        return ""
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
