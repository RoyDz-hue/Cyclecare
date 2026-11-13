package com.cyclecare.app

import com.cyclecare.app.data.local.CycleDayLog
import com.cyclecare.app.data.local.CycleSettings
import com.cyclecare.app.data.model.CyclePhase
import com.cyclecare.app.data.model.DayPrediction
import com.cyclecare.app.domain.learning.LearningEngine
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
class ExplainFullNativeParityTest {
    private fun prediction(p:CyclePhase,d:Int)=DayPrediction(LocalDate.MAX,d,p,true,true,true,"ignored","ignored")
    private fun parity(p:DayPrediction?) {
        val settings=CycleSettings(averageCycleLength=Int.MIN_VALUE,lastPeriodStartDate="bad")
        val logs=listOf(CycleDayLog("bad",notes="\uD800"))
        val n=CycleCareCoreBridge.fullExplain(p);assertNotNull("actual complete explain JNI BEFORE caller",n)
        val k=LearningEngine.synthesizeExplainMyBodyKotlin(settings,logs,p,"\uD800 ignored query")
        assertEquals(k,n);assertEquals(k,LearningEngine.synthesizeExplainMyBody(settings,logs,p,"anything"))
    }
    @Test fun noPredictionAndEveryPhaseExactCopyRawDaysAndUnusedFields() {
        parity(null)
        for(p in CyclePhase.entries)for(d in listOf(Int.MIN_VALUE,-1,0,1,28,Int.MAX_VALUE))parity(prediction(p,d))
        val r=java.util.Random(27291);repeat(150){parity(prediction(CyclePhase.entries[r.nextInt(4)],r.nextInt()))}
    }
    @Test fun strictWireAndCompleteReplyTypesNeverPartial() {
        for(s in listOf("{}","{\"prediction\":{}}","{\"prediction\":{\"phase\":\"bad\",\"day\":1}}","{\"prediction\":{\"phase\":\"LUTEAL\",\"day\":1.0}}","{\"prediction\":null,\"extra\":0}"))assertNull(CycleCareCoreBridge.call("explain_full_v1",JSONObject(s)))
        val native=CycleCareCoreBridge.call("explain_full_v1",JSONObject().put("prediction",JSONObject.NULL));assertNotNull(native);assertNotNull(CycleCareCoreBridge.decodeFullExplain(native!!))
        for(k in native.getJSONObject("ok").getJSONObject("result").keys().asSequence().toList()) {
            for(v in listOf<Any>(JSONObject.NULL,1,true,"")) {val n=JSONObject(native.toString());n.getJSONObject("ok").getJSONObject("result").put(k,v);assertNull(CycleCareCoreBridge.decodeFullExplain(n))}
            val n=JSONObject(native.toString());n.getJSONObject("ok").getJSONObject("result").remove(k);assertNull(CycleCareCoreBridge.decodeFullExplain(n))
        }
        for(s in listOf("{}","{\"ok\":null}","{\"ok\":{\"version\":1,\"result\":{}}}"))assertNull(CycleCareCoreBridge.decodeFullExplain(JSONObject(s)))
        val n=JSONObject(native.toString());n.getJSONObject("ok").put("version",1.0);assertNull(CycleCareCoreBridge.decodeFullExplain(n))
        val e=JSONObject(native.toString());e.getJSONObject("ok").getJSONObject("result").put("extra",0);assertNull(CycleCareCoreBridge.decodeFullExplain(e))
        val safe=JSONObject(native.toString());safe.getJSONObject("ok").getJSONObject("result").put("safetyLevel","HIGH");assertNull(CycleCareCoreBridge.decodeFullExplain(safe))
    }
    @Test fun nativeUnavailableKeepsOriginalCompleteOutput() {
        val state=CycleCareCoreBridge::class.java.getDeclaredField("nativeState").apply {isAccessible=true};val old=state.get(CycleCareCoreBridge)
        try {state.set(CycleCareCoreBridge,CycleCareCoreBridge.NativeState.UNAVAILABLE)
            for(p in listOf(null,prediction(CyclePhase.LUTEAL,Int.MAX_VALUE))) {
                assertNull(CycleCareCoreBridge.fullExplain(p));assertEquals(LearningEngine.synthesizeExplainMyBodyKotlin(CycleSettings(),emptyList(),p,"q"),LearningEngine.synthesizeExplainMyBody(CycleSettings(),emptyList(),p,"q"))
            }
        }finally {state.set(CycleCareCoreBridge,old)}
    }
}
