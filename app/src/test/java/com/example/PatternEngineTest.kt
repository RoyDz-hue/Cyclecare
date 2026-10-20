package com.example

import com.example.data.local.CycleDayLog
import com.example.data.local.CycleSettings
import com.example.domain.pattern.PatternEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PatternEngineTest {
    @Test
    fun baselineUsesObservedCycleStartsInsteadOfConfiguredEcho() {
        val settings = CycleSettings(
            averageCycleLength = 31,
            lastPeriodStartDate = "2026-02-26"
        )
        val logs = listOf(
            bleed("2026-01-01"),
            bleed("2026-01-02", "HEAVY"),
            bleed("2026-01-29"),
            bleed("2026-02-26")
        )

        val result = PatternEngine.computeBaseline(settings, logs)

        assertEquals(28.0, result.averageCycleLength, 0.001)
        assertEquals(28, result.medianCycleLength)
        assertEquals(28, result.minCycleLength)
        assertEquals(28, result.maxCycleLength)
        assertTrue(result.isRegular)
        assertEquals(logs.size, result.totalLoggedDays)
    }

    @Test
    fun baselineReportsMedianAndVariabilityForIrregularHistory() {
        val starts = listOf("2026-01-01", "2026-01-24", "2026-02-21", "2026-03-25")
        val result = PatternEngine.computeBaseline(CycleSettings(), starts.map(::bleed))

        assertEquals(27.666, result.averageCycleLength, 0.01)
        assertEquals(28, result.medianCycleLength)
        assertEquals(23, result.minCycleLength)
        assertEquals(32, result.maxCycleLength)
        assertEquals(4, result.variabilityDays)
        assertFalse(result.isRegular)
    }

    @Test
    fun baselineIgnoresSpottingAndInvalidDatesAndDoesNotCountFlowDaysAsStarts() {
        val logs = listOf(
            bleed("2026-01-01"),
            bleed("2026-01-05", "MODERATE"),
            bleed("2026-01-20", "SPOTTING"),
            bleed("2026-02-01")
        )
        val result = PatternEngine.computeBaseline(CycleSettings(), logs + bleed("not-a-date"))

        assertEquals(31.0, result.averageCycleLength, 0.001)
        assertEquals(31, result.minCycleLength)
        assertEquals(31, result.maxCycleLength)
    }

    @Test
    fun noObservedIntervalUsesConfiguredLengthAsAnEstimate() {
        val result = PatternEngine.computeBaseline(
            CycleSettings(averageCycleLength = 33),
            listOf(CycleDayLog(dateString = "2026-03-01", flowLevel = "NONE"))
        )

        assertEquals(33.0, result.averageCycleLength, 0.001)
        assertEquals(33, result.medianCycleLength)
        assertEquals(33, result.minCycleLength)
        assertEquals(33, result.maxCycleLength)
        assertEquals(0, result.variabilityDays)
        assertFalse(result.isRegular)
    }

    @Test
    fun calculatePhaseForDateMapsCorrectlyAcrossCycle() {
        val settings = CycleSettings(
            averageCycleLength = 28,
            periodDuration = 5,
            lutealPhaseDays = 14,
            lastPeriodStartDate = "2026-04-01"
        )
        // Day 1 to 5: Menstrual
        assertEquals(com.example.data.model.CyclePhase.MENSTRUAL, PatternEngine.calculatePhaseForDate(java.time.LocalDate.parse("2026-04-01"), settings))
        assertEquals(com.example.data.model.CyclePhase.MENSTRUAL, PatternEngine.calculatePhaseForDate(java.time.LocalDate.parse("2026-04-05"), settings))

        // Day 6 to 8: Follicular
        assertEquals(com.example.data.model.CyclePhase.FOLLICULAR, PatternEngine.calculatePhaseForDate(java.time.LocalDate.parse("2026-04-06"), settings))
        assertEquals(com.example.data.model.CyclePhase.FOLLICULAR, PatternEngine.calculatePhaseForDate(java.time.LocalDate.parse("2026-04-08"), settings))

        // Fertile window: Day 9 to 15 (Ovulation day is 14) -> OVULATION
        assertEquals(com.example.data.model.CyclePhase.OVULATION, PatternEngine.calculatePhaseForDate(java.time.LocalDate.parse("2026-04-14"), settings))

        // Day 16 to 28: Luteal
        assertEquals(com.example.data.model.CyclePhase.LUTEAL, PatternEngine.calculatePhaseForDate(java.time.LocalDate.parse("2026-04-20"), settings))
        assertEquals(com.example.data.model.CyclePhase.LUTEAL, PatternEngine.calculatePhaseForDate(java.time.LocalDate.parse("2026-04-28"), settings))
    }

    @Test
    fun detectPatternsIdentifiesRecurrentSymptomsInPhase() {
        val settings = CycleSettings(
            averageCycleLength = 28,
            periodDuration = 5,
            lastPeriodStartDate = "2026-04-01"
        )
        // Two logs in luteal phase with acne
        val logs = listOf(
            CycleDayLog(dateString = "2026-04-20", symptomsCsv = "acne,bloating"),
            CycleDayLog(dateString = "2026-04-22", symptomsCsv = "acne")
        )

        val patterns = PatternEngine.detectPatterns(settings, logs)
        val acnePattern = patterns.find { it.id.contains("acne") }

        assertTrue("Acne pattern should be recognized with 2 occurrences", acnePattern != null)
        assertEquals(com.example.data.model.CyclePhase.LUTEAL.title, acnePattern!!.cyclePhase)
        assertTrue(acnePattern.supportingObservations.contains("2 times"))
    }

    private fun bleed(date: String, flow: String = "LIGHT") = CycleDayLog(
        dateString = date,
        flowLevel = flow
    )
}
