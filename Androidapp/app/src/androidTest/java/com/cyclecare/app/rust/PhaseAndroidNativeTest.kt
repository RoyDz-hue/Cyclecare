package com.cyclecare.app.rust

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cyclecare.app.data.local.CycleSettings
import com.cyclecare.app.data.model.CyclePhase
import com.cyclecare.app.domain.pattern.PatternEngine
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate

/** Compiled synthetic direct-native witnesses, not execution evidence until run. */
@RunWith(AndroidJUnit4::class)
class PhaseAndroidNativeTest {
    private fun check(date:LocalDate,s:CycleSettings,want:CyclePhase) {
        val reply=CycleCareCoreBridge.call("phase_full_v1",JSONObject().put("date",date.toString()).put("anchor",s.lastPeriodStartDate)
            .put("average",s.averageCycleLength).put("duration",s.periodDuration).put("luteal",s.lutealPhaseDays))
        assertNotNull("actual phase JNI before shared caller required",reply)
        assertEquals(setOf("ok"),reply!!.keys().asSequence().toSet())
        val ok=reply.getJSONObject("ok");assertEquals(setOf("version","phase"),ok.keys().asSequence().toSet())
        assertTrue(ok.opt("version") is Int);assertEquals(1,ok.opt("version"))
        assertTrue(ok.opt("phase") is String);assertEquals(want.name,ok.opt("phase"))
        assertEquals(want,PatternEngine.calculatePhaseForDate(date,s))
    }
    @Test fun actualPhaseBoundariesAndFullDateNarrowing() {
        val start=LocalDate.of(2026,1,1);val s=CycleSettings(lastPeriodStartDate=start.toString())
        for ((offset,phase) in listOf(0L to CyclePhase.MENSTRUAL,4L to CyclePhase.MENSTRUAL,5L to CyclePhase.FOLLICULAR,
            7L to CyclePhase.FOLLICULAR,8L to CyclePhase.OVULATION,14L to CyclePhase.OVULATION,15L to CyclePhase.LUTEAL,
            27L to CyclePhase.LUTEAL,28L to CyclePhase.MENSTRUAL,-1L to CyclePhase.LUTEAL)) check(start.plusDays(offset),s,phase)
        val far=LocalDate.of(-10000000,1,1)
        check(far.plusDays(4294967296L+8),s.copy(lastPeriodStartDate=far.toString()),CyclePhase.OVULATION)
        check(LocalDate.MAX,s.copy(lastPeriodStartDate=LocalDate.MAX.toString()),CyclePhase.MENSTRUAL)
        check(LocalDate.MIN,s.copy(lastPeriodStartDate="invalid"),CyclePhase.FOLLICULAR)
        check(LocalDate.of(10000,1,1),s.copy(lastPeriodStartDate="+0010000-01-01"),CyclePhase.MENSTRUAL)
        check(start.plusDays(10),s.copy(averageCycleLength=Int.MIN_VALUE,periodDuration=Int.MAX_VALUE,lutealPhaseDays=Int.MAX_VALUE),CyclePhase.OVULATION)
    }
    @Test fun phaseMalformedWireRefusesAndUnsupportedAnchorFallsBack() {
        check(LocalDate.of(2026,1,1),CycleSettings(),CyclePhase.FOLLICULAR)
        for (s in listOf("{}","{\"date\":\"bad\",\"anchor\":\"\",\"average\":28,\"duration\":5,\"luteal\":14}",
            "{\"date\":\"2026-01-01\",\"anchor\":\"\",\"average\":28.5,\"duration\":5,\"luteal\":14}"))
            assertNull(CycleCareCoreBridge.call("phase_full_v1",JSONObject(s)))
        assertEquals(CyclePhase.FOLLICULAR,PatternEngine.calculatePhaseForDate(LocalDate.MIN,CycleSettings(lastPeriodStartDate="\uD800")))
    }
}
