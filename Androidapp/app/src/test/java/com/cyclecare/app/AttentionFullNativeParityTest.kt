package com.cyclecare.app

import com.cyclecare.app.data.local.*
import com.cyclecare.app.data.model.*
import com.cyclecare.app.domain.attention.AttentionEngine
import com.cyclecare.app.domain.pattern.SomethingChangedEngine
import com.cyclecare.app.rust.CycleCareCoreBridge as B
import java.time.LocalDate
import java.util.Locale
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34])
class AttentionFullNativeParityTest {
    private val today=LocalDate.now()
    private val safe=SafetyEvaluation(SafetyLevel.LOW,"t","m","a")
    private fun pred(day:Int=25,period:Boolean=false,fertile:Boolean=false)=DayPrediction(today,day,CyclePhase.MENSTRUAL,period,fertile,false,"Calendar Estimate","Low")
    private fun log(date:String=today.toString(),hours:Float=6f,quality:String="POOR")=CycleDayLog(date,sleepHours=hours,sleepQuality=quality)
    private fun evidence(logs:List<CycleDayLog>,average:Int=28,day:Int=25) {
        val actual=B.prepareFullAttention(average,day,logs);assertNotNull("actual prepare JNI before independent expression",actual)
        val filtered=logs.sortedByDescending {it.dateString}.take(3).filter {it.sleepHours>0 && (kotlin.math.abs(it.sleepHours-7.5f)>0.01f || it.sleepQuality.uppercase() !in setOf("","GOOD"))}
        val expected=filtered.takeIf {it.isNotEmpty()}?.map {it.sleepHours}?.average()
        assertEquals((average-day).coerceAtLeast(0),actual!!.days)
        assertEquals(expected?.toRawBits(),actual.sleep?.toRawBits())
    }
    private fun parity(p:DayPrediction?,logs:List<CycleDayLog>,settings:CycleSettings=CycleSettings()) {
        assertEquals(AttentionEngine.evaluateTodayPriority(p,safe,settings,logs),AttentionEngine.evaluateTodayPriorityNonsafetyNativeFirst(p,safe,settings,logs))
    }
    @Test fun exactFloatBitsOrderedSumStableUtf16TakeThreeAndWrapping() {
        val floats=listOf(0f,-0f,-1f,Float.MIN_VALUE,Float.MAX_VALUE,7.49f,7.4900002f,7.5f,7.51f,6.8f)
        for(f in floats)for(q in listOf("","GOOD","good","ı","poor"))evidence(listOf(log(hours=f,quality=q)))
        val r=java.util.Random(760341)
        repeat(200) {
            val logs=(0..r.nextInt(14)).map { log(listOf("z","a","a","🌸","\uE000","+10000-01-01","bad")[r.nextInt(7)],Float.fromBits(r.nextInt().and(0x7f7fffff)),listOf("good","","poor","i")[r.nextInt(4)]) }
            evidence(logs);evidence(logs.reversed(),Int.MIN_VALUE,Int.MAX_VALUE)
        }
        evidence(listOf(log("z",7.5f,"good"),log("y",6f),log("x",6f),log("w",1f)))
    }
    @Test fun sixCompleteOutputsDirectNativeBeforeWholeSharedCaller() {
        val deviations=(0..19).map {i->CycleDayLog(today.minusDays(if(i<10)100L+i else (i-10).toLong()).toString(),painIntensity=if(i<10)2 else 6)}
        val notable=(0..19).map {i->CycleDayLog(today.minusDays(if(i<10)100L+i else (i-10).toLong()).toString(),symptomsCsv=if((i<10 && i<3)||(i>=10 && i<15))"cramps" else "")}
        val cases=listOf(pred() to deviations,pred() to notable,pred() to listOf(log()),pred(12,fertile=true) to emptyList(),pred(1,period=true) to emptyList(),pred(20) to listOf(log(today.minusDays(5).toString(),0f)))
        val ids=listOf("baseline_shift_strong","baseline_shift_notable","period_sleep_taper","fertile_window_notice","phase_menstrual_start","wellbeing_checkin")
        for((i,pair) in cases.withIndex()) {
            val (p,logs)=pair;val settings=CycleSettings(averageCycleLength=28)
            val ds=SomethingChangedEngine().detectDeviations(settings,logs)
            val e=B.prepareFullAttention(28,p.dayOfCycle,logs);assertNotNull(e)
            val c=B.chooseFullAttention(false,ds,true,p.dayOfCycle,p.isPeriod,p.isFertile,e!!.days,e.sleep,i==5,if(i==5)5 else null);assertNotNull(c)
            val d=c!!.deviationIndex?.let {ds[it]}
            val actual=B.renderFullAttention(c)
            assertNotNull("direct complete output JNI",actual);assertEquals(ids[i],actual!!.id)
            assertEquals(AttentionEngine.evaluateTodayPriority(p,safe,settings,logs),actual);parity(p,logs,settings)
        }
    }
    @Test fun localeCopyThresholdNullAndFallbackParity() {
        val previous=Locale.getDefault()
        try {for(locale in listOf(Locale.US,Locale.GERMANY,Locale.forLanguageTag("tr-TR"),Locale.forLanguageTag("ar-EG"))) {Locale.setDefault(locale)
            for(f in listOf(6.25f,6.8f,7.5f,0f,Float.NaN,Float.POSITIVE_INFINITY,Float.NEGATIVE_INFINITY))for(day in listOf(1,24,25,27,28,Int.MIN_VALUE))parity(pred(day),listOf(log(hours=f)))
            parity(null,emptyList());parity(pred(12,fertile=true),listOf(log("\uD800")));parity(pred(),listOf(log(quality="\uD800")))
        }}finally{Locale.setDefault(previous)}
        val state=B::class.java.getDeclaredField("nativeState").apply {isAccessible=true};val old=state.get(B)
        try {state.set(B,B.NativeState.UNAVAILABLE);assertNull(B.prepareFullAttention(28,25,listOf(log())));parity(pred(),listOf(log()))}finally{state.set(B,old)}
    }
    @Test fun immediateSafetyLateClockAndUnmintedChoiceRefused() {
        val urgent=SafetyEvaluation(SafetyLevel.URGENT,"t","m","a")
        assertEquals("safety_priority",AttentionEngine.evaluateTodayPriorityNonsafetyNativeFirst(pred(),urgent,CycleSettings(),listOf(log())) {error("safety must not read clock") }!!.id)
        assertEquals("period_sleep_taper",AttentionEngine.evaluateTodayPriorityNonsafetyNativeFirst(pred(),safe,CycleSettings(),listOf(log())) {error("earlier branch must not read clock") }!!.id)
        var reads=0
        AttentionEngine.evaluateTodayPriorityNonsafetyNativeFirst(pred(2,true),safe,CycleSettings(),listOf(log(hours=0f))) {reads++;today};assertEquals(1,reads)
        AttentionEngine.evaluateTodayPriorityNonsafetyNativeFirst(pred(20),safe,CycleSettings(),listOf(log("invalid",0f))) {error("invalid dates must not read clock")}
        val fabricated=object:B.FullAttentionChoice {override val branch=B.NonsafetyBranch.PERIOD_SLEEP_TAPER;override val deviationIndex:Int?=null}
        assertNull(B.renderFullAttention(fabricated))
    }
    @Test fun strictWireSchemaTypesVersionAndOutputBinding() {
        for(text in listOf("{}","{\"ok\":null}","{\"ok\":{\"version\":1.5,\"daysUntilPeriod\":1,\"sleepBits\":null}}","{\"ok\":{\"version\":1,\"daysUntilPeriod\":-1,\"sleepBits\":null}}"))assertNull(B.decodeFullAttentionEvidence(JSONObject(text)))
        fun evidence(bits:Any)=JSONObject().put("ok",JSONObject().put("version",1).put("daysUntilPeriod",3).put("sleepBits",bits))
        for(bits in listOf<Any>(true,0,"bad","7ff0000000000000","7ff8000000000000","bff0000000000000","0000000000000000","3FF0000000000000"))assertNull(B.decodeFullAttentionEvidence(evidence(bits)))
        assertNotNull(B.decodeFullAttentionEvidence(evidence("4018000000000000")))
        val choice=B.chooseFullAttention(false,emptyList(),true,12,false,true,16,null,false,null)!!
        val reply=B.call("attention_full_render_v1",JSONObject().put("branch","fertile_window_notice").put("title","").put("message","").put("until",16).put("day",12).put("sleep",""))!!
        assertNotNull(B.decodeFullAttentionItem(reply,"fertile_window_notice"));assertNull(B.decodeFullAttentionItem(reply,"period_sleep_taper"))
        for(k in reply.getJSONObject("ok").getJSONObject("item").keys().asSequence().toList()) {
            val copy=JSONObject(reply.toString());copy.getJSONObject("ok").getJSONObject("item").remove(k);assertNull(B.decodeFullAttentionItem(copy,"fertile_window_notice"))
        }
        for(value in listOf<Any>("false",0,true,JSONObject.NULL)) {val copy=JSONObject(reply.toString());copy.getJSONObject("ok").getJSONObject("item").put("isUrgent",value);assertNull(B.decodeFullAttentionItem(copy,"fertile_window_notice"))}
        for(contract in listOf("attention_full_prepare_v1","attention_full_render_v1"))assertNull(B.call(contract,JSONObject()))
        assertNull(B.prepareFullAttention(28,25,listOf(log(hours=Float.NaN))));assertNotNull(B.renderFullAttention(choice))
    }
}
