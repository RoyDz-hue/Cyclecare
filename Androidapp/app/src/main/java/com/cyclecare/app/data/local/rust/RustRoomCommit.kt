package com.cyclecare.app.data.local.rust
import androidx.sqlite.db.SupportSQLiteDatabase
import com.cyclecare.app.data.local.CycleDayLog
import com.cyclecare.app.data.local.HealthSyncPayloadCodec
/** Typed local Android consumer, not a JSON/JNI parser and not yet selected by
 * production repository. Host locks actual prefs+DB through entire call.
 * No default settings creation here: captured settings read dependency must be
 * supplied by a full planner before log/anchor integration can activate.
 */
internal object RustRoomCommit {
 data class LogIntent(val namespace:String,val operation:String,val childOperation:String,val generation:Long,val now:Long,val expectedRevision:Long,val expectedPayloadHash:String,val log:CycleDayLog?)
 sealed class Outcome {data class Committed(val replay:Boolean,val receipt:String):Outcome();object Conflict:Outcome();object InvalidReuse:Outcome()}
 fun apply(db:SupportSQLiteDatabase,owner:String,date:String,intent:LogIntent,identityBinding:String):Outcome {
  check(db.inTransaction()){ "caller must own same-connection transaction" }
  require(intent.namespace.isNotBlank()&&intent.operation.isNotBlank()&&intent.operation.length<=128&&intent.childOperation.isNotBlank()&&intent.childOperation.length<=128)
  require(intent.generation>=0&&intent.now>=0&&intent.expectedRevision>=0)
  require(java.time.LocalDate.parse(date).toString()==date)
  val row=intent.log;require(row==null||row.dateString==date&&row.bbtCelsius.isFinite()&&row.sleepHours.isFinite())
  val payload=row?.let(HealthSyncPayloadCodec::logPayload)
  val canonical=org.json.JSONArray().put(1).put(identityBinding).put(owner).put(intent.namespace).put(intent.operation).put(intent.childOperation).put(intent.generation).put(intent.now).put(date).put(intent.expectedRevision).put(intent.expectedPayloadHash).put(payload?:org.json.JSONObject.NULL).toString()
  val digest=HealthSyncPayloadCodec.hash(canonical)
  db.query("SELECT intentDigest,version,resultJson FROM rust_commit_receipts WHERE namespace=? AND operationId=?",arrayOf(intent.namespace,intent.operation)).use{c->if(c.moveToFirst()){
   return if(c.getInt(1)==1&&c.getString(0)==digest)Outcome.Committed(true,c.getString(2))else Outcome.InvalidReuse
  }}
  val current=RustFloatRows.readOne(db,date)
  var prior=0L;var server=0L
  db.query("SELECT revision,serverRevision FROM health_sync_revisions WHERE ownerId=? AND recordId=?",arrayOf(owner,date)).use{c->if(c.moveToFirst()){prior=c.getLong(0);server=c.getLong(1)}}
  require(prior>=0&&server>=0)
  if(prior!=intent.expectedRevision||HealthSyncPayloadCodec.hash(current?.let(HealthSyncPayloadCodec::logPayload))!=intent.expectedPayloadHash)return Outcome.Conflict
  val next=Math.addExact(prior,1L) // typed by outer host before any domain effect
  RustDomainRows.write(db,date,row?.let(RustDomainRows.Row::Log))
  val hash=HealthSyncPayloadCodec.hash(payload)
  db.execSQL("INSERT OR REPLACE INTO health_sync_revisions(ownerId,recordId,revision,payloadHash,tombstone,updatedAt,serverRevision) VALUES(?,?,?,?,?,?,?)",arrayOf<Any?>(owner,date,next,if(row==null)null else hash,if(row==null)1 else 0,intent.now,server))
  db.execSQL("INSERT INTO health_sync_outbox(ownerId,operationId,recordId,kind,baseRevision,revision,payloadJson,payloadHash,consentGeneration,attempts,nextAttemptAt,lastError,serverReceipt,serverRevision) VALUES(?,?,?,?,?,?,?,?,?,0,0,NULL,NULL,NULL)",arrayOf<Any?>(owner,intent.childOperation,date,if(row==null)"DELETE"else"UPSERT",server,next,payload,hash,intent.generation))
  val receipt=org.json.JSONArray().put(1).put("Committed").put(date).put(next).put(hash).toString()
  db.execSQL("INSERT INTO rust_commit_receipts(namespace,operationId,intentDigest,version,resultJson) VALUES(?,?,?,?,?)",arrayOf<Any>(intent.namespace,intent.operation,digest,1,receipt))
  return Outcome.Committed(false,receipt)
 }
}
