package com.cyclecare.app.lab.store
import androidx.room.withTransaction
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.time.LocalDate

internal fun labHash(s:String?)=MessageDigest.getInstance("SHA-256").digest((s?:"null").toByteArray(Charsets.UTF_8)).joinToString(""){"%02x".format(it)}
// One owner namespace/handle. A different namespace requires preopened validated adapter,
// not relabeling this database. Same namespace session/epoch transition uses this mutex.
internal class LabEncryptedStore(private val db:LabReceiptDatabase,private var identity:LabIdentity,private val mutex:Mutex,private val secret:RetainedFactorySecret):PreparedLabStore {
 override val namespace get()=identity.namespace
 override val instance get()=identity.instance
 private var closed=false
 override suspend fun capturedIdentity():LabIdentity?=mutex.withLock{if(closed)null else identity}
 override suspend fun validatePrepared():Boolean=try{validateOpen();true}catch(e:kotlinx.coroutines.CancellationException){throw e}catch(e:Exception){false}
 override suspend fun apply(plan:LabCommitPlan)=commit(plan)
 suspend fun close()=mutex.withLock{if(!closed){db.close();secret.close();closed=true}}
 private val dao get()=db.dao()
 suspend fun capture():LabIdentity?=capturedIdentity()
 suspend fun transition(expected:LabIdentity,next:LabIdentity):Boolean=mutex.withLock{
  if(closed)return@withLock false
  next.validate();if(identity!=expected||next.epoch<=identity.epoch||next.namespace!=identity.namespace||next.instance!=identity.instance)false else{identity=next;true}
 }
 suspend fun validateOpen()=mutex.withLock{check(!closed);db.withTransaction{
  val rows=dao.records();val revisions=dao.revisions();val journal=dao.journals();val receipts=dao.receipts()
  require(rows.size<=10000&&revisions.size<=10000&&journal.size<=10000&&receipts.size<=10000)
  for(r in revisions){require(r.namespace==identity.namespace&&r.revision>0);require(r.payloadHash==null&&r.tombstone||r.payloadHash?.matches(Regex("[0-9a-f]{64}"))==true)}
  for(r in rows){require(r.namespace==identity.namespace);labValidatePayload(r.recordId,r.payload);val rev=revisions.single{it.namespace==r.namespace&&it.recordId==r.recordId};require(!rev.tombstone&&rev.payloadHash==labHash(r.payload))}
  for(j in journal){require(j.namespace==identity.namespace&&j.revision>0&&j.kind in setOf("UPSERT","DELETE")&&j.payloadHash==labHash(j.payload));require((j.kind=="DELETE")== (j.payload==null));require(receipts.any{it.namespace==j.namespace&&it.operationId==j.operationId})}
  for(r in receipts){require(r.namespace==identity.namespace&&r.resultVersion==1&&r.intentDigest.matches(Regex("[0-9a-f]{64}")));validateReceipt(r.resultJson);val result=JSONObject(r.resultJson).getJSONArray("records")
   val matching=journal.filter{it.namespace==r.namespace&&it.operationId==r.operationId}
   require(matching.size==result.length())
   for(n in 0 until result.length()){val item=result.getJSONObject(n);val j=matching.single{it.recordId==item.getString("record")};require(j.revision==item.getLong("revision")&&(j.kind=="DELETE")==item.getBoolean("tombstone"));require((if(item.isNull("hash"))null else item.getString("hash"))==(if(j.kind=="DELETE")null else j.payloadHash))}}
 }}
 private fun validateReceipt(raw:String){require(raw.length<=65536);val o=JSONObject(raw);require(o.keys().asSequence().toSet()==setOf("version","outcome","records"));require(o.get("version") is Int&&o.getInt("version")==1&&o.get("outcome")=="Committed");val a=o.getJSONArray("records");require(a.length() in 1..2);for(n in 0 until a.length()){val r=a.getJSONObject(n);require(r.keys().asSequence().toSet()==setOf("record","revision","hash","tombstone"));require(r.get("revision") is Int||r.get("revision") is Long);require(r.getLong("revision")>0&&r.get("tombstone") is Boolean);require(r.get("record") is String&&r.getString("record").length<=128)}}
 suspend fun commit(plan:LabCommitPlan,faultAfterStep:Int=0,loseReceiptAfterCommit:Boolean=false):LabCommitResult=mutex.withLock{
  try{
   if(closed)return@withLock LabCommitResult.RejectedClosed
   plan.identity.validate();require(plan.operationId.isNotBlank()&&plan.operationId.length<=128);require(plan.mutations.size in 1..2&&plan.mutations.map{it.recordId}.distinct().size==plan.mutations.size)
   if(plan.identity!=identity)return@withLock LabCommitResult.Stale
   val canonical=JSONArray()
   for(m in plan.mutations){require(m.recordId.length in 1..128&&m.expectedRevision>=0&&m.kind in setOf("UPSERT","DELETE"));require((m.kind=="DELETE")== (m.payload==null));if(m.payload!=null)labValidatePayload(m.recordId,m.payload,m.bbtBits,m.sleepBits);require(m.payloadHash==labHash(m.payload));canonical.put(JSONArray().put(m.recordId).put(m.expectedRevision).put(m.kind).put(m.payloadHash))}
   require(plan.intentDigest==labHash(canonical.toString()))
   val result=db.withTransaction{
    val previous=dao.receipt(identity.namespace,plan.operationId)
    if(previous!=null){require(previous.resultVersion==1);validateReceipt(previous.resultJson);if(previous.intentDigest!=plan.intentDigest)return@withTransaction LabCommitResult.InvalidReuse;return@withTransaction LabCommitResult.Committed(previous.resultJson,true)}
    val priors=plan.mutations.map{dao.revision(identity.namespace,it.recordId)}
    if(plan.mutations.indices.any{(priors[it]?.revision?:0)!=plan.mutations[it].expectedRevision})return@withTransaction LabCommitResult.Conflict
    require(priors.none{it?.revision==Long.MAX_VALUE})
    var step=0;fun fault(){step++;if(step==faultAfterStep)throw IllegalStateException("Synthetic injected fault")}
    // Domain writes first: log+anchor or settings+profile, then each revision/journal.
    for(m in plan.mutations){if(m.payload==null)dao.deleteRecord(identity.namespace,m.recordId)else dao.putRecord(LabRecord(identity.namespace,m.recordId,m.payload));fault()}
    val records=JSONArray()
    for((index,m)in plan.mutations.withIndex()){
     val revision=(priors[index]?.revision?:0)+1
     dao.putRevision(LabRevision(identity.namespace,m.recordId,revision,m.kind=="DELETE",if(m.kind=="DELETE")null else m.payloadHash));fault()
     dao.putJournal(LabJournal(identity.namespace,plan.operationId,m.recordId,revision,m.kind,m.payload,m.payloadHash));fault()
     records.put(JSONObject().put("record",m.recordId).put("revision",revision).put("hash",if(m.kind=="DELETE")JSONObject.NULL else m.payloadHash).put("tombstone",m.kind=="DELETE"))
    }
    val receipt=JSONObject().put("version",1).put("outcome","Committed").put("records",records).toString();dao.putReceipt(LabReceipt(identity.namespace,plan.operationId,plan.intentDigest,1,receipt));fault();LabCommitResult.Committed(receipt,false)
   }
   if(loseReceiptAfterCommit&&result is LabCommitResult.Committed&&!result.replay)LabCommitResult.Unknown(plan.operationId)else result
  }catch(e:kotlinx.coroutines.CancellationException){throw e}catch(e:IllegalArgumentException){LabCommitResult.Malformed("planOrPayload")}catch(e:org.json.JSONException){LabCommitResult.Malformed("json")}catch(e:Exception){LabCommitResult.StorageFailure}
 }
}

