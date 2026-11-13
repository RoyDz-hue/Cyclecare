package com.cyclecare.app.rust

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cyclecare.app.data.local.CycleDayLog
import com.cyclecare.app.data.local.CycleSettings
import com.cyclecare.app.domain.pattern.BaselineMetrics
import com.cyclecare.app.domain.pattern.PatternEngine
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Synthetic tests. Compiled/packaged presence is not Android execution proof. */
@RunWith(AndroidJUnit4::class)
class BaselineAndroidNativeTest {
    private fun check(logs: List<CycleDayLog>, expected: BaselineMetrics, duration: Int=5) {
        val entries=JSONArray()
        logs.forEach { entries.put(JSONObject().put("date",it.dateString)
            .put("bleeding",it.flowLevel.uppercase() in setOf("LIGHT","MODERATE","HEAVY","UNUSUALLY_HEAVY"))) }
        val reply=CycleCareCoreBridge.call("baseline_full_v1",JSONObject().put("average",28).put("duration",duration).put("logs",entries))
        assertNotNull("nonempty baseline actual JNI required before caller comparison",reply)
        assertEquals(setOf("ok"),reply!!.keys().asSequence().toSet())
        val ok=reply.getJSONObject("ok")
        assertEquals(setOf("version","metrics"),ok.keys().asSequence().toSet())
        assertTrue(ok.opt("version") is Int);assertEquals(1,ok.opt("version"))
        val m=ok.getJSONObject("metrics")
        assertEquals(setOf("averageCycleLength","medianCycleLength","minCycleLength","maxCycleLength","variabilityDays","isRegular","totalLoggedDays","observedCycleIntervals"),m.keys().asSequence().toSet())
        val avg=m.opt("averageCycleLength");assertTrue(avg is Number);assertEquals(expected.averageCycleLength,(avg as Number).toDouble(),0.0)
        for ((k,v) in listOf("medianCycleLength" to expected.medianCycleLength,"minCycleLength" to expected.minCycleLength,"maxCycleLength" to expected.maxCycleLength,"variabilityDays" to expected.variabilityDays,"totalLoggedDays" to expected.totalLoggedDays,"observedCycleIntervals" to expected.observedCycleIntervals)) {
            assertTrue(k,m.opt(k) is Int);assertEquals(k,v,m.opt(k))
        }
        assertTrue(m.opt("isRegular") is Boolean);assertEquals(expected.isRegular,m.opt("isRegular"))
        assertEquals(expected,PatternEngine.computeBaseline(CycleSettings(periodDuration=duration),logs))
    }
    @Test fun actualNonemptyNativeMetricsMatchSharedCaller() {
        check(listOf("2026-01-01","2026-01-29","2026-02-26").map {CycleDayLog(it,flowLevel="heavy")},
            BaselineMetrics(28.0,28,28,28,0,true,3,2))
        check(listOf(CycleDayLog("2026-01-01",flowLevel="NONE"),CycleDayLog("2026-01-01",flowLevel="HEAVY"),CycleDayLog("2026-01-29",flowLevel="HEAVY"),CycleDayLog("bad",flowLevel="HEAVY")),
            BaselineMetrics(28.0,28,28,28,0,false,2,1))
        check((1..24).map {CycleDayLog("2026-01-%02d".format(it),flowLevel="HEAVY")},
            BaselineMetrics(28.0,28,28,28,0,false,24,0))
        val first=java.time.LocalDate.of(-10000000,1,1)
        check(listOf(first,first.plusDays(4294967296L+28)).map {CycleDayLog(it.toString(),flowLevel="HEAVY")},
            BaselineMetrics(28.0,28,28,28,0,false,2,1),1)
    }
    @Test fun malformedBaselineWireRefuses() {
        assertNotNull(CycleCareCoreBridge.call("baseline_full_v1",JSONObject("{\"average\":28,\"duration\":5,\"logs\":[]}")))
        for (s in listOf("{}","{\"average\":28,\"duration\":5,\"logs\":[{}]}","{\"average\":28,\"duration\":5,\"logs\":[{\"date\":\"2026-01-01\",\"bleeding\":\"true\"}]}"))
            assertNull(CycleCareCoreBridge.call("baseline_full_v1",JSONObject(s)))
    }
}
