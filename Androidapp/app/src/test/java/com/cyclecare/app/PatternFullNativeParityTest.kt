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
class PatternFullNativeParityTest {
    private val today=LocalDate.of(2026,11,7)
    private fun parity(logs:List<CycleDayLog>,s:CycleSettings=CycleSettings(),day:LocalDate=today) {
        val n=CycleCareCoreBridge.fullPatterns(s,logs,day)
        assertNotNull("actual two-stage pattern JNI required BEFORE original oracle",n)
        assertEquals(PatternEngine.detectPatternsKotlin(s,logs,day),n)
    }
    @Test fun emptyInvalidAndSingleOccurrencesCannotInventPatterns() {
        parity(emptyList());parity(listOf(CycleDayLog("bad",symptomsCsv="a")))
        parity(listOf(CycleDayLog("2026-01-01",symptomsCsv="a,a,z")))
        parity(listOf(CycleDayLog("2026-01-01",symptomsCsv=""),CycleDayLog("2026-01-02",symptomsCsv="")))
    }
    @Test fun stableFirstSeenTiesTopSixAndLowercaseIdCollisions() {
        for(sym in listOf("z,a,b,c,d,e,f,g,h","h,g,f,e,d,c,b,a,z","a,A,ßeta,ǳone,𐐨case,🌸,\uE000,_")) {
            val logs=(0..4).map {CycleDayLog(today.minusDays(it.toLong()).toString(),symptomsCsv=sym)}
            parity(logs);parity(logs.reversed())
        }
        val logs=(0..1).map {CycleDayLog(today.minusDays(it.toLong()).toString(),symptomsCsv="z,a,b,c,d,e,f")}
        assertEquals(listOf("z","a","b","c","d","e"),CycleCareCoreBridge.fullPatterns(CycleSettings(),logs,today)!!.map {it.id.removePrefix("pattern_").removeSuffix("_follicular")})
        val collision=(0..1).map {CycleDayLog(today.minusDays(it.toLong()).toString(),symptomsCsv="a,A")}
        assertEquals(2,CycleCareCoreBridge.fullPatterns(CycleSettings(),collision,today)!!.size)
        assertEquals(1,CycleCareCoreBridge.fullPatterns(CycleSettings(),collision,today)!!.map {it.id}.distinct().size)
    }
    @Test fun exactRecentBoundaryFutureUnderflowAndPhaseConfidence() {
        for(offsets in listOf(listOf(90L,91L),listOf(89L,90L,91L),listOf(-10000L,-1L,0L,1L,2L),listOf(100000L,100001L,100002L,100003L))) {
            val logs=offsets.map {CycleDayLog(today.minusDays(it).toString(),symptomsCsv="x,z")}
            parity(logs);parity(logs,CycleSettings(lastPeriodStartDate="2026-01-01"))
        }
        val logs=(0..180).step(7).map {CycleDayLog(today.minusDays(it.toLong()).toString(),symptomsCsv="x,x,z")}
        for(avg in listOf(Int.MIN_VALUE,28,Int.MAX_VALUE)) parity(logs,CycleSettings(lastPeriodStartDate="2026-01-01",averageCycleLength=avg,periodDuration=Int.MAX_VALUE,lutealPhaseDays=Int.MIN_VALUE))
    }
    @Test fun fullDatesFirstParsedAliasWinsAndIntPhaseNarrowing() {
        val logs=listOf(CycleDayLog("+0010000-01-01",symptomsCsv="a,z"),CycleDayLog("+10000-01-01",symptomsCsv="wrong"),
            CycleDayLog("-999999999-01-01",symptomsCsv="a,z"),CycleDayLog("+999999999-12-31",symptomsCsv="a,z"),CycleDayLog("0000-02-29",symptomsCsv="a,z"),CycleDayLog("-0000-01-01",symptomsCsv="wrong"))
        for(day in listOf(LocalDate.MIN,LocalDate.MAX,today))for(anchor in listOf("bad","+0010000-01-01","-999999999-01-01")) {
            parity(logs,CycleSettings(lastPeriodStartDate=anchor),day);parity(logs.reversed(),CycleSettings(lastPeriodStartDate=anchor),day)
        }
    }
    @Test fun generatedExactExpScoreOrderAndSharedCurrentClockCaller() {
        val r=java.util.Random(82631)
        repeat(160) {
            val logs=(0 until r.nextInt(70)).map {CycleDayLog(if(r.nextInt(12)==0) "bad" else today.plusDays((r.nextInt(300)-150).toLong()).toString(),symptomsCsv=listOf("z,a,z,b","a,A,ßeta,ǳone","🌸,\uE000", "a_b,a b","")[r.nextInt(5)])}
            parity(logs,CycleSettings(lastPeriodStartDate=if(r.nextBoolean())"2026-01-01" else "bad",averageCycleLength=r.nextInt(),periodDuration=r.nextInt(),lutealPhaseDays=r.nextInt()))
        }
        val day=LocalDate.now();val logs=(0..8).map {CycleDayLog(day.minusDays(it.toLong()).toString(),symptomsCsv="z,a")};val s=CycleSettings()
        assertEquals(PatternEngine.detectPatternsKotlin(s,logs,day),PatternEngine.detectPatterns(s,logs))
    }
    @Test fun strictPlanResultSchemasAndWeightCoverage() {
        for(s in listOf("{}","{\"ok\":null}","{\"ok\":{\"version\":1.0,\"days\":[]}}","{\"ok\":{\"version\":1,\"days\":[0,0]}}","{\"ok\":{\"version\":1,\"days\":[1,0]}}","{\"ok\":{\"version\":1,\"days\":[-1]}}","{\"ok\":{\"version\":1,\"days\":[1.0]}}")) assertNull(CycleCareCoreBridge.decodePatternOffsets(JSONObject(s),10))
        for(s in listOf("{}","{\"ok\":null}","{\"ok\":{\"version\":1.0,\"patterns\":[]}}","{\"ok\":{\"version\":1,\"patterns\":[{}]}}","{\"ok\":{\"version\":1,\"patterns\":[],\"extra\":0}}")) assertNull(CycleCareCoreBridge.decodeFullPatterns(JSONObject(s)))
        val p=JSONObject().put("today",today.toString()).put("anchor","").put("average",28).put("duration",5).put("luteal",14).put("logs",org.json.JSONArray()).put("weights",org.json.JSONArray())
        assertNotNull(CycleCareCoreBridge.call("pattern_full_plan_v1",p));assertNotNull(CycleCareCoreBridge.call("pattern_full_v1",p))
        for(k in listOf("today","anchor","average","duration","luteal","logs","weights")) {val m=JSONObject(p.toString());m.remove(k);assertNull(CycleCareCoreBridge.call("pattern_full_v1",m))}
        assertNull(CycleCareCoreBridge.call("pattern_full_v1",JSONObject(p.toString()).put("average",28.5)))
        assertNull(CycleCareCoreBridge.call("pattern_full_v1",JSONObject(p.toString()).put("weights",org.json.JSONArray().put(JSONObject().put("day",0).put("bits","3ff0000000000000")))))
        val withLog=JSONObject(p.toString()).put("logs",org.json.JSONArray().put(JSONObject().put("date",today.toString()).put("symptoms",org.json.JSONArray())))
        assertNull(CycleCareCoreBridge.call("pattern_full_v1",withLog))
        for(bits in listOf("bad","7ff8000000000000","7ff0000000000000","bff0000000000000","4000000000000000"))assertNull(CycleCareCoreBridge.call("pattern_full_v1",JSONObject(withLog.toString()).put("weights",org.json.JSONArray().put(JSONObject().put("day",0).put("bits",bits)))))
    }
    @Test fun wholeCallSurrogateAndNativeUnavailableFallback() {
        val state=CycleCareCoreBridge::class.java.getDeclaredField("nativeState").apply {isAccessible=true};val old=state.get(CycleCareCoreBridge)
        val day=LocalDate.now();val logs=(0..4).map {CycleDayLog(day.minusDays(it.toLong()).toString(),symptomsCsv="z,a")};val s=CycleSettings()
        try {state.set(CycleCareCoreBridge,CycleCareCoreBridge.NativeState.UNAVAILABLE)
            assertNull(CycleCareCoreBridge.fullPatterns(s,logs,day));assertEquals(PatternEngine.detectPatternsKotlin(s,logs,day),PatternEngine.detectPatterns(s,logs))
        } finally {state.set(CycleCareCoreBridge,old)}
        for(log in listOf(CycleDayLog(day.toString(),symptomsCsv="\uD800"),CycleDayLog("\uDC00",symptomsCsv="x"))) {
            assertNull(CycleCareCoreBridge.fullPatterns(s,listOf(log),day));assertEquals(PatternEngine.detectPatternsKotlin(s,listOf(log),day),PatternEngine.detectPatterns(s,listOf(log)))
        }
    }
}
