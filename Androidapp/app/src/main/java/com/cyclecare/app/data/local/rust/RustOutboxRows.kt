package com.cyclecare.app.data.local.rust
import androidx.sqlite.db.SupportSQLiteDatabase
import com.cyclecare.app.data.local.HealthSyncOutboxEntry
/** Same-transaction CAS effects only. No network, consent inference or ACK
 * invention: service verifies remote receipt identity before calling host.
 */
internal object RustOutboxRows {
 fun matches(db:SupportSQLiteDatabase,e:HealthSyncOutboxEntry):Boolean = db.query("SELECT recordId,revision,baseRevision,payloadHash,consentGeneration FROM health_sync_outbox WHERE ownerId=? AND operationId=?",arrayOf(e.ownerId,e.operationId)).use{c->c.moveToFirst()&&c.getString(0)==e.recordId&&c.getLong(1)==e.revision&&c.getLong(2)==e.baseRevision&&c.getString(3)==e.payloadHash&&c.getLong(4)==e.consentGeneration}
 fun acknowledge(db:SupportSQLiteDatabase,e:HealthSyncOutboxEntry,serverRevision:Long):Boolean {
  check(db.inTransaction());require(serverRevision==Math.addExact(e.baseRevision,1L));if(!matches(db,e))return false
  db.execSQL("UPDATE health_sync_revisions SET serverRevision=MAX(serverRevision,?) WHERE ownerId=? AND recordId=?",arrayOf(serverRevision,e.ownerId,e.recordId))
  db.execSQL("DELETE FROM health_sync_outbox WHERE ownerId=? AND operationId=? AND recordId=? AND revision=?",arrayOf(e.ownerId,e.operationId,e.recordId,e.revision))
  db.execSQL("UPDATE health_sync_outbox SET baseRevision=? WHERE ownerId=? AND recordId=? AND baseRevision=?",arrayOf(serverRevision,e.ownerId,e.recordId,e.baseRevision))
  return true
 }
 fun conflict(db:SupportSQLiteDatabase,e:HealthSyncOutboxEntry,remoteRevision:Long,payload:String?,tombstone:Boolean,now:Long):Boolean {
  check(db.inTransaction());require(remoteRevision>=0&&now>=0);if(!matches(db,e))return false
  var latest=e
  db.query("SELECT operationId,revision,payloadJson,kind FROM health_sync_outbox WHERE ownerId=? AND recordId=? ORDER BY revision DESC LIMIT 1",arrayOf(e.ownerId,e.recordId)).use{c->if(c.moveToFirst())latest=e.copy(operationId=c.getString(0),revision=c.getLong(1),payloadJson=if(c.isNull(2))null else c.getString(2),kind=c.getString(3))}
  db.execSQL("INSERT OR REPLACE INTO health_sync_conflicts(ownerId,recordId,operationId,localRevision,localPayloadJson,localKind,remoteRevision,remotePayloadJson,remoteTombstone,createdAt,resolvedAt,resolution) VALUES(?,?,?,?,?,?,?,?,?,?,NULL,NULL)",arrayOf<Any?>(e.ownerId,e.recordId,latest.operationId,latest.revision,latest.payloadJson,latest.kind,remoteRevision,payload,if(tombstone)1 else 0,now));return true
 }
 fun retry(db:SupportSQLiteDatabase,e:HealthSyncOutboxEntry,nextAttempt:Long,error:String):Boolean {
  check(db.inTransaction());require(nextAttempt>=0);if(!matches(db,e))return false
  val next=Math.addExact(e.attempts,1);return db.compileStatement("UPDATE health_sync_outbox SET attempts=?,nextAttemptAt=?,lastError=? WHERE ownerId=? AND operationId=? AND attempts=?").use{statement->statement.bindLong(1,next.toLong());statement.bindLong(2,nextAttempt);statement.bindString(3,error);statement.bindString(4,e.ownerId);statement.bindString(5,e.operationId);statement.bindLong(6,e.attempts.toLong());statement.executeUpdateDelete()==1}
 }
}
