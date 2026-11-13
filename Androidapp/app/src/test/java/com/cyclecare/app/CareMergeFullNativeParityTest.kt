package com.cyclecare.app

import com.cyclecare.app.data.local.CycleDayLog
import com.cyclecare.app.data.model.ParsedLogInterpretation
import com.cyclecare.app.domain.ai.CareConversationPolicy as C
import com.cyclecare.app.rust.CycleCareCoreBridge as B
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34])
class CareMergeFullNativeParityTest {
    private fun base()=CycleDayLog("not a date",flowLevel="OLD_FLOW",energyLevel="OLD_ENERGY",moodLevel="OLD_MOOD",symptomsCsv=" a , b,a ,,",painIntensity=-3,notes="KEEP_NOTES",vaginalHealthCsv="KEEP_VAGINAL",urinaryHealthCsv="KEEP_URINARY",cervicalMucus="KEEP_CERVICAL",ovulationTest="KEEP_OVULATION",bbtCelsius=Float.fromBits(0x7fc00001),sleepHours=-0f,sleepQuality="KEEP_SLEEP",waterGlasses=Int.MAX_VALUE)
    private fun parsed(flow:String?=null,energy:String?=null,mood:String?=null,pain:Int=0,symptoms:List<String> = emptyList())=ParsedLogInterpretation("FORBIDDEN_RAW_CHAT",emptyList(),"FORBIDDEN_FOLLOWUP",flow,energy,mood,symptoms,pain)
    private fun parity(existing:CycleDayLog,parsed:ParsedLogInterpretation) {
        val native=B.fullCareMerge(existing,parsed);assertNotNull("actual affected-field merge JNI before complete original oracle",native)
        assertEquals(C.mergeKotlin(existing,parsed),native);assertEquals(native,C.merge(existing,parsed))
        assertEquals(existing.bbtCelsius.toRawBits(),native!!.bbtCelsius.toRawBits());assertEquals(existing.sleepHours.toRawBits(),native.sleepHours.toRawBits())
    }
    @Test fun allOptionalSelectionsEmptyStringsAndPainClampsPreserveUntouchedFields() {
        for(flow in listOf(null,"","NEW"))for(energy in listOf(null,"","NEW"))for(mood in listOf(null,"","NEW"))for(pain in listOf(Int.MIN_VALUE,-1,0,1,9,10,11,Int.MAX_VALUE))parity(base(),parsed(flow,energy,mood,pain,listOf("b"," c ","a","b")))
        val payload=B.careMergePayload(base(),parsed())!!;assertEquals(setOf("flow","energy","mood","pain","newFlow","newEnergy","newMood","newPain","tokens"),payload.keys().asSequence().toSet());assertFalse(payload.toString().contains("KEEP_"));assertFalse(payload.toString().contains("FORBIDDEN"));assertFalse(payload.toString().contains("date"))
    }
    @Test fun exactSplitTrimBlankDistinctOrderCommaBearingTokensAndGeneratedCorpus() {
        val tokens=listOf("a","A","a,b","ß","ss","ǳone","🌸","\uE000","\u001c","\u0085","\u00a0","\u2007","\u202f","",",",",,")
        for(t in tokens)parity(base().copy(symptomsCsv="$t,$t,,"),parsed(symptoms=listOf(t," $t ","a,b","a","b")))
        val r=java.util.Random(902631);repeat(200) {val csv=(0..r.nextInt(15)).map {tokens[r.nextInt(tokens.size)]}.joinToString(",");val ps=(0..r.nextInt(10)).map {tokens[r.nextInt(tokens.size)]};parity(base().copy(symptomsCsv=csv,painIntensity=r.nextInt()),parsed(pain=r.nextInt(),symptoms=ps))}
    }
    @Test fun strictSchemaTypedReplyAndWholeUnavailableOrSurrogateFallback() {
        val payload=B.careMergePayload(base(),parsed())!!;val reply=B.call("care_merge_full_v1",payload)!!;assertNotNull(B.decodeFullCareMerge(reply,base()))
        for(k in payload.keys().asSequence().toList()){val x=JSONObject(payload.toString());x.remove(k);assertNull(B.call("care_merge_full_v1",x))}
        for(k in reply.getJSONObject("ok").keys().asSequence().toList()){val x=JSONObject(reply.toString());x.getJSONObject("ok").remove(k);assertNull(B.decodeFullCareMerge(x,base()))}
        for(k in listOf("flow","energy","mood","symptoms"))for(v in listOf<Any>(true,1,JSONObject.NULL)){val x=JSONObject(reply.toString());x.getJSONObject("ok").put(k,v);assertNull(B.decodeFullCareMerge(x,base()))}
        for(v in listOf<Any>("1",1.5,true,JSONObject.NULL)){val x=JSONObject(reply.toString());x.getJSONObject("ok").put("pain",v);assertNull(B.decodeFullCareMerge(x,base()))}
        for(v in listOf<Any>("1",1.5,true,2)){val x=JSONObject(reply.toString());x.getJSONObject("ok").put("version",v);assertNull(B.decodeFullCareMerge(x,base()))}
        val extra=JSONObject(reply.toString());extra.getJSONObject("ok").put("extra",0);assertNull(B.decodeFullCareMerge(extra,base()))
        val unsafe=parsed(symptoms=listOf("\uD800"));assertNull(B.fullCareMerge(base(),unsafe));assertEquals(C.mergeKotlin(base(),unsafe),C.merge(base(),unsafe))
        val state=B::class.java.getDeclaredField("nativeState").apply {isAccessible=true};val old=state.get(B)
        try {state.set(B,B.NativeState.UNAVAILABLE);assertNull(B.fullCareMerge(base(),parsed()));assertEquals(C.mergeKotlin(base(),parsed()),C.merge(base(),parsed()))}finally{state.set(B,old)}
    }
}
