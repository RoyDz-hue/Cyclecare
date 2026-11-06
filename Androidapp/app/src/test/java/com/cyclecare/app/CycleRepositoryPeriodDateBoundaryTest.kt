package com.cyclecare.app

import com.cyclecare.app.data.repository.CycleRepository
import java.time.LocalDate
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Full java.time.LocalDate domain boundaries for the chronology-only helper
 * CycleRepository.validatePeriodDates. The test calls the real route;
 * whichever tier answers - the native bridge if its library is loadable in
 * the execution environment, otherwise the Kotlin comparison - must produce
 * these exact full-domain Java answers. It intentionally does not assert
 * which tier executed; the native port's matching answers are pinned
 * separately by the rust core-ffi validate_period_dates_contract dispatch
 * tests. Future-date clinical policy is a separate helper
 * (PeriodDateConsistency) and stays untouched.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CycleRepositoryPeriodDateBoundaryTest {

    private val javaMax: LocalDate = LocalDate.of(999999999, 12, 31)
    private val javaMin: LocalDate = LocalDate.of(-999999999, 1, 1)
    private val ordinary: LocalDate = LocalDate.of(2026, 10, 20)

    @Test
    fun extremeFutureEndIsValid() {
        assertTrue(CycleRepository.validatePeriodDates(ordinary, javaMax))
    }

    @Test
    fun equalMaxPairIsValid() {
        assertTrue(CycleRepository.validatePeriodDates(javaMax, javaMax))
    }

    @Test
    fun equalMinPairIsValid() {
        assertTrue(CycleRepository.validatePeriodDates(javaMin, javaMin))
    }

    @Test
    fun maxStartAfterOrdinaryEndIsInvalid() {
        assertFalse(CycleRepository.validatePeriodDates(javaMax, ordinary))
    }

    @Test
    fun minToMaxSpanIsValid() {
        assertTrue(CycleRepository.validatePeriodDates(javaMin, javaMax))
    }

    @Test
    fun negativeBoundaryOrderingIsValid() {
        assertTrue(CycleRepository.validatePeriodDates(javaMin, LocalDate.of(-999999998, 12, 31)))
    }

    @Test
    fun negativeReversedIsInvalid() {
        assertFalse(CycleRepository.validatePeriodDates(LocalDate.of(-999999998, 12, 31), javaMin))
    }

    @Test
    fun nullInputStillInvalid() {
        assertFalse(CycleRepository.validatePeriodDates(null, javaMax))
        assertFalse(CycleRepository.validatePeriodDates(javaMin, null))
    }
}
