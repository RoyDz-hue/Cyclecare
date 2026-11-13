package com.cyclecare.app.domain.pattern

import com.cyclecare.app.data.local.CycleDayLog
import com.cyclecare.app.data.local.CycleSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class SomethingChangedEngineTest {

    private val engine = SomethingChangedEngine()
    private val settings = CycleSettings(
        averageCycleLength = 28,
        periodDuration = 5,
        lastPeriodStartDate = "2026-01-01"
    )

    @Test
    fun returnsEmptyWhenInsufficientData() {
        // Less than 14 logs should yield no deviations
        val logs = (1..10).map { i ->
            CycleDayLog(dateString = "2026-01-%02d".format(i))
        }
        val deviations = engine.detectDeviations(settings, logs)
        assertTrue(deviations.isEmpty())
    }

    @Test
    fun detectsPainIntensityDeviationWhenRecentExceedsBaseline() {
        val today = LocalDate.now()
        val logs = mutableListOf<CycleDayLog>()

        // Historical logs (>90 days ago) with mild pain (1-2)
        for (i in 100..120) {
            val date = today.minusDays(i.toLong()).toString()
            logs.add(
                CycleDayLog(
                    dateString = date,
                    painIntensity = 1
                )
            )
        }

        // Recent logs (<=90 days ago) with high pain (7-8)
        for (i in 1..20) {
            val date = today.minusDays(i.toLong()).toString()
            logs.add(
                CycleDayLog(
                    dateString = date,
                    painIntensity = 8
                )
            )
        }

        val deviations = engine.detectDeviations(settings, logs)
        assertFalse("Expected pain deviation to be detected", deviations.isEmpty())

        val painDeviation = deviations.find { it.factorKey == "pain_intensity" }
        assertTrue("Pain deviation must exist", painDeviation != null)
        assertEquals(DeviationSeverity.STRONG, painDeviation!!.severity)
        assertTrue(painDeviation.deltaPercent > 100.0)
    }

    @Test
    fun detectsSymptomFrequencyShift() {
        val today = LocalDate.now()
        val logs = mutableListOf<CycleDayLog>()

        // Historical logs (>90 days ago): headache on 10% of days
        for (i in 100..130) {
            val date = today.minusDays(i.toLong()).toString()
            val symptoms = if (i % 10 == 0) "headache" else ""
            logs.add(CycleDayLog(dateString = date, symptomsCsv = symptoms))
        }

        // Recent logs (<=90 days ago): headache on 80% of days
        for (i in 1..25) {
            val date = today.minusDays(i.toLong()).toString()
            val symptoms = if (i % 5 != 0) "headache" else ""
            logs.add(CycleDayLog(dateString = date, symptomsCsv = symptoms))
        }

        val deviations = engine.detectDeviations(settings, logs)
        val headacheDev = deviations.find { it.factorKey == "headache" }
        assertTrue("Headache shift must be flagged", headacheDev != null)
        assertTrue(headacheDev!!.deltaPercent > 50.0)
    }
}
