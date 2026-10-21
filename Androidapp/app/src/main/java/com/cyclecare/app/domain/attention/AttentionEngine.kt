package com.cyclecare.app.domain.attention

import com.cyclecare.app.data.local.CycleDayLog
import com.cyclecare.app.data.local.CycleSettings
import com.cyclecare.app.data.model.DayPrediction
import com.cyclecare.app.data.model.SafetyEvaluation
import com.cyclecare.app.data.model.SafetyLevel
import com.cyclecare.app.data.model.TodayAttentionItem
import com.cyclecare.app.domain.pattern.DeviationSeverity
import com.cyclecare.app.domain.pattern.SomethingChangedEngine
import java.time.LocalDate
import java.time.temporal.ChronoUnit

object AttentionEngine {

    private val somethingChangedEngine = SomethingChangedEngine()

    /**
     * Evaluates daily candidates under a strict attention budget.
     * Priority order:
     * 1. Safety concern (Acute, Non-negotiable)
     * 2. Something Changed (Baseline shift detected by SomethingChangedEngine)
     * 3. Approaching Period (1-3 days away)
     * 4. Approaching Fertile Window
     * 5. Cycle Phase Transition Education
     * 6. Wellbeing Check-in
     */
    fun evaluateTodayPriority(
        prediction: DayPrediction?,
        safety: SafetyEvaluation,
        settings: CycleSettings,
        recentLogs: List<CycleDayLog>
    ): TodayAttentionItem? {
        // Priority 1: Acute Deterministic Safety Concern (NEVER preempted)
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

        // Priority 2: "Something Changed" Engine Baseline Deviations (Spec §21)
        val deviations = somethingChangedEngine.detectDeviations(settings, recentLogs)
        val strongDev = deviations.firstOrNull { it.severity == DeviationSeverity.STRONG }
        val notableDev = deviations.firstOrNull { it.severity == DeviationSeverity.SIGNIFICANT || it.severity == DeviationSeverity.NOTABLE }

        if (strongDev != null) {
            return TodayAttentionItem(
                id = "baseline_shift_strong",
                category = "Body Rhythm Shift",
                headline = "Noticeable change: ${strongDev.title}",
                message = strongDev.message,
                whyThisAppeared = "Calculated from comparing your recent 90-day records with your previous historical baseline.",
                careSuggestion = "Consider noting any changes in sleep, stress, or nutrition, or discussing with a healthcare professional."
            )
        } else if (notableDev != null && prediction?.isPeriod != true) {
            return TodayAttentionItem(
                id = "baseline_shift_notable",
                category = "Body Rhythm Shift",
                headline = "A subtle shift in your rhythm",
                message = notableDev.message,
                whyThisAppeared = "Observed across your recent cycle patterns.",
                careSuggestion = "Cycle rhythms naturally ebb and flow with lifestyle and seasonal changes."
            )
        }

        // Do not infer cycle timing from a missing or invalid date anchor.
        if (prediction == null) return null

        // Priority 3: Approaching Period & Sleep Deviation
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
                message = "You also saved sleep entries averaging ${"%.1f".format(avgRecentSleep)} hours across recent dates.",
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

        // Priority 5: Cycle Phase Transition Moment (Day 1 of new phase)
        if (prediction.dayOfCycle == 1 && prediction.isPeriod) {
            return TodayAttentionItem(
                id = "phase_menstrual_start",
                category = "Cycle Reset",
                headline = "Welcome to Day 1 of your cycle",
                message = "Hormones are at their baseline. Your body is taking time to rest and reset.",
                whyThisAppeared = "Recorded first day of period bleeding.",
                careSuggestion = "Focus on warmth, nourishing hydration, and gentle pacing."
            )
        }

        // Priority 6: Gentle Wellbeing Check-in if unlogged for 4+ days
        val lastLogDate = recentLogs.mapNotNull { runCatching { LocalDate.parse(it.dateString) }.getOrNull() }.maxOrNull()
        if (lastLogDate != null && ChronoUnit.DAYS.between(lastLogDate, LocalDate.now()) >= 4 && !prediction.isPeriod) {
            return TodayAttentionItem(
                id = "wellbeing_checkin",
                category = "Gentle Check-in",
                headline = "How is your body feeling lately?",
                message = "Checking in when you have a quiet moment helps keep your body patterns clear.",
                whyThisAppeared = "No journal entries in the last few days.",
                careSuggestion = "Add a quick note or log in Tell CycleCare whenever you feel ready."
            )
        }

        return null
    }
}
