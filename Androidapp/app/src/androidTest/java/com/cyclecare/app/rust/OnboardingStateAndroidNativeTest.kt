package com.cyclecare.app.rust

import com.cyclecare.app.ui.viewmodel.OnboardingUiState as S
import com.cyclecare.app.ui.viewmodel.OnboardingViewModel
import com.cyclecare.app.ui.viewmodel.OnboardingStateReducer as R
import com.cyclecare.app.rust.CycleCareCoreBridge as B
import kotlinx.coroutines.flow.MutableStateFlow
import java.time.LocalDate
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import androidx.test.ext.junit.runners.AndroidJUnit4

@RunWith(AndroidJUnit4::class)
class OnboardingStateAndroidNativeTest {
    private fun seed()=S(step=3,selectedGoals=linkedSetOf("a","b"),selectedExactDate=LocalDate.MIN,selectedPeriodEndDate=LocalDate.MAX,selectedApproximateChip="KEEP_CHIP",periodDurationDays=Int.MIN_VALUE,selectedPeriodDurationOption="KEEP_DURATION",isPeriodOngoing=true,periodDateDialogMessage="REVIEW",dynamicAnswers=linkedMapOf("q0" to linkedSetOf("x","y")),dynamicQuestions=mapOf(6 to emptyList()),isLoadingDynamicQuestions=true)
    private fun parity(s:S,e:R.Event):S {
        val n=B.fullOnboardingState(s,e);assertNotNull("actual non-date onboarding reducer JNI before complete original-expression oracle",n)
        val expected=R.reduceKotlin(s,e);assertEquals(expected,n);assertEquals(n,R.reduce(s,e))
        assertEquals(expected.selectedGoals.toList(),n!!.selectedGoals.toList());assertEquals(expected.selectedTypicalSymptoms.toList(),n.selectedTypicalSymptoms.toList());assertEquals(expected.dynamicAnswers.keys.toList(),n.dynamicAnswers.keys.toList())
        for(k in expected.dynamicAnswers.keys)assertEquals(expected.dynamicAnswers[k]!!.toList(),n.dynamicAnswers[k]!!.toList())
        assertSame(s.selectedExactDate,n.selectedExactDate);assertSame(s.selectedPeriodEndDate,n.selectedPeriodEndDate);assertSame(s.dynamicQuestions,n.dynamicQuestions)
        assertEquals(s.periodDurationDays,n.periodDurationDays);assertEquals(s.isPeriodOngoing,n.isPeriodOngoing);assertEquals(s.selectedApproximateChip,n.selectedApproximateChip);assertEquals(s.isLoadingDynamicQuestions,n.isLoadingDynamicQuestions)
        return n
    }
    @Test fun allTwentyLiveActionsFullStateOrderedSetsMapsAndIntBoundaries() {
        for(a in R.Action.entries)for(flag in listOf(false,true))for(text in listOf("","a","🌸","\u0000","ß","q0"))for(days in listOf(Int.MIN_VALUE,0,1,Int.MAX_VALUE))parity(seed(),R.Event(a,text,"x",flag,days))
        for(step in listOf(Int.MIN_VALUE,-1,0,1,6,Int.MAX_VALUE))for(total in listOf(Int.MIN_VALUE,0,6,Int.MAX_VALUE))for(a in listOf(R.Action.NEXT,R.Action.PREVIOUS))parity(seed().copy(step=step,totalSteps=total),R.Event(a))
        var s=seed()
        for(e in listOf(R.Event(R.Action.GOAL,"a"),R.Event(R.Action.GOAL,"a"),R.Event(R.Action.ANSWER,"q0","x",true),R.Event(R.Action.ANSWER,"q0","x",true),R.Event(R.Action.ANSWER,"q1","z",false),R.Event(R.Action.ANSWER,"q1","",true),R.Event(R.Action.SYMPTOM,"a"),R.Event(R.Action.SYMPTOM,"a"),R.Event(R.Action.SYMPTOM,"a"),R.Event(R.Action.DEEP,flag=false),R.Event(R.Action.ENERGY,""),R.Event(R.Action.CONFIRM_END)))s=parity(s,e)
        assertEquals(listOf("b","a"),s.selectedGoals.toList());assertEquals(listOf("y","x"),s.dynamicAnswers["q0"]!!.toList());assertEquals(listOf("q0","q1"),s.dynamicAnswers.keys.toList())
        val r=java.util.Random(803071);repeat(250){s=parity(s,R.Event(R.Action.entries[r.nextInt(R.Action.entries.size)],listOf("","a","b","🌸")[r.nextInt(4)],listOf("","x","z")[r.nextInt(3)],r.nextBoolean(),r.nextInt()))}
    }
    private fun invoke(vm:OnboardingViewModel,e:R.Event) {when(e.action){
        R.Action.NEXT->vm.nextStep();R.Action.PREVIOUS->vm.previousStep();R.Action.GOAL->vm.toggleGoal(e.text);R.Action.ANSWER->vm.toggleDynamicAnswer(e.text,e.other,e.flag)
        R.Action.NOT_SURE->vm.setNotSureSelected(e.flag);R.Action.DATE_PICKER->vm.setDatePickerDialogVisible(e.flag);R.Action.END_PICKER->vm.setEndDatePickerDialogVisible(e.flag);R.Action.CYCLE->vm.selectCycleOption(e.text,e.days)
        R.Action.REMIND->vm.setRemindFertile(e.flag);R.Action.AGE->vm.setAgeVerified(e.flag);R.Action.TERMS->vm.setTermsAgreed(e.flag);R.Action.CLOUD->vm.setCloudSyncAllowed(e.flag);R.Action.TERMS_DIALOG->vm.setShowTermsDialog(e.flag);R.Action.PRIVACY_DIALOG->vm.setShowPrivacyDialog(e.flag)
        R.Action.SYMPTOM->vm.toggleTypicalSymptom(e.text);R.Action.ENERGY->vm.selectTypicalEnergy(e.text);R.Action.SLEEP->vm.selectTypicalSleep(e.text);R.Action.DEEP->vm.setCompletedDeepBaseline(e.flag);R.Action.CONFIRM_END->vm.confirmPeriodEndDate();R.Action.EDIT_END->vm.editPeriodEndDate()
    }}
    @Test fun actualViewModelHandlersAndProgressiveQuestionEffectMatchCompleteOracle() {
        val vm=OnboardingViewModel();val mutable=OnboardingViewModel::class.java.getDeclaredField("_uiState").let{it.isAccessible=true;@Suppress("UNCHECKED_CAST")(it.get(vm) as MutableStateFlow<S>)}
        val t=LocalDate.of(2026,11,7);var oracle=seed().copy(selectedExactDate=t.minusDays(10),selectedPeriodEndDate=t,periodDurationDays=5,isPeriodOngoing=false,isLoadingDynamicQuestions=false)
        mutable.value=oracle
        val events=R.Action.entries.map{R.Event(it,"track my periods","Energy",true,28)}+listOf(R.Event(R.Action.NEXT),R.Event(R.Action.NEXT),R.Event(R.Action.NEXT),R.Event(R.Action.PREVIOUS),R.Event(R.Action.ANSWER,"q","Sleep",true),R.Event(R.Action.SLEEP,"LOW"))
        for(e in events){assertNotNull(B.fullOnboardingState(oracle,e));oracle=R.reduceKotlin(oracle,e);if(e.action==R.Action.NEXT&&oracle.step in 4..6){val local=com.cyclecare.app.domain.onboarding.LocalAdaptiveOnboardingBank.questionsFor(oracle.step,oracle.selectedGoals,oracle.dynamicAnswers);oracle=oracle.copy(dynamicQuestions=oracle.dynamicQuestions+(oracle.step to local))};invoke(vm,e);assertEquals(e.action.name,oracle,vm.uiState.value);assertEquals(oracle.dynamicAnswers.keys.toList(),vm.uiState.value.dynamicAnswers.keys.toList())}
    }
    @Test fun strictSchemaTypesDuplicateOrderAndWholeSurrogateUnavailableFallback() {
        val s=seed();val e=R.Event(R.Action.GOAL,"c");fun payload()=B.onboardingStatePayload(s,e)!!
        val good=B.call("onboarding_state_full_v1",payload())!!;assertNotNull(B.decodeFullOnboardingState(good,s))
        for(k in listOf("state","event")){val p=payload();p.remove(k);assertNull(B.call("onboarding_state_full_v1",p))}
        for(group in listOf("state","event"))for(k in payload().getJSONObject(group).keys().asSequence().toList()){val p=payload();p.getJSONObject(group).remove(k);assertNull(B.call("onboarding_state_full_v1",p))}
        for(k in good.getJSONObject("ok").getJSONObject("state").keys().asSequence().toList()){val p=JSONObject(good.toString());p.getJSONObject("ok").getJSONObject("state").remove(k);assertNull(B.decodeFullOnboardingState(p,s))}
        for(k in listOf("step","total","cycleDays"))for(v in listOf<Any>("1",1.5,true,JSONObject.NULL)){val p=JSONObject(good.toString());p.getJSONObject("ok").getJSONObject("state").put(k,v);assertNull(B.decodeFullOnboardingState(p,s))}
        for(k in listOf("notSure","datePicker","endPicker","remind","age","terms","cloud","termsDialog","privacyDialog","deep","endConfirmed"))for(v in listOf<Any>("true",1)){val p=JSONObject(good.toString());p.getJSONObject("ok").getJSONObject("state").put(k,v);assertNull(B.decodeFullOnboardingState(p,s))}
        for(v in listOf<Any>("1",1.5,true,2,JSONObject.NULL)){val p=JSONObject(good.toString());p.getJSONObject("ok").put("version",v);assertNull(B.decodeFullOnboardingState(p,s))}
        for(k in listOf("goals","symptoms","answers"))for(v in listOf<Any>("[]",true,JSONObject.NULL)){val p=JSONObject(good.toString());p.getJSONObject("ok").getJSONObject("state").put(k,v);assertNull(B.decodeFullOnboardingState(p,s))}
        val extra=JSONObject(good.toString());extra.getJSONObject("ok").put("extra",0);assertNull(B.decodeFullOnboardingState(extra,s))
        for(k in listOf("notSure","age","terms","cloud","deep"))for(v in listOf<Any>("true",1,JSONObject.NULL)){val p=payload();p.getJSONObject("state").put(k,v);assertNull(B.call("onboarding_state_full_v1",p))}
        for(k in listOf("step","cycleDays"))for(v in listOf<Any>("1",1.5,true,Long.MAX_VALUE)){val p=payload();p.getJSONObject("state").put(k,v);assertNull(B.call("onboarding_state_full_v1",p))}
        val duplicate=payload();duplicate.getJSONObject("state").getJSONArray("goals").put("a");assertNull(B.call("onboarding_state_full_v1",duplicate))
        val replyDuplicate=JSONObject(good.toString());replyDuplicate.getJSONObject("ok").getJSONObject("state").getJSONArray("goals").put("a");assertNull(B.decodeFullOnboardingState(replyDuplicate,s))
        val unknown=payload();unknown.getJSONObject("event").put("action","UNKNOWN");assertNull(B.call("onboarding_state_full_v1",unknown))
        val unsafe=e.copy(text="\uD800");assertNull(B.fullOnboardingState(s,unsafe));assertEquals(R.reduceKotlin(s,unsafe),R.reduce(s,unsafe))
        val unsafeState=s.copy(selectedGoals=setOf("\uD800"));assertNull(B.fullOnboardingState(unsafeState,e));assertEquals(R.reduceKotlin(unsafeState,e),R.reduce(unsafeState,e))
        val f=B::class.java.getDeclaredField("nativeState").apply{isAccessible=true};val old=f.get(B)
        try{f.set(B,B.NativeState.UNAVAILABLE);for(a in R.Action.entries){val event=R.Event(a,"a","x",true,28);assertNull(B.fullOnboardingState(s,event));assertEquals(R.reduceKotlin(s,event),R.reduce(s,event))}}finally{f.set(B,old)}
    }
}
