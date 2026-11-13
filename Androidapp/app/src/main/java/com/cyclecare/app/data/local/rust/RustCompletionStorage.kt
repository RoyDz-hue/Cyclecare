package com.cyclecare.app.data.local.rust
import androidx.sqlite.db.SupportSQLiteDatabase
import com.cyclecare.app.data.local.HealthSyncPayloadCodec as Codec
import org.json.JSONArray
import org.json.JSONObject
/** Completion-only native same-thread driver. All capabilities remain private
 * to this admitted transaction. Original generic executor is the full fallback
 * only BEFORE native entry. Never retry/fallback after entry. */
internal object RustCompletionStorage {
 private val entered=ThreadLocal<Boolean>()
 fun apply(db:SupportSQLiteDatabase,owner:String,binding:String,intent:RustRoomBatch.Intent):RustRoomBatch.Outcome {
  check(entered.get()!=true){"completion executor reentry"}
  if(!RustTrackNative.completionReady())return RustRoomBatch.apply(db,owner,binding,intent)
  require(intent.mutations.size==2&&intent.reads.isEmpty())
  require(intent.mutations[0].after is RustDomainRows.Row.Profile&&intent.mutations[0].base.record=="profile")
  require(intent.mutations[1].after is RustDomainRows.Row.Settings&&intent.mutations[1].base.record=="settings")
  val frozen=intent.copy(reads=intent.reads.toList(),mutations=intent.mutations.toList())
  entered.set(true)
  val session=Session(db,owner,binding,frozen)
  try {return session.outcome(RustTrackNative.completionDriver(session))}finally{session.close();entered.remove()}
 }
 private class Session(private val db:SupportSQLiteDatabase,private val owner:String,private val binding:String,private val intent:RustRoomBatch.Intent){
  private val thread=Thread.currentThread();private var active=true;private var inCall=false;private var stage="receipt"
  private var digest="";private var replay:String?=null;private val next=LongArray(2);private val servers=LongArray(2);private val hashes=arrayOfNulls<String>(2);private val payloads=arrayOfNulls<String>(2);private val results=JSONArray();private var finalReceipt:String?=null
  private fun base(b:RustRoomBatch.Base)=JSONArray().put(b.record).put(b.revision).put(b.physical).put(b.serverRevision).put(b.storage?:JSONObject.NULL)
  private inline fun <T> admitted(expected:String,block:()->T):T {check(active&&Thread.currentThread()===thread&&db.inTransaction()&&entered.get()==true&&!inCall);check(stage==expected){"completion callback order"};inCall=true;try{return block()}finally{inCall=false}}
  fun close(){active=false}
  fun receipt():Int=admitted("receipt"){
  check(db.inTransaction());require(intent.namespace.isNotBlank()&&intent.operation.isNotBlank()&&intent.operation.length<=128&&intent.now>=0&&intent.generation>=0)
  require(intent.mutations.size==2&&intent.reads.isEmpty())
  val ids=mutableSetOf<String>();val children=mutableSetOf<String>()
  val changes=JSONArray();intent.mutations.forEach{m->require(ids.add(m.base.record)&&children.add(m.child)&&m.child.isNotBlank()&&m.child.length<=128);require(m.after==null||RustDomainRows.record(m.after)==m.base.record);changes.put(JSONArray().put(base(m.base)).put(m.child).put(RustDomainRows.physical(m.after)).put(RustDomainRows.payload(m.after)?:JSONObject.NULL))}
  digest=Codec.hash(JSONArray().put("android-room-batch-v2").put(binding).put(owner).put(intent.namespace).put(intent.operation).put(intent.generation).put(intent.now).put(intent.plannerDigest).put(JSONArray().apply{intent.reads.forEach{put(base(it))}}).put(changes).toString())

   db.query("SELECT intentDigest,version,resultJson FROM rust_commit_receipts WHERE namespace=? AND operationId=?",arrayOf(intent.namespace,intent.operation)).use{c->if(c.moveToFirst()){if(c.getInt(1)==2&&c.getString(0)==digest){replay=c.getString(2);stage="replay";return@admitted 1}else{stage="invalid";return@admitted 2}}}
   stage="valid0";0
  }
  fun valid(index:Int):Boolean=admitted("valid$index"){
   require(index in 0..1);val b=intent.mutations[index].base
   require(b.revision>=0&&b.serverRevision>=0)
   var rev=0L;var server=0L;db.query("SELECT revision,serverRevision FROM health_sync_revisions WHERE ownerId=? AND recordId=?",arrayOf(owner,b.record)).use{c->if(c.moveToFirst()){rev=c.getLong(0);server=c.getLong(1)}}
   require(rev>=0&&server>=0)
   val valid=rev==b.revision&&server==b.serverRevision&&RustDomainRows.physical(RustDomainRows.read(db,b.record))==b.physical&&(b.storage==null||RustDomainRows.storedPhysical(db,b.record)==b.storage)
   stage=if(!valid)"conflict" else if(index==0)"valid1" else "revision0";valid
  }
  fun revision(index:Int):Long=admitted("revision$index"){
   require(index in 0..1);val revision=intent.mutations[index].base.revision
   // Rust checks overflow before effects; retain independent expected args.
   next[index]=if(revision==Long.MAX_VALUE)Long.MIN_VALUE else revision+1
   stage=if(index==0)"revision1"else"server0";revision
  }
  fun server(index:Int):Long=admitted("server$index"){
   require(index in 0..1);check(next.all{it>0})
   val m=intent.mutations[index];var server=0L;db.query("SELECT serverRevision FROM health_sync_revisions WHERE ownerId=? AND recordId=?",arrayOf(owner,m.base.record)).use{c->if(c.moveToFirst())server=c.getLong(0)};require(server>=0)
   servers[index]=server;val payload=RustDomainRows.payload(m.after);payloads[index]=payload;hashes[index]=Codec.hash(payload)
   stage="domain$index";server
  }
  fun domain(index:Int):Unit=admitted("domain$index"){
   require(index in 0..1);val m=intent.mutations[index];RustDomainRows.write(db,m.base.record,m.after)
   check(RustDomainRows.physical(RustDomainRows.read(db,m.base.record))==RustDomainRows.physical(m.after)){"domain readback differs"}
   stage="writeRevision$index"
  }
  fun revisionWrite(index:Int,n:Long,server:Long):Unit=admitted("writeRevision$index"){
   require(index in 0..1&&n==next[index]&&server==servers[index]);val m=intent.mutations[index]
   db.execSQL("INSERT OR REPLACE INTO health_sync_revisions(ownerId,recordId,revision,payloadHash,tombstone,updatedAt,serverRevision) VALUES(?,?,?,?,?,?,?)",arrayOf<Any?>(owner,m.base.record,n,hashes[index],0,intent.now,server))
   stage="outbox$index"
  }
  fun outboxWrite(index:Int,n:Long,server:Long):Unit=admitted("outbox$index"){
   require(index in 0..1&&n==next[index]&&server==servers[index]);val m=intent.mutations[index]
   db.execSQL("INSERT INTO health_sync_outbox(ownerId,operationId,recordId,kind,baseRevision,revision,payloadJson,payloadHash,consentGeneration,attempts,nextAttemptAt,lastError,serverReceipt,serverRevision) VALUES(?,?,?,?,?,?,?,?,?,0,0,NULL,NULL,NULL)",arrayOf<Any?>(owner,m.child,m.base.record,"UPSERT",server,n,payloads[index],hashes[index],intent.generation))
   results.put(JSONArray().put(m.base.record).put(n).put(hashes[index]));stage=if(index==0)"server1"else"finish"
  }
  fun finish(a:Long,b:Long):Unit=admitted("finish"){
   require(a==next[0]&&b==next[1]);val receipt=JSONArray().put("android-room-batch-v2").put(results).toString()
   db.execSQL("INSERT INTO rust_commit_receipts(namespace,operationId,intentDigest,version,resultJson) VALUES(?,?,?,?,?)",arrayOf<Any>(intent.namespace,intent.operation,digest,2,receipt))
   finalReceipt=receipt;stage="done"
  }
  fun outcome(code:Int):RustRoomBatch.Outcome {check(active&&Thread.currentThread()===thread&&db.inTransaction()&&!inCall);return when(code){0->{check(stage=="done");RustRoomBatch.Outcome.Committed(false,checkNotNull(finalReceipt))};1->{check(stage=="replay");RustRoomBatch.Outcome.Committed(true,checkNotNull(replay))};2->{check(stage=="invalid");RustRoomBatch.Outcome.InvalidReuse};3->{check(stage=="conflict");RustRoomBatch.Outcome.Conflict};else->error("invalid completion outcome")}}
 }
}
