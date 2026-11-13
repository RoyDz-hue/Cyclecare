package com.cyclecare.app

import com.cyclecare.app.domain.sync.*
import com.cyclecare.app.rust.CycleCareCoreBridge as B
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34])
class LifecycleStateNativeParityTest {
    @Test fun allAccountStartDestinationsDirectNativeThenActualSharedCaller() {
        for(valid in listOf(false,true))for(state in LocalDataState.entries)for(remote in listOf(null,false,true)) {
            val native=B.accountStart(valid,state,remote);assertNotNull("actual strict account start JNI before oracle",native)
            assertEquals(AccountStartPolicy.decideKotlin(valid,state,remote),native)
            assertEquals(native,AccountStartPolicy.decide(valid,state,remote))
        }
    }
    private fun parity(pre:ReconnectWorkPolicy.State,trailing:Boolean,connected:Boolean,eligible:Boolean,owner:String?,now:Long) {
        val native=B.reconnectState(pre,trailing,connected,eligible,owner,now);assertNotNull("actual strict reconnect JNI before oracle",native)
        val oracle=ReconnectWorkPolicy.State(pre.wasConnected,pre.lastRunAtMs,pre.owner)
        val expected=if(trailing)ReconnectWorkPolicy.decideTrailingKotlin(oracle,connected,eligible,owner,now) else ReconnectWorkPolicy.decideKotlin(oracle,connected,eligible,owner,now)
        assertEquals(expected,native!!.decision);assertEquals(oracle.wasConnected,native.connected);assertEquals(oracle.lastRunAtMs,native.last);assertEquals(oracle.owner,native.owner)
        val actual=ReconnectWorkPolicy.State(pre.wasConnected,pre.lastRunAtMs,pre.owner)
        assertEquals(expected,if(trailing)ReconnectWorkPolicy.decideTrailing(actual,connected,eligible,owner,now) else ReconnectWorkPolicy.decide(actual,connected,eligible,owner,now))
        assertEquals(oracle.wasConnected,actual.wasConnected);assertEquals(oracle.lastRunAtMs,actual.lastRunAtMs);assertEquals(oracle.owner,actual.owner)
    }
    @Test fun reconnectWholePostStateExactLongWrapNullOwnersAndTrailing() {
        val times=listOf(Long.MIN_VALUE,-1L,0L,1L,59999L,60000L,60001L,Long.MAX_VALUE)
        for(trailing in listOf(false,true))for(before in listOf(false,true))for(connected in listOf(false,true))for(eligible in listOf(false,true))for(last in times)for(now in times)for(owner in listOf(null,"a","b","","🌸"))parity(ReconnectWorkPolicy.State(before,last,"a"),trailing,connected,eligible,owner,now)
        val r=java.util.Random(370901);repeat(500) {parity(ReconnectWorkPolicy.State(r.nextBoolean(),r.nextLong(),listOf(null,"","a","b")[r.nextInt(4)]),r.nextBoolean(),r.nextBoolean(),r.nextBoolean(),listOf(null,"","a","b")[r.nextInt(4)],r.nextLong())}
    }
    @Test fun sequenceOwnerResetThrottlingAndWholeUnavailableFallback() {
        val native=ReconnectWorkPolicy.State();val oracle=ReconnectWorkPolicy.State()
        for((connected,eligible,owner,now) in listOf(listOf(false,true,"a",1000L),listOf(true,true,"a",2000L),listOf(false,true,"a",3000L),listOf(true,true,"a",4000L),listOf(false,false,null,5000L),listOf(true,true,"a",6000L))) {
            assertEquals(ReconnectWorkPolicy.decideKotlin(oracle,connected as Boolean,eligible as Boolean,owner as String?,now as Long),ReconnectWorkPolicy.decide(native,connected,eligible,owner,now))
            assertEquals(oracle.owner,native.owner);assertEquals(oracle.lastRunAtMs,native.lastRunAtMs);assertEquals(oracle.wasConnected,native.wasConnected)
        }
        val f=B::class.java.getDeclaredField("nativeState").apply {isAccessible=true};val old=f.get(B)
        try {f.set(B,B.NativeState.UNAVAILABLE);assertNull(B.accountStart(false,LocalDataState.CLEAN,false));assertEquals(AccountStartDestination.ONBOARDING,AccountStartPolicy.decide(false,LocalDataState.CLEAN,false));assertNull(B.reconnectState(native,false,true,true,"a",7000));val expected=ReconnectWorkPolicy.decideKotlin(oracle,true,true,"a",7000);assertEquals(expected,ReconnectWorkPolicy.decide(native,true,true,"a",7000));assertEquals(oracle.lastRunAtMs,native.lastRunAtMs)}finally{f.set(B,old)}
        val surrogate="\uD800";val s=ReconnectWorkPolicy.State(false,0,surrogate);assertNull(B.reconnectState(s,false,true,true,surrogate,1));assertEquals(ReconnectWorkPolicy.Decision.Run,ReconnectWorkPolicy.decide(s,true,true,surrogate,1));assertEquals(surrogate,s.owner)
    }
    @Test fun strictSchemaVersionTypesAndNoCoercion() {
        for(s in listOf("{}","{\"valid\":true,\"state\":\"CLEAN\"}","{\"valid\":true,\"state\":\"BAD\",\"remote\":null}","{\"valid\":true,\"state\":\"CLEAN\",\"remote\":0}"))assertNull(B.call("account_start_v1",JSONObject(s)))
        for(route in listOf<Any>("home",1,true,JSONObject.NULL))assertNull(B.decodeAccountStart(JSONObject().put("ok",JSONObject().put("version",1).put("destination",route))))
        for(v in listOf<Any>("1",1.5,2,true))assertNull(B.decodeAccountStart(JSONObject().put("ok",JSONObject().put("version",v).put("destination","HOME"))))
        fun request()=JSONObject().put("state",JSONObject().put("connected",false).put("last",1L).put("owner","a")).put("trailing",false).put("connected",true).put("eligible",true).put("owner","a").put("now",2L)
        val good=B.call("reconnect_state_v1",request())!!;assertNotNull(B.decodeReconnectState(good))
        for(k in request().keys().asSequence().toList()) {val x=request();x.remove(k);assertNull(B.call("reconnect_state_v1",x))}
        for(k in listOf("connected","last","owner")) {val x=request();x.getJSONObject("state").remove(k);assertNull(B.call("reconnect_state_v1",x))}
        for(k in good.getJSONObject("ok").keys().asSequence().toList()) {val x=JSONObject(good.toString());x.getJSONObject("ok").remove(k);assertNull(B.decodeReconnectState(x))}
        for(k in listOf("connected","last","owner")) {val x=JSONObject(good.toString());x.getJSONObject("ok").getJSONObject("state").remove(k);assertNull(B.decodeReconnectState(x))}
        for(v in listOf<Any>("1",1.5,true,JSONObject.NULL)) {val x=JSONObject(good.toString());x.getJSONObject("ok").getJSONObject("state").put("last",v);assertNull(B.decodeReconnectState(x))}
        for(v in listOf<Any>("true",1,JSONObject.NULL)) {val x=JSONObject(good.toString());x.getJSONObject("ok").getJSONObject("state").put("connected",v);assertNull(B.decodeReconnectState(x))}
        // Wrapped Long waits may be negative. Preserve them rather than invent a clamp.
        val wrapped=JSONObject(good.toString());wrapped.getJSONObject("ok").put("wait",Long.MIN_VALUE);assertNotNull(B.decodeReconnectState(wrapped))
    }
}
