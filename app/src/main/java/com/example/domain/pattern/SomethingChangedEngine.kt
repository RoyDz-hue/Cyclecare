package com.example.domain.pattern

import com.example.data.local.CycleDayLog
import com.example.data.local.CycleSettings
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.abs

enum class DeviationSeverity {
    NOTABLE,
    SIGNIFICANT,
    STRONG
}

data class BaselineDeviation(
    val factorKey: String,
    val title: String,
    val baselineValue: Double,
    val recentValue: Double,
    val deltaPercent: Double,
    val severity: DeviationSeverity,
    val message: String
)

/**
 * Spec §21: "Something Changed" Engine.
 * Compares the recent 90-day (approx 3 cycle) window against the user's
 * established personal baseline to detect meaningful shifts in cycle timing,
 * symptom burden, or pain intensity.
 */
class SomethingChangedEngine {
    fun detectDeviations(
        settings: CycleSettings,
        logs: List<CycleDayLog>
    ): List<BaselineDeviation> {
        val today = LocalDate.now()
        val datedLogs = logs.mapNotNull { log ->
            runCatching { LocalDate.parse(log.dateString) to log }.getOrNull()
        }.distinctBy { it.first }.sortedBy { it.first }

        // Require at least 2 distinct cycles/months of data to compute baseline
        if (datedLogs.size < 14) return emptyList()

        val deviations = mutableListOf<BaselineDeviation>()
        val recentLogs = datedLogs.filter { ChronoUnit.DAYS.between(it.first, today) <= 90 }
        val historicalLogs = datedLogs.filter { ChronoUnit.DAYS.between(it.first, today) > 90 }

        if (recentLogs.isEmpty() || historicalLogs.isEmpty()) return emptyList()

        // 1. Pain Intensity Deviation
        val historicalPain = historicalLogs.map { it.second.painIntensity }.filter { it > 0 }
        val recentPain = recentLogs.map { it.second.painIntensity }.filter { it > 0 }

        if (historicalPain.isNotEmpty() && recentPain.isNotEmpty()) {
            val histAvg = historicalPain.average()
            val recAvg = recentPain.average()
            val diff = recAvg - histAvg
            if (diff >= 1.5) {
                val deltaPct = (diff / histAvg) * 100.0
                val severity = when {
                    diff >= 3.5 -> DeviationSeverity.STRONG
                    diff >= 2.5 -> DeviationSeverity.SIGNIFICANT
                    else -> DeviationSeverity.NOTABLE
                }
                deviations += BaselineDeviation(
                    factorKey = "pain_intensity",
                    title = "Pelvic Comfort Shift",
                    baselineValue = histAvg,
                    recentValue = recAvg,
                    deltaPercent = deltaPct,
                    severity = severity,
                    message = "Your logged discomfort levels recently averaged higher than your previous baseline."
                )
            }
        }

        // 2. Symptom Frequency Shifts
        val histSymptoms = historicalLogs.flatMap { it.second.symptomsCsv.split(',').map(String::trim).filter(String::isNotBlank) }
        val recSymptoms = recentLogs.flatMap { it.second.symptomsCsv.split(',').map(String::trim).filter(String::isNotBlank) }

        val allSymptomKeys = (histSymptoms + recSymptoms).distinct()
        for (sym in allSymptomKeys) {
            val histRate = histSymptoms.count { it == sym }.toDouble() / historicalLogs.size
            val recRate = recSymptoms.count { it == sym }.toDouble() / recentLogs.size

            if (histRate > 0.05 && recRate > histRate * 1.6) {
                val deltaPct = ((recRate - histRate) / histRate) * 100.0
                val severity = when {
                    recRate >= histRate * 2.5 -> DeviationSeverity.STRONG
                    recRate >= histRate * 2.0 -> DeviationSeverity.SIGNIFICANT
                    else -> DeviationSeverity.NOTABLE
                }
                val label = sym.replace('_', ' ').replaceFirstChar { it.titlecase() }
                deviations += BaselineDeviation(
                    factorKey = sym,
                    title = "$label Frequency Shift",
                    baselineValue = histRate * 100,
                    recentValue = recRate * 100,
                    deltaPercent = deltaPct,
                    severity = severity,
                    message = "You have recorded $label noticeably more often over your last few cycles."
                )
            }
        }

        return deviations.sortedByDescending { it.severity }
    }
}
