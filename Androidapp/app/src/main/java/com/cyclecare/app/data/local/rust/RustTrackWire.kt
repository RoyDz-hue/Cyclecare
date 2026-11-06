package com.cyclecare.app.data.local.rust
import com.cyclecare.app.data.local.CycleDayLog
import com.cyclecare.app.data.local.CycleSettings
import com.cyclecare.app.data.local.HealthSyncPayloadCodec as Codec
import org.json.JSONArray
import org.json.JSONObject
/** Pure transport preparation. No bridge load/fallback, DB access or effects.
 * Host must supply actual captured identity and physical dependencies, call JNI
 * while guarded, and translate validated Rust result to typed batch explicitly.
 */
internal object RustTrackWire {
 data class Identity(val namespace:String,val effective:String,val instance:String,val epoch:Long,val session:Long,val generation:Long,val local:Boolean){fun array()=JSONArray().put(namespace).put(effective).put(instance).put(epoch).put(session).put(generation).put(local)}
 fun request(identity:Identity,operation:String,date:String,revision:Long,settingsRevision:Long,log:CycleDayLog?,settings:CycleSettings):String {
  val body=log?.let{l->JSONArray().put(JSONArray().put(l.dateString).put(l.flowLevel).put(l.energyLevel).put(l.moodLevel).put(l.symptomsCsv).put(l.vaginalHealthCsv).put(l.urinaryHealthCsv).put(l.cervicalMucus).put(l.ovulationTest).put(l.bbtCelsius.toRawBits().toLong() and 0xffffffffL).put(l.sleepHours.toRawBits().toLong() and 0xffffffffL).put(l.sleepQuality).put(l.waterGlasses).put(l.notes).put(l.painIntensity)).put(settings.let{s->JSONArray().put(s.id).put(s.isOnboardingCompleted).put(s.selectedGoalsCsv).put(s.lastPeriodStartDate).put(s.averageCycleLength).put(s.periodDuration).put(s.lutealPhaseDays).put(s.isAppLockEnabled).put(s.lockTimeoutMinutes).put(s.isDiscreetNotificationMode).put(s.isPregnancyMode).put(s.pregnancyDueDate).put(s.remindPeriod).put(s.remindFertile).put(s.remindDailyLog)})}
  return JSONArray().put("local-plan-wire-v1").put(identity.array()).put(operation).put(if(log==null)"DELETE"else"SAVE").put(date).put(revision).put(settingsRevision).put(body?:JSONObject.NULL).toString()
 }
 data class Plan(val canonical:String,val digest:String,val anchor:Boolean)
 /** Exact response validation, not opt/coercing getters. Recompute canonical
  * from requested payloads/bases and response-selected mutation set; this avoids
  * relabeling an unrelated/changed result as the current request.
  */
 fun validate(reply:String,identity:Identity,operation:String,date:String,revision:Long,settingsRevision:Long,log:CycleDayLog?,settings:CycleSettings):Plan {
  require(reply.length<=131072);val a=JSONArray(reply);require(a.length()==5&&a.get(0)=="local-plan-result-v1")
  val canonical=a.get(1) as String;val digest=a.get(2) as String;require(Codec.hash(canonical)==digest)
  val reads=a.get(3) as JSONArray;val mutations=a.get(4) as JSONArray
  val expectedReads=JSONArray();if(log!=null)expectedReads.put(JSONArray().put("settings").put(settingsRevision).put(Codec.hash(Codec.settingsPayload(settings))))
  require(reads.toString()==expectedReads.toString())
  require(mutations.length() in 1..2);val anchor=mutations.length()==2;require(!anchor||log!=null)
  val expected=JSONArray();val canonicalMutations=JSONArray()
  fun mutation(id:String,rev:Long,payload:String?,bbt:Long?,sleep:Long?){val hash=Codec.hash(payload);expected.put(JSONArray().put(id).put(rev).put(payload?:JSONObject.NULL).put(hash).put(bbt?:JSONObject.NULL).put(sleep?:JSONObject.NULL));canonicalMutations.put(JSONArray().put(id).put(rev).put(if(payload==null)"DELETE"else"UPSERT").put(hash).put(bbt?:JSONObject.NULL).put(sleep?:JSONObject.NULL))}
  require(log==null||log.dateString==date)
  mutation(date,revision,log?.let(Codec::logPayload),log?.bbtCelsius?.toRawBits()?.toLong()?.and(0xffffffffL),log?.sleepHours?.toRawBits()?.toLong()?.and(0xffffffffL))
  if(anchor)mutation("settings",settingsRevision,Codec.settingsPayload(settings.copy(lastPeriodStartDate=date)),null,null)
  require(mutations.toString()==expected.toString())
  val expectedCanonical=JSONArray().put(2).put(identity.array()).put(operation).put(expectedReads).put(canonicalMutations).toString()
  require(canonical==expectedCanonical)
  return Plan(canonical,digest,anchor)
 }
}
