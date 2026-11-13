package com.cyclecare.app.data.local.rust
import androidx.sqlite.db.SupportSQLiteDatabase
import com.cyclecare.app.data.local.HealthSyncOutboxEntry
/** Only capture pending conflict. Resolution and pull-page are unchanged. */
internal object RustConflictStorage {
 private val entered=ThreadLocal<Boolean>()
 fun apply(db:SupportSQLiteDatabase,e:HealthSyncOutboxEntry,remoteRevision:Long,payload:String?,tombstone:Boolean,now:Long):Boolean {
  check(entered.get()!=true){"conflict executor reentry"}
  if(!RustTrackNative.completionReady())return RustOutboxRows.conflict(db,e,remoteRevision,payload,tombstone,now)
  val session=Session(db,e,remoteRevision,payload,tombstone,now);entered.set(true)
  try{return session.outcome(RustTrackNative.conflictDriver(session))}finally{session.close();entered.remove()}
 }
 private class Session(private val db:SupportSQLiteDatabase,private val e:HealthSyncOutboxEntry,private val remoteRevision:Long,private val payload:String?,private val tombstone:Boolean,private val now:Long){
  private val thread=Thread.currentThread();private var active=true;private var inCall=false;private var stage="ready";private var matched=false;private var result=false;private var selected=e
  private inline fun <T> admitted(expected:String,block:()->T):T {check(active&&Thread.currentThread()===thread&&db.inTransaction()&&entered.get()==true&&!inCall&&stage==expected){"conflict callback authority/order"};inCall=true;try{return block()}finally{inCall=false}}
  fun close(){active=false}
  fun ready():Unit=admitted("ready"){require(remoteRevision>=0&&now>=0);stage="matches"}
  fun matches():Boolean=admitted("matches"){matched=RustOutboxRows.matches(db,e);stage=if(matched)"latest"else"done";matched}
  fun latest():Unit=admitted("latest"){check(matched);var latest=e
   db.query("SELECT operationId,revision,payloadJson,kind FROM health_sync_outbox WHERE ownerId=? AND recordId=? ORDER BY revision DESC LIMIT 1",arrayOf(e.ownerId,e.recordId)).use{c->if(c.moveToFirst())latest=e.copy(operationId=c.getString(0),revision=c.getLong(1),payloadJson=if(c.isNull(2))null else c.getString(2),kind=c.getString(3))}
   selected=latest;stage="write"
  }
  fun write():Unit=admitted("write"){check(matched);val latest=selected
   db.execSQL("INSERT OR REPLACE INTO health_sync_conflicts(ownerId,recordId,operationId,localRevision,localPayloadJson,localKind,remoteRevision,remotePayloadJson,remoteTombstone,createdAt,resolvedAt,resolution) VALUES(?,?,?,?,?,?,?,?,?,?,NULL,NULL)",arrayOf<Any?>(e.ownerId,e.recordId,latest.operationId,latest.revision,latest.payloadJson,latest.kind,remoteRevision,payload,if(tombstone)1 else 0,now));result=true;stage="done"
  }
  fun outcome(code:Int):Boolean{check(active&&Thread.currentThread()===thread&&db.inTransaction()&&!inCall&&stage=="done");check(code in 0..1&&(code==1)==result);return result}
 }
}