internal fun labValidatePayload(record:String,payload:String,bbtBits:Long?=null,sleepBits:Long?=null){
  require(payload.length<=65536)
  val o=JSONObject(payload)
  if(record !in setOf("settings","profile")){
   require(LocalDate.parse(record).toString()==record)
   require(o.keys().asSequence().toSet()==setOf("date","flow","energy","mood","symptoms","vaginal_health","urinary_health","cervical_mucus","ovulation_test","bbt_celsius","sleep_hours","sleep_quality","water_glasses","notes","pain_intensity"))
   require(o.get("date")==record)
   for(k in listOf("flow","energy","mood","symptoms","vaginal_health","urinary_health","cervical_mucus","ovulation_test","sleep_quality","notes"))require(o.get(k) is String&&o.getString(k).length<=4096)
   for(k in listOf("bbt_celsius","sleep_hours")){val v=o.get(k);require(v is Number&&v.toDouble().isFinite()&&v.toFloat().isFinite())}
   for(k in listOf("water_glasses","pain_intensity"))require(o.get(k) is Int)
   val negativeB=Regex("\"bbt_celsius\":-0(?:[,}])").containsMatchIn(payload)
   val negativeS=Regex("\"sleep_hours\":-0(?:[,}])").containsMatchIn(payload)
   val b=bbtBits?.let{require(it in 0..0xffffffffL);Float.fromBits(it.toInt())}?:if(negativeB)-0f else o.getDouble("bbt_celsius").toFloat()
   val sleep=sleepBits?.let{require(it in 0..0xffffffffL);Float.fromBits(it.toInt())}?:if(negativeS)-0f else o.getDouble("sleep_hours").toFloat()
   require(b.isFinite()&&sleep.isFinite()&&o.getDouble("bbt_celsius").toFloat()==b)
   require(o.getDouble("sleep_hours").toFloat()==sleep)
   val log=com.cyclecare.app.data.local.CycleDayLog(record,o.getString("flow"),o.getString("energy"),o.getString("mood"),o.getString("symptoms"),o.getString("vaginal_health"),o.getString("urinary_health"),o.getString("cervical_mucus"),o.getString("ovulation_test"),b,sleep,o.getString("sleep_quality"),o.getInt("water_glasses"),o.getString("notes"),o.getInt("pain_intensity"))
   require(com.cyclecare.app.data.local.HealthSyncPayloadCodec.logPayload(log)==payload){"nonCanonicalPayload"}
  }else{
   val strings=if(record=="settings")setOf("selected_goals","last_period_start_date","pregnancy_due_date")else setOf("selected_goals","last_period_date","last_period_end_date","approximate_period_choice","cycle_length_option","typical_symptoms","typical_energy","typical_sleep_quality")
   val integers=if(record=="settings")setOf("average_cycle_length","period_duration","luteal_phase_days")else setOf("cycle_length_days","period_duration_days")
   val boolean=if(record=="settings")"pregnancy_mode" else "has_completed_deep_baseline"
   require(o.keys().asSequence().toSet()==strings+integers+boolean)
   strings.forEach{require(o.get(it) is String&&o.getString(it).length<=4096)};integers.forEach{require(o.get(it) is Int)};require(o.get(boolean) is Boolean)
  }
 }
