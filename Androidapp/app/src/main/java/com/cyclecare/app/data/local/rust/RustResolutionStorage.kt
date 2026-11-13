package com.cyclecare.app.data.local.rust
import androidx.sqlite.db.SupportSQLiteDatabase
import com.cyclecare.app.data.local.*
/** Local admitted conflict resolution only. Host transaction and identity unchanged. */
internal object RustResolutionStorage{
 private val entered=ThreadLocal<Boolean>()
 fun apply(db:SupportSQLiteDatabase,owner:String,record:String,retain:Boolean,child:String,now:Long,generation:Long){
  check(entered.get()!=true){"resolution executor reentry"}
  if(!RustTrackNative.completionReady())return RustConflictRows.apply(db,owner,record,retain,child,now,generation)
  val session=Session(db,owner,record,retain,child,now,generation);entered.set(true)
  try{session.outcome(RustTrackNative.resolutionDriver(session))}finally{session.close();entered.remove()}
 }
 private class Session(private val db:SupportSQLiteDatabase,private val owner:String,private val record:String,private val retain:Boolean,private val child:String,private val now:Long,private val generation:Long){
  private data class Conflict(val local:Long,val localPayload:String?,val kind:String,val remote:Long,val remotePayload:String?,val tombstone:Boolean)
  private val thread=Thread.currentThread();private var active=true;private var inCall=false;private var stage="ready";private var conflict:Conflict?=null;private var present=false;private var revision=0L;private var priorHash:String?=null;private var priorTombstone=false;private var hashChecked=false;private var hashMatched=false;private var next=0L;private var payload:String?=null;private var hash:String?=null;private var after:RustDomainRows.Row?=null;private var result=false
  private inline fun <T> admitted(expected:String,block:()->T):T{check(active&&Thread.currentThread()===thread&&db.inTransaction()&&entered.get()==true&&!inCall&&stage==expected){"resolution callback authority/order"};inCall=true;try{return block()}finally{inCall=false}}
  private fun eligible():Boolean{val c=conflict?:return false;return (if(present)revision==c.local&&hashChecked&&hashMatched else c.local==0L)&&!(retain&&!present)}
  fun close(){active=false}
  fun ready():Unit=admitted("ready"){require(now>=0&&generation>=0);stage="metadata"}
  fun metadata():LongArray=admitted("metadata"){
   conflict=db.query("SELECT localRevision,localPayloadJson,localKind,remoteRevision,remotePayloadJson,remoteTombstone FROM health_sync_conflicts WHERE ownerId=? AND recordId=? AND resolvedAt IS NULL",arrayOf(owner,record)).use{c->if(!c.moveToFirst())null else Conflict(c.getLong(0),if(c.isNull(1))null else c.getString(1),c.getString(2),c.getLong(3),if(c.isNull(4))null else c.getString(4),c.getInt(5)==1)}
   val c=conflict;stage="decision";if(c==null)longArrayOf()else{
    db.query("SELECT revision,payloadHash,tombstone FROM health_sync_revisions WHERE ownerId=? AND recordId=?",arrayOf(owner,record)).use{c->if(c.moveToFirst()){present=true;revision=c.getLong(0);priorHash=if(c.isNull(1))null else c.getString(1);priorTombstone=c.getInt(2)==1}}
    longArrayOf(if(present)1 else 0,revision,c.local,c.remote,if(c.tombstone)1 else 0,if(retain)1 else 0)
   }
  }
  fun hashMatches():Boolean=admitted("decision"){val c=checkNotNull(conflict);check(present&&revision==c.local&&!hashChecked);hashMatched=priorHash==HealthSyncPayloadCodec.hash(c.localPayload);hashChecked=true;hashMatched}
  fun skip():Unit=admitted("decision"){val c=conflict;check(c==null||if(present)revision!=c.local||(hashChecked&&!hashMatched)else c.local!=0L||retain);stage="done"}
  fun prepare(n:Long,r:Boolean):Unit=admitted("decision"){
   val conflict=checkNotNull(conflict);check(eligible()&&r==retain);next=if(revision==Long.MAX_VALUE)Long.MIN_VALUE else revision+1;check(n==next);check(if(retain)conflict.remote>=0&&!conflict.tombstone else conflict.remote>0)
   if(retain){require(child.isNotBlank()&&child.length<=128);payload=conflict.localPayload;hash=HealthSyncPayloadCodec.hash(payload);stage="delete"}else{
    val current=RustDomainRows.read(db,record)
    after=if(conflict.tombstone){require(conflict.remotePayload==null);when(record){
     "settings"->RustDomainRows.Row.Settings(HealthSyncPayloadCodec.parseSettingsPayload(HealthSyncPayloadCodec.settingsPayload(CycleSettings()),record,(current as? RustDomainRows.Row.Settings)?.value))
     "profile"->RustDomainRows.Row.Profile(HealthSyncPayloadCodec.parseProfilePayload(HealthSyncPayloadCodec.profilePayload(OnboardingProfile()),record,(current as? RustDomainRows.Row.Profile)?.value))
     else->null
    }}else{val remote=conflict.remotePayload?:error("missing_remote_payload");when(record){
     "settings"->RustDomainRows.Row.Settings(HealthSyncPayloadCodec.parseSettingsPayload(remote,record,(current as? RustDomainRows.Row.Settings)?.value))
     "profile"->RustDomainRows.Row.Profile(HealthSyncPayloadCodec.parseProfilePayload(remote,record,(current as? RustDomainRows.Row.Profile)?.value))
     else->RustDomainRows.Row.Log(HealthSyncPayloadCodec.parseLogPayload(remote,record))
    }};stage="domain"
   }
  }
  fun domain():Unit=admitted("domain"){val conflict=checkNotNull(conflict);check(!retain);RustDomainRows.write(db,record,after);payload=conflict.remotePayload;hash=if(conflict.tombstone)null else HealthSyncPayloadCodec.hash(payload);stage="delete"}
  fun delete():Unit=admitted("delete"){db.execSQL("DELETE FROM health_sync_outbox WHERE ownerId=? AND recordId=? AND revision<=?",arrayOf(owner,record,revision));stage="revision"}
  fun revision(n:Long):Unit=admitted("revision"){val conflict=checkNotNull(conflict);check(n==next);db.execSQL("INSERT OR REPLACE INTO health_sync_revisions(ownerId,recordId,revision,payloadHash,tombstone,updatedAt,serverRevision) VALUES(?,?,?,?,?,?,?)",arrayOf<Any?>(owner,record,n,hash,if(if(retain)priorTombstone else conflict.tombstone)1 else 0,now,conflict.remote));stage=if(retain)"outbox"else"resolve"}
  fun outbox():Unit=admitted("outbox"){val conflict=checkNotNull(conflict);check(retain);db.execSQL("INSERT INTO health_sync_outbox(ownerId,operationId,recordId,kind,baseRevision,revision,payloadJson,payloadHash,consentGeneration,attempts,nextAttemptAt,lastError,serverReceipt,serverRevision) VALUES(?,?,?,?,?,?,?,?,?,0,0,NULL,NULL,NULL)",arrayOf<Any?>(owner,child,record,conflict.kind,conflict.remote,next,payload,hash,generation));stage="resolve"}
  fun resolve():Unit=admitted("resolve"){db.execSQL("UPDATE health_sync_conflicts SET resolvedAt=?,resolution=? WHERE ownerId=? AND recordId=? AND resolvedAt IS NULL",arrayOf<Any>(now,if(retain)"RETAIN_LOCAL"else"ACCEPT_REMOTE",owner,record));result=true;stage="done"}
  fun outcome(code:Int){check(active&&Thread.currentThread()===thread&&db.inTransaction()&&!inCall&&stage=="done"&&code in 0..1&&(code==1)==result)}
 }
}
