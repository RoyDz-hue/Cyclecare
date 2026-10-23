package com.cyclecare.app

import com.cyclecare.app.domain.health.PeriodEpisodeEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class PeriodEpisodeEntryTest {

    private val today: LocalDate = LocalDate.of(2026, 10, 23)

    @Test
    fun typicalPairStoresRawEndAndUpdatesTypicalEstimate() {
        val d = PeriodEpisodeEntry.decide(today.minusDays(4), today.minusDays(1), false, 5, today)
        assertNull(d.errorMessage)
        assertFalse(d.requiresConfirmation)
        assertEquals(today.minusDays(1), d.rawEndDate)
        assertEquals(4, d.typicalDurationDays)
    }

    @Test
    fun unusualPairPreservesRawEndAndKeepsTypicalEstimate() {
        val start = today.minusDays(19)
        val end = today
        val d = PeriodEpisodeEntry.decide(start, end, false, 5, today)
        assertNull(d.errorMessage)
        assertTrue(d.requiresConfirmation)
        assertEquals(end, d.rawEndDate) // 20 days preserved, never clamped to 10
        assertEquals(5, d.typicalDurationDays) // estimate not dragged by the unusual episode
        assertTrue(d.warningMessage!!.contains("period lasted 20 days"))
    }

    @Test
    fun unusualOngoingPairUsesOngoingWording() {
        val d = PeriodEpisodeEntry.decide(today.minusDays(19), today, true, 5, today)
        assertTrue(d.requiresConfirmation)
        assertTrue(d.warningMessage!!.contains("so far"))
        assertEquals(5, d.typicalDurationDays)
    }

    @Test
    fun chronologyErrorStoresNothing() {
        val d = PeriodEpisodeEntry.decide(today, today.minusDays(3), false, 5, today)
        assertFalse(d.errorMessage.isNullOrBlank())
        assertNull(d.rawEndDate)
        assertFalse(d.requiresConfirmation)
        assertEquals(5, d.typicalDurationDays)
    }

    @Test
    fun veryLongRealEpisodeStaysConfirmableAndPreservesRawEnd() {
        val d = PeriodEpisodeEntry.decide(today.minusDays(89), today, false, 6, today)
        assertNull(d.errorMessage)
        assertTrue(d.requiresConfirmation)
        assertEquals(today, d.rawEndDate)
        assertEquals(6, d.typicalDurationDays)
    }

    @Test
    fun futureDatesAreRejected() {
        val d = PeriodEpisodeEntry.decide(today.plusDays(1), today.plusDays(5), false, 5, today)
        assertFalse(d.errorMessage.isNullOrBlank())
        assertNull(d.rawEndDate)
    }
}
