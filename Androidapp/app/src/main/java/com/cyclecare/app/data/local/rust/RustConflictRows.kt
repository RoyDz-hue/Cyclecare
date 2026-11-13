package com.cyclecare.app.data.local.rust
import androidx.sqlite.db.SupportSQLiteDatabase
import com.cyclecare.app.data.local.*
/** Typed host conflict effects, same production semantics, no network release.
 * Host identity/lifecycle guard and same Room transaction span every effect.
 */
internal class ConflictRejected(val reason:String):IllegalStateException(reason)
internal object RustConflictRows {
 private data class Conflict(val local:Long,val localPayload:String?,val kind:String,val remote:Long,val remotePayload:String?,val tombstone:Boolean)
 fun apply(db:SupportSQLiteDatabase,owner:String,record:String,retain:Boolean,child:String,now:Long,generation:Long){
  check(db.inTransaction());require(now>=0&&generation>=0)
  val conflict=db.query("SELECT localRevision,localPayloadJson,localKind,remoteRevision,remotePayloadJson,remoteTombstone FROM health_sync_conflicts WHERE ownerId=? AND recordId=? AND resolvedAt IS NULL",arrayOf(owner,record)).use{c->if(!c.moveToFirst())return else Conflict(c.getLong(0),if(c.isNull(1))null else c.getString(1),c.getString(2),c.getLong(3),if(c.isNull(4))null else c.getString(4),c.getInt(5)==1)}
  var revision=0L;var priorHash:String?=null;var present=false;var priorTombstone=false
  db.query("SELECT revision,payloadHash,tombstone FROM health_sync_revisions WHERE ownerId=? AND recordId=?",arrayOf(owner,record)).use{c->if(c.moveToFirst()){present=true;revision=c.getLong(0);priorHash=if(c.isNull(1))null else c.getString(1);priorTombstone=c.getInt(2)==1}}
  if((present&&(revision!=conflict.local||priorHash!=HealthSyncPayloadCodec.hash(conflict.localPayload)))||(!present&&conflict.local!=0L))return
  if(retain&&!present)return
  val next=Math.addExact(revision,1L);if(if(retain)conflict.remote<0 else conflict.remote<=0)throw ConflictRejected("remote_record_missing_accept_unavailable")
  val payload:String?;val hash:String?
  if(retain){
   if(conflict.tombstone)throw ConflictRejected("keep_local_unavailable_server_tombstone");require(child.isNotBlank()&&child.length<=128)
   payload=conflict.localPayload;hash=HealthSyncPayloadCodec.hash(payload)
  }else{
   val current=RustDomainRows.read(db,record)
   val after=if(conflict.tombstone){require(conflict.remotePayload==null);when(record){
    "settings"->RustDomainRows.Row.Settings(HealthSyncPayloadCodec.parseSettingsPayload(HealthSyncPayloadCodec.settingsPayload(CycleSettings()),record,(current as? RustDomainRows.Row.Settings)?.value))
    "profile"->RustDomainRows.Row.Profile(HealthSyncPayloadCodec.parseProfilePayload(HealthSyncPayloadCodec.profilePayload(OnboardingProfile()),record,(current as? RustDomainRows.Row.Profile)?.value))
    else->null
   }}else{val remote=conflict.remotePayload?:error("missing_remote_payload");when(record){
    "settings"->RustDomainRows.Row.Settings(HealthSyncPayloadCodec.parseSettingsPayload(remote,record,(current as? RustDomainRows.Row.Settings)?.value))
    "profile"->RustDomainRows.Row.Profile(HealthSyncPayloadCodec.parseProfilePayload(remote,record,(current as? RustDomainRows.Row.Profile)?.value))
    else->RustDomainRows.Row.Log(HealthSyncPayloadCodec.parseLogPayload(remote,record))
   }}
   RustDomainRows.write(db,record,after);payload=conflict.remotePayload;hash=if(conflict.tombstone)null else HealthSyncPayloadCodec.hash(payload)
  }
  db.execSQL("DELETE FROM health_sync_outbox WHERE ownerId=? AND recordId=? AND revision<=?",arrayOf(owner,record,revision))
  db.execSQL("INSERT OR REPLACE INTO health_sync_revisions(ownerId,recordId,revision,payloadHash,tombstone,updatedAt,serverRevision) VALUES(?,?,?,?,?,?,?)",arrayOf<Any?>(owner,record,next,hash,if(if(retain)priorTombstone else conflict.tombstone)1 else 0,now,conflict.remote))
  if(retain)db.execSQL("INSERT INTO health_sync_outbox(ownerId,operationId,recordId,kind,baseRevision,revision,payloadJson,payloadHash,consentGeneration,attempts,nextAttemptAt,lastError,serverReceipt,serverRevision) VALUES(?,?,?,?,?,?,?,?,?,0,0,NULL,NULL,NULL)",arrayOf<Any?>(owner,child,record,conflict.kind,conflict.remote,next,payload,hash,generation))
  db.execSQL("UPDATE health_sync_conflicts SET resolvedAt=?,resolution=? WHERE ownerId=? AND recordId=? AND resolvedAt IS NULL",arrayOf<Any>(now,if(retain)"RETAIN_LOCAL"else"ACCEPT_REMOTE",owner,record))
 }
}
