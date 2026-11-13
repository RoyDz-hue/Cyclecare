package com.cyclecare.app.rust

import com.cyclecare.app.data.local.*
import com.cyclecare.app.domain.ai.CareConversationPolicy as C
import com.cyclecare.app.rust.CycleCareCoreBridge as B
import java.time.LocalDate
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import androidx.test.ext.junit.runners.AndroidJUnit4

@RunWith(AndroidJUnit4::class)
class CareContextAndroidNativeTest {
    private val today=LocalDate.of(2026,11,7)
    private fun log(date:String=today.toString())=CycleDayLog(date,flowLevel="HEAVY",energyLevel="LOW",moodLevel="SAD",sleepHours=6.25f,sleepQuality="POOR",symptomsCsv="cramps,headache",painIntensity=8,notes="FORBIDDEN_NOTES",vaginalHealthCsv="FORBIDDEN_VAGINAL",urinaryHealthCsv="FORBIDDEN_URINARY",cervicalMucus="FORBIDDEN_CERVICAL",ovulationTest="FORBIDDEN_OVULATION",bbtCelsius=38f,waterGlasses=9)
    private fun parity(q:String,logs:List<CycleDayLog>,settings:CycleSettings=CycleSettings(),day:LocalDate=today) {
        val native=B.fullCareContext(q,logs,settings,day);assertNotNull("actual full care context JNI before original oracle",native)
        assertEquals(C.compileContextKotlin(q,logs,settings,day),native);assertEquals(native,C.compileContext(q,logs,settings,day))
    }
    @Test fun allTopicCombinationsAllowedFieldsAndNoPrivatePayload() {
        val qs=listOf("hello","period","energy","mood","symptom")
        for(mask in 0..31)parity(qs.filterIndexed {i,_->mask and (1 shl i)!=0}.joinToString(" "),listOf(log()),CycleSettings(lastPeriodStartDate="raw anchor",averageCycleLength=Int.MIN_VALUE))
        val payload=B.careContextPayload("energy",listOf(log()),CycleSettings(selectedGoalsCsv="FORBIDDEN_GOALS",pregnancyDueDate="FORBIDDEN_PREGNANCY"),today)!!
        assertEquals(setOf("question","anchor","length","today","logs"),payload.keys().asSequence().toSet())
        assertEquals(setOf("date","flow","energy","mood","symptoms","bits","sleep","quality","pain"),payload.getJSONArray("logs").getJSONObject(0).keys().asSequence().toSet())
        assertFalse(payload.toString().contains("FORBIDDEN"))
        val actual=B.fullCareContext("energy",listOf(log()),CycleSettings(),today)!!;assertTrue(actual.contains("energy=LOW"));assertFalse(actual.contains("flow=HEAVY"));assertFalse(actual.contains("mood=SAD"));assertFalse(actual.contains("symptoms="))
        assertFalse(actual.contains("FORBIDDEN"))
    }
    @Test fun fullJavaDatesAliasesStableStringSortTakeEightBeforeFieldsAndWindow() {
        val dates=listOf(today.toString(),today.minusDays(30).toString(),today.minusDays(31).toString(),today.plusDays(1).toString(),"bad","+0010000-01-01","+10000-01-01","-999999999-01-01","+999999999-12-31")
        for(day in listOf(today,LocalDate.of(10000,1,15),LocalDate.MAX)) {val logs=dates.map(::log);parity("period energy mood symptoms",logs,day=day);parity("period energy mood symptoms",logs.reversed(),day=day)}
        parity("pain",(0..10).map {log(today.minusDays(it.toLong()).toString()).copy(symptomsCsv=if(it<8)"" else "excluded",painIntensity=0)})
        parity("energy",listOf(log(),log().copy(energyLevel="SECOND")))
        parity("hello",emptyList(),day=LocalDate.MIN)
        val logs=listOf(log(LocalDate.MIN.toString()));assertNull(B.fullCareContext("energy",logs,CycleSettings(),LocalDate.MIN))
        try {C.compileContext("energy",logs,CycleSettings(),LocalDate.MIN);fail("original MIN minus30 throws")}catch(_:java.time.DateTimeException){}
        val r=java.util.Random(189061);repeat(200){val logs=(0..r.nextInt(20)).map {log(today.plusDays((r.nextInt(100)-60).toLong()).toString()).copy(sleepHours=Float.fromBits(r.nextInt()),energyLevel=if(r.nextBoolean())"" else "LOW")};parity("period sleep mood pain",logs)}
    }
    @Test fun exactFloatTextBlankPrimitiveUtf16TruncationAndWholeFallback() {
        for(f in listOf(Float.NaN,Float.POSITIVE_INFINITY,Float.NEGATIVE_INFINITY,-0f,Float.MIN_VALUE,Float.MAX_VALUE,6.8f))parity("sleep",listOf(log().copy(sleepHours=f)))
        for(blank in listOf(""," ","\u001c","\u0085","\u00a0","\u2007","\u202f"))parity("period energy mood symptom",listOf(log().copy(flowLevel=blank,energyLevel=blank,moodLevel=blank,symptomsCsv=blank,sleepQuality=blank)),CycleSettings(lastPeriodStartDate=blank))
        parity("SYMPTOM energy period",listOf(log().copy(symptomsCsv="a".repeat(178)+"🌸")),CycleSettings(lastPeriodStartDate="x".repeat(3000)))
        // symptoms.take180 cuts a surrogate pair, so whole original fallback is required.
        val cut=listOf(log().copy(symptomsCsv="a".repeat(179)+"🌸"));assertNull(B.fullCareContext("symptom",cut,CycleSettings(),today));assertEquals(C.compileContextKotlin("symptom",cut,CycleSettings(),today),C.compileContext("symptom",cut,CycleSettings(),today))
        parity("period",emptyList(),CycleSettings(lastPeriodStartDate="🌸".repeat(1300)))
        val state=B::class.java.getDeclaredField("nativeState").apply {isAccessible=true};val old=state.get(B)
        try {state.set(B,B.NativeState.UNAVAILABLE);assertNull(B.fullCareContext("energy",listOf(log()),CycleSettings(),today));assertEquals(C.compileContextKotlin("energy",listOf(log()),CycleSettings(),today),C.compileContext("energy",listOf(log()),CycleSettings(),today))}finally{state.set(B,old)}
    }
    @Test fun strictInputOutputSchemaNoPartialOrDefaultReply() {
        val payload=B.careContextPayload("energy",listOf(log()),CycleSettings(),today)!!;val good=B.call("care_context_full_v1",payload)!!;assertNotNull(B.decodeFullCareContext(good))
        for(k in payload.keys().asSequence().toList()){val x=JSONObject(payload.toString());x.remove(k);assertNull(B.call("care_context_full_v1",x))}
        for(k in payload.getJSONArray("logs").getJSONObject(0).keys().asSequence().toList()){val x=JSONObject(payload.toString());x.getJSONArray("logs").getJSONObject(0).remove(k);assertNull(B.call("care_context_full_v1",x))}
        for(v in listOf<Any>("1",1.5,true,2)){val x=JSONObject(good.toString());x.getJSONObject("ok").put("version",v);assertNull(B.decodeFullCareContext(x))}
        for(v in listOf<Any>("",true,JSONObject.NULL,1)){val x=JSONObject(good.toString());x.getJSONObject("ok").put("text",v);assertNull(B.decodeFullCareContext(x))}
        for(k in good.getJSONObject("ok").keys().asSequence().toList()){val x=JSONObject(good.toString());x.getJSONObject("ok").remove(k);assertNull(B.decodeFullCareContext(x))}
        val x=JSONObject(good.toString());x.getJSONObject("ok").put("extra",0);assertNull(B.decodeFullCareContext(x))
    }
}
