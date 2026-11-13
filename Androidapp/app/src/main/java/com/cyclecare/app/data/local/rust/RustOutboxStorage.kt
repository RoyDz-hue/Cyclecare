package com.cyclecare.app.data.local.rust
import androidx.sqlite.db.SupportSQLiteDatabase
import com.cyclecare.app.data.local.HealthSyncOutboxEntry
/** ACK/retry only. Original complete effects remain pre-entry fallback. */
internal object RustOutboxStorage {
 private val entered=ThreadLocal<Boolean>()
 fun acknowledge(db:SupportSQLiteDatabase,e:HealthSyncOutboxEntry,server:Long):Boolean {
  check(entered.get()!=true){"outbox executor reentry"}
  if(!RustTrackNative.completionReady())return RustOutboxRows.acknowledge(db,e,server)
  return apply(Session(db,e,0,server,0,""),0)
 }
 fun retry(db:SupportSQLiteDatabase,e:HealthSyncOutboxEntry,nextAttempt:Long,error:String):Boolean {
  check(entered.get()!=true){"outbox executor reentry"}
  if(!RustTrackNative.completionReady())return RustOutboxRows.retry(db,e,nextAttempt,error)
  return apply(Session(db,e,1,0,nextAttempt,error),1)
 }
 private fun apply(session:Session,mode:Int):Boolean {
  entered.set(true)
  try{return session.outcome(RustTrackNative.outboxDriver(session,mode))}finally{session.close();entered.remove()}
 }
 private class Session(private val db:SupportSQLiteDatabase,private val e:HealthSyncOutboxEntry,private val mode:Int,private val server:Long,private val nextAttempt:Long,private val error:String){
  private val thread=Thread.currentThread();private var active=true;private var inCall=false;private var stage="ready";private var expectedServer=0L;private var expectedAttempts=0;private var matched=false;private var result=false
  private inline fun <T> admitted(expected:String,block:()->T):T {check(active&&Thread.currentThread()===thread&&db.inTransaction()&&entered.get()==true&&!inCall&&stage==expected){"outbox callback authority/order"};inCall=true;try{return block()}finally{inCall=false}}
  fun close(){active=false}
  fun ready():Unit=admitted("ready") {require(mode in 0..1);if(mode==1)require(nextAttempt>=0);stage=if(mode==0)"base"else"matches"}
  fun base():Long=admitted("base") {check(mode==0);expectedServer=if(e.baseRevision==Long.MAX_VALUE)Long.MIN_VALUE else e.baseRevision+1;stage="acknowledged";e.baseRevision}
  fun acknowledged():Long=admitted("acknowledged") {check(mode==0);stage=if(server==expectedServer)"matches"else"invalid";server}
  fun matches():Boolean=admitted("matches") {check(mode==1||server==expectedServer);matched=RustOutboxRows.matches(db,e);stage=if(!matched)"done"else if(mode==0)"max"else"attempts";matched}
  fun attempts():Int=admitted("attempts") {check(mode==1&&matched);expectedAttempts=if(e.attempts==Int.MAX_VALUE)Int.MIN_VALUE else e.attempts+1;stage="retry";e.attempts}
  fun maxServer(value:Long):Unit=admitted("max") {check(mode==0&&matched&&value==server&&value==expectedServer);db.execSQL("UPDATE health_sync_revisions SET serverRevision=MAX(serverRevision,?) WHERE ownerId=? AND recordId=?",arrayOf(server,e.ownerId,e.recordId));stage="delete"}
  fun deleteAcked():Unit=admitted("delete") {check(mode==0&&matched);db.execSQL("DELETE FROM health_sync_outbox WHERE ownerId=? AND operationId=? AND recordId=? AND revision=?",arrayOf(e.ownerId,e.operationId,e.recordId,e.revision));stage="rebase"}
  fun rebase(value:Long):Unit=admitted("rebase") {check(mode==0&&matched&&value==server&&value==expectedServer);db.execSQL("UPDATE health_sync_outbox SET baseRevision=? WHERE ownerId=? AND recordId=? AND baseRevision=?",arrayOf(server,e.ownerId,e.recordId,e.baseRevision));result=true;stage="done"}
  fun retryCas(next:Int):Boolean=admitted("retry") {check(mode==1&&matched&&e.attempts!=Int.MAX_VALUE&&next==expectedAttempts);result=db.compileStatement("UPDATE health_sync_outbox SET attempts=?,nextAttemptAt=?,lastError=? WHERE ownerId=? AND operationId=? AND attempts=?").use{statement->statement.bindLong(1,next.toLong());statement.bindLong(2,nextAttempt);statement.bindString(3,error);statement.bindString(4,e.ownerId);statement.bindString(5,e.operationId);statement.bindLong(6,e.attempts.toLong());statement.executeUpdateDelete()==1};stage="done";result}
  fun outcome(code:Int):Boolean {check(active&&Thread.currentThread()===thread&&db.inTransaction()&&!inCall&&stage=="done");check(code in 0..1&&(code==1)==result);return result}
 }
}
