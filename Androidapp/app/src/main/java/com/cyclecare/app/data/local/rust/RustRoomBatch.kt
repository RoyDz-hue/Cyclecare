package com.cyclecare.app.data.local.rust
import androidx.sqlite.db.SupportSQLiteDatabase
import com.cyclecare.app.data.local.HealthSyncPayloadCodec as Codec
import org.json.JSONArray
import org.json.JSONObject
/** Same-connection consumer for prevalidated typed plans. Deliberately not a
 * parser for the Rust V2 format. This Android V2 receipt format is distinct.
 * Full physical dependencies preserve local-only fields omitted by sync codec.
 */
internal object RustRoomBatch {
 data class Base(val record:String,val revision:Long,val physical:String,val serverRevision:Long=0,val storage:String?=null)
 data class Mutation(val base:Base,val after:RustDomainRows.Row?,val child:String)
 data class Intent(val namespace:String,val operation:String,val generation:Long,val now:Long,val reads:List<Base>,val mutations:List<Mutation>,val plannerDigest:String="typed-host")
 sealed class Outcome {data class Committed(val replay:Boolean,val receipt:String):Outcome();object Conflict:Outcome();object InvalidReuse:Outcome()}
 private fun base(b:Base)=JSONArray().put(b.record).put(b.revision).put(b.physical).put(b.serverRevision).put(b.storage?:JSONObject.NULL)
 fun apply(db:SupportSQLiteDatabase,owner:String,binding:String,intent:Intent):Outcome {
  check(db.inTransaction());require(intent.namespace.isNotBlank()&&intent.operation.isNotBlank()&&intent.operation.length<=128&&intent.now>=0&&intent.generation>=0)
  require(intent.mutations.isNotEmpty()&&intent.mutations.size<=16&&intent.reads.size<=16)
  val ids=mutableSetOf<String>();val children=mutableSetOf<String>()
  val changes=JSONArray();intent.mutations.forEach{m->require(ids.add(m.base.record)&&children.add(m.child)&&m.child.isNotBlank()&&m.child.length<=128);require(m.after==null||RustDomainRows.record(m.after)==m.base.record);changes.put(JSONArray().put(base(m.base)).put(m.child).put(RustDomainRows.physical(m.after)).put(RustDomainRows.payload(m.after)?:JSONObject.NULL))}
  val digest=Codec.hash(JSONArray().put("android-room-batch-v2").put(binding).put(owner).put(intent.namespace).put(intent.operation).put(intent.generation).put(intent.now).put(intent.plannerDigest).put(JSONArray().apply{intent.reads.forEach{put(base(it))}}).put(changes).toString())
  db.query("SELECT intentDigest,version,resultJson FROM rust_commit_receipts WHERE namespace=? AND operationId=?",arrayOf(intent.namespace,intent.operation)).use{c->if(c.moveToFirst())return if(c.getInt(1)==2&&c.getString(0)==digest)Outcome.Committed(true,c.getString(2))else Outcome.InvalidReuse}
  fun valid(b:Base):Boolean {
   require(b.revision>=0&&b.serverRevision>=0);if(b.record!="settings"&&b.record!="profile")require(java.time.LocalDate.parse(b.record).toString()==b.record)
   var rev=0L;var server=0L;db.query("SELECT revision,serverRevision FROM health_sync_revisions WHERE ownerId=? AND recordId=?",arrayOf(owner,b.record)).use{c->if(c.moveToFirst()){rev=c.getLong(0);server=c.getLong(1)}}
   require(rev>=0&&server>=0);return rev==b.revision&&server==b.serverRevision&&RustDomainRows.physical(RustDomainRows.read(db,b.record))==b.physical&&(b.storage==null||RustDomainRows.storedPhysical(db,b.record)==b.storage)
  }
  if(intent.reads.any{!valid(it)}||intent.mutations.any{!valid(it.base)})return Outcome.Conflict
  val next=intent.mutations.map{Math.addExact(it.base.revision,1L)} // validate ALL overflow before effects
  val results=JSONArray()
  intent.mutations.forEachIndexed{index,m->
   var server=0L;db.query("SELECT serverRevision FROM health_sync_revisions WHERE ownerId=? AND recordId=?",arrayOf(owner,m.base.record)).use{c->if(c.moveToFirst())server=c.getLong(0)};require(server>=0)
   val payload=RustDomainRows.payload(m.after);val hash=Codec.hash(payload)
   RustDomainRows.write(db,m.base.record,m.after)
   check(RustDomainRows.physical(RustDomainRows.read(db,m.base.record))==RustDomainRows.physical(m.after)){"domain readback differs"}
   db.execSQL("INSERT OR REPLACE INTO health_sync_revisions(ownerId,recordId,revision,payloadHash,tombstone,updatedAt,serverRevision) VALUES(?,?,?,?,?,?,?)",arrayOf<Any?>(owner,m.base.record,next[index],if(m.after==null)null else hash,if(m.after==null)1 else 0,intent.now,server))
   db.execSQL("INSERT INTO health_sync_outbox(ownerId,operationId,recordId,kind,baseRevision,revision,payloadJson,payloadHash,consentGeneration,attempts,nextAttemptAt,lastError,serverReceipt,serverRevision) VALUES(?,?,?,?,?,?,?,?,?,0,0,NULL,NULL,NULL)",arrayOf<Any?>(owner,m.child,m.base.record,if(m.after==null)"DELETE"else"UPSERT",server,next[index],payload,hash,intent.generation))
   results.put(JSONArray().put(m.base.record).put(next[index]).put(hash))
  }
  val receipt=JSONArray().put("android-room-batch-v2").put(results).toString()
  db.execSQL("INSERT INTO rust_commit_receipts(namespace,operationId,intentDigest,version,resultJson) VALUES(?,?,?,?,?)",arrayOf<Any>(intent.namespace,intent.operation,digest,2,receipt))
  return Outcome.Committed(false,receipt)
 }
}
