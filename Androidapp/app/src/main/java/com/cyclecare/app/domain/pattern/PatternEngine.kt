package com.cyclecare.app.domain.pattern

import com.cyclecare.app.data.local.CycleDayLog
import com.cyclecare.app.data.local.CycleSettings
import com.cyclecare.app.data.model.CyclePhase
import com.cyclecare.app.data.model.HealthPattern
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.abs
import kotlin.math.exp
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

data class EvaluatedPattern(
    val symptomKey: String,
    val phase: CyclePhase,
    val totalCount: Int,
    val recentCount: Int,
    val weightedScore: Double,
    val confidence: String,
    val isAnomaly: Boolean
)

object PatternEngine {
    /**
     * Computes cycle-length statistics from distinct observed bleeding episodes.
     */
    fun computeBaseline(settings: CycleSettings, logs: List<CycleDayLog>): BaselineMetrics {
        return com.cyclecare.app.rust.CycleCareCoreBridge.fullBaseline(settings, logs)
            ?: computeBaselineKotlin(settings, logs)
    }

    /** Verbatim source oracle retained for native failure/unsupported wire. */
    internal fun computeBaselineKotlin(settings: CycleSettings, logs: List<CycleDayLog>): BaselineMetrics {
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
        var lastBleedingDate: LocalDate? = null
        val episodeWindow = settings.periodDuration.coerceIn(1, 10)
        for (date in bleedingDays) {
            val currentStart = episodeStart
            // A run of consecutive logged bleeding days is positive evidence of
            // ONE episode - a genuinely long period must never be split into a
            // fake new cycle start. Only a gap beyond the typical-duration
            // window (missing or non-bleeding days - uncertain evidence) lets
            // the window semantics start a new episode, as before.
            val previousDayWasBleeding =
                lastBleedingDate?.let { ChronoUnit.DAYS.between(it, date) == 1L } == true
            if (currentStart == null ||
                (ChronoUnit.DAYS.between(currentStart, date) > episodeWindow && !previousDayWasBleeding)
            ) {
                starts += date
                episodeStart = date
            }
            lastBleedingDate = date
        }

        val configuredStart = settings.lastPeriodStartDate
            .takeIf(String::isNotBlank)
            ?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
        // A configured start is a setup estimate, not a second observed episode.
        // Never turn one saved bleeding log into a measured cycle interval.
        if (configuredStart != null && starts.isEmpty()) starts += configuredStart
        return starts.distinct().sorted()
    }

    /**
     * Determines the estimated cycle phase for a specific date based on settings.
     */
    fun calculatePhaseForDate(date: LocalDate, settings: CycleSettings): CyclePhase {
        return com.cyclecare.app.rust.CycleCareCoreBridge.fullPhase(date,settings)
            ?: calculatePhaseForDateKotlin(date,settings)
    }

    /** Verbatim original phase oracle, retained for unsupported/failed native calls. */
    internal fun calculatePhaseForDateKotlin(date: LocalDate, settings: CycleSettings): CyclePhase {
        val lastStart = settings.lastPeriodStartDate.takeIf(String::isNotBlank)?.let {
            runCatching { LocalDate.parse(it) }.getOrNull()
        } ?: return CyclePhase.FOLLICULAR

        val cycleLength = settings.averageCycleLength.coerceIn(21, 45)
        val periodDuration = settings.periodDuration.coerceIn(2, 10)
        val lutealLength = settings.lutealPhaseDays.coerceIn(10, 16)
        val ovulationDay = (cycleLength - lutealLength).coerceIn(periodDuration + 1, cycleLength - 1)
        val fertileStart = (ovulationDay - 5).coerceAtLeast(periodDuration + 1)
        val fertileEnd = (ovulationDay + 1).coerceAtMost(cycleLength)

        val daysDiff = ChronoUnit.DAYS.between(lastStart, date).toInt()
        val cycleDay = Math.floorMod(daysDiff, cycleLength) + 1

        return when {
            cycleDay <= periodDuration -> CyclePhase.MENSTRUAL
            cycleDay < fertileStart -> CyclePhase.FOLLICULAR
            cycleDay <= fertileEnd -> CyclePhase.OVULATION
            else -> CyclePhase.LUTEAL
        }
    }

    /**
     * Spec §17-20: Advanced pattern detection with:
     * 1. Cycle phase correlation
     * 2. Exponential recency weighting (half-life ~23 days)
     * 3. Windowed statistics (all-time vs recent 90-day window)
     * 4. Median Absolute Deviation (MAD) for anomaly detection
     * 5. Multi-factor confidence scoring
     */
    fun detectPatterns(settings: CycleSettings, logs: List<CycleDayLog>): List<HealthPattern> {
        val today = LocalDate.now()
        return com.cyclecare.app.rust.CycleCareCoreBridge.fullPatterns(settings,logs,today)
            ?: detectPatternsKotlin(settings,logs,today)
    }

