package com.cyclecare.app.data.repository
import java.time.LocalDate
data class PeriodDateRange(val period_start_date: LocalDate?, val period_end_date: LocalDate?)
data class PeriodDates(val start: LocalDate?, val end: LocalDate?)
data class PeriodDateValidationResult(val isValid: Boolean, val errorMessage: String? = null,
    val durationDays: Long = 0, val range: PeriodDateRange? = null, val warningMessage: String? = null)
