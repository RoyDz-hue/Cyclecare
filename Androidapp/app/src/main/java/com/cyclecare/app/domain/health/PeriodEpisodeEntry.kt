package com.cyclecare.app.domain.health

import java.time.LocalDate

/**
 * Decides what onboarding may store for a period start/end pair, keeping the
 * raw episode record (what the user actually entered and confirmed) separate
 * from the typical-duration estimate used for predictions.
 *
 * Rules follow the flow Roy approved in chat on 2026-10-23 (see
 * Androidapp/docs/period_date_consistency.md):
 * - Hard chronology/parse errors still block; nothing is stored from them.
 * - A typical-length pair stores its end date and may update the typical
 *   duration estimate.
 * - An unusual-length pair (> 7 days, ACOG patient guidance) requires the
 *   user's explicit confirmation. Its raw end date is preserved exactly as
 *   entered - never clamped into the estimate range - and it must NOT drag
 *   the typical-duration estimate, which stays at its current value.
 * - Missing evidence is unknown, never non-bleeding; nothing here infers
 *   episode ends.
 */
object PeriodEpisodeEntry {

    data class StorageDecision(
        val rawEndDate: LocalDate?,
        val typicalDurationDays: Int,
        val requiresConfirmation: Boolean,
        val warningMessage: String? = null,
        val errorMessage: String? = null
    )

    fun decide(start:LocalDate?,end:LocalDate?,isOngoing:Boolean,currentTypicalDurationDays:Int,today:LocalDate=LocalDate.now()):StorageDecision =
        com.cyclecare.app.rust.CycleCareCoreBridge.fullPeriodEpisode(start,end,today,isOngoing,currentTypicalDurationDays)?.storage
            ?: decideKotlin(start,end,isOngoing,currentTypicalDurationDays,today)

    internal fun decideKotlin(
        start: LocalDate?,
        end: LocalDate?,
        isOngoing: Boolean,
        currentTypicalDurationDays: Int,
        today: LocalDate = LocalDate.now()
    ): StorageDecision {
        val check = PeriodDateConsistency.validatePairKotlin(start, end, today, isOngoing)
        if (!check.isValid) {
            return StorageDecision(
                rawEndDate = null,
                typicalDurationDays = currentTypicalDurationDays,
                requiresConfirmation = false,
                errorMessage = check.errorMessage
            )
        }
        val unusual = check.warningMessage != null
        return StorageDecision(
            rawEndDate = end,
            typicalDurationDays = if (unusual) currentTypicalDurationDays else check.durationDays,
            requiresConfirmation = unusual,
            warningMessage = check.warningMessage
        )
    }
}
