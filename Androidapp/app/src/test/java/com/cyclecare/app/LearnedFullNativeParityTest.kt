package com.cyclecare.app

import com.cyclecare.app.data.local.CycleDayLog
import com.cyclecare.app.data.local.CycleSettings
import com.cyclecare.app.data.local.OnboardingProfile
import com.cyclecare.app.domain.learning.LearningEngine
import com.cyclecare.app.rust.CycleCareCoreBridge
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34])
class LearnedFullNativeParityTest {
    private fun label(s:String)=s.replace('_',' ').trim().replaceFirstChar {if(it.isLowerCase())it.titlecase() else it.toString()}
    private fun parity(logs:List<CycleDayLog>,s:CycleSettings=CycleSettings(),p:OnboardingProfile?=null) {
        val n=CycleCareCoreBridge.learnedObservations(s,logs,p,::label)
        assertNotNull("learned_full_v1 actual native required before caller parity",n)
        val k=LearningEngine.compileLearnedObservationsKotlin(s,logs,p)
        assertEquals(k,n);assertEquals(k,LearningEngine.compileLearnedObservations(s,logs,p))
    }
    @Test fun setupCompletionProfileNotesAndNoRecords() {
        for(done in listOf(false,true))for(pd in listOf(false,true))for(avg in listOf(Int.MIN_VALUE,28,Int.MAX_VALUE)) {
            parity(emptyList(),CycleSettings(isOnboardingCompleted=done,averageCycleLength=avg,periodDuration=Int.MIN_VALUE),
                OnboardingProfile(isCompleted=pd,typicalSymptomsCsv=" ßeta,ßeta,ǳone,𐐨case,_lower,\u00a0 \u00a0"))
        }
        parity(emptyList());parity(listOf(CycleDayLog("bad",symptomsCsv="wrong")))
    }
    @Test fun firstValidParsedDateCountsOrderingSparseAndCanonicalCoverage() {
        val logs=listOf(CycleDayLog("+0010000-01-01",symptomsCsv="z,z,a,🌸,\uE000,ǳone,ßeta"),
            CycleDayLog("+10000-01-01",symptomsCsv="wrong"),CycleDayLog("-999999999-01-01",symptomsCsv="a,b"),
            CycleDayLog("+999999999-12-31",symptomsCsv="z,b"),CycleDayLog("2026-02-29",symptomsCsv="wrong"))
        parity(logs);parity(logs.take(1));parity(logs.take(2));parity(logs.reversed())
        parity(listOf(CycleDayLog("0000-02-29",symptomsCsv="a_b,a b,\u0085x,\u001c,\u3000")))
    }
    @Test fun exactPhaseDenominatorsThresholdsTieTopThreeAndInvalidAnchor() {
        val logs=(0..39).map {CycleDayLog(java.time.LocalDate.of(2026,1,1).plusDays(it.toLong()).toString(),symptomsCsv=if(it%2==0)"z,a,🌸,\uE000" else "a,b")}
        for(anchor in listOf("2026-01-01","bad","-0000-01-01","+999999999-12-31"))
            parity(logs,CycleSettings(lastPeriodStartDate=anchor))
        for(n in 1..4) parity(logs.take(n),CycleSettings(lastPeriodStartDate="2026-01-01"))
    }
    @Test fun generatedCorpusAndLoneSurrogateFallback() {
        val r=java.util.Random(5719)
        repeat(120) {
            val logs=(0 until r.nextInt(35)).map {CycleDayLog(if(r.nextInt(10)==0)"bad" else java.time.LocalDate.of(2026,1,1).plusDays(r.nextInt(40).toLong()).toString(),symptomsCsv=listOf("a,z,a,b","🌸,\uE000,ßeta,ǳone","","a_b,a b")[r.nextInt(4)])}
            parity(logs,CycleSettings(lastPeriodStartDate="2026-01-01",averageCycleLength=r.nextInt(60),periodDuration=r.nextInt(15),lutealPhaseDays=r.nextInt(25)))
        }
        val logs=listOf(CycleDayLog("2026-01-01",symptomsCsv="\uD800"));val s=CycleSettings()
        assertNull(CycleCareCoreBridge.learnedObservations(s,logs,null,::label))
        assertEquals(LearningEngine.compileLearnedObservationsKotlin(s,logs),LearningEngine.compileLearnedObservations(s,logs))
    }
    @Test fun strictReplyAndInputFailureNoPartialResults() {
        for(s in listOf("{}","{\"ok\":null}","{\"ok\":{\"version\":\"1\",\"observations\":[]}}",
            "{\"ok\":{\"version\":1,\"observations\":[]}}","{\"ok\":{\"version\":1,\"observations\":[{}]}}"))
            assertNull(CycleCareCoreBridge.decodeLearnedObservations(JSONObject(s)))
        for(s in listOf("{}","{\"completed\":false,\"average\":28,\"duration\":5,\"luteal\":14,\"anchor\":\"\",\"setup\":[],\"logs\":[{}]}"))
            assertNull(CycleCareCoreBridge.call("learned_full_v1",JSONObject(s)))
    }
    @Test fun unavailableNativeAndStrictMalformedFullObservationReplies() {
        val state=CycleCareCoreBridge::class.java.getDeclaredField("nativeState").apply {isAccessible=true}
        val old=state.get(CycleCareCoreBridge)
        val settings=CycleSettings(lastPeriodStartDate="2026-01-01",isOnboardingCompleted=true)
        val logs=(1..5).map {CycleDayLog("2026-01-0$it",symptomsCsv="a,z")}
        try {state.set(CycleCareCoreBridge,CycleCareCoreBridge.NativeState.UNAVAILABLE)
            assertNull(CycleCareCoreBridge.learnedObservations(settings,logs,null,::label))
            assertEquals(LearningEngine.compileLearnedObservationsKotlin(settings,logs),LearningEngine.compileLearnedObservations(settings,logs))
        } finally {state.set(CycleCareCoreBridge,old)}
        fun item()=JSONObject().put("id","a").put("category","b").put("headline","c").put("description","d").put("confidence","e")
        fun reply(o:JSONObject=item())=JSONObject().put("ok",JSONObject().put("version",1).put("observations",org.json.JSONArray().put(o)))
        assertNotNull(CycleCareCoreBridge.decodeLearnedObservations(reply()))
        for(k in listOf("id","category","headline","description","confidence")) {
            for(v in listOf<Any>(JSONObject.NULL,1,true,org.json.JSONArray(),""))
                assertNull(CycleCareCoreBridge.decodeLearnedObservations(reply(item().put(k,v))))
            val o=item();o.remove(k);assertNull(CycleCareCoreBridge.decodeLearnedObservations(reply(o)))
        }
        assertNull(CycleCareCoreBridge.decodeLearnedObservations(reply(item().put("extra",0))))
        assertNull(CycleCareCoreBridge.decodeLearnedObservations(reply().put("error","bad")))
        val r=reply();r.getJSONObject("ok").put("version",1.0)
        assertNull(CycleCareCoreBridge.decodeLearnedObservations(r))
        for(n in listOf(2,12)) {
            val a=org.json.JSONArray();repeat(n) {a.put(item())}
            assertNull(CycleCareCoreBridge.decodeLearnedObservations(JSONObject().put("ok",JSONObject().put("version",1).put("observations",a))))
        }
        val valid=JSONObject().put("completed",false).put("average",28).put("duration",5).put("luteal",14).put("anchor","").put("setup",org.json.JSONArray()).put("logs",org.json.JSONArray())
        assertNotNull(CycleCareCoreBridge.call("learned_full_v1",valid))
        for(k in listOf("completed","average","duration","luteal","anchor","setup","logs")) {
            val v=JSONObject(valid.toString()).put(k,JSONObject.NULL);assertNull(CycleCareCoreBridge.call("learned_full_v1",v))
            val missing=JSONObject(valid.toString());missing.remove(k);assertNull(CycleCareCoreBridge.call("learned_full_v1",missing))
        }
        assertNull(CycleCareCoreBridge.call("learned_full_v1",JSONObject(valid.toString()).put("average",28.5)))
        assertNull(CycleCareCoreBridge.call("learned_full_v1",JSONObject(valid.toString()).put("extra",0)))
        for(a in listOf("anchor","setup")) {
            val s=if(a=="anchor") settings.copy(lastPeriodStartDate="\uD800") else settings
            val p=if(a=="setup") OnboardingProfile(typicalSymptomsCsv="\uDC00") else null
            assertNull(CycleCareCoreBridge.learnedObservations(s,logs,p,::label))
            assertEquals(LearningEngine.compileLearnedObservationsKotlin(s,logs,p),LearningEngine.compileLearnedObservations(s,logs,p))
        }
    }

}
