package com.cyclecare.app

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.cyclecare.app.data.local.CycleCareDatabase
import com.cyclecare.app.data.local.CycleSettings
import com.cyclecare.app.data.model.CyclePhase
import com.cyclecare.app.data.repository.CycleRepository
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Day-prediction route through the real CycleRepository.calculateDayPrediction
 * funnel (6 production call sites in CycleCareViewModel). Whichever tier
 * answers - the native bridge if loadable, otherwise the Kotlin computation -
 * must produce these exact results; the test intentionally does not assert
 * which tier executed. The native port's matching answers are pinned by the
 * rust core-ffi calculate_day_prediction_contract dispatch tests.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CycleRepositoryDayPredictionRouteTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val repo by lazy {
        CycleRepository(Room.inMemoryDatabaseBuilder(context, CycleCareDatabase::class.java)
            .allowMainThreadQueries().build())
    }
    private val settings = CycleSettings(
        lastPeriodStartDate = "2026-10-01",
        averageCycleLength = 28,
        periodDuration = 5,
        lutealPhaseDays = 14
    )

    @Test
    fun lutealDayTwenty() {
        val p = repo.calculateDayPrediction(LocalDate.of(2026, 10, 20), settings)!!
        assertEquals(20, p.dayOfCycle)
        assertEquals(CyclePhase.LUTEAL, p.phase)
        assertFalse(p.isPeriod); assertFalse(p.isFertile); assertFalse(p.isOvulation)
        assertEquals("Calendar Estimate", p.fertilityConfidence)
        assertEquals("Not calculated from calendar dates", p.pregnancyChance)
    }

    @Test
    fun periodDayThree() {
        val p = repo.calculateDayPrediction(LocalDate.of(2026, 10, 3), settings)!!
        assertEquals(3, p.dayOfCycle)
        assertEquals(CyclePhase.MENSTRUAL, p.phase)
        assertTrue(p.isPeriod)
    }

    @Test
    fun ovulationDayFourteen() {
        val p = repo.calculateDayPrediction(LocalDate.of(2026, 10, 14), settings)!!
        assertEquals(14, p.dayOfCycle)
        assertEquals(CyclePhase.OVULATION, p.phase)
        assertTrue(p.isOvulation); assertTrue(p.isFertile)
    }

    @Test
    fun clampedExtremes() {
        val extreme = CycleSettings(lastPeriodStartDate = "2026-10-01", averageCycleLength = 10, periodDuration = 20, lutealPhaseDays = 30)
        // Clamped to 21/10/16: ovulation = max(21-16, 10+2) = 12.
        val p = repo.calculateDayPrediction(LocalDate.of(2026, 10, 12), extreme)!!
        assertEquals(12, p.dayOfCycle)
        assertTrue(p.isOvulation)
        assertFalse(p.isPeriod)
    }

    @Test
    fun beforeAnchorWrapsWithFloorMod() {
        val p = repo.calculateDayPrediction(LocalDate.of(2026, 9, 25), settings)!!
        assertEquals(23, p.dayOfCycle)
    }

    @Test
    fun blankAnchorIsNull() {
        assertNull(repo.calculateDayPrediction(LocalDate.of(2026, 10, 20), settings.copy(lastPeriodStartDate = "")))
    }

    @Test
    fun unparseableAnchorIsNull() {
        assertNull(repo.calculateDayPrediction(LocalDate.of(2026, 10, 20), settings.copy(lastPeriodStartDate = "not-a-date")))
    }
}
