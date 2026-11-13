package com.cyclecare.app
import com.cyclecare.app.domain.onboarding.OnboardingCompletionMapping as M
import com.cyclecare.app.data.local.*
import com.cyclecare.app.rust.CycleCareCoreBridge as B
import java.time.LocalDate
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34])
class OnboardingCompletionNativeParityTest {
    private fun input()=M.Input("PRIVATE",Long.MAX_VALUE,listOf("a","","a,b","🌸"),LocalDate.MIN,LocalDate.MAX,"EXACT"," ",Int.MIN_VALUE,Int.MAX_VALUE,true,listOf("cramps","","cramps"),"energy","sleep",true)
    private fun current()=CycleSettings(id=7,lutealPhaseDays=Int.MIN_VALUE,lockTimeoutMinutes=Int.MAX_VALUE,isDiscreetNotificationMode=false,isPregnancyMode=true,pregnancyDueDate="KEEP_DUE",remindPeriod=true,remindDailyLog=true)
    private fun parity(i:M.Input) {
        val p=B.fullCompletionProfile(i);assertNotNull("actual full final-profile JNI before complete constructor oracle",p);assertEquals(M.profileKotlin(i),p);assertEquals(p,M.profile(i))
        for(lock in listOf(false,true)){val s=B.fullCompletionSettings(current(),p!!,lock);assertNotNull("actual settings-patch JNI before original complete copy oracle",s);assertEquals(M.settingsKotlin(current(),p,lock),s);assertEquals(s,M.settings(current(),p,lock));assertEquals(7,s!!.id);assertEquals("KEEP_DUE",s.pregnancyDueDate);assertEquals(Int.MIN_VALUE,s.lutealPhaseDays);assertEquals(Int.MAX_VALUE,s.lockTimeoutMinutes)}
    }
    @Test fun completeProfileDateDomainBlankUnicodeExactJoinAndFullSettingsPatch() {
        for(start in listOf(LocalDate.MIN,LocalDate.of(0,2,29),LocalDate.of(10000,1,1),LocalDate.MAX))for(end in listOf(null,LocalDate.MIN,LocalDate.MAX))for(choice in listOf("UNKNOWN","unknown","EXACT","APPROXIMATE:🌸",""))for(option in listOf(""," ","\u00a0","\u0085","28 days","🌸"))parity(input().copy(lastPeriodDate=start,lastPeriodEndDate=end,periodStartChoice=choice,cycleLengthOption=option))
        for(now in listOf(Long.MIN_VALUE,-1,0,Long.MAX_VALUE))for(cycle in listOf(Int.MIN_VALUE,0,1,Int.MAX_VALUE))parity(input().copy(now=now,cycleLength=cycle,periodDuration=cycle,accountMode="anything",hasCompletedDeepBaseline=false))
        val r=java.util.Random(890107);repeat(150){parity(input().copy(submittedGoals=(0..r.nextInt(12)).map{listOf("","a","a,b","🌸","ß")[r.nextInt(5)]},submittedSymptoms=(0..r.nextInt(12)).map{listOf("","s","s,s","🌸")[r.nextInt(4)]},now=r.nextLong(),cycleLength=r.nextInt(),periodDuration=r.nextInt(),remindFertile=r.nextBoolean()))}
    }
    @Test fun strictSchemasAllFieldsTypesNoSilentDefaultsAndUnavailableSurrogateFallback() {
        val i=input()
        fun payload()=B.completionProfilePayload(i)!!
        val good=B.call("onboarding_completion_profile_v1",payload())!!;assertNotNull(B.decodeCompletionProfile(good))
        for(k in payload().keys().asSequence().toList()){val p=payload();p.remove(k);assertNull(B.call("onboarding_completion_profile_v1",p))}
        for(k in good.getJSONObject("ok").getJSONObject("profile").keys().asSequence().toList()){val p=JSONObject(good.toString());p.getJSONObject("ok").getJSONObject("profile").remove(k);assertNull(B.decodeCompletionProfile(p))}
        for(k in listOf("id","completedAtTimestamp","cycleLengthDays","periodDurationDays"))for(v in listOf<Any>("1",1.5,true,JSONObject.NULL)){val p=JSONObject(good.toString());p.getJSONObject("ok").getJSONObject("profile").put(k,v);assertNull(B.decodeCompletionProfile(p))}
        for(k in listOf("isCompleted","isAgeVerified","isTermsAgreed","remindFertileWindow","hasCompletedDeepBaseline"))for(v in listOf<Any>("true",1,JSONObject.NULL)){val p=JSONObject(good.toString());p.getJSONObject("ok").getJSONObject("profile").put(k,v);assertNull(B.decodeCompletionProfile(p))}
        val profile=M.profileKotlin(i);val patch=B.call("onboarding_completion_settings_v1",B.completionSettingsPayload(profile,true)!!)!!;assertNotNull(B.decodeCompletionSettings(patch,current()))
        for(k in patch.getJSONObject("ok").getJSONObject("patch").keys().asSequence().toList()){val p=JSONObject(patch.toString());p.getJSONObject("ok").getJSONObject("patch").remove(k);assertNull(B.decodeCompletionSettings(p,current()))}
        for(v in listOf<Any>("1",true,1.5,2)){val p=JSONObject(good.toString());p.getJSONObject("ok").put("version",v);assertNull(B.decodeCompletionProfile(p))}
        val extra=JSONObject(good.toString());extra.getJSONObject("ok").getJSONObject("profile").put("extra",0);assertNull(B.decodeCompletionProfile(extra))
        val unsafe=i.copy(submittedGoals=listOf("\uD800"));assertNull(B.fullCompletionProfile(unsafe));assertEquals(M.profileKotlin(unsafe),M.profile(unsafe));val unsafeP=profile.copy(typicalEnergy="\uD800");assertNull(B.fullCompletionSettings(current(),unsafeP,false));assertEquals(M.settingsKotlin(current(),unsafeP,false),M.settings(current(),unsafeP,false))
        val payloadText=payload().toString();assertFalse(payloadText.contains("pin"));assertFalse(payloadText.contains("token"));assertFalse(payloadText.contains("owner"))
        val f=B::class.java.getDeclaredField("nativeState").apply{isAccessible=true};val old=f.get(B);try{f.set(B,B.NativeState.UNAVAILABLE);assertNull(B.fullCompletionProfile(i));assertEquals(M.profileKotlin(i),M.profile(i));assertNull(B.fullCompletionSettings(current(),profile,true));assertEquals(M.settingsKotlin(current(),profile,true),M.settings(current(),profile,true))}finally{f.set(B,old)}
    }
}
