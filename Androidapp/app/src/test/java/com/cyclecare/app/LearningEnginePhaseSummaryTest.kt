package com.cyclecare.app

import com.cyclecare.app.data.local.CycleDayLog
import com.cyclecare.app.data.local.CycleSettings
import com.cyclecare.app.domain.learning.LearningEngine
import org.junit.Assert.assertTrue
import org.junit.Test

class LearningEnginePhaseSummaryTest {

    private fun settings() = CycleSettings(
        isOnboardingCompleted = true,
        lastPeriodStartDate = "2026-10-01",
        averageCycleLength = 28,
        periodDuration = 5,
        lutealPhaseDays = 14
    )

    @Test
    fun phaseSummaryUsesLoggedDayDenominatorAndEstimatedPhaseLabel() {
        // With these settings: ovulation day 14, fertile 9-15, so Oct 16-19
        // (cycle days 16-19) are all estimated luteal.
        val logs = listOf(
            CycleDayLog(dateString = "2026-10-16", symptomsCsv = "cramps"),
            CycleDayLog(dateString = "2026-10-17", symptomsCsv = "cramps"),
            CycleDayLog(dateString = "2026-10-18", symptomsCsv = "cramps,headache"),
            CycleDayLog(dateString = "2026-10-19", symptomsCsv = "")
        )

        val observations = LearningEngine.compileLearnedObservations(settings(), logs)
        val summary = observations.first { it.id == "phase_symptom_luteal_cramps" }

        assertTrue(summary.description.contains("3 of 4 logged days"))
        assertTrue(summary.description.contains("estimated luteal phase"))
        assertTrue(summary.description.contains("calendar estimate"))
        assertTrue(summary.description.contains("does not establish a cause"))
    }

    @Test
    fun phaseWithTooFewLoggedDaysIsGuarded() {
        // Only 2 estimated-luteal logs: below the 3-logged-day minimum.
        val logs = listOf(
            CycleDayLog(dateString = "2026-10-16", symptomsCsv = "cramps"),
            CycleDayLog(dateString = "2026-10-17", symptomsCsv = "cramps"),
            CycleDayLog(dateString = "2026-10-01", flowLevel = "MODERATE"),
            CycleDayLog(dateString = "2026-10-02", flowLevel = "MODERATE"),
            CycleDayLog(dateString = "2026-10-03", flowLevel = "LIGHT")
        )

        val observations = LearningEngine.compileLearnedObservations(settings(), logs)

        assertTrue(observations.none { it.id.startsWith("phase_symptom_") })
    }

    @Test
    fun blankLastPeriodStartYieldsNoPhaseSummaries() {
        val logs = (16..20).map { day ->
            CycleDayLog(dateString = "2026-10-$day", symptomsCsv = "cramps")
        }
        val blankSettings = settings().copy(lastPeriodStartDate = "")

        val observations = LearningEngine.compileLearnedObservations(blankSettings, logs)

        assertTrue(observations.none { it.id.startsWith("phase_symptom_") })
    }
}
