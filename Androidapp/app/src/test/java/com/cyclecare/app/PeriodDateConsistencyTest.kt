package com.cyclecare.app

import com.cyclecare.app.domain.health.PeriodDateConsistency
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PeriodDateConsistencyTest {

    private val today = LocalDate.of(2026, 10, 23)

    @Test
    fun typicalDurationIsValidWithoutWarning() {
        val check = PeriodDateConsistency.validatePair(LocalDate.of(2026, 10, 16), LocalDate.of(2026, 10, 22), today)
        assertTrue(check.isValid)
        assertNull(check.warningMessage)
        assertEquals(7, check.durationDays)
    }

    @Test
    fun unusualDurationIsValidWithGentleWarningThatKeepsTheEntry() {
        val check = PeriodDateConsistency.validatePair(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 20), today)
        assertTrue(check.isValid)
        assertEquals(20, check.durationDays)
        val warning = check.warningMessage!!
        assertTrue(warning.contains("period lasted 20 days"))
        assertTrue(warning.contains("check the dates"))
        assertTrue(warning.contains("healthcare professional"))
        assertTrue(warning.contains("kept as you entered it"))
        assertFalse(warning.contains("bleeding"))
        assertFalse(warning.contains("impossible"))
    }

    @Test
    fun endBeforeStartIsHardInvalid() {
        val check = PeriodDateConsistency.validatePair(LocalDate.of(2026, 10, 20), LocalDate.of(2026, 10, 1), today)
        assertFalse(check.isValid)
        assertEquals("Period end date cannot be earlier than start date", check.errorMessage)
    }

    @Test
    fun futureDatesAreHardInvalid() {
        assertFalse(PeriodDateConsistency.validatePair(today.plusDays(1), today.plusDays(5), today).isValid)
        assertFalse(PeriodDateConsistency.validatePair(today.minusDays(5), today.plusDays(1), today).isValid)
    }

    @Test
    fun implausiblyLongRangeIsHardInvalidAtFortySixDays() {
        val check = PeriodDateConsistency.validatePair(LocalDate.of(2026, 9, 1), LocalDate.of(2026, 10, 17), today)
        assertFalse(check.isValid)
        assertEquals(47, check.durationDays)
        assertEquals(PeriodDateConsistency.IMPLAUSIBLE_MESSAGE, check.errorMessage)
    }

    @Test
    fun fortyFiveDaysStaysConfirmableNotRejected() {
        val check = PeriodDateConsistency.validatePair(LocalDate.of(2026, 9, 8), LocalDate.of(2026, 10, 22), today)
        assertTrue(check.isValid)
        assertTrue(check.warningMessage != null)
    }

    @Test
    fun missingDatesAreRequiredErrors() {
        assertEquals("Period start date is required", PeriodDateConsistency.validatePair(null, today, today).errorMessage)
        assertEquals("Period end date is required", PeriodDateConsistency.validatePair(today, null, today).errorMessage)
    }

    @Test
    fun verdictBoundaries() {
        assertEquals(PeriodDateConsistency.Verdict.TYPICAL, PeriodDateConsistency.verdictFor(1))
        assertEquals(PeriodDateConsistency.Verdict.TYPICAL, PeriodDateConsistency.verdictFor(7))
        assertEquals(PeriodDateConsistency.Verdict.UNUSUAL_CONFIRM, PeriodDateConsistency.verdictFor(8))
        assertEquals(PeriodDateConsistency.Verdict.UNUSUAL_CONFIRM, PeriodDateConsistency.verdictFor(45))
        assertEquals(PeriodDateConsistency.Verdict.IMPLAUSIBLE_EDIT, PeriodDateConsistency.verdictFor(46))
    }
}
