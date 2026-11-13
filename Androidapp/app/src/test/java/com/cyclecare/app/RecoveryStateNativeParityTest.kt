package com.cyclecare.app

import com.cyclecare.app.rust.CycleCareCoreBridge as B
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Pure reducer JNI proof; existing RustPortRecoveryTest owns actual coroutine/lock witnesses. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34])
class RecoveryStateNativeParityTest {
    private fun parity(round:Int,absolute:Int,max:Int,budget:Int,pending:Boolean,recoverable:Boolean) {
        val native=B.recoveryAfterPass(round,absolute,max,budget,pending,recoverable)
        assertNotNull("actual recovery JNI before original expression",native)
        val expected=when {
            absolute>=budget -> B.RecoveryDecision(false,false,round,false,false)
            pending -> B.RecoveryDecision(true,false,0,false,true)
            recoverable && round<max -> B.RecoveryDecision(true,true,round,false,true)
            else -> B.RecoveryDecision(false,false,round,false,false)
        }
        assertEquals(expected,native)
    }
    @Test fun fullIntBudgetPendingAndRetryBoundariesDirectNative() {
        for(round in listOf(Int.MIN_VALUE,-1,0,1,4,5,Int.MAX_VALUE))for(absolute in listOf(Int.MIN_VALUE,-1,0,1,19,20,Int.MAX_VALUE))for(max in listOf(Int.MIN_VALUE,0,1,5,Int.MAX_VALUE))for(budget in listOf(Int.MIN_VALUE,0,1,20,Int.MAX_VALUE))for(pending in listOf(false,true))for(recoverable in listOf(false,true))parity(round,absolute,max,budget,pending,recoverable)
        val r=java.util.Random(989761);repeat(500){parity(r.nextInt(),r.nextInt(),r.nextInt(),r.nextInt(),r.nextBoolean(),r.nextBoolean())}
    }
    @Test fun strictReplySchemaActionCoherenceAndUnavailable() {
        fun reply(action:Any="stop",round:Any=1,active:Any=false)=JSONObject().put("ok",JSONObject().put("version",1).put("action",action).put("round",round).put("pending",false).put("inFlight",active))
        assertNotNull(B.decodeRecoveryDecision(reply(),1))
        for(action in listOf<Any>("bogus",1,true,JSONObject.NULL))assertNull(B.decodeRecoveryDecision(reply(action),1))
        for(round in listOf<Any>("1",1.5,true,JSONObject.NULL))assertNull(B.decodeRecoveryDecision(reply(round=round),1))
        assertNull(B.decodeRecoveryDecision(reply(round=2),1));assertNull(B.decodeRecoveryDecision(reply(active=true),1));assertNull(B.decodeRecoveryDecision(reply("prompt",1,true),1));assertNull(B.decodeRecoveryDecision(reply("delayed",1,false),1))
        for(k in reply().getJSONObject("ok").keys().asSequence().toList()) {val x=reply();x.getJSONObject("ok").remove(k);assertNull(B.decodeRecoveryDecision(x,1))}
        for(k in listOf("pending","inFlight"))for(value in listOf<Any>("false",0,JSONObject.NULL)) {val x=reply();x.getJSONObject("ok").put(k,value);assertNull(B.decodeRecoveryDecision(x,1))}
        for(version in listOf<Any>("1",1.5,true,2)) {val x=reply();x.getJSONObject("ok").put("version",version);assertNull(B.decodeRecoveryDecision(x,1))}
        val extra=reply();extra.getJSONObject("ok").put("extra",0);assertNull(B.decodeRecoveryDecision(extra,1))
        for(s in listOf("{}","{\"ok\":null}","{\"error\":\"bad\"}"))assertNull(B.decodeRecoveryDecision(JSONObject(s),1))
        for(s in listOf("{}","{\"round\":1}","{\"round\":1,\"absolute\":1,\"max\":5,\"budget\":20,\"pending\":0,\"recoverable\":true}"))assertNull(B.call("recovery_after_pass_v1",JSONObject(s)))
        val state=B::class.java.getDeclaredField("nativeState").apply {isAccessible=true};val old=state.get(B)
        try {state.set(B,B.NativeState.UNAVAILABLE);assertNull(B.recoveryAfterPass(1,1,5,20,false,true))}finally{state.set(B,old)}
    }
}
