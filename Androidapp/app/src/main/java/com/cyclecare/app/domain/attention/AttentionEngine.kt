package com.cyclecare.app.domain.attention

import com.cyclecare.app.data.local.CycleDayLog
import com.cyclecare.app.data.local.CycleSettings
import com.cyclecare.app.data.model.DayPrediction
import com.cyclecare.app.data.model.SafetyEvaluation
import com.cyclecare.app.data.model.SafetyLevel
import com.cyclecare.app.data.model.TodayAttentionItem
import com.cyclecare.app.domain.pattern.DeviationSeverity
import com.cyclecare.app.domain.pattern.SomethingChangedEngine
import com.cyclecare.app.rust.CycleCareCoreBridge
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

    /**
     * Native-first variant: NON-SAFETY priority choice and output are delegated to the Rust
     * select_nonsafety_priority port. The safety branch keeps its original immediate Kotlin
     * return; deviation detection uses the qualified shared SomethingChangedEngine caller;
     * nonsafety sleep evidence and complete item assembly are Rust-owned with exact JVM
     * uppercase and locale-format primitives; the original oracle stays intact as fallback; the wellbeing
     * check-in's current-date evidence is resolved at the original late stage, only after no
     * earlier branch won, via [daySupplier] (default: a fresh LocalDate.now() at that moment,
     * matching production). Native unavailability or any malformed reply falls back to the
     * verbatim oracle; an authoritative native NONE is honored without re-running the oracle.
     */
    fun evaluateTodayPriorityNonsafetyNativeFirst(
        prediction: DayPrediction?,
        safety: SafetyEvaluation,
        settings: CycleSettings,
        recentLogs: List<CycleDayLog>,
        daySupplier: () -> LocalDate = { LocalDate.now() }
    ): TodayAttentionItem? {
        // Priority 1: unchanged immediate Kotlin safety return (never routed natively).
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

        val deviations = somethingChangedEngine.detectDeviations(settings, recentLogs)
        var daysUntilPeriod = 0
        var sleepAverage: Double? = null
        if (prediction != null) {
            val evidence=CycleCareCoreBridge.prepareFullAttention(settings.averageCycleLength,prediction.dayOfCycle,recentLogs)
                ?: return evaluateTodayPriority(prediction,safety,settings,recentLogs)
            daysUntilPeriod=evidence.days
            sleepAverage=evidence.sleep
        }
        val dayOfCycle = prediction?.dayOfCycle ?: 0
        val isPeriod = prediction?.isPeriod == true
        val isFertile = prediction?.isFertile == true

        val first = CycleCareCoreBridge.chooseFullAttention(
            safetyUrgentOrSeek = false,
            deviations = deviations,
            hasPrediction = prediction != null,
            dayOfCycle = dayOfCycle,
            isPeriod = isPeriod,
            isFertile = isFertile,
            daysUntilPeriod = daysUntilPeriod,
            sleepAverage = sleepAverage,
            checkinResolved = false,
            daysSinceLastLog = null
        ) ?: return evaluateTodayPriority(prediction, safety, settings, recentLogs)

        when (first.branch) {
            CycleCareCoreBridge.NonsafetyBranch.BASELINE_SHIFT_STRONG -> {
                return (CycleCareCoreBridge.renderFullAttention(first) ?: return evaluateTodayPriority(prediction,safety,settings,recentLogs))
            }
            CycleCareCoreBridge.NonsafetyBranch.BASELINE_SHIFT_NOTABLE -> {
                return (CycleCareCoreBridge.renderFullAttention(first) ?: return evaluateTodayPriority(prediction,safety,settings,recentLogs))
            }
            CycleCareCoreBridge.NonsafetyBranch.NONE -> return null
            CycleCareCoreBridge.NonsafetyBranch.PERIOD_SLEEP_TAPER -> {
                return (CycleCareCoreBridge.renderFullAttention(first) ?: return evaluateTodayPriority(prediction,safety,settings,recentLogs))
            }
            CycleCareCoreBridge.NonsafetyBranch.FERTILE_WINDOW_NOTICE -> {
                return (CycleCareCoreBridge.renderFullAttention(first) ?: return evaluateTodayPriority(prediction,safety,settings,recentLogs))
            }
            CycleCareCoreBridge.NonsafetyBranch.PHASE_MENSTRUAL_START -> {
                return (CycleCareCoreBridge.renderFullAttention(first) ?: return evaluateTodayPriority(prediction,safety,settings,recentLogs))
            }
            CycleCareCoreBridge.NonsafetyBranch.NEEDS_CHECKIN -> {
                // Original late stage, preserved exactly: parse every date with runCatching,
                // take the max, then read the current date ONLY when lastLogDate != null,
                // ChronoUnit difference before the !isPeriod guard (source order).
                val lastLogDate = recentLogs
                    .mapNotNull { runCatching { LocalDate.parse(it.dateString) }.getOrNull() }
                    .maxOrNull()
                val daysSinceLastLog = if (lastLogDate != null) ChronoUnit.DAYS.between(lastLogDate, daySupplier()) else null
                val second = CycleCareCoreBridge.chooseFullAttention(
                    safetyUrgentOrSeek = false,
                    deviations = deviations,
                    hasPrediction = prediction != null,
                    dayOfCycle = dayOfCycle,
                    isPeriod = isPeriod,
                    isFertile = isFertile,
                    daysUntilPeriod = daysUntilPeriod,
                    sleepAverage = sleepAverage,
                    checkinResolved = true,
                    daysSinceLastLog = daysSinceLastLog
                ) ?: return evaluateTodayPriority(prediction, safety, settings, recentLogs)
                return when (second.branch) {
                    CycleCareCoreBridge.NonsafetyBranch.WELLBEING_CHECKIN -> (CycleCareCoreBridge.renderFullAttention(second) ?: return evaluateTodayPriority(prediction,safety,settings,recentLogs))
                    CycleCareCoreBridge.NonsafetyBranch.NONE -> null
                    // Defensive: any other tag on pass 2 falls back to the verbatim oracle.
                    else -> evaluateTodayPriority(prediction, safety, settings, recentLogs)
                }
            }
            // SAFETY_PRIORITY is never sent to the native call and WELLBEING_CHECKIN is only
            // valid after check-in resolution; both fall back defensively if ever returned here.
            else -> return evaluateTodayPriority(prediction, safety, settings, recentLogs)
        }
    }
}
