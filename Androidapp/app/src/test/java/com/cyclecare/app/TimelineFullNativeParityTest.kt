package com.cyclecare.app

import com.cyclecare.app.data.local.CycleDayLog
import com.cyclecare.app.data.local.CycleSettings
import com.cyclecare.app.domain.learning.LearningEngine
import com.cyclecare.app.rust.CycleCareCoreBridge
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34])
class TimelineFullNativeParityTest {
    private fun label(s:String)=s.replace('_',' ').trim().replaceFirstChar {if(it.isLowerCase())it.titlecase() else it.toString()}
    private fun parity(logs:List<CycleDayLog>) {
        val n=CycleCareCoreBridge.fullTimeline(logs,::label);assertNotNull("actual full timeline JNI before caller",n)
        val k=LearningEngine.generateMonthlyTimelineKotlin(CycleSettings(),logs)
        assertEquals(k,n);assertEquals(k,LearningEngine.generateMonthlyTimeline(CycleSettings(),logs))
    }
    @Test fun emptyInvalidFirstDateAliasesAndMonthReverseOrder() {
        parity(emptyList());parity(listOf(CycleDayLog("bad")))
        val logs=listOf(CycleDayLog("+0010000-01-01",symptomsCsv="a",flowLevel="HEAVY"),CycleDayLog("+10000-01-01",symptomsCsv="wrong",flowLevel="NONE"),CycleDayLog("-999999999-01-01",symptomsCsv="z"),CycleDayLog("+999999999-12-31",symptomsCsv="a"),CycleDayLog("0000-02-29",symptomsCsv="x"),CycleDayLog("2026-02-29",symptomsCsv="wrong"))
        parity(logs);parity(logs.reversed())
    }
    @Test fun exactLocaleFormattingEnergyTiesSymptomTiesAndBleeding() {
        val old=Locale.getDefault()
        try {for(locale in listOf(Locale.US,Locale.FRANCE,Locale("tr","TR"),Locale("ar","EG"),Locale.JAPAN)) {
            Locale.setDefault(locale)
            val logs=listOf(CycleDayLog("2026-01-03",symptomsCsv="b,a,b",energyLevel="tired",flowLevel="heavy"),CycleDayLog("2026-01-01",symptomsCsv="a,b",energyLevel="normal",flowLevel="LIGHT"),CycleDayLog("2026-01-02",symptomsCsv="z",energyLevel="low",flowLevel="UNKNOWN"),CycleDayLog("2026-02-01",symptomsCsv="ßeta,ǳone,🌸,\uE000",energyLevel="ıi",flowLevel="unusually_heavy"))
            parity(logs);parity(logs.reversed())
        }}finally {Locale.setDefault(old)}
    }
    @Test fun generatedCorpusAndWholeSurrogateFallback() {
        val r=java.util.Random(823)
        repeat(120) {parity((0 until r.nextInt(45)).map {CycleDayLog(if(r.nextInt(10)==0)"bad" else java.time.LocalDate.of(2026,1,1).plusDays(r.nextInt(120).toLong()).toString(),symptomsCsv=listOf("a,z,a","a_b,a b","", "ßeta,ǳone")[r.nextInt(4)],energyLevel=listOf("NORMAL","low","tired", "UNKNOWN","")[r.nextInt(5)],flowLevel=listOf("HEAVY","heavy","LIGHT","NONE","")[r.nextInt(5)])})}
        val logs=listOf(CycleDayLog("2026-01-01",symptomsCsv="\uD800"))
        assertNull(CycleCareCoreBridge.fullTimeline(logs,::label));assertEquals(LearningEngine.generateMonthlyTimelineKotlin(CycleSettings(),logs),LearningEngine.generateMonthlyTimeline(CycleSettings(),logs))
    }
    @Test fun strictVersionTypesFieldsAndUnavailableFallback() {
        for(s in listOf("{}","{\"ok\":null}","{\"ok\":{\"version\":1.0,\"entries\":[]}}","{\"ok\":{\"version\":1,\"entries\":[{}]}}","{\"ok\":{\"version\":1,\"entries\":[],\"extra\":0}}"))assertNull(CycleCareCoreBridge.decodeFullTimeline(JSONObject(s),5))
        assertNull(CycleCareCoreBridge.call("timeline_full_v1",JSONObject("{\"logs\":[{}]}")))
        fun item()=JSONObject().put("monthTitle","January 2026").put("cycleLengthDays",0).put("periodBleedingDays",1).put("dominantEnergy","Low").put("dominantSymptom","A").put("patternNoticed",JSONObject.NULL)
        fun reply(o:JSONObject)=JSONObject().put("ok",JSONObject().put("version",1).put("entries",org.json.JSONArray().put(o)))
        assertNotNull(CycleCareCoreBridge.decodeFullTimeline(reply(item()),5))
        for(k in listOf("monthTitle","dominantEnergy","dominantSymptom"))for(v in listOf<Any>(1,true,JSONObject.NULL,""))assertNull(CycleCareCoreBridge.decodeFullTimeline(reply(item().put(k,v)),5))
        for(k in listOf("cycleLengthDays","periodBleedingDays"))for(v in listOf<Any>("0",1.0,true,JSONObject.NULL,-1))assertNull(CycleCareCoreBridge.decodeFullTimeline(reply(item().put(k,v)),5))
        assertNull(CycleCareCoreBridge.decodeFullTimeline(reply(item().put("cycleLengthDays",1)),5))
        assertNull(CycleCareCoreBridge.decodeFullTimeline(reply(item().put("periodBleedingDays",6)),5))
        assertNull(CycleCareCoreBridge.decodeFullTimeline(reply(item().put("patternNoticed","guessed")),5))
        assertNull(CycleCareCoreBridge.decodeFullTimeline(reply(item().put("extra",0)),5))
        for(k in item().keys().asSequence().toList()) {val o=item();o.remove(k);assertNull(CycleCareCoreBridge.decodeFullTimeline(reply(o),5))}
        assertNull(CycleCareCoreBridge.decodeFullTimeline(reply(item()),0))
        val input=JSONObject().put("logs",org.json.JSONArray().put(JSONObject().put("date","2026-01-01").put("month_title","January 2026").put("energy",JSONObject().put("key","LOW").put("label","LOW")).put("bleeding",true).put("symptoms",org.json.JSONArray())))
        assertNotNull(CycleCareCoreBridge.call("timeline_full_v1",input))
        for(k in listOf("date","month_title","energy","bleeding","symptoms")) {val v=JSONObject(input.toString());v.getJSONArray("logs").getJSONObject(0).remove(k);assertNull(CycleCareCoreBridge.call("timeline_full_v1",v))}
        assertNull(CycleCareCoreBridge.call("timeline_full_v1",JSONObject(input.toString()).put("extra",0)))
        val state=CycleCareCoreBridge::class.java.getDeclaredField("nativeState").apply {isAccessible=true};val old=state.get(CycleCareCoreBridge);val logs=listOf(CycleDayLog("2026-01-01",symptomsCsv="x"))
        try {state.set(CycleCareCoreBridge,CycleCareCoreBridge.NativeState.UNAVAILABLE);assertNull(CycleCareCoreBridge.fullTimeline(logs,::label));assertEquals(LearningEngine.generateMonthlyTimelineKotlin(CycleSettings(),logs),LearningEngine.generateMonthlyTimeline(CycleSettings(),logs))}finally{state.set(CycleCareCoreBridge,old)}
    }
}
