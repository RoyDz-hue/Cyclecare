package com.cyclecare.app.data.local.rust
import androidx.sqlite.db.SupportSQLiteDatabase
import com.cyclecare.app.data.local.*
/** Only local admitted pull-page sequence, no network or release cutover. */
internal object RustRemoteStorage{
 private val entered=ThreadLocal<Boolean>()
 fun apply(db:SupportSQLiteDatabase,owner:String,changes:List<RustRemoteRows.Change>,now:Long):Int{
  check(entered.get()!=true){"remote executor reentry"}
  if(!RustTrackNative.completionReady())return RustRemoteRows.apply(db,owner,changes,now)
  val session=Session(db,owner,changes.toList(),now);entered.set(true)
  try{return session.outcome(RustTrackNative.remoteDriver(session))}finally{session.close();entered.remove()}
 }
 private class Session(private val db:SupportSQLiteDatabase,private val owner:String,private val changes:List<RustRemoteRows.Change>,private val now:Long){
  private val thread=Thread.currentThread();private var active=true;private var inCall=false;private var stage="ready";private var index=0;private var cursor=0L;private var count=0;private var local=0L;private var server=0L;private var next=0L;private var pending:HealthSyncOutboxEntry?=null;private var after:RustDomainRows.Row?=null
  private inline fun <T> admitted(expected:String,block:()->T):T{check(active&&Thread.currentThread()===thread&&db.inTransaction()&&entered.get()==true&&!inCall&&stage==expected){"remote callback authority/order"};inCall=true;try{return block()}finally{inCall=false}}
  private fun change(i:Int):RustRemoteRows.Change{require(i==index&&i in changes.indices);return changes[i]}
  fun close(){active=false}
  fun ready():Int=admitted("ready"){require(changes.size<=100&&now>=0);stage="cursor";changes.size}
  fun cursor():Long=admitted("cursor"){db.query("SELECT changeSeq FROM health_sync_cursors WHERE ownerId=?",arrayOf(owner)).use{c->if(c.moveToFirst())cursor=c.getLong(0)};stage=if(changes.isEmpty())"done"else"remote";cursor}
  fun remote(i:Int):Long=admitted("remote"){val c=change(i);stage="sequence";c.serverRevision}
  fun sequence(i:Int):Long=admitted("sequence"){val c=change(i);stage="inspect";c.sequence}
  fun inspect(i:Int):Unit=admitted("inspect"){
   val c=change(i);require(c.serverRevision>0&&c.sequence>cursor);val id=c.record;require(id=="settings"||id=="profile"||java.time.LocalDate.parse(id).toString()==id);require(!c.tombstone||c.payload==null);local=0;server=0;pending=null;after=null
   db.query("SELECT revision,serverRevision FROM health_sync_revisions WHERE ownerId=? AND recordId=?",arrayOf(owner,id)).use{c->if(c.moveToFirst()){local=c.getLong(0);server=c.getLong(1)}};stage="server"
  }
  fun server(i:Int):Long=admitted("server"){val c=change(i);stage=if(c.serverRevision>server)"pending"else"advance";server}
  fun pending(i:Int):Boolean=admitted("pending"){val c=change(i);val id=c.record;db.query("SELECT operationId,revision,payloadJson,kind FROM health_sync_outbox WHERE ownerId=? AND recordId=? ORDER BY revision DESC LIMIT 1",arrayOf(owner,id)).use{c->if(c.moveToFirst())pending=HealthSyncOutboxEntry(owner,c.getString(0),id,c.getString(3),0,c.getLong(1),if(c.isNull(2))null else c.getString(2),"",0)};stage=if(pending!=null)"conflict"else"prepare";pending!=null}
  fun conflict(i:Int):Unit=admitted("conflict"){val change=change(i);val id=change.record;val p=checkNotNull(pending)
   db.execSQL("INSERT OR REPLACE INTO health_sync_conflicts(ownerId,recordId,operationId,localRevision,localPayloadJson,localKind,remoteRevision,remotePayloadJson,remoteTombstone,createdAt,resolvedAt,resolution) VALUES(?,?,?,?,?,?,?,?,?,?,NULL,NULL)",arrayOf<Any?>(owner,id,p.operationId,if(local==0L)p.revision else local,p.payloadJson,p.kind,change.serverRevision,change.payload,if(change.tombstone)1 else 0,now));stage="advance"
  }
  fun prepare(i:Int):Unit=admitted("prepare"){val change=change(i);val id=change.record;val current=RustDomainRows.read(db,id)
   after=when(id){
    "settings"->RustDomainRows.Row.Settings(HealthSyncPayloadCodec.parseSettingsPayload(if(change.tombstone)HealthSyncPayloadCodec.settingsPayload(CycleSettings())else change.payload?:error("missing_remote"),id,(current as? RustDomainRows.Row.Settings)?.value))
    "profile"->RustDomainRows.Row.Profile(HealthSyncPayloadCodec.parseProfilePayload(if(change.tombstone)HealthSyncPayloadCodec.profilePayload(OnboardingProfile())else change.payload?:error("missing_remote"),id,(current as? RustDomainRows.Row.Profile)?.value))
    else->if(change.tombstone)null else RustDomainRows.Row.Log(HealthSyncPayloadCodec.parseLogPayload(change.payload?:error("missing_remote"),id))
   };stage="local"
  }
  fun local(i:Int):Long=admitted("local"){change(i);next=if(local==Long.MAX_VALUE)Long.MIN_VALUE else local+1;stage="domain";local}
  fun domain(i:Int):Unit=admitted("domain"){val c=change(i);RustDomainRows.write(db,c.record,after);stage="revision"}
  fun revision(i:Int,n:Long):Unit=admitted("revision"){val change=change(i);check(n==next);val id=change.record;db.execSQL("INSERT OR REPLACE INTO health_sync_revisions VALUES(?,?,?,?,?,?,?)",arrayOf<Any?>(owner,id,n,if(change.tombstone)null else HealthSyncPayloadCodec.hash(change.payload),if(change.tombstone)1 else 0,now,change.serverRevision));count++;stage="advance"}
  fun advance(i:Int,seq:Long):Unit=admitted("advance"){val c=change(i);check(seq==c.sequence&&seq>cursor);cursor=seq;db.execSQL("INSERT OR REPLACE INTO health_sync_cursors(ownerId,changeSeq) VALUES(?,?)",arrayOf(owner,cursor));index++;stage=if(index==changes.size)"done"else"remote"}
  fun outcome(code:Int):Int{check(active&&Thread.currentThread()===thread&&db.inTransaction()&&!inCall&&stage=="done"&&code==count);return count}
 }
}
