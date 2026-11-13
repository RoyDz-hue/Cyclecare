package com.cyclecare.app

import com.cyclecare.app.data.local.CycleDayLog
import com.cyclecare.app.data.local.CycleSettings
import com.cyclecare.app.domain.pattern.SomethingChangedEngine
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
class DeviationFullNativeParityTest {
    private val today=LocalDate.of(2026,11,7)
    private fun parity(logs:List<CycleDayLog>,day:LocalDate=today) {
        val n=CycleCareCoreBridge.fullDeviations(logs,day);assertNotNull("actual deviation JNI before oracle",n)
        assertEquals(SomethingChangedEngine().detectDeviationsKotlin(CycleSettings(),logs,day),n)
    }
    private fun corpus(h:Int,r:Int,hpain:Int=2,rpain:Int=6,sym:String="z,a,ßeta,ǳone")=(0 until h+r).map {i ->
        CycleDayLog(today.minusDays(if(i<h)100L+i else (i-h).toLong()).toString(),painIntensity=if(i<h)hpain else rpain,symptomsCsv=if(i<h)sym else "$sym,$sym,$sym")}
    @Test fun minimumDistinctDatesRecent90FutureAndNoHistory() {
        parity(emptyList());parity(listOf(CycleDayLog("bad")));parity(corpus(7,6));parity(corpus(7,7));parity(corpus(14,0));parity(corpus(0,14))
        val logs=corpus(7,7);parity(logs+logs);parity(logs.reversed())
        parity((0..15).map {CycleDayLog(today.minusDays(90L+it).toString(),painIntensity=if(it==0)8 else 2,symptomsCsv="a")})
        parity(corpus(7,7).mapIndexed {i,l ->if(i>6)l.copy(dateString=today.plusDays(i.toLong()).toString()) else l})
    }
    @Test fun exactPainThresholdsDuplicateSymptomRatesAndStableSeverityOrder() {
        for(hp in listOf(-1,0,1,2,Int.MAX_VALUE))for(rp in listOf(-1,0,3,4,5,6,Int.MAX_VALUE))parity(corpus(7,7,hp,rp))
        for(count in listOf(1,2,3,4)) {
            val logs=(0..19).map {i->CycleDayLog(today.minusDays(if(i<10)100L+i else (i-10).toLong()).toString(),painIntensity=if(i<10)2 else 6,symptomsCsv=if(i<10)"z,a,b" else List(count){"z,a,b"}.joinToString(","))}
            parity(logs);parity(logs.reversed())
        }
        parity(corpus(20,20,sym="_,a_b,a b,🌸,\uE000"))
    }
    @Test fun fullDateAliasFirstWinsAndGeneratedBitsExact() {
        val aliases=corpus(7,7)+listOf(CycleDayLog("+0010000-01-01",symptomsCsv="z,z",painIntensity=5),CycleDayLog("+10000-01-01",symptomsCsv="wrong",painIntensity=Int.MAX_VALUE),CycleDayLog("-999999999-01-01",symptomsCsv="a,z"),CycleDayLog("+999999999-12-31",symptomsCsv="a,z"))
        for(day in listOf(today,LocalDate.MIN,LocalDate.MAX)){parity(aliases,day);parity(aliases.reversed(),day)}
        val r=java.util.Random(672810)
        repeat(170){parity((0 until 14+r.nextInt(70)).map {CycleDayLog(if(r.nextInt(15)==0)"bad" else today.plusDays((r.nextInt(400)-200).toLong()).toString(),symptomsCsv=listOf("a,z,a","z,z,z,b", "ßeta,ǳone", "", "🌸,\uE000")[r.nextInt(5)],painIntensity=r.nextInt(20)-3)})}
        val day=LocalDate.now();val logs=(0..15).map {CycleDayLog(day.minusDays(if(it<8)100L+it else it.toLong()).toString(),painIntensity=if(it<8)2 else 7,symptomsCsv=if(it<8)"a" else "a,a,a")}
        assertEquals(SomethingChangedEngine().detectDeviationsKotlin(CycleSettings(),logs,day),SomethingChangedEngine().detectDeviations(CycleSettings(),logs))
    }
    @Test fun strictWireBitsAndWholeNativeUnavailableFallback() {
        for(s in listOf("{}","{\"ok\":null}","{\"ok\":{\"version\":1.0,\"entries\":[]}}","{\"ok\":{\"version\":1,\"entries\":[{}]}}"))assertNull(CycleCareCoreBridge.decodeFullDeviations(JSONObject(s)))
        for(s in listOf("{}","{\"today\":\"bad\",\"logs\":[]}","{\"today\":\"2026-01-01\",\"logs\":[{}]}"))assertNull(CycleCareCoreBridge.call("deviation_full_v1",JSONObject(s)))
        fun item()=JSONObject().put("factorKey","a").put("title","A").put("baselineBits","3ff0000000000000").put("recentBits","4000000000000000").put("deltaBits","4059000000000000").put("severity","STRONG").put("message","M")
        fun reply(o:JSONObject)=JSONObject().put("ok",JSONObject().put("version",1).put("entries",org.json.JSONArray().put(o)))
        assertNotNull(CycleCareCoreBridge.decodeFullDeviations(reply(item())))
        for(k in listOf("baselineBits","recentBits","deltaBits"))for(bits in listOf("bad","7ff8000000000000","7ff0000000000000","bff0000000000000","3FF0000000000000"))assertNull(CycleCareCoreBridge.decodeFullDeviations(reply(item().put(k,bits))))
        for(k in listOf("factorKey","title","message","severity"))for(v in listOf<Any>(1,true,JSONObject.NULL,""))assertNull(CycleCareCoreBridge.decodeFullDeviations(reply(item().put(k,v))))
        assertNull(CycleCareCoreBridge.decodeFullDeviations(reply(item().put("severity","strong"))))
        assertNull(CycleCareCoreBridge.decodeFullDeviations(reply(item().put("extra",0))))
        for(k in item().keys().asSequence().toList()) {val o=item();o.remove(k);assertNull(CycleCareCoreBridge.decodeFullDeviations(reply(o)))}
        val state=CycleCareCoreBridge::class.java.getDeclaredField("nativeState").apply {isAccessible=true};val old=state.get(CycleCareCoreBridge)
        val logs=corpus(7,7);val day=LocalDate.now()
        try {state.set(CycleCareCoreBridge,CycleCareCoreBridge.NativeState.UNAVAILABLE);assertNull(CycleCareCoreBridge.fullDeviations(logs,day));assertEquals(SomethingChangedEngine().detectDeviationsKotlin(CycleSettings(),logs,day),SomethingChangedEngine().detectDeviations(CycleSettings(),logs))}finally{state.set(CycleCareCoreBridge,old)}
        val unsupported=listOf(CycleDayLog("2026-01-01",symptomsCsv="\uD800"));assertNull(CycleCareCoreBridge.fullDeviations(unsupported,today));assertEquals(SomethingChangedEngine().detectDeviationsKotlin(CycleSettings(),unsupported,day),SomethingChangedEngine().detectDeviations(CycleSettings(),unsupported))
    }
}
