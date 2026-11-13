package com.cyclecare.app.rust

import com.cyclecare.app.domain.health.PeriodDateConsistency as P
import com.cyclecare.app.domain.health.PeriodEpisodeEntry as E
import com.cyclecare.app.rust.CycleCareCoreBridge as B
import java.time.LocalDate
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import androidx.test.ext.junit.runners.AndroidJUnit4

@RunWith(AndroidJUnit4::class)
class PeriodEpisodeAndroidNativeTest {
    private fun parity(start:LocalDate?,end:LocalDate?,today:LocalDate,ongoing:Boolean,typical:Int) {
        val native=B.fullPeriodEpisode(start,end,today,ongoing,typical);assertNotNull("actual period episode JNI before independent complete Kotlin oracles",native)
        assertEquals(P.validatePairKotlin(start,end,today,ongoing),native!!.check)
        assertEquals(E.decideKotlin(start,end,ongoing,typical,today),native.storage)
        assertEquals(native.check,P.validatePair(start,end,today,ongoing));assertEquals(native.storage,E.decide(start,end,ongoing,typical,today))
    }
    @Test fun exactChronologyPriorityThresholdGuidanceAndRawEndStorage() {
        val t=LocalDate.of(2026,11,7)
        for(s in listOf(null,t.minusDays(7),t,t.plusDays(1)))for(e in listOf(null,t.minusDays(8),t,t.plusDays(1)))for(o in listOf(false,true))for(typical in listOf(Int.MIN_VALUE,-1,0,7,Int.MAX_VALUE))parity(s,e,t,o,typical)
        for(days in listOf(1L,6,7,8,10,60,Int.MAX_VALUE.toLong(),2147483648L,4294967296L,4294967304L))for(o in listOf(false,true))parity(t.minusDays(days-1),t,t,o,-7)
        val d=B.fullPeriodEpisode(t.minusDays(59),t,t,false,5)!!;assertEquals(t,d.storage.rawEndDate);assertEquals(5,d.storage.typicalDurationDays);assertTrue(d.storage.requiresConfirmation)
    }
    @Test fun fullLocalDateDomainIntNarrowingAndDeterministicCorpus() {
        val dates=listOf(LocalDate.MIN,LocalDate.MIN.plusDays(1),LocalDate.of(-1,1,1),LocalDate.of(0,2,29),LocalDate.of(10000,1,1),LocalDate.MAX.minusDays(1),LocalDate.MAX)
        for(s in dates)for(e in dates)for(t in dates)for(o in listOf(false,true))parity(s,e,t,o,Int.MIN_VALUE)
        val r=java.util.Random(770238);val lo=LocalDate.MIN.toEpochDay();val span=LocalDate.MAX.toEpochDay()-lo+1
        fun day()=LocalDate.ofEpochDay(lo+Math.floorMod(r.nextLong(),span))
        repeat(250) {val s=day();val e=day();parity(s,e,day(),r.nextBoolean(),r.nextInt())}
    }
    @Test fun strictVersionTypesSchemaCoherenceAndWholeUnavailableFallback() {
        val t=LocalDate.of(2026,11,7)
        fun input()=JSONObject().put("start",t.minusDays(10).toEpochDay()).put("end",t.toEpochDay()).put("today",t.toEpochDay()).put("ongoing",false).put("typical",5)
        val good=B.call("period_episode_full_v1",input())!!;assertNotNull(B.decodeFullPeriodEpisode(good,t,5))
        for(k in input().keys().asSequence().toList()){val p=input();p.remove(k);assertNull(B.call("period_episode_full_v1",p))}
        for(k in listOf("start","end","today"))for(v in listOf<Any>("1",1.5,true,Long.MIN_VALUE,Long.MAX_VALUE)){val p=input();p.put(k,v);assertNull(B.call("period_episode_full_v1",p))}
        for(v in listOf<Any>("5",1.5,true,Long.MAX_VALUE)){val p=input();p.put("typical",v);assertNull(B.call("period_episode_full_v1",p))}
        for(k in good.getJSONObject("ok").keys().asSequence().toList()){val p=JSONObject(good.toString());p.getJSONObject("ok").remove(k);assertNull(B.decodeFullPeriodEpisode(p,t,5))}
        for(k in listOf("version","duration","typical"))for(v in listOf<Any>("1",1.5,true,JSONObject.NULL)){val p=JSONObject(good.toString());p.getJSONObject("ok").put(k,v);assertNull(B.decodeFullPeriodEpisode(p,t,5))}
        for(k in listOf("valid","confirm","keepEnd"))for(v in listOf<Any>("true",1,JSONObject.NULL)){val p=JSONObject(good.toString());p.getJSONObject("ok").put(k,v);assertNull(B.decodeFullPeriodEpisode(p,t,5))}
        for(k in listOf("error","warning"))for(v in listOf<Any>(true,1)){val p=JSONObject(good.toString());p.getJSONObject("ok").put(k,v);assertNull(B.decodeFullPeriodEpisode(p,t,5))}
        val extra=JSONObject(good.toString());extra.getJSONObject("ok").put("extra",0);assertNull(B.decodeFullPeriodEpisode(extra,t,5))
        val wrong=JSONObject(good.toString());wrong.getJSONObject("ok").put("keepEnd",false);assertNull(B.decodeFullPeriodEpisode(wrong,t,5))
        val f=B::class.java.getDeclaredField("nativeState").apply{isAccessible=true};val old=f.get(B)
        try {f.set(B,B.NativeState.UNAVAILABLE);assertNull(B.fullPeriodEpisode(t,t,t,false,5));assertEquals(P.validatePairKotlin(t,t,t,false),P.validatePair(t,t,t,false));assertEquals(E.decideKotlin(t,t,false,5,t),E.decide(t,t,false,5,t))}finally{f.set(B,old)}
    }
}
