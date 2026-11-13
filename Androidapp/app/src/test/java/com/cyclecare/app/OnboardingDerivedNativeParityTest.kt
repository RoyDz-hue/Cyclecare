package com.cyclecare.app
import com.cyclecare.app.ui.viewmodel.OnboardingDerived as D
import com.cyclecare.app.ui.viewmodel.OnboardingUiState as S
import com.cyclecare.app.ui.viewmodel.OnboardingViewModel
import com.cyclecare.app.rust.CycleCareCoreBridge as B
import kotlinx.coroutines.flow.MutableStateFlow
import java.time.LocalDate
import org.json.JSONObject
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34])
class OnboardingDerivedNativeParityTest {
    private fun direct(op:String,values:Map<String,Any?>):Pair<Any?,List<String>> {
        val evidence=JSONObject();val trace=mutableListOf<String>()
        repeat(10){val reply=B.call("onboarding_derived_full_v1",JSONObject().put("op",op).put("evidence",evidence));assertNotNull("actual staged derived JNI",reply);val decoded=D.decode(reply,op);assertNotNull(decoded)
            when(decoded){is D.Result.Need->{trace+=decoded.key;assertTrue(values.containsKey(decoded.key));val v=values[decoded.key];evidence.put(decoded.key,if(v is List<*>)JSONArray(v)else v ?: JSONObject.NULL)};is D.Result.Value->return decoded.value to trace;D.Result.Arithmetic->throw ArithmeticException("/ by zero");null->error("decode")}}
        error("unbounded requests")
    }
    private fun parity(op:String,values:Map<String,Any?>) {
        val trace=mutableListOf<String>();val oracle=runCatching{D.oracle(op){trace+=it;values.getValue(it)}};val native=runCatching{direct(op,values)};val actualTrace=mutableListOf<String>();val actual=runCatching{D.evaluate(op){actualTrace+=it;values.getValue(it)}}
        if(oracle.isFailure){assertTrue(native.isFailure);assertTrue(actual.isFailure);assertEquals(oracle.exceptionOrNull()!!.javaClass,native.exceptionOrNull()!!.javaClass);assertEquals(oracle.exceptionOrNull()!!.javaClass,actual.exceptionOrNull()!!.javaClass)}else{assertEquals(oracle.getOrThrow(),native.getOrThrow().first);assertEquals(trace.distinct(),native.getOrThrow().second);assertEquals(oracle.getOrThrow(),actual.getOrThrow())}
        assertEquals(trace.distinct(),actualTrace)
    }
    @Test fun stagedShortCircuitArithmeticIntWrapPhaseCopyAndChangingDayEvidence() {
        for(a in listOf(false,true))for(b in listOf(false,true))for(c in listOf(false,true)){
            parity("privacy",mapOf("age" to a,"terms" to b));parity("answered",mapOf("exact" to a,"chipPresent" to b));parity("estimate",mapOf("exact" to a,"chipPresent" to b,"unknown" to c));parity("choice",mapOf("exact" to a,"estimate" to b,"chip" to "🌸"));parity("active",mapOf("estimate" to a,"ongoing" to b,"day" to 9,"duration" to -3));parity("confirmed",mapOf("ongoing" to a,"confirmed" to b,"exact" to c,"end" to true,"ordered" to true))
        }
        val ints=listOf(Int.MIN_VALUE,-28,-1,0,1,7,28,Int.MAX_VALUE)
        for(days in ints)for(cycle in ints){parity("day",mapOf("estimate" to true,"days" to days,"cycle" to cycle));parity("day",mapOf("estimate" to false,"days" to days,"cycle" to cycle))}
        for(cycle in ints)for(first in ints)for(second in ints)parity("phase",mapOf("estimate" to true,"active" to false,"day1" to first,"day2" to second,"cycle" to cycle))
        parity("phase",mapOf("estimate" to false));parity("phase",mapOf("estimate" to true,"active" to true))
        for(span in listOf(Long.MIN_VALUE,Long.MAX_VALUE,-1L,0L,2147483648L,4294967296L,4294967304L))parity("days",mapOf("span" to span))
        for(duration in ints)parity("endOffset",mapOf("duration" to duration))
        for(chip in listOf(null,"Today","A few days ago","About a week ago","About two weeks ago","I don’t know","It varies / I don’t know","","🌸"))parity("offset",mapOf("chip" to chip))
        for(flags in listOf(emptyList(),listOf(false),listOf(true,false,true)))parity("any",mapOf("flags" to flags))
    }
    private fun same(a:()->Any?,b:()->Any?) {val x=runCatching(a);val y=runCatching(b);if(x.isFailure){assertTrue(y.isFailure);assertEquals(x.exceptionOrNull()!!.javaClass,y.exceptionOrNull()!!.javaClass)}else assertEquals(x.getOrThrow(),y.getOrThrow())}
    @Test fun actualDerivedGettersCompleteOriginalCorpusAndConfirmedRawEndBoundary() {
        val today=LocalDate.now();val dates=listOf(null,LocalDate.MIN,today.minusDays(10),today,today.plusDays(1),LocalDate.MAX)
        for(date in dates)for(chip in listOf(null,"","Today","About a week ago","I don’t know","It varies / I don’t know","🌸"))for(cycle in listOf(Int.MIN_VALUE,-1,0,1,28,Int.MAX_VALUE))for(ongoing in listOf(false,true)){
            val s=S(selectedExactDate=date,selectedApproximateChip=chip,cycleLengthDays=cycle,isPeriodOngoing=ongoing,periodDurationDays=Int.MIN_VALUE,selectedGoals=setOf("UNDERSTAND MY CYCLE","changes in my body","fertility"))
            assertEquals(s.canProceedFromPrivacyKotlin,s.canProceedFromPrivacy);assertEquals(s.lastPeriodAnsweredKotlin,s.lastPeriodAnswered);assertEquals(s.needsCycleFollowupKotlin,s.needsCycleFollowup);assertEquals(s.needsPeriodFollowupKotlin,s.needsPeriodFollowup);assertEquals(s.needsSymptomFollowupKotlin,s.needsSymptomFollowup);assertEquals(s.needsFertilityFollowupKotlin,s.needsFertilityFollowup);assertEquals(s.hasCycleStartEstimateKotlin,s.hasCycleStartEstimate);assertEquals(s.periodStartChoiceKotlin,s.periodStartChoice)
            same({s.effectiveLastPeriodDateKotlin},{s.effectiveLastPeriodDate});same({s.effectivePeriodEndDateKotlin},{s.effectivePeriodEndDate});same({s.daysSinceLastPeriodKotlin},{s.daysSinceLastPeriod});same({s.dayOfCycleKotlin},{s.dayOfCycle});same({s.isPeriodActiveTodayKotlin},{s.isPeriodActiveToday});same({s.currentPhaseNameKotlin},{s.currentPhaseName})
        }
        val vm=OnboardingViewModel();val field=OnboardingViewModel::class.java.getDeclaredField("_uiState").apply{isAccessible=true};@Suppress("UNCHECKED_CAST")val mutable=field.get(vm) as MutableStateFlow<S>
        for(start in dates)for(end in dates)for(ongoing in listOf(false,true))for(confirmed in listOf(false,true)){mutable.value=S(selectedExactDate=start,selectedPeriodEndDate=end,isPeriodOngoing=ongoing,periodEndDateConfirmed=confirmed);assertEquals(vm.confirmedPeriodEndDateKotlin(),vm.confirmedPeriodEndDate());if(vm.confirmedPeriodEndDate()!=null)assertSame(end,vm.confirmedPeriodEndDate())}
    }
    @Test fun strictRepliesUnavailableSurrogateFallbackAndNoAdditionalEvidenceReads() {
        fun reply(v:Any)=JSONObject().put("ok",JSONObject().put("version",1).put("value",v))
        for(v in listOf<Any>("true",1,JSONObject.NULL))assertNull(D.decode(reply(v),"privacy"))
        for(v in listOf<Any>("1",true,1.5,JSONObject.NULL))assertNull(D.decode(reply(v),"day"))
        for(v in listOf<Any>(1,true,JSONObject.NULL))assertNull(D.decode(reply(v),"phase"))
        val need=JSONObject().put("ok",JSONObject().put("version",1).put("need","secret"));assertNull(D.decode(need,"phase"));need.getJSONObject("ok").put("need","age");assertNull(D.decode(need,"phase"))
        for(v in listOf<Any>("1",true,1.5,2)){val p=reply(true);p.getJSONObject("ok").put("version",v);assertNull(D.decode(p,"privacy"))}
        val extra=reply(true);extra.getJSONObject("ok").put("extra",0);assertNull(D.decode(extra,"privacy"))
        for(k in listOf("op","evidence")){val p=JSONObject().put("op","day").put("evidence",JSONObject());p.remove(k);assertNull(B.call("onboarding_derived_full_v1",p))}
        val request=JSONObject().put("op","privacy").put("evidence",JSONObject().put("extra",true));assertNull(B.call("onboarding_derived_full_v1",request))
        for(v in listOf<Any>("true",1,JSONObject.NULL)){val p=JSONObject().put("op","privacy").put("evidence",JSONObject().put("age",v));assertNull(B.call("onboarding_derived_full_v1",p))}
        for(v in listOf<Any>("1",1.5,true,Long.MAX_VALUE)){val p=JSONObject().put("op","day").put("evidence",JSONObject().put("cycle",v));assertNull(B.call("onboarding_derived_full_v1",p))}
        assertNull(B.call("onboarding_derived_full_v1",JSONObject().put("op","unknown").put("evidence",JSONObject())))
        val unsafe="\uD800";val reads=mutableListOf<String>();assertEquals("APPROXIMATE:$unsafe",D.evaluate("choice"){reads+=it;when(it){"exact"->false;"estimate"->true;else->unsafe}});assertEquals(listOf("exact","estimate","chip"),reads)
        val f=B::class.java.getDeclaredField("nativeState").apply{isAccessible=true};val old=f.get(B);try{f.set(B,B.NativeState.UNAVAILABLE);val t=mutableListOf<String>();assertEquals("No estimate yet",D.evaluate("phase"){t+=it;false});assertEquals(listOf("estimate"),t);parityUnavailable("phase",mapOf("estimate" to true,"active" to false,"day1" to 20,"day2" to 21,"cycle" to 28));parityUnavailable("day",mapOf("estimate" to true,"days" to 10,"cycle" to 0))}finally{f.set(B,old)}
    }
    private fun parityUnavailable(op:String,values:Map<String,Any?>){val a=mutableListOf<String>();val b=mutableListOf<String>();same({D.oracle(op){a+=it;values.getValue(it)}},{D.evaluate(op){b+=it;values.getValue(it)}});assertEquals(a.distinct(),b)}
}
