package com.cyclecare.app

import com.cyclecare.app.data.local.CycleDayLog
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
class BaselineFullNativeParityTest {
    private fun parity(logs: List<CycleDayLog>, settings: CycleSettings=CycleSettings()) {
        val native=CycleCareCoreBridge.fullBaseline(settings, logs)
        assertNotNull("baseline_full_v1 actual JNI required",native)
        val expected=PatternEngine.computeBaselineKotlin(settings,logs)
        assertEquals(expected,native)
        assertEquals(expected,PatternEngine.computeBaseline(settings,logs))
    }
    @Test fun emptyInvalidAndConfiguredAnchorCannotInventIntervals() {
        for (anchor in listOf("", "2026-01-01", "+999999999-12-31", "not-a-date")) {
            for (avg in listOf(Int.MIN_VALUE,20,21,28,45,46,Int.MAX_VALUE)) {
                parity(emptyList(),CycleSettings(lastPeriodStartDate=anchor,averageCycleLength=avg))
                parity(listOf(CycleDayLog("2026-02-29",flowLevel="HEAVY")),CycleSettings(lastPeriodStartDate=anchor,averageCycleLength=avg))
            }
        }
    }
    @Test fun consecutiveBleedingIsOneEpisodeAndWindowBoundaryExact() {
        for (duration in listOf(Int.MIN_VALUE,1,5,10,Int.MAX_VALUE)) {
            parity((0L..24L).map {CycleDayLog(LocalDate.of(2026,1,1).plusDays(it).toString(),flowLevel="HEAVY")},CycleSettings(periodDuration=duration))
            parity(listOf(0L,1,5,6,10,11,28,29,56).map {CycleDayLog(LocalDate.of(2026,1,1).plusDays(it).toString(),flowLevel="light")},CycleSettings(periodDuration=duration))
        }
    }
    @Test fun fullDatesAliasesInvalidsAndBleedingDuplicateUnion() {
        val dates=listOf("+999999999-12-31", "-999999999-01-01", "+10000-01-01", "+0010000-01-01", "0000-02-29", "-0000-01-01", "2026-1-01", "2026-01-1", "2026-04-31")
        parity(dates.map { CycleDayLog(it,flowLevel="MODERATE") })
        parity(listOf(CycleDayLog("2026-01-01",flowLevel="NONE"),CycleDayLog("2026-01-01",flowLevel="HEAVY"),CycleDayLog("2026-01-29",flowLevel="moderate")))
        for (flow in listOf("light","Heavy","UNUSUALLY_HEAVY","heaVy","heavı","ＨＥＡＶＹ","HEAVY ","", "NONE"))
            parity(listOf(CycleDayLog("2026-01-01",flowLevel=flow),CycleDayLog("2026-01-29",flowLevel=flow)))
    }
    @Test fun intDayNarrowingHappensBeforeIntervalFilter() {
        val start=LocalDate.of(-10000000,1,1)
        for (delta in listOf(1L,120L,121L,2147483648L,4294967296L+28,4294967296L+120,4294967296L+121)) {
            parity(listOf(CycleDayLog(start.toString(),flowLevel="HEAVY"),CycleDayLog(start.plusDays(delta).toString(),flowLevel="HEAVY")),CycleSettings(periodDuration=1))
        }
    }
    @Test fun exactStatsRegularityRoundingAndGeneratedCorpus() {
        for (intervals in listOf(listOf(28),listOf(21,22),listOf(21,28),listOf(21,29),listOf(20,28),listOf(45,45),listOf(46,46),listOf(1,120),listOf(26,27,28,29))) {
            var day=LocalDate.of(2020,1,1)
            val logs=mutableListOf(CycleDayLog(day.toString(),flowLevel="LIGHT"))
            intervals.forEach {day=day.plusDays(it.toLong());logs+=CycleDayLog(day.toString(),flowLevel="LIGHT")}
            parity(logs,CycleSettings(periodDuration=1))
        }
        val random=java.util.Random(2917)
        repeat(150) {
            val logs=(0 until random.nextInt(50)).map {CycleDayLog(LocalDate.of(2020,1,1).plusDays(random.nextInt(900).toLong()).toString(),flowLevel=listOf("HEAVY","moderate","NONE","")[random.nextInt(4)])}
            parity(logs,CycleSettings(averageCycleLength=random.nextInt(80)-10,periodDuration=random.nextInt(20)-5))
        }
    }
    @Test fun strictDecodeAndUnavailableFallback() {
        for (body in listOf("{}","{\"ok\":null}","{\"ok\":{\"version\":\"1\",\"metrics\":{}}}","{\"ok\":{\"version\":1,\"metrics\":{}}}"))
            assertNull(CycleCareCoreBridge.decodeFullBaseline(JSONObject(body),0))
        val good = CycleCareCoreBridge.call("baseline_full_v1", JSONObject("{\"average\":28,\"duration\":5,\"logs\":[]}"))!!
        assertNotNull(CycleCareCoreBridge.decodeFullBaseline(good,0))
        for ((key,value) in listOf("totalLoggedDays" to -1,"observedCycleIntervals" to 1,"medianCycleLength" to 29,
            "minCycleLength" to 0,"maxCycleLength" to 121,"variabilityDays" to 1,"isRegular" to true,
            "averageCycleLength" to "28","totalLoggedDays" to 0.5)) {
            val reply=JSONObject(good.toString());reply.getJSONObject("ok").getJSONObject("metrics").put(key,value)
            assertNull(key,CycleCareCoreBridge.decodeFullBaseline(reply,0))
        }
        for (raw in listOf("{}","{\"average\":28,\"duration\":5,\"logs\":[{}]}",
            "{\"average\":28,\"duration\":5,\"logs\":[{\"date\":\"2026-01-01\",\"bleeding\":\"true\"}]}"))
            assertNull(CycleCareCoreBridge.call("baseline_full_v1",JSONObject(raw)))
        val logs=listOf(CycleDayLog("2026-01-01",flowLevel="HEAVY"))
        val state=CycleCareCoreBridge::class.java.getDeclaredField("nativeState").apply {isAccessible=true}
        val old=state.get(CycleCareCoreBridge)
        try {
            state.set(CycleCareCoreBridge,CycleCareCoreBridge.NativeState.UNAVAILABLE)
            assertNull(CycleCareCoreBridge.fullBaseline(CycleSettings(),logs))
            assertEquals(PatternEngine.computeBaselineKotlin(CycleSettings(),logs),PatternEngine.computeBaseline(CycleSettings(),logs))
        } finally {state.set(CycleCareCoreBridge,old)}
    }
}
