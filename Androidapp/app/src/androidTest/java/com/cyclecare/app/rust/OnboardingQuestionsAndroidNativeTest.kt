package com.cyclecare.app.rust
import com.cyclecare.app.domain.onboarding.LocalAdaptiveOnboardingBank as L
import com.cyclecare.app.domain.onboarding.OnboardingQuestion as Q
import com.cyclecare.app.ui.viewmodel.OnboardingQuestionReducer as R
import com.cyclecare.app.ui.viewmodel.OnboardingUiState as S
import com.cyclecare.app.ui.viewmodel.OnboardingViewModel
import com.cyclecare.app.rust.CycleCareCoreBridge as B
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import androidx.test.ext.junit.runners.AndroidJUnit4
@RunWith(AndroidJUnit4::class)
class OnboardingQuestionsAndroidNativeTest {
    @Test fun completeAdaptiveBankAllSelectionsExactCopyOrderAndJvmLowercase() {
        val pieces=listOf("cycle","symptom","fertility","in tune","exploring","changes in my body")
        for(step in listOf(Int.MIN_VALUE,-1,0,3,4,5,6,7,Int.MAX_VALUE))for(mask in 0 until (1 shl pieces.size))for(answer in listOf("","Sleep","ENERGY","sleepenergy","a","İ SLEEP","🌸")) {
            val goals=pieces.filterIndexed{i,_->mask and (1 shl i)!=0}.toSet();val prior=linkedMapOf("a" to linkedSetOf(answer,"x"));val native=B.fullOnboardingBank(step,goals,prior);assertNotNull("actual full bank JNI before exact independent original oracle",native);assertEquals(L.questionsForKotlin(step,goals,prior),native);assertEquals(native,L.questionsFor(step,goals,prior))
        }
        for(g in listOf("PERIOD","FERTİLE","ςΣ","I\u0307","🌸","cycle period symptom exploring")){assertEquals(L.questionsForKotlin(4,setOf(g),emptyMap()),B.fullOnboardingBank(4,setOf(g),emptyMap()))}
    }
    private fun parity(s:S,step:Int,q:List<Q>?,a:R.Action):S {
        val native=B.fullOnboardingQuestions(s,step,q,a);assertNotNull("actual full question-state JNI before original expression oracle",native);val expected=R.reduceKotlin(s,step,q,a);assertEquals(expected,native);assertEquals(native,R.reduce(s,step,q,a));assertEquals(expected.dynamicQuestions.keys.toList(),native!!.dynamicQuestions.keys.toList());assertSame(s.selectedExactDate,native.selectedExactDate);assertSame(s.dynamicAnswers,native.dynamicAnswers);return native
    }
    @Test fun orderedLocalStartGeneratedStateAndActualProgressionKeepOriginalEffectBoundaries() {
        val q=Q("same","",listOf("","a","a","🌸"),false);var s=S(step=4,dynamicQuestions=linkedMapOf(6 to listOf(q),4 to emptyList()),isLoadingDynamicQuestions=true)
        for(step in listOf(Int.MIN_VALUE,4,5,6,Int.MAX_VALUE))for(a in R.Action.entries)for(incoming in listOf(null,emptyList(),listOf(q,q))){if(a==R.Action.LOCAL&&incoming==null)continue;s=parity(s,step,incoming,a)}
        assertEquals(listOf(6,4,Int.MIN_VALUE,5,Int.MAX_VALUE),s.dynamicQuestions.keys.toList())
        val vm=OnboardingViewModel();val field=OnboardingViewModel::class.java.getDeclaredField("_uiState").apply{isAccessible=true};@Suppress("UNCHECKED_CAST")val mutable=field.get(vm) as MutableStateFlow<S>
        mutable.value=S(step=3,selectedGoals=linkedSetOf("track my periods","symptoms"),dynamicAnswers=mapOf("prior" to setOf("Sleep")))
        for(step in 4..6){vm.nextStep();val state=vm.uiState.value;assertEquals(step,state.step);assertEquals(L.questionsForKotlin(step,state.selectedGoals,state.dynamicAnswers),state.dynamicQuestions[step]);assertFalse(state.isLoadingDynamicQuestions);vm.toggleDynamicAnswer("s${step}","Energy",true)}
        val before=vm.uiState.value;vm.nextStep();assertEquals(before,vm.uiState.value)
        val source=vm.uiState.value;val started=parity(source,5,null,R.Action.START);assertTrue(started.isLoadingDynamicQuestions);assertEquals(source.dynamicQuestions,parity(started,5,null,R.Action.GENERATED).dynamicQuestions);assertEquals(source.dynamicQuestions,parity(started,5,emptyList(),R.Action.GENERATED).dynamicQuestions);assertEquals(listOf(q),parity(started,5,listOf(q),R.Action.GENERATED).dynamicQuestions[5])
    }
    @Test fun strictSchemasTypesVersionDuplicateMapAndWholeUnavailableSurrogateFallback() {
        val s=S();val q=listOf(Q("q","Prompt",listOf("A","B")));fun payload()=B.onboardingQuestionsPayload(s,4,q,R.Action.LOCAL)!!
        val good=B.call("onboarding_questions_state_v1",payload())!!;assertNotNull(B.decodeFullOnboardingQuestions(good,s))
        for(k in payload().keys().asSequence().toList()){val p=payload();p.remove(k);assertNull(B.call("onboarding_questions_state_v1",p))}
        for(k in listOf("id","prompt","options","multiSelect")){val p=payload();p.getJSONArray("incoming").getJSONObject(0).remove(k);assertNull(B.call("onboarding_questions_state_v1",p))}
        for(k in listOf("version","entries","loading")){val p=JSONObject(good.toString());p.getJSONObject("ok").remove(k);assertNull(B.decodeFullOnboardingQuestions(p,s))}
        for(v in listOf<Any>("1",1.5,true,2)){val p=JSONObject(good.toString());p.getJSONObject("ok").put("version",v);assertNull(B.decodeFullOnboardingQuestions(p,s))}
        val dup=JSONObject(good.toString());val entries=dup.getJSONObject("ok").getJSONArray("entries");entries.put(entries.getJSONObject(0));assertNull(B.decodeFullOnboardingQuestions(dup,s))
        val bad=payload();bad.put("action","OTHER");assertNull(B.call("onboarding_questions_state_v1",bad))
        for(k in listOf("step","goals","earlier")){val p=JSONObject().put("step",4).put("goals","cycle").put("earlier","");p.remove(k);assertNull(B.call("onboarding_bank_full_v1",p))}
        val bank=B.call("onboarding_bank_full_v1",JSONObject().put("step",4).put("goals","cycle").put("earlier",""))!!;assertNotNull(B.decodeFullOnboardingBank(bank));bank.getJSONObject("ok").getJSONArray("questions").getJSONObject(0).put("multiSelect",1);assertNull(B.decodeFullOnboardingBank(bank))
        val unsafe="\uD800";assertNull(B.fullOnboardingBank(4,setOf(unsafe),emptyMap()));assertEquals(L.questionsForKotlin(4,setOf(unsafe),emptyMap()),L.questionsFor(4,setOf(unsafe),emptyMap()));val uq=listOf(Q("q",unsafe,listOf("a")));assertNull(B.fullOnboardingQuestions(s,4,uq,R.Action.LOCAL));assertEquals(R.reduceKotlin(s,4,uq,R.Action.LOCAL),R.reduce(s,4,uq,R.Action.LOCAL))
        val f=B::class.java.getDeclaredField("nativeState").apply{isAccessible=true};val old=f.get(B);try{f.set(B,B.NativeState.UNAVAILABLE);assertNull(B.fullOnboardingBank(4,setOf("cycle"),emptyMap()));assertEquals(L.questionsForKotlin(4,setOf("cycle"),emptyMap()),L.questionsFor(4,setOf("cycle"),emptyMap()));for(a in R.Action.entries)assertEquals(R.reduceKotlin(s,4,q,a),R.reduce(s,4,q,a))}finally{f.set(B,old)}
    }
}
