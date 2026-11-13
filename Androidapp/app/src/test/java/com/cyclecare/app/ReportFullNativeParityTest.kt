package com.cyclecare.app

import com.cyclecare.app.data.local.CycleDayLog
import com.cyclecare.app.data.local.CycleSettings
import com.cyclecare.app.data.local.OnboardingProfile
import com.cyclecare.app.domain.learning.LearningEngine
import com.cyclecare.app.rust.CycleCareCoreBridge
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34])
class ReportFullNativeParityTest {
    private fun label(s:String)=s.replace('_',' ').trim().replaceFirstChar {if(it.isLowerCase())it.titlecase() else it.toString()}
    private fun parity(logs:List<CycleDayLog>,s:CycleSettings=CycleSettings(),p:OnboardingProfile?=null) {
        val today=LocalDate.now().format(DateTimeFormatter.ofPattern("MMM d, yyyy"))
        val n=CycleCareCoreBridge.fullReport(s,logs,p,today,::label);assertNotNull("actual whole report JNI before caller",n)
        val k=LearningEngine.generateClinicianReportTextKotlin(s,logs,p,today)
        assertEquals(k,n);assertEquals(k,LearningEngine.generateClinicianReportText(s,logs,p))
    }
    @Test fun emptyInvalidRawSettingsApproximateUnicodeAnchorAndLocale() {
        val old=Locale.getDefault()
        try {for(locale in listOf(Locale.US,Locale.FRANCE,Locale("tr","TR"),Locale("ar","EG"),Locale.JAPAN)) {
            Locale.setDefault(locale)
            for(anchor in listOf("", "\u00a0 \u3000", "bad", "+999999999-12-31"))for(choice in listOf("", "aPpRoXiMaTe:range", "APPROXİMATE:range")) {
                parity(emptyList(),CycleSettings(averageCycleLength=Int.MIN_VALUE,periodDuration=Int.MAX_VALUE,lastPeriodStartDate=anchor),OnboardingProfile(approximatePeriodChoice=choice))
                parity(listOf(CycleDayLog("bad",notes="discarded")))
            }
        }}finally{Locale.setDefault(old)}
    }
    @Test fun fullDatesFirstAliasWinsCountsUnicodeSortingPainFlowsAndNotes() {
        val logs=listOf(CycleDayLog("+0010000-01-01",symptomsCsv="z,z,a,🌸,\uE000,ßeta,ǳone",flowLevel="heavy",painIntensity=6,notes="  line1\nline2\t  "),
            CycleDayLog("+10000-01-01",symptomsCsv="wrong",flowLevel="UNUSUALLY_HEAVY",painIntensity=Int.MAX_VALUE,notes="wrong"),
            CycleDayLog("-999999999-01-01",symptomsCsv="a,z",flowLevel="HEAVY",painIntensity=7,notes="\u00a0 note \u3000"),
            CycleDayLog("+999999999-12-31",symptomsCsv="a,z",flowLevel="UNUSUALLY_HEAVY",painIntensity=Int.MAX_VALUE),
            CycleDayLog("0000-02-29",symptomsCsv="a_b,a b",flowLevel="UNKNOWN",painIntensity=-5,notes="\u00a0 \u3000"),CycleDayLog("-0000-01-01",symptomsCsv="wrong"))
        parity(logs);parity(logs.reversed());parity(logs.take(1));parity(listOf(CycleDayLog("2026-01-01",notes="note\n\t\u3000")))
        for(flow in listOf("", "NONE", "UNKNOWN", "none", "UNUSUALLY_HEAVY", "heavy", "a_b", "ß"))parity(listOf(CycleDayLog("2026-01-01",flowLevel=flow)))
    }
    @Test fun generatedEvidenceAndWholeSurrogateFallback() {
        val r=java.util.Random(73901)
        repeat(120) {parity((0 until r.nextInt(35)).map {CycleDayLog(if(r.nextInt(12)==0)"bad" else LocalDate.of(2026,1,1).plusDays(r.nextInt(45).toLong()).toString(),symptomsCsv=listOf("a,z,a","a_b,a b","🌸,\uE000,ßeta,ǳone","")[r.nextInt(4)],painIntensity=r.nextInt(16)-2,flowLevel=listOf("HEAVY","heavy","NONE","MODERATE","UNUSUALLY_HEAVY")[r.nextInt(5)],notes=listOf("", "\t\u3000", "multi\nline  "," one ")[r.nextInt(4)])})}
        val s=CycleSettings();val logs=listOf(CycleDayLog("2026-01-01",notes="\uD800"))
        assertNull(CycleCareCoreBridge.fullReport(s,logs,null,"today",::label))
        assertEquals(LearningEngine.generateClinicianReportTextKotlin(s,logs),LearningEngine.generateClinicianReportText(s,logs))
    }
    @Test fun strictReplyInputAndNativeUnavailableNeverPartial() {
        for(s in listOf("{}","{\"ok\":null}","{\"ok\":{\"version\":1.0,\"text\":\"x\"}}","{\"ok\":{\"version\":1,\"text\":1}}","{\"ok\":{\"version\":1,\"text\":\"\"}}","{\"ok\":{\"version\":1,\"text\":\"x\",\"extra\":0}}"))assertNull(CycleCareCoreBridge.decodeFullReport(JSONObject(s)))
        assertNotNull(CycleCareCoreBridge.decodeFullReport(JSONObject("{\"ok\":{\"version\":1,\"text\":\"x\"}}")))
        val input=JSONObject().put("today","date").put("average",28).put("duration",5).put("anchor","").put("anchor_blank",true).put("approximate",false).put("logs",org.json.JSONArray())
        assertNotNull(CycleCareCoreBridge.call("report_full_v1",input))
        for(k in input.keys().asSequence().toList()) {val n=JSONObject(input.toString());n.remove(k);assertNull(CycleCareCoreBridge.call("report_full_v1",n))}
        assertNull(CycleCareCoreBridge.call("report_full_v1",JSONObject(input.toString()).put("average",28.5)))
        assertNull(CycleCareCoreBridge.call("report_full_v1",JSONObject(input.toString()).put("extra",0)))
        assertNull(CycleCareCoreBridge.call("report_full_v1",JSONObject(input.toString()).put("logs",org.json.JSONArray().put(JSONObject()))))
        val state=CycleCareCoreBridge::class.java.getDeclaredField("nativeState").apply {isAccessible=true};val old=state.get(CycleCareCoreBridge);val logs=listOf(CycleDayLog("2026-01-01",symptomsCsv="z,a",painIntensity=7,flowLevel="HEAVY",notes="note"))
        try {state.set(CycleCareCoreBridge,CycleCareCoreBridge.NativeState.UNAVAILABLE);assertNull(CycleCareCoreBridge.fullReport(CycleSettings(),logs,null,"today",::label));assertEquals(LearningEngine.generateClinicianReportTextKotlin(CycleSettings(),logs),LearningEngine.generateClinicianReportText(CycleSettings(),logs))}finally{state.set(CycleCareCoreBridge,old)}
    }
}
