package com.cyclecare.app

import com.cyclecare.app.data.local.CycleSettings
import com.cyclecare.app.domain.pattern.PatternEngine
import com.cyclecare.app.rust.CycleCareCoreBridge
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34])
class PhaseFullNativeParityTest {
    private fun parity(date:LocalDate,settings:CycleSettings) {
        val n=CycleCareCoreBridge.fullPhase(date,settings)
        assertNotNull("phase_full_v1 actual JNI required",n)
        val k=PatternEngine.calculatePhaseForDateKotlin(date,settings)
        assertEquals(k,n);assertEquals(k,PatternEngine.calculatePhaseForDate(date,settings))
    }
    @Test fun everyCycleDayAndExactClamps() {
        val start=LocalDate.of(2026,1,1)
        for (avg in listOf(Int.MIN_VALUE,21,28,45,Int.MAX_VALUE))
            for (duration in listOf(Int.MIN_VALUE,2,5,10,Int.MAX_VALUE))
                for (luteal in listOf(Int.MIN_VALUE,10,14,16,Int.MAX_VALUE))
                    for (offset in -2L..46L) parity(start.plusDays(offset),CycleSettings(lastPeriodStartDate=start.toString(),averageCycleLength=avg,periodDuration=duration,lutealPhaseDays=luteal))
    }
    @Test fun fullDateExtremesAliasesInvalidAndOverflow() {
        val dates=listOf(LocalDate.MIN,LocalDate.MAX,LocalDate.of(0,2,29),LocalDate.of(2026,1,1))
        val anchors=listOf("", " \u00a0", "bad", "2026-1-01", "2026-02-29", "-0000-01-01", "+10000-01-01", "+0010000-01-01", "-999999999-01-01", "+999999999-12-31")
        for (d in dates) for (a in anchors) parity(d,CycleSettings(lastPeriodStartDate=a))
        val anchor=LocalDate.of(-10000000,1,1)
        for (delta in listOf(-4294967296L-1,-2147483649L,-2147483648L,2147483647L,2147483648L,4294967296L+28))
            parity(anchor.plusDays(delta),CycleSettings(lastPeriodStartDate=anchor.toString()))
    }
    @Test fun deterministicGeneratedFullDomain() {
        val r=java.util.Random(7201)
        repeat(500) {
            val a=LocalDate.of(r.nextInt(1999999999)-999999999,1+r.nextInt(12),1+r.nextInt(28))
            val d=LocalDate.of(r.nextInt(1999999999)-999999999,1+r.nextInt(12),1+r.nextInt(28))
            parity(d,CycleSettings(lastPeriodStartDate=a.toString(),averageCycleLength=r.nextInt(),periodDuration=r.nextInt(),lutealPhaseDays=r.nextInt()))
        }
    }
    @Test fun strictWireTypesTagsAndUnavailableFallback() {
        for (s in listOf("{}","{\"ok\":null}","{\"ok\":{\"version\":\"1\",\"phase\":\"LUTEAL\"}}",
            "{\"ok\":{\"version\":1.0,\"phase\":\"LUTEAL\"}}","{\"ok\":{\"version\":1,\"phase\":1}}",
            "{\"ok\":{\"version\":1,\"phase\":\"luteal\"}}","{\"ok\":{\"version\":1,\"phase\":\"LUTEAL\",\"extra\":0}}",
            "{\"ok\":{\"version\":1,\"phase\":\"LUTEAL\"},\"error\":\"bad\"}"))
            assertNull(CycleCareCoreBridge.decodeFullPhase(JSONObject(s)))
        for (s in listOf("{}","{\"date\":\"bad\",\"anchor\":\"\",\"average\":28,\"duration\":5,\"luteal\":14}",
            "{\"date\":\"2026-01-01\",\"anchor\":\"\",\"average\":28.5,\"duration\":5,\"luteal\":14}"))
            assertNull(CycleCareCoreBridge.call("phase_full_v1",JSONObject(s)))
        val d=LocalDate.of(2026,1,1);val settings=CycleSettings(lastPeriodStartDate="\uD800")
        assertNull(CycleCareCoreBridge.fullPhase(d,settings));assertEquals(PatternEngine.calculatePhaseForDateKotlin(d,settings),PatternEngine.calculatePhaseForDate(d,settings))
        val state=CycleCareCoreBridge::class.java.getDeclaredField("nativeState").apply {isAccessible=true};val old=state.get(CycleCareCoreBridge)
        try {state.set(CycleCareCoreBridge,CycleCareCoreBridge.NativeState.UNAVAILABLE)
            assertNull(CycleCareCoreBridge.fullPhase(d,CycleSettings()));assertEquals(PatternEngine.calculatePhaseForDateKotlin(d,CycleSettings()),PatternEngine.calculatePhaseForDate(d,CycleSettings()))
        } finally {state.set(CycleCareCoreBridge,old)}
    }
}
