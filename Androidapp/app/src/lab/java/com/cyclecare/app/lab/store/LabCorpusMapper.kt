package com.cyclecare.app.lab.store
import org.json.JSONArray
import org.json.JSONObject

/** Test corpus bridge only. No effects, no real identity inference. */
internal object LabCorpusMapper {
 fun track(identity:LabIdentity,raw:JSONObject,logBase:Long,settingsBase:Long):LabCommitPlan {
  val kind=raw.getString("kind");require(kind in setOf("UPSERT","DELETE"))
  val payload=if(raw.isNull("payload"))null else raw.getString("payload")
  val mutations=mutableListOf(LabMutation(raw.getString("date"),logBase,payload,raw.getString("payloadHash"),kind,if(kind=="UPSERT")raw.getLong("bbtBits")else null,if(kind=="UPSERT")raw.getLong("sleepBits")else null))
  if(!raw.isNull("anchorAfter")){
   require(kind=="UPSERT");val before=JSONObject(raw.getString("settingsBefore"));require(raw.getString("anchorBefore")==before.getString("last_period_start_date"))
   val after=raw.getString("settingsAfter");mutations.add(LabMutation("settings",settingsBase,after,raw.getString("settingsHash"),"UPSERT"))
  }
  return plan(identity,raw.getString("op"),mutations)
 }
 fun settings(identity:LabIdentity,operation:String,settingsPayload:String,profilePayload:String?,settingsBase:Long,profileBase:Long):LabCommitPlan {
  val mutations=mutableListOf(LabMutation("settings",settingsBase,settingsPayload,labHash(settingsPayload),"UPSERT"));if(profilePayload!=null)mutations.add(LabMutation("profile",profileBase,profilePayload,labHash(profilePayload),"UPSERT"));return plan(identity,operation,mutations)
 }
 private fun plan(identity:LabIdentity,operation:String,mutations:List<LabMutation>):LabCommitPlan{
  val canonical=JSONArray();mutations.forEach{canonical.put(JSONArray().put(it.recordId).put(it.expectedRevision).put(it.kind).put(it.payloadHash))};return LabCommitPlan(identity,operation,labHash(canonical.toString()),mutations)
 }
}