    /** Original pattern oracle; one clock value shared with the native attempt. */
    internal fun detectPatternsKotlin(settings: CycleSettings, logs: List<CycleDayLog>, today: LocalDate): List<HealthPattern> {
        val datedLogs = logs.mapNotNull { log ->
            runCatching { LocalDate.parse(log.dateString) to log }.getOrNull()
        }.distinctBy { it.first }

        if (datedLogs.isEmpty()) return emptyList()

        // 1. Map each dated entry to symptom occurrences with date and phase
        data class SymptomOccurrence(val symptom: String, val date: LocalDate, val phase: CyclePhase)
        val occurrences = datedLogs.flatMap { (date, log) ->
            val phase = calculatePhaseForDate(date, settings)
            log.symptomsCsv.split(',')
                .map(String::trim)
                .filter(String::isNotBlank)
                .distinct()
                .map { symptom -> SymptomOccurrence(symptom, date, phase) }
        }

        if (occurrences.isEmpty()) return emptyList()

        // 2. Group by symptom and phase
        val grouped = occurrences.groupBy { it.symptom to it.phase }

        val evaluatedList = mutableListOf<EvaluatedPattern>()

        for (entry in grouped.entries) {
            val symptom = entry.key.first
            val phase = entry.key.second
            val list = entry.value
            val totalCount = list.size
            if (totalCount < 2) continue // Minimum 2 occurrences to form a pattern

            // Recency weighting: weight = exp(-lambda * daysAgo)
            val weightedScore = list.sumOf { occ ->
                val daysAgo = maxOf(0L, ChronoUnit.DAYS.between(occ.date, today))
                exp(-0.03 * daysAgo.toDouble())
            }

            val recentCount = list.count { occ ->
                ChronoUnit.DAYS.between(occ.date, today) <= 90L
            }

            // Anomaly detection via MAD over intervals
            val sortedDates: List<LocalDate> = list.map { it.date }.sorted()
            val intervals: List<Double> = sortedDates.zipWithNext { a, b -> ChronoUnit.DAYS.between(a, b).toDouble() }
            val isAnomaly = if (intervals.size >= 3) {
                val medianInterval = computeMedian(intervals)
                val absoluteDeviations = intervals.map { abs(it - medianInterval) }
                val mad = computeMedian(absoluteDeviations)
                val prevDate = sortedDates[sortedDates.size - 2]
                val lastDate = sortedDates[sortedDates.size - 1]
                val lastInterval = ChronoUnit.DAYS.between(prevDate, lastDate).toDouble()
                mad > 0.0 && abs(lastInterval - medianInterval) > (2.5 * mad)
            } else false

            // Confidence score based on consistency and recency
            val confidence = when {
                totalCount >= 5 && recentCount >= 2 -> "High"
                totalCount >= 3 || recentCount >= 2 -> "Moderate"
                else -> "Emerging"
            }

            evaluatedList += EvaluatedPattern(
                symptomKey = symptom,
                phase = phase,
                totalCount = totalCount,
                recentCount = recentCount,
                weightedScore = weightedScore,
                confidence = confidence,
                isAnomaly = isAnomaly
            )
        }

        // Sort by weighted score descending, prioritizing active and frequent patterns
        val topPatterns = evaluatedList.sortedByDescending { it.weightedScore }.take(6)

        return topPatterns.map { ep ->
            val label = ep.symptomKey.replace('_', ' ').replaceFirstChar { it.titlecase() }
            val phaseTitle = ep.phase.title

            val explanation = when (ep.phase) {
                CyclePhase.LUTEAL -> "Progesterone fluctuations before menstruation commonly accompany recurring $label in your luteal phase."
                CyclePhase.MENSTRUAL -> "Inflammatory prostaglandins and shedding uterine tissue frequently correlate with $label during menstrual bleeding."
                CyclePhase.OVULATION -> "The rapid LH surge and estrogen peak around ovulation may trigger $label."
                CyclePhase.FOLLICULAR -> "Rising follicular estrogen and metabolic activity frequently align with $label."
            }

            val careAdvice = when (ep.phase) {
                CyclePhase.LUTEAL -> "Ensure magnesium-rich nourishment, gentle hydration, and warm compresses for comfort."
                CyclePhase.MENSTRUAL -> "Rest, warm herbal infusions, and light restorative pacing support ease during this phase."
                CyclePhase.OVULATION -> "Stay hydrated and observe whether this recurring signal assists your natural body awareness."
                CyclePhase.FOLLICULAR -> "Support rising vitality with balanced nutrition, hydration, and active movement."
            }

            val observationsText = if (ep.recentCount > 0) {
                "Observed ${ep.totalCount} times (${ep.recentCount} in past 3 months) during $phaseTitle"
            } else {
                "Observed ${ep.totalCount} times historically during $phaseTitle"
            }

            HealthPattern(
                id = "pattern_${ep.symptomKey.lowercase()}_${ep.phase.name.lowercase()}",
                title = "$label during $phaseTitle",
                supportingObservations = observationsText,
                confidence = ep.confidence,
                cyclePhase = phaseTitle,
                explanation = explanation,
                actionableCare = careAdvice
            )
        }
    }

    private fun computeMedian(values: List<Double>): Double {
        if (values.isEmpty()) return 0.0
        val sorted = values.sorted()
        val n = sorted.size
        return if (n % 2 == 1) sorted[n / 2] else (sorted[n / 2 - 1] + sorted[n / 2]) / 2.0
    }

    private fun validLogDateCount(logs: List<CycleDayLog>): Int = logs
        .mapNotNull { runCatching { LocalDate.parse(it.dateString) }.getOrNull() }
        .distinct()
        .size

    private val PERIOD_FLOW_LEVELS = setOf("LIGHT", "MODERATE", "HEAVY", "UNUSUALLY_HEAVY")
}
