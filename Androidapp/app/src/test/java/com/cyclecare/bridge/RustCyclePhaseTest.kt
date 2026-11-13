package com.cyclecare.bridge

import com.cyclecare.app.data.local.CycleSettings
import com.cyclecare.app.data.model.CyclePhase
import com.cyclecare.app.domain.pattern.PatternEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RustCyclePhaseTest {

    private fun settings(lastStart: String = "2026-10-01", cycle: Int = 28, period: Int = 5, luteal: Int = 14) =
        CycleSettings(lastPeriodStartDate = lastStart, averageCycleLength = cycle, periodDuration = period, lutealPhaseDays = luteal)

    // With no native library on the host, every answer must equal the exact Kotlin result.
    @Test fun absentNativeMatchesKotlin_menstrual() {
        val s = settings()
        assertEquals(PatternEngine.calculatePhaseForDate(LocalDate.of(2026, 10, 3), s), RustCyclePhase.forDate(LocalDate.of(2026, 10, 3), s))
    }
    @Test fun absentNativeMatchesKotlin_follicular() {
        val s = settings()
        assertEquals(PatternEngine.calculatePhaseForDate(LocalDate.of(2026, 10, 8), s), RustCyclePhase.forDate(LocalDate.of(2026, 10, 8), s))
    }
    @Test fun absentNativeMatchesKotlin_ovulation() {
        val s = settings()
        assertEquals(PatternEngine.calculatePhaseForDate(LocalDate.of(2026, 10, 14), s), RustCyclePhase.forDate(LocalDate.of(2026, 10, 14), s))
    }
    @Test fun absentNativeMatchesKotlin_luteal() {
        val s = settings()
        assertEquals(PatternEngine.calculatePhaseForDate(LocalDate.of(2026, 10, 25), s), RustCyclePhase.forDate(LocalDate.of(2026, 10, 25), s))
    }
    @Test fun blankLastStartMatchesKotlin() {
        val s = settings(lastStart = "")
        assertEquals(CyclePhase.FOLLICULAR, RustCyclePhase.forDate(LocalDate.of(2026, 11, 3), s))
    }
    @Test fun invalidLastStartMatchesKotlin() {
        val s = settings(lastStart = "not-a-date")
        assertEquals(PatternEngine.calculatePhaseForDate(LocalDate.of(2026, 11, 3), s), RustCyclePhase.forDate(LocalDate.of(2026, 11, 3), s))
    }
    @Test fun nonCanonicalLastStartFallsBack() {
        val s = settings(lastStart = "2026-1-1")
        assertNull(RustCyclePhase.nativeOrNull(LocalDate.of(2026, 11, 3), s))
        assertEquals(PatternEngine.calculatePhaseForDate(LocalDate.of(2026, 11, 3), s), RustCyclePhase.forDate(LocalDate.of(2026, 11, 3), s))
    }
    @Test fun boundaryLengthsMatchKotlin() {
        val s = settings(cycle = 21, period = 10, luteal = 16)
        for (d in 1..60) {
            val date = LocalDate.of(2026, 9, 1).plusDays(d.toLong())
            assertEquals("day $d", PatternEngine.calculatePhaseForDate(date, s), RustCyclePhase.forDate(date, s))
        }
    }

    // --- strict decode directed cases ---
    private fun ok(v: Any) = org.json.JSONObject().put("phase", v)
    @Test fun decodeAcceptsValid() = assertEquals(CyclePhase.OVULATION, RustCyclePhase.decode(ok("OVULATION")))
    @Test fun decodeRejectsUnknownName() = assertNull(RustCyclePhase.decode(ok("PERIODS")))
    @Test fun decodeRejectsNull() = assertNull(RustCyclePhase.decode(ok(org.json.JSONObject.NULL)))
    @Test fun decodeRejectsNumber() = assertNull(RustCyclePhase.decode(ok(2)))
    @Test fun decodeRejectsLowercase() = assertNull(RustCyclePhase.decode(ok("ovulation")))

    @Test fun flagRemainsDisabled() = assertTrue(!RustCyclePhaseConfig.ENABLED)
}
