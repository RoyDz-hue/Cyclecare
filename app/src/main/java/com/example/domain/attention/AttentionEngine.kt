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
                whyThisAppeared = "You logged symptoms that meet clinical criteria for professional review.",
                careSuggestion = safety.actionAdvice,
                isUrgent = true
            )
        }

        // Priority 2: Approaching Period & Sleep Deviation
        val daysUntilPeriod = (settings.averageCycleLength - (prediction?.dayOfCycle ?: 1)).coerceAtLeast(0)
        val recentSleepLogs = recentLogs.take(3).filter { it.sleepHours > 0 }
        val avgRecentSleep = if (recentSleepLogs.isNotEmpty()) recentSleepLogs.map { it.sleepHours }.average() else 7.5

        if (daysUntilPeriod in 1..3 && avgRecentSleep < 6.8) {
            return TodayAttentionItem(
                id = "period_sleep_taper",
                category = "Today's Picture",
                headline = "Your period is about $daysUntilPeriod days away",
                message = "You've also been sleeping a bit less than usual over the last few nights. Lower energy is common as progesterone drops.",
                whyThisAppeared = "Upcoming cycle phase transition combined with recorded sleep under 7 hours.",
                careSuggestion = "Prioritize a calming evening routine and gentle wind-down tonight."
            )
        }

        // Priority 3: Something Changed (Cycle Length Deviation)
        if (settings.averageCycleLength > 32) {
            return TodayAttentionItem(
                id = "cycle_variation_notice",
                category = "Something to Notice",
                headline = "Your cycle has been longer recently",
                message = "Your current cycle length of ${settings.averageCycleLength} days is slightly higher than a typical median. Stress, sleep, or travel can naturally shift ovulation.",
                whyThisAppeared = "Your recorded cycle length is above 32 days.",
                careSuggestion = "Keep logging your fluid or temperatures to confirm ovulation timing."
            )
        }

        // Priority 4: Approaching Fertile Window
        if (prediction?.isFertile == true && !prediction.isPeriod) {
            return TodayAttentionItem(
                id = "fertile_window_notice",
                category = "Cycle Milestone",
                headline = "You may be entering your fertile window",
                message = "Estrogen is peaking, often accompanied by clear or stretchy cervical fluid and higher baseline stamina.",
                whyThisAppeared = "Estimated Day ${prediction.dayOfCycle} corresponds to your typical ovulatory window.",
                careSuggestion = "Track any cervical fluid or ovulation tests if fertility is one of your focus areas."
            )
        }

        // If nothing needs attention: Attention budget is empty (All Caught Up!)
        return null
    }
}
