package com.cyclecare.app.domain.health

import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * Semantic and biological plausibility checks for a period start/end date pair.
 * Unusual entries raise a question; they are never silently rewritten or
 * rejected when they could be real prolonged bleeding.
 *
 * Threshold grounding: ACOG patient guidance - a normal menstrual period
 * generally lasts up to 7 days
 * (https://www.acog.org/womens-health/faqs/abnormal-uterine-bleeding).
 * MAX_PLAUSIBLE_ENTRY_DAYS is a product sanity bound for a single period
 * entry, not a clinical limit; real prolonged bleeding is recorded through
 * daily flow logs, not the setup estimate.
 */
object PeriodDateConsistency {

    const val MAX_TYPICAL_BLEEDING_DAYS = 7
    const val MAX_PLAUSIBLE_ENTRY_DAYS = 45

    const val CHRONOLOGY_MESSAGE = "The end date can't be before the start date. Please check the dates."
    const val FUTURE_DATE_MESSAGE = "That date is in the future. Please check the dates."
    const val IMPLAUSIBLE_MESSAGE = "That's too long for one period. Please check the start and end dates."

    enum class Verdict { TYPICAL, UNUSUAL_CONFIRM, IMPLAUSIBLE_EDIT }

    fun verdictFor(durationDays: Int): Verdict = when {
        durationDays > MAX_PLAUSIBLE_ENTRY_DAYS -> Verdict.IMPLAUSIBLE_EDIT
        durationDays > MAX_TYPICAL_BLEEDING_DAYS -> Verdict.UNUSUAL_CONFIRM
        else -> Verdict.TYPICAL
    }

    fun unusualMessage(durationDays: Int): String =
        "Your period lasted $durationDays days. That's longer than the usual range of up to about a week - please check the dates. If they're right, it's worth mentioning to a healthcare professional. Your entry is kept as you entered it."

    data class PeriodPairCheck(
        val isValid: Boolean,
        val errorMessage: String? = null,
        val warningMessage: String? = null,
        val durationDays: Int = 0
    )

    /**
     * Full pair validation: chronology violations, future dates and implausible
     * ranges are hard errors; unusual durations are kept as entered with a
     * warning for the user to confirm.
     */
    fun validatePair(start: LocalDate?, end: LocalDate?, today: LocalDate = LocalDate.now()): PeriodPairCheck {
        if (start == null) return PeriodPairCheck(isValid = false, errorMessage = "Period start date is required")
        if (end == null) return PeriodPairCheck(isValid = false, errorMessage = "Period end date is required")
        if (end.isBefore(start)) return PeriodPairCheck(isValid = false, errorMessage = "Period end date cannot be earlier than start date")
        if (start.isAfter(today) || end.isAfter(today)) return PeriodPairCheck(isValid = false, errorMessage = "Period dates cannot be in the future")
        val duration = (ChronoUnit.DAYS.between(start, end) + 1).toInt()
        if (verdictFor(duration) == Verdict.IMPLAUSIBLE_EDIT) {
            return PeriodPairCheck(isValid = false, errorMessage = IMPLAUSIBLE_MESSAGE, durationDays = duration)
        }
        return PeriodPairCheck(
            isValid = true,
            warningMessage = if (verdictFor(duration) == Verdict.UNUSUAL_CONFIRM) unusualMessage(duration) else null,
            durationDays = duration
        )
    }
}
