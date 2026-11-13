package com.cyclecare.app.data.local.rust
import androidx.sqlite.db.SupportSQLiteDatabase
import com.cyclecare.app.data.local.*
/** Typed local pull-page consumer. Network and release/consent checks stay in
 * sync service; host provides captured full identity and server generation.
 */
internal object RustRemoteRows {
 data class Change(val record:String,val serverRevision:Long,val sequence:Long,val payload:String?,val tombstone:Boolean)
 fun apply(db:SupportSQLiteDatabase,owner:String,changes:List<Change>,now:Long):Int {
  check(db.inTransaction());require(changes.size<=100&&now>=0)
  var cursor=0L;db.query("SELECT changeSeq FROM health_sync_cursors WHERE ownerId=?",arrayOf(owner)).use{c->if(c.moveToFirst())cursor=c.getLong(0)}
  var count=0
  for(change in changes){
   require(change.serverRevision>0&&change.sequence>cursor)
   val id=change.record;require(id=="settings"||id=="profile"||java.time.LocalDate.parse(id).toString()==id)
   require(!change.tombstone||change.payload==null)
   var local=0L;var server=0L
   db.query("SELECT revision,serverRevision FROM health_sync_revisions WHERE ownerId=? AND recordId=?",arrayOf(owner,id)).use{c->if(c.moveToFirst()){local=c.getLong(0);server=c.getLong(1)}}
   if(change.serverRevision>server){
    var pending:HealthSyncOutboxEntry?=null
    db.query("SELECT operationId,revision,payloadJson,kind FROM health_sync_outbox WHERE ownerId=? AND recordId=? ORDER BY revision DESC LIMIT 1",arrayOf(owner,id)).use{c->if(c.moveToFirst())pending=HealthSyncOutboxEntry(owner,c.getString(0),id,c.getString(3),0,c.getLong(1),if(c.isNull(2))null else c.getString(2),"",0)}
    val p=pending
    if(p!=null){db.execSQL("INSERT OR REPLACE INTO health_sync_conflicts(ownerId,recordId,operationId,localRevision,localPayloadJson,localKind,remoteRevision,remotePayloadJson,remoteTombstone,createdAt,resolvedAt,resolution) VALUES(?,?,?,?,?,?,?,?,?,?,NULL,NULL)",arrayOf<Any?>(owner,id,p.operationId,if(local==0L)p.revision else local,p.payloadJson,p.kind,change.serverRevision,change.payload,if(change.tombstone)1 else 0,now))}
    else{
     val current=RustDomainRows.read(db,id)
     val after=when(id){
      "settings"->RustDomainRows.Row.Settings(HealthSyncPayloadCodec.parseSettingsPayload(if(change.tombstone)HealthSyncPayloadCodec.settingsPayload(CycleSettings())else change.payload?:error("missing_remote"),id,(current as? RustDomainRows.Row.Settings)?.value))
      "profile"->RustDomainRows.Row.Profile(HealthSyncPayloadCodec.parseProfilePayload(if(change.tombstone)HealthSyncPayloadCodec.profilePayload(OnboardingProfile())else change.payload?:error("missing_remote"),id,(current as? RustDomainRows.Row.Profile)?.value))
      else->if(change.tombstone)null else RustDomainRows.Row.Log(HealthSyncPayloadCodec.parseLogPayload(change.payload?:error("missing_remote"),id))
     }
     val next=Math.addExact(local,1L);RustDomainRows.write(db,id,after)
     db.execSQL("INSERT OR REPLACE INTO health_sync_revisions VALUES(?,?,?,?,?,?,?)",arrayOf<Any?>(owner,id,next,if(change.tombstone)null else HealthSyncPayloadCodec.hash(change.payload),if(change.tombstone)1 else 0,now,change.serverRevision));count++
    }
   }
   cursor=change.sequence;db.execSQL("INSERT OR REPLACE INTO health_sync_cursors(ownerId,changeSeq) VALUES(?,?)",arrayOf(owner,cursor))
  };return count
 }
}
