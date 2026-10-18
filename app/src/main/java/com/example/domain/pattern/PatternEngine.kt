package com.example.domain.pattern

import com.example.data.local.CycleDayLog
import com.example.data.local.CycleSettings
import com.example.data.model.HealthPattern
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.roundToInt
import kotlin.math.sqrt

data class BaselineMetrics(
    val averageCycleLength: Double,
    val medianCycleLength: Int,
    val minCycleLength: Int,
    val maxCycleLength: Int,
    val variabilityDays: Int,
    val isRegular: Boolean,
    val totalLoggedDays: Int,
    val observedCycleIntervals: Int = 0
)

object PatternEngine {
    /**
     * Computes cycle-length statistics from distinct observed bleeding episodes.
     * A bleeding episode is grouped up to the configured period duration to avoid
     * counting every logged bleeding day as a new cycle start. The current profile
     * start date is included when it is valid. With fewer than two observed starts,
     * the user's configured cycle length is shown as an estimate, not as measured history.
     */
    fun computeBaseline(settings: CycleSettings, logs: List<CycleDayLog>): BaselineMetrics {
        val starts = observedPeriodStarts(settings, logs)
        val cycleLengths = starts.zipWithNext { first, next ->
            ChronoUnit.DAYS.between(first, next).toInt()
        }.filter { it in 1..120 }

        val configuredLength = settings.averageCycleLength.coerceIn(21, 45)
        if (cycleLengths.isEmpty()) {
            return BaselineMetrics(
                averageCycleLength = configuredLength.toDouble(),
                medianCycleLength = configuredLength,
                minCycleLength = configuredLength,
                maxCycleLength = configuredLength,
                variabilityDays = 0,
                isRegular = false,
                totalLoggedDays = validLogDateCount(logs),
                observedCycleIntervals = 0
            )
        }

        val sorted = cycleLengths.sorted()
        val average = sorted.average()
        val median = if (sorted.size % 2 == 1) {
            sorted[sorted.size / 2].toDouble()
        } else {
            (sorted[sorted.size / 2 - 1] + sorted[sorted.size / 2]) / 2.0
        }
        val variance = sorted.sumOf { (it - average) * (it - average) } / sorted.size
        val variability = sqrt(variance).roundToInt()

        return BaselineMetrics(
            averageCycleLength = average,
            medianCycleLength = median.roundToInt(),
            minCycleLength = sorted.first(),
            maxCycleLength = sorted.last(),
            variabilityDays = variability,
            isRegular = sorted.size >= 2 &&
                sorted.all { it in 21..45 } &&
                sorted.last() - sorted.first() <= 7,
            totalLoggedDays = validLogDateCount(logs),
            observedCycleIntervals = sorted.size
        )
    }

    private fun observedPeriodStarts(settings: CycleSettings, logs: List<CycleDayLog>): List<LocalDate> {
        val bleedingDays = logs.asSequence()
            .filter { it.flowLevel.uppercase() in PERIOD_FLOW_LEVELS }
            .mapNotNull { log -> runCatching { LocalDate.parse(log.dateString) }.getOrNull() }
            .distinct()
            .sorted()
            .toList()

        val starts = mutableListOf<LocalDate>()
        var episodeStart: LocalDate? = null
        val episodeWindow = settings.periodDuration.coerceIn(1, 10)
        for (date in bleedingDays) {
            val currentStart = episodeStart
            if (currentStart == null || ChronoUnit.DAYS.between(currentStart, date) > episodeWindow) {
                starts += date
                episodeStart = date
            }
        }

        val configuredStart = settings.lastPeriodStartDate
            .takeIf(String::isNotBlank)
            ?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
        if (configuredStart != null && starts.none { it == configuredStart }) starts += configuredStart
        return starts.distinct().sorted()
    }

    /**
     * Surfaces repeated entries without assigning them to an estimated phase or claiming a cause.
     * A single observation is shown in the log summary, not promoted to a recurring pattern.
     */
    fun detectPatterns(@Suppress("UNUSED_PARAMETER") settings: CycleSettings, logs: List<CycleDayLog>): List<HealthPattern> {
        val datedLogs = logs.mapNotNull { log ->
            runCatching { LocalDate.parse(log.dateString) to log }.getOrNull()
        }.distinctBy { it.first }
        if (datedLogs.isEmpty()) return emptyList()

        val symptomCounts = datedLogs.flatMap { (_, log) ->
            log.symptomsCsv.split(',').map(String::trim).filter(String::isNotBlank).distinct()
        }.groupingBy { it }.eachCount()

        val repeatedSymptoms = symptomCounts.entries
            .filter { it.value >= 2 }
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            .take(6)

        return repeatedSymptoms.map { (symptom, count) ->
            val label = symptom.replace('_', ' ').replaceFirstChar { it.titlecase() }
            HealthPattern(
                id = "logged_${symptom.lowercase().replace(Regex("[^a-z0-9]+"), "_")}",
                title = "Repeated log: $label",
                supportingObservations = "Recorded on $count of ${datedLogs.size} saved dates",
                confidence = "Observed in saved logs",
                cyclePhase = "Not assigned",
                explanation = "This is a repeated entry in your records only. The dates do not establish a cause, phase relationship, or medical significance.",
                actionableCare = "Review the dated entries and share them with a qualified health professional if you would like help interpreting them."
            )
        }
    }

    private fun validLogDateCount(logs: List<CycleDayLog>): Int = logs
        .mapNotNull { runCatching { LocalDate.parse(it.dateString) }.getOrNull() }
        .distinct()
        .size

    private val PERIOD_FLOW_LEVELS = setOf("LIGHT", "MODERATE", "HEAVY", "UNUSUALLY_HEAVY")
}
