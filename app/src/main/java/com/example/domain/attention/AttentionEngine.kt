package com.example.domain.attention

import com.example.data.local.CycleDayLog
import com.example.data.local.CycleSettings
import com.example.data.model.DayPrediction
import com.example.data.model.SafetyEvaluation
import com.example.data.model.SafetyLevel
import com.example.data.model.TodayAttentionItem

object AttentionEngine {

    /**
     * Evaluates daily candidates under a strict attention budget.
     * Yields at most ONE or TWO high-relevance items, or null when all is quiet.
     */
    fun evaluateTodayPriority(
        prediction: DayPrediction?,
        safety: SafetyEvaluation,
        settings: CycleSettings,
        recentLogs: List<CycleDayLog>
    ): TodayAttentionItem? {
        // Priority 1: Acute Deterministic Safety Concern
        if (safety.level == SafetyLevel.URGENT || safety.level == SafetyLevel.SEEK_PROFESSIONAL_ADVICE) {
            return TodayAttentionItem(
                id = "safety_priority",
                category = "Safety",
                headline = safety.title,
                message = safety.message,
                whyThisAppeared = "This notice is based on the symptom entry you saved today.",
                careSuggestion = safety.actionAdvice,
                isUrgent = true
            )
        }

        // Do not infer cycle timing from a missing or invalid date anchor.
        if (prediction == null) return null

        // Priority 2: Approaching Period & Sleep Deviation
        val daysUntilPeriod = (settings.averageCycleLength - prediction.dayOfCycle).coerceAtLeast(0)
        val recentSleepLogs = recentLogs
            .sortedByDescending { it.dateString }
            .take(3)
            .filter { it.sleepHours > 0 && (kotlin.math.abs(it.sleepHours - 7.5f) > 0.01f || it.sleepQuality.uppercase() !in setOf("", "GOOD")) }
        val avgRecentSleep = recentSleepLogs.takeIf { it.isNotEmpty() }?.map { it.sleepHours }?.average()

        if (daysUntilPeriod in 1..3 && avgRecentSleep != null && avgRecentSleep < 6.8) {
            return TodayAttentionItem(
                id = "period_sleep_taper",
                category = "Today's Picture",
                headline = "Calendar estimate: period in about $daysUntilPeriod days",
                message = "You also saved sleep entries averaging ${"%.1f".format(avgRecentSleep)} hours across the latest logged dates. The relationship between these entries is not established.",
                whyThisAppeared = "Calendar date estimate plus non-default sleep values saved in your logs.",
                careSuggestion = "Prioritize a calming evening routine and gentle wind-down tonight."
            )
        }

        // Priority 4: Approaching Fertile Window
        if (prediction.isFertile && !prediction.isPeriod) {
            return TodayAttentionItem(
                id = "fertile_window_notice",
                category = "Cycle Milestone",
                headline = "You may be entering your fertile window",
                message = "This calendar estimate places the date within a possible fertile window. It cannot confirm ovulation or prevent pregnancy.",
                whyThisAppeared = "Estimated cycle day ${prediction.dayOfCycle} from your saved date and settings.",
                careSuggestion = "If fertility matters to you, use appropriate clinical guidance rather than relying on a calendar estimate."
            )
        }

        // If nothing needs attention: Attention budget is empty (All Caught Up!)
        return null
    }
}
