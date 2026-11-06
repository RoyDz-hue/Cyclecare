package com.cyclecare.app.security

import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage

/** Inactive, single-process candidate. Every instance must use the SAME prefs
 * object as its monitor. No multiprocess or independent-wrapper writer support.
 * Intent is NOT enqueue success or current worker authority. No production use.
 */
internal class CandidateSyncWorkLedger(
 private val prefs:SharedPreferences,
 private val clock:()->Long=System::currentTimeMillis
) {
 data class Binding(val owner:String,val session:Long,val consent:Long,val lifecycle:Long) {
  init{require(owner.isNotBlank()&&session>=0&&consent>=0&&lifecycle>=0)}
  fun key()=JSONArray().put(owner).put(session).put(consent).put(lifecycle).toString()
 }
 data class Work(val id:UUID,val binding:Binding)
 data class Cancellation(val id:UUID,val attempts:Int,val status:String="pending")
 interface Transport {
  fun enqueue(work:Work):CompletionStage<Unit>
  fun cancel(id:UUID):CompletionStage<Unit>
 }
 private val key="candidate_sync_work_ledger_v1"
 private class Gate {var uncertain:String?=null;var transaction=false}
 companion object {private val gates=java.util.WeakHashMap<SharedPreferences,Gate>()}
 private val gate=synchronized(gates){gates.getOrPut(prefs){Gate()}}
 private var unresolvedWrite:String?
  get()=gate.uncertain
  set(value){gate.uncertain=value}
 fun writeBlocker():String?=synchronized(prefs){unresolvedWrite}
 private fun state():JSONObject {
  try {
   val state=JSONObject(prefs.getString(key,null)?:"""{"active":[],"cancel":[]}""")
   val seen=mutableSetOf<UUID>()
   for(name in listOf("active","cancel")) {
    val rows=state.getJSONArray(name);check(rows.length()<=128){"work_ledger_capacity"}
    for(i in 0 until rows.length()) {
     val row=rows.getJSONObject(i);check(seen.add(UUID.fromString(row.getString("id")))){"work_ledger_duplicate"}
     if(name=="active") {val raw=JSONArray(row.getString("binding"));Binding(raw.getString(0),raw.getLong(1),raw.getLong(2),raw.getLong(3));check(raw.length()==4)}
     else {check(row.getInt("attempts") in 0..5);check(row.optString("status","pending") in setOf("pending","inflight","done","exhausted"))
      if(row.optString("status")=="inflight"){UUID.fromString(row.getString("lease"));check(row.getLong("until")>=0)}}
    }
   }
   return state
  }catch(t:Exception){unresolvedWrite="corrupt_or_invalid";throw IllegalStateException("work_ledger_blocked",t)}
 }
 private fun persist(state:JSONObject,phase:String){
  try{check(prefs.edit().putString(key,state.toString()).commit()){"work_ledger_commit_failed"}}
  catch(t:Exception){unresolvedWrite=phase;throw t}
 }
 /** No rollback assumption. Rewrite the currently observed full ledger with a
  * fresh successful commit before unblocking. DONE is retained as a receipt:
  * restart with an older disk value may recancel the same UUID, never another.
  * Caller must also reconcile any consent/editor uncertainty separately.
  */
 fun recoverWrite():Boolean=synchronized(prefs){
  check(!gate.transaction){"work_ledger_transaction_reentry"}
  val observed=state();persist(observed,"recovery");unresolvedWrite=null;true
 }
 /** Own the full lock + editor + commit lifetime. No raw editor is exposed.
  * Edit capabilities expire even when the block throws. Caller exceptions never
  * commit staged state. Commit uncertainty quarantines this instance.
  */
 fun <T> transaction(block:Edit.()->T):T=synchronized(prefs){
  check(unresolvedWrite==null){"work_ledger_write_uncertain"}
  check(!gate.transaction){"work_ledger_transaction_reentry"}
  val editor=prefs.edit();val staged=Edit(state(),editor);gate.transaction=true
  try {
   val value=staged.block();staged.flush()
   try{check(editor.commit()){"work_ledger_transaction_commit_failed"}}
   catch(t:Exception){unresolvedWrite="transaction";throw t}
   value
  }finally{staged.expire();gate.transaction=false}
 }
 inner class Edit internal constructor(private val state:JSONObject,private val editor:SharedPreferences.Editor){
  private var live=true
  private fun checkLive(){check(live&&Thread.holdsLock(prefs)){"work_ledger_edit_expired"}}
  internal fun expire(){live=false}
  internal fun flush(){checkLive();editor.putString(key,state.toString())}
  fun putBoolean(key:String,value:Boolean){checkLive();require(key!=this@CandidateSyncWorkLedger.key);editor.putBoolean(key,value)}
  fun putLong(key:String,value:Long){checkLive();require(key!=this@CandidateSyncWorkLedger.key);editor.putLong(key,value)}
  fun schedule(expected:Binding):Work {
   checkLive()
   val active=state.getJSONArray("active")
   for(i in 0 until active.length()) {val row=active.getJSONObject(i);if(row.getString("binding")==expected.key())return Work(UUID.fromString(row.getString("id")),expected)}
   check(active.length()<128){"work_ledger_active_capacity"}
   val work=Work(UUID.randomUUID(),expected);active.put(JSONObject().put("id",work.id.toString()).put("binding",expected.key()));return work
  }
  fun retire(expected:Binding):List<UUID> {
   checkLive()
   val active=state.getJSONArray("active");val kept=JSONArray();val cancel=state.getJSONArray("cancel");val ids=mutableListOf<UUID>()
   for(i in 0 until active.length()) {val row=active.getJSONObject(i)
    if(row.getString("binding")==expected.key()) {
     check(cancel.length()<128){"work_ledger_cancel_capacity"};val id=UUID.fromString(row.getString("id"));ids.add(id)
     cancel.put(JSONObject().put("id",id.toString()).put("attempts",0).put("status","pending"))
    }else kept.put(row)
   };state.put("active",kept);return ids
  }
 }
 fun accepts(id:UUID,current:Binding):Boolean=synchronized(prefs){
  check(unresolvedWrite==null){"work_ledger_write_uncertain"};val active=state().getJSONArray("active")
  (0 until active.length()).any{val row=active.getJSONObject(it);row.getString("id")==id.toString()&&row.getString("binding")==current.key()}
 }
 fun pending():List<Cancellation> = synchronized(prefs){
  val rows=state().getJSONArray("cancel");(0 until rows.length()).map{val row=rows.getJSONObject(it);Cancellation(UUID.fromString(row.getString("id")),row.getInt("attempts"),row.optString("status","pending"))}.filter{it.status!="done"}
 }
 private data class Claim(val id:UUID,val lease:String)
 /** Bounded durable lease; another sweep can reclaim after 60s. Old completion
  * cannot receipt a new claim. Sweep is a liveness obligation of its caller;
  * no timer is installed here, and an individual returned stage may never end.
  * Five attempts become observable EXHAUSTED, never silently disappear.
  */
 fun retryCancellations(transport:Transport,limit:Int=16):CompletionStage<Int>{
  require(limit in 1..32)
  val claimed=synchronized(prefs){
   check(unresolvedWrite==null){"work_ledger_write_uncertain"}
   check(!gate.transaction){"work_ledger_transaction_reentry"}
   val state=state();val rows=state.getJSONArray("cancel");val result=mutableListOf<Claim>();val now=clock();require(now>=0)
   for(i in 0 until rows.length()) {val row=rows.getJSONObject(i);val status=row.optString("status","pending")
    if(status!="done"&&!(status=="inflight"&&row.getLong("until")>now)) {
     if(row.getInt("attempts")>=5)row.put("status","exhausted")
     else if(result.size<limit) {
      val claim=Claim(UUID.fromString(row.getString("id")),UUID.randomUUID().toString());result.add(claim)
      row.put("attempts",row.getInt("attempts")+1).put("status","inflight").put("lease",claim.lease).put("until",Math.addExact(now,60000))
     }
    }
   };persist(state,"claim");result
  }
  val results=claimed.map{claim->
   val result=CompletableFuture<Boolean>()
   fun finish(error:Throwable?) {
    try {
     val updated=synchronized(prefs){
      check(unresolvedWrite==null){"work_ledger_write_uncertain"}
      check(!gate.transaction){"work_ledger_transaction_reentry"}
      val state=state();val rows=state.getJSONArray("cancel");var matched=false
      for(i in 0 until rows.length()) {val row=rows.getJSONObject(i)
       if(row.getString("id")==claim.id.toString()&&row.optString("lease")==claim.lease&&row.optString("status")=="inflight") {
        row.put("status",if(error==null)"done" else if(row.getInt("attempts")>=5)"exhausted" else "pending");matched=true
       }
      };if(matched)persist(state,if(error==null)"receipt:${claim.id}" else "failure:${claim.id}");matched
     };result.complete(updated&&error==null)
    }catch(t:Exception){result.completeExceptionally(t)}
   }
   try{transport.cancel(claim.id).whenComplete{_,error->finish(error)}}catch(t:Exception){finish(t)}
   result
  }
  return CompletableFuture.allOf(*results.toTypedArray()).thenApply{results.count{it.join()}}
 }
}
