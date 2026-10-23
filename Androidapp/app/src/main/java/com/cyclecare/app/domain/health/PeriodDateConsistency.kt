package com.cyclecare.app.domain.health

import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * Semantic and biological consistency checks for a period start/end date pair.
 *
 * Only chronology and parse rules are hard errors. An unusually long period is
 * never rejected: prolonged bleeding can be real at any length, so long
 * durations always stay confirmable with appropriate health guidance. Nothing
 * here silently rewrites what the user entered.
 *
 * Threshold grounding: ACOG patient guidance - a normal menstrual period
 * generally lasts up to 7 days, and bleeding lasting more than 7 days is
 * listed among abnormal-uterine-bleeding signs to discuss with a doctor
 * (https://www.acog.org/womens-health/faqs/abnormal-uterine-bleeding).
 */
object PeriodDateConsistency {

    const val MAX_TYPICAL_BLEEDING_DAYS = 7

    const val CHRONOLOGY_MESSAGE = "The end date can't be before the start date. Please check the dates."
    const val FUTURE_DATE_MESSAGE = "That date is in the future. Please check the dates."

    enum class Verdict { TYPICAL, UNUSUAL_CONFIRM }

    fun verdictFor(durationDays: Int): Verdict =
        if (durationDays > MAX_TYPICAL_BLEEDING_DAYS) Verdict.UNUSUAL_CONFIRM else Verdict.TYPICAL

    /**
     * Gentle confirmation wording for an unusually long period. Must not
     * promise the entry is kept - saving happens only after the user confirms.
     * [isOngoing] distinguishes a period still in progress (firmer guidance)
     * from a historical, ended episode.
     */
    fun unusualMessage(durationDays: Int, isOngoing: Boolean): String =
        if (isOngoing) {
            "Your period has lasted $durationDays days so far. That's longer than the usual range of up to about a week - please check the start date. If it's right, it's worth speaking with a healthcare professional soon."
        } else {
            "Your period lasted $durationDays days. That's longer than the usual range of up to about a week - please check the dates. If they're right, it's worth mentioning to a healthcare professional."
        }

    data class PeriodPairCheck(
        val isValid: Boolean,
        val errorMessage: String? = null,
        val warningMessage: String? = null,
        val durationDays: Int = 0
    )

    /**
     * Pair validation: only chronology violations and future dates are hard
     * errors. Every real duration is accepted; unusual durations carry a
     * confirmation warning. [isOngoing] marks the period as still in progress.
     */
    fun validatePair(
        start: LocalDate?,
        end: LocalDate?,
        today: LocalDate = LocalDate.now(),
        isOngoing: Boolean = false
    ): PeriodPairCheck {
        if (start == null) return PeriodPairCheck(isValid = false, errorMessage = "Period start date is required")
        if (end == null) return PeriodPairCheck(isValid = false, errorMessage = "Period end date is required")
        if (end.isBefore(start)) return PeriodPairCheck(isValid = false, errorMessage = "Period end date cannot be earlier than start date")
        if (start.isAfter(today) || end.isAfter(today)) return PeriodPairCheck(isValid = false, errorMessage = "Period dates cannot be in the future")
        val duration = (ChronoUnit.DAYS.between(start, end) + 1).toInt()
        return PeriodPairCheck(
            isValid = true,
            warningMessage = if (verdictFor(duration) == Verdict.UNUSUAL_CONFIRM) unusualMessage(duration, isOngoing) else null,
            durationDays = duration
        )
    }
}
