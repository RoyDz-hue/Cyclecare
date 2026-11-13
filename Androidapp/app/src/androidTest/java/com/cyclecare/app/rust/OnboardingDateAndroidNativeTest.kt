package com.cyclecare.app.rust
import com.cyclecare.app.ui.viewmodel.OnboardingDateReducer as R
import com.cyclecare.app.ui.viewmodel.OnboardingUiState as S
import com.cyclecare.app.ui.viewmodel.OnboardingViewModel
import com.cyclecare.app.rust.CycleCareCoreBridge as B
import kotlinx.coroutines.flow.MutableStateFlow
import java.time.LocalDate
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import androidx.test.ext.junit.runners.AndroidJUnit4
@RunWith(AndroidJUnit4::class)
class OnboardingDateAndroidNativeTest {
    private val today=LocalDate.of(2026,11,7)
    private fun seed()=S(selectedExactDate=today.minusDays(20),selectedPeriodEndDate=today,selectedApproximateChip="KEEP",selectedPeriodDurationOption="OLD",periodDateDialogMessage="REVIEW",periodDurationDays=5,showDatePickerDialog=true,showEndDatePickerDialog=true,selectedGoals=linkedSetOf("KEEP"),dynamicAnswers=mapOf("KEEP" to setOf("value")))
    private fun parity(s:S,e:R.Event):S? {
        var oldCalls=0;val old=runCatching{R.reduceKotlin(s,e){today.plusDays((oldCalls++).toLong())}}
        var newCalls=0;val next=runCatching{R.reduce(s,e){today.plusDays((newCalls++).toLong())}}
        assertEquals("clock evaluations",oldCalls,newCalls)
        if(old.isFailure){assertTrue(next.isFailure);assertEquals(old.exceptionOrNull()!!.javaClass,next.exceptionOrNull()!!.javaClass);return null}
        val v=R.evidence(s,e){today};val native=B.fullOnboardingDate(s,e,v);assertNotNull("actual live date-state JNI before complete original-expression oracle",native)
        assertEquals(R.reduceKotlin(s,e){today},native);assertEquals(old.getOrThrow(),next.getOrThrow());assertSame(s.selectedGoals,next.getOrThrow().selectedGoals);assertSame(s.dynamicAnswers,next.getOrThrow().dynamicAnswers)
        return native
    }
    @Test fun fullDatesRawEndClampingTypicalUnusualFutureAndExactClockEvaluation() {
        val dates=listOf(LocalDate.MIN,LocalDate.MIN.plusDays(7),today.minusDays(8),today.minusDays(7),today,today.plusDays(1),LocalDate.MAX)
        for(start in listOf(null)+dates)for(end in listOf(null)+dates)for(date in dates){val s=seed().copy(selectedExactDate=start,selectedPeriodEndDate=end);parity(s,R.Event(R.Action.EXACT_START,date));parity(s,R.Event(R.Action.EXACT_END,date))}
        for(chip in listOf(null,"Today","A few days ago","About a week ago","About two weeks ago","I don’t know","It varies / I don’t know","unknown",""))for(days in listOf(Int.MIN_VALUE,-1,0,1,7,8,Int.MAX_VALUE))for(ongoing in listOf(false,true)){val s=seed().copy(selectedExactDate=null,selectedApproximateChip=chip);parity(s,R.Event(R.Action.DURATION,text="OPTION",days=days,ongoing=ongoing))}
        for(days in listOf(2147483648L,4294967296L,4294967304L))parity(seed().copy(selectedExactDate=today.minusDays(days-1)),R.Event(R.Action.EXACT_END,today))
        for(days in listOf(Int.MIN_VALUE,Int.MAX_VALUE,1,0))parity(seed().copy(selectedExactDate=LocalDate.MAX),R.Event(R.Action.DURATION,text="preset",days=days))
        val r=java.util.Random(803096);repeat(150){val start=today.minusDays(r.nextInt(200).toLong());val s=seed().copy(selectedExactDate=start,periodDurationDays=r.nextInt());parity(s,R.Event(R.Action.EXACT_END,today.minusDays(r.nextInt(100).toLong())))}
    }
    @Test fun actualViewModelDateEntryPresetConfirmationAndCompletionBoundary() {
        val vm=OnboardingViewModel();val field=OnboardingViewModel::class.java.getDeclaredField("_uiState").apply{isAccessible=true};@Suppress("UNCHECKED_CAST")val mutable=field.get(vm) as MutableStateFlow<S>
        val now=LocalDate.now();var oracle=seed().copy(selectedExactDate=now.minusDays(20),selectedPeriodEndDate=now)
        mutable.value=oracle
        val events=listOf(R.Event(R.Action.APPROXIMATE,text="About a week ago"),R.Event(R.Action.EXACT_START,now.minusDays(20)),R.Event(R.Action.EXACT_END,now),R.Event(R.Action.DURATION,text="5 days",days=5),R.Event(R.Action.EXACT_END,now.minusDays(30)),R.Event(R.Action.EXACT_END,now),R.Event(R.Action.DURATION,text="Still ongoing",days=3,ongoing=true),R.Event(R.Action.EXACT_END,now))
        for(e in events){oracle=R.reduceKotlin(oracle,e){LocalDate.now()};when(e.action){R.Action.EXACT_START->vm.setExactDate(e.date!!);R.Action.APPROXIMATE->vm.selectApproximateChip(e.text);R.Action.EXACT_END->vm.setExactEndDate(e.date!!);R.Action.DURATION->vm.selectPeriodDurationOption(e.text,e.days,e.ongoing)};assertEquals(e.action.name,oracle,vm.uiState.value)}
        assertNull(vm.confirmedPeriodEndDate());vm.confirmPeriodEndDate();assertEquals(now,vm.confirmedPeriodEndDate());assertEquals(3,vm.uiState.value.periodDurationDays)
        vm.editPeriodEndDate();assertTrue(vm.uiState.value.showEndDatePickerDialog);assertEquals(now,vm.confirmedPeriodEndDate())
        vm.selectPeriodDurationOption("Still ongoing",3,true);assertNull(vm.confirmedPeriodEndDate())
    }
    @Test fun strictSchemaDateBindingSurrogateAndUnavailableReplayNoExtraClockCalls() {
        val s=seed();val e=R.Event(R.Action.EXACT_END,today);val evidence=R.evidence(s,e){today};fun payload()=B.onboardingDatePayload(s,e,evidence)!!
        val good=B.call("onboarding_date_full_v1",payload())!!;val dates=listOfNotNull(s.selectedExactDate,s.selectedPeriodEndDate,e.date,evidence.start,evidence.end,evidence.today);assertNotNull(B.decodeFullOnboardingDate(good,s,dates))
        for(k in payload().keys().asSequence().toList()){val p=payload();p.remove(k);assertNull(B.call("onboarding_date_full_v1",p))}
        for(k in payload().getJSONObject("state").keys().asSequence().toList()){val p=payload();p.getJSONObject("state").remove(k);assertNull(B.call("onboarding_date_full_v1",p))}
        for(k in good.getJSONObject("ok").getJSONObject("state").keys().asSequence().toList()){val p=JSONObject(good.toString());p.getJSONObject("ok").getJSONObject("state").remove(k);assertNull(B.decodeFullOnboardingDate(p,s,dates))}
        for(k in listOf("exact","end"))for(v in listOf<Any>("1",1.5,true,Long.MAX_VALUE)){val p=JSONObject(good.toString());p.getJSONObject("ok").getJSONObject("state").put(k,v);assertNull(B.decodeFullOnboardingDate(p,s,dates))}
        val unbound=JSONObject(good.toString());unbound.getJSONObject("ok").getJSONObject("state").put("end",today.minusDays(2).toEpochDay());assertNull(B.decodeFullOnboardingDate(unbound,s,dates))
        for(v in listOf<Any>("1",1.5,true,2)){val p=JSONObject(good.toString());p.getJSONObject("ok").put("version",v);assertNull(B.decodeFullOnboardingDate(p,s,dates))}
        val unsafe=R.Event(R.Action.APPROXIMATE,text="\uD800");assertNull(B.fullOnboardingDate(s,unsafe,R.evidence(s,unsafe){error("clock must stay lazy")}));assertEquals(R.reduceKotlin(s,unsafe){error("clock")},R.reduce(s,unsafe){error("clock")})
        val f=B::class.java.getDeclaredField("nativeState").apply{isAccessible=true};val old=f.get(B)
        try{f.set(B,B.NativeState.UNAVAILABLE);for(event in listOf(e,R.Event(R.Action.EXACT_START,today),R.Event(R.Action.APPROXIMATE,text="Today"),R.Event(R.Action.DURATION,text="preset",days=Int.MIN_VALUE),R.Event(R.Action.DURATION,ongoing=true))){parityUnavailable(s,event)}}finally{f.set(B,old)}
    }
    private fun parityUnavailable(s:S,e:R.Event){var a=0;var b=0;val expected=R.reduceKotlin(s,e){today.plusDays((a++).toLong())};val actual=R.reduce(s,e){today.plusDays((b++).toLong())};assertEquals(expected,actual);assertEquals(a,b)}
}
