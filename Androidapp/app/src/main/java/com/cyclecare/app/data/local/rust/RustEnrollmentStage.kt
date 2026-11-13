package com.cyclecare.app.data.local.rust
import androidx.sqlite.db.SupportSQLiteDatabase
/** Inactive staging protocol. READY is atomic enrollment visibility only, not
 * upload/ACK. Domain-trigger epoch catches every SQL writer without relying on
 * a Kotlin callsite inventory. Sender integration remains separate.
 */
internal object RustEnrollmentStage {
 fun schema(db:SupportSQLiteDatabase){
  db.execSQL("CREATE TABLE IF NOT EXISTS rust_domain_epoch(id INTEGER NOT NULL PRIMARY KEY CHECK(id=1),value INTEGER NOT NULL)")
  db.execSQL("INSERT OR IGNORE INTO rust_domain_epoch VALUES(1,0)")
  for(table in listOf("cycle_day_logs","cycle_settings","onboarding_profiles"))for(action in listOf("INSERT","UPDATE","DELETE"))db.execSQL("CREATE TRIGGER IF NOT EXISTS rust_epoch_${table}_${action.lowercase()} AFTER $action ON $table BEGIN SELECT CASE WHEN (SELECT value FROM rust_domain_epoch WHERE id=1)=9223372036854775807 THEN RAISE(ABORT,'domain_epoch_overflow') END; UPDATE rust_domain_epoch SET value=value+1 WHERE id=1; END")
  db.execSQL("CREATE TABLE IF NOT EXISTS rust_enrollments(namespace TEXT NOT NULL,operation TEXT NOT NULL,binding TEXT NOT NULL,epoch INTEGER NOT NULL,state TEXT NOT NULL,nextRecord TEXT NOT NULL,recordCount INTEGER NOT NULL,nextSend TEXT NOT NULL DEFAULT '',PRIMARY KEY(namespace,operation))")
  db.execSQL("CREATE TABLE IF NOT EXISTS rust_enrollment_rows(namespace TEXT NOT NULL,operation TEXT NOT NULL,record TEXT NOT NULL,payload TEXT NOT NULL,physical TEXT NOT NULL,revision INTEGER NOT NULL,serverRevision INTEGER NOT NULL,child TEXT NOT NULL,attempts INTEGER NOT NULL DEFAULT 0,nextAttempt INTEGER NOT NULL DEFAULT 0,PRIMARY KEY(namespace,operation,record))")
 }
 fun epoch(db:SupportSQLiteDatabase):Long=db.query("SELECT value FROM rust_domain_epoch WHERE id=1").use{c->check(c.moveToFirst());c.getLong(0)}
 fun senderSchema(db:SupportSQLiteDatabase){
  db.execSQL("CREATE TABLE IF NOT EXISTS rust_enrollment_receipts(namespace TEXT NOT NULL,operation TEXT NOT NULL,record TEXT NOT NULL,child TEXT NOT NULL,serverRevision INTEGER NOT NULL,receiptHash TEXT NOT NULL,adopted INTEGER NOT NULL DEFAULT 0,PRIMARY KEY(namespace,operation,record))")
 }
 fun begin(db:SupportSQLiteDatabase,namespace:String,operation:String,binding:String){
  check(db.inTransaction());require(namespace.isNotBlank()&&operation.isNotBlank())
  // Limit unfinished enrollment per namespace. Never bypass exhausted or
  // unknown remote effects by generating a new operation/child budget.
  check(!db.query("SELECT 1 FROM rust_enrollments WHERE namespace=? AND (state IN ('STAGING','ABORTED') OR (state='READY' AND EXISTS(SELECT 1 FROM rust_enrollment_rows r WHERE r.namespace=rust_enrollments.namespace AND r.operation=rust_enrollments.operation AND NOT EXISTS(SELECT 1 FROM rust_enrollment_receipts a WHERE a.namespace=r.namespace AND a.operation=r.operation AND a.record=r.record AND a.adopted=1)))) LIMIT 1",arrayOf(namespace)).use{it.moveToFirst()}){"stage_cleanup_required"}
  db.execSQL("INSERT INTO rust_enrollments(namespace,operation,binding,epoch,state,nextRecord,recordCount) VALUES(?,?,?,?, 'STAGING','',0)",arrayOf(namespace,operation,binding,epoch(db)))
 }
 /** Only a never-attempted snapshot can be discarded for recapture. Unknown
  * transport effects/exhausted children/receipts cannot be reset by restaging.
  * Caller must finish bounded cleanup before a new capture can begin.
  */
 fun abandonUnused(db:SupportSQLiteDatabase,namespace:String,operation:String,binding:String):Boolean{
  check(db.inTransaction())
  db.query("SELECT binding FROM rust_enrollments WHERE namespace=? AND operation=?",arrayOf(namespace,operation)).use{c->if(!c.moveToFirst()||c.getString(0)!=binding)return false}
  if(db.query("SELECT 1 FROM rust_enrollment_rows WHERE namespace=? AND operation=? AND attempts>0 LIMIT 1",arrayOf(namespace,operation)).use{it.moveToFirst()})return false
  if(db.query("SELECT 1 FROM rust_enrollment_receipts WHERE namespace=? AND operation=? LIMIT 1",arrayOf(namespace,operation)).use{it.moveToFirst()})return false
  db.execSQL("UPDATE rust_enrollments SET state='ABORTED' WHERE namespace=? AND operation=?",arrayOf(namespace,operation));return true
 }
 data class Progress(val count:Int,val ready:Boolean,val stale:Boolean)
 /** pageSize bounded but no total-history cap; keyset over full typed rows. */
 fun page(db:SupportSQLiteDatabase,namespace:String,operation:String,binding:String,owner:String,pageSize:Int=32):Progress {
  check(db.inTransaction());require(pageSize in 1..64)
  var captured=0L;var state="";var after="";var count=0
  db.query("SELECT binding,epoch,state,nextRecord,recordCount FROM rust_enrollments WHERE namespace=? AND operation=?",arrayOf(namespace,operation)).use{c->check(c.moveToFirst());check(c.getString(0)==binding);captured=c.getLong(1);state=c.getString(2);after=c.getString(3);count=c.getInt(4)}
  if(state=="READY")return Progress(count,true,false)
  if(state=="ABORTED"||epoch(db)!=captured){db.execSQL("UPDATE rust_enrollments SET state='ABORTED' WHERE namespace=? AND operation=?",arrayOf(namespace,operation));return Progress(count,false,true)}
  val ids=mutableListOf<String>()
  db.query("SELECT record FROM (SELECT dateString AS record FROM cycle_day_logs UNION ALL SELECT 'settings' FROM cycle_settings WHERE id=1 UNION ALL SELECT 'profile' FROM onboarding_profiles WHERE id=1) WHERE record>? ORDER BY record LIMIT ?",arrayOf(after,pageSize+1)).use{c->while(c.moveToNext())ids.add(c.getString(0))}
  for(id in ids.take(pageSize)){
   val row=RustDomainRows.read(db,id)?:error("row_disappeared");val payload=RustDomainRows.payload(row)?:error("missing_payload")
   var revision=0L;var server=0L;db.query("SELECT revision,serverRevision FROM health_sync_revisions WHERE ownerId=? AND recordId=?",arrayOf(owner,id)).use{c->if(c.moveToFirst()){revision=c.getLong(0);server=c.getLong(1)}}
   require(revision>=0&&server>=0)
   db.execSQL("INSERT INTO rust_enrollment_rows(namespace,operation,record,payload,physical,revision,serverRevision,child) VALUES(?,?,?,?,?,?,?,?)",arrayOf(namespace,operation,id,payload,RustDomainRows.storedPhysical(db,id),revision,server,java.util.UUID.randomUUID().toString()))
   after=id;count=Math.addExact(count,1)
  }
  val ready=ids.size<=pageSize
  db.execSQL("UPDATE rust_enrollments SET state=?,nextRecord=?,recordCount=? WHERE namespace=? AND operation=?",arrayOf(if(ready)"READY"else"STAGING",after,count,namespace,operation))
  return Progress(count,ready,false)
 }
 /** Sender must only use atomically READY rows under current binding. Stage
  * revision/server-base CAS remains required per send before any network call.
  */
 fun requireReady(db:SupportSQLiteDatabase,namespace:String,operation:String,binding:String){
  db.query("SELECT state,binding FROM rust_enrollments WHERE namespace=? AND operation=?",arrayOf(namespace,operation)).use{c->check(c.moveToFirst()&&c.getString(0)=="READY"&&c.getString(1)==binding){"stage_missing_not_ready_or_stale_binding"}}
 }
 fun readyRows(db:SupportSQLiteDatabase,namespace:String,operation:String,binding:String,after:String,limit:Int=32,now:Long=0,includeBlocked:Boolean=false):List<Staged>{
  require(limit in 1..64)
  db.query("SELECT state,binding FROM rust_enrollments WHERE namespace=? AND operation=?",arrayOf(namespace,operation)).use{c->if(!c.moveToFirst()||c.getString(0)!="READY"||c.getString(1)!=binding)return emptyList()}
  val out=mutableListOf<Staged>();db.query("SELECT r.record,r.payload,r.physical,r.revision,r.serverRevision,r.child FROM rust_enrollment_rows r WHERE r.namespace=? AND r.operation=? AND r.record>? AND (?=1 OR (r.attempts<5 AND r.nextAttempt<=?)) AND NOT EXISTS(SELECT 1 FROM rust_enrollment_receipts a WHERE a.namespace=r.namespace AND a.operation=r.operation AND a.record=r.record AND a.adopted=1) ORDER BY r.record LIMIT ?",arrayOf(namespace,operation,after,if(includeBlocked)1 else 0,now,limit)).use{c->while(c.moveToNext())out.add(Staged(c.getString(0),c.getString(1),c.getString(2),c.getLong(3),c.getLong(4),c.getString(5)))};return out
 }
 /** Durable round-robin keyset, one bounded window per invocation. Blocked
  * earlier rows cannot starve later eligible records. Cursor advances before
  * callout; child/attempt/receipt evidence is unchanged by selection.
  */
 fun takeSendWindow(db:SupportSQLiteDatabase,namespace:String,operation:String,binding:String,now:Long,limit:Int=32):List<Staged>{
  check(db.inTransaction());requireReady(db,namespace,operation,binding)
  val after=db.query("SELECT nextSend FROM rust_enrollments WHERE namespace=? AND operation=?",arrayOf(namespace,operation)).use{c->c.moveToFirst();c.getString(0)}
  var rows=readyRows(db,namespace,operation,binding,after,limit,now,true)
  if(rows.isEmpty()&&after.isNotEmpty())rows=readyRows(db,namespace,operation,binding,"",limit,now,true)
  db.execSQL("UPDATE rust_enrollments SET nextSend=? WHERE namespace=? AND operation=?",arrayOf(rows.lastOrNull()?.record?:"",namespace,operation))
  return rows
 }
 data class Staged(val record:String,val payload:String,val physical:String,val revision:Long,val serverRevision:Long,val child:String)
 /** Explicit bounded stale cleanup; never scans/deletes another namespace. */
 fun cleanupAborted(db:SupportSQLiteDatabase,namespace:String,operation:String,limit:Int=64):Boolean {
  check(db.inTransaction());require(limit in 1..128)
  db.query("SELECT state FROM rust_enrollments WHERE namespace=? AND operation=?",arrayOf(namespace,operation)).use{c->if(!c.moveToFirst())return true;check(c.getString(0)=="ABORTED")}
  db.execSQL("DELETE FROM rust_enrollment_rows WHERE namespace=? AND operation=? AND record IN (SELECT record FROM rust_enrollment_rows WHERE namespace=? AND operation=? LIMIT ?)",arrayOf(namespace,operation,namespace,operation,limit))
  val remains=db.query("SELECT 1 FROM rust_enrollment_rows WHERE namespace=? AND operation=? LIMIT 1",arrayOf(namespace,operation)).use{it.moveToFirst()}
  if(!remains)db.execSQL("DELETE FROM rust_enrollments WHERE namespace=? AND operation=?",arrayOf(namespace,operation))
  return !remains
 }
 data class SendStatus(val pending:Boolean,val eligible:Boolean,val exhausted:Boolean,val nextAttempt:Long?)
 /** Bounded keyset status window only. Never treats an exhausted/cooling row
  * as completion. Caller advances after-record across windows explicitly.
  */
 fun sendStatus(db:SupportSQLiteDatabase,namespace:String,operation:String,binding:String,after:String,now:Long,limit:Int=32):SendStatus {
  require(limit in 1..64&&now>=0)
  db.query("SELECT state,binding FROM rust_enrollments WHERE namespace=? AND operation=?",arrayOf(namespace,operation)).use{c->check(c.moveToFirst()&&c.getString(0)=="READY"&&c.getString(1)==binding)}
  var pending=false;var eligible=false;var exhausted=false;var next:Long?=null
  db.query("SELECT r.attempts,r.nextAttempt FROM rust_enrollment_rows r WHERE r.namespace=? AND r.operation=? AND r.record>? AND NOT EXISTS(SELECT 1 FROM rust_enrollment_receipts a WHERE a.namespace=r.namespace AND a.operation=r.operation AND a.record=r.record AND a.adopted=1) ORDER BY r.record LIMIT ?",arrayOf(namespace,operation,after,limit)).use{c->while(c.moveToNext()){pending=true;val attempts=c.getInt(0);val due=c.getLong(1);if(attempts>=5)exhausted=true else if(due<=now)eligible=true else next=next?.let{minOf(it,due)}?:due}}
  return SendStatus(pending,eligible,exhausted,next)
 }
 fun validateSend(db:SupportSQLiteDatabase,owner:String,row:Staged):Boolean {
  check(db.inTransaction())
  // Ordinary pending operations own earlier server bases. Never race staged
  // enrollment ahead of them or discard their retry/ACK evidence.
  if(db.query("SELECT 1 FROM health_sync_outbox WHERE ownerId=? AND recordId=? LIMIT 1",arrayOf(owner,row.record)).use{it.moveToFirst()})return false
  if(RustDomainRows.storedPhysical(db,row.record)!=row.physical)return false
  var revision=0L;var server=0L
  db.query("SELECT revision,serverRevision FROM health_sync_revisions WHERE ownerId=? AND recordId=?",arrayOf(owner,row.record)).use{c->if(c.moveToFirst()){revision=c.getLong(0);server=c.getLong(1)}}
  return revision==row.revision&&server==row.serverRevision
 }
 /** Durable bounded retry claim, committed BEFORE external transport. The same
  * child survives lost receipts/process death. No automatic restarts; exhausted
  * rows stay visible to status inspection, never silently regenerated. Backoff
  * is also a short send lease, so concurrent callers cannot hot-loop a row.
  */
 enum class SendDecision { ELIGIBLE, COOLDOWN, EXHAUSTED, ACKNOWLEDGED, ACK_REQUIRES_RECONCILIATION, STALE_BINDING, STALE_BASE, ORDINARY_PENDING }
 fun sendDecision(db:SupportSQLiteDatabase,namespace:String,operation:String,binding:String,owner:String,row:Staged,now:Long):SendDecision {
  check(db.inTransaction());require(now>=0)
  db.query("SELECT state,binding FROM rust_enrollments WHERE namespace=? AND operation=?",arrayOf(namespace,operation)).use{c->if(!c.moveToFirst()||c.getString(0)!="READY"||c.getString(1)!=binding)return SendDecision.STALE_BINDING}
  db.query("SELECT adopted FROM rust_enrollment_receipts WHERE namespace=? AND operation=? AND record=?",arrayOf(namespace,operation,row.record)).use{c->if(c.moveToFirst())return if(c.getInt(0)==1)SendDecision.ACKNOWLEDGED else SendDecision.ACK_REQUIRES_RECONCILIATION}
  if(db.query("SELECT 1 FROM health_sync_outbox WHERE ownerId=? AND recordId=? LIMIT 1",arrayOf(owner,row.record)).use{it.moveToFirst()})return SendDecision.ORDINARY_PENDING
  if(!validateSend(db,owner,row))return SendDecision.STALE_BASE
  db.query("SELECT payload,physical,revision,serverRevision,child,attempts,nextAttempt FROM rust_enrollment_rows WHERE namespace=? AND operation=? AND record=?",arrayOf(namespace,operation,row.record)).use{c->
   if(!c.moveToFirst()||c.getString(0)!=row.payload||c.getString(1)!=row.physical||c.getLong(2)!=row.revision||c.getLong(3)!=row.serverRevision||c.getString(4)!=row.child)return SendDecision.STALE_BASE
   if(c.getInt(5)>=5)return SendDecision.EXHAUSTED
   if(c.getLong(6)>now)return SendDecision.COOLDOWN
  };return SendDecision.ELIGIBLE
 }
 fun claimSend(db:SupportSQLiteDatabase,namespace:String,operation:String,binding:String,owner:String,row:Staged,now:Long):Boolean {
  check(db.inTransaction());require(now>=0)
  if(sendDecision(db,namespace,operation,binding,owner,row,now)!=SendDecision.ELIGIBLE)return false
  db.query("SELECT state,binding FROM rust_enrollments WHERE namespace=? AND operation=?",arrayOf(namespace,operation)).use{c->if(!c.moveToFirst()||c.getString(0)!="READY"||c.getString(1)!=binding)return false}
  var attempts=0
  db.query("SELECT child,attempts,nextAttempt FROM rust_enrollment_rows WHERE namespace=? AND operation=? AND record=?",arrayOf(namespace,operation,row.record)).use{c->if(!c.moveToFirst()||c.getString(0)!=row.child||c.getInt(1)>=5||c.getLong(2)>now)return false;attempts=c.getInt(1)}
  if(db.query("SELECT 1 FROM rust_enrollment_receipts WHERE namespace=? AND operation=? AND record=?",arrayOf(namespace,operation,row.record)).use{it.moveToFirst()})return false
  if(!validateSend(db,owner,row))return false
  val next=Math.addExact(now,60_000L*(1L shl attempts))
  db.execSQL("UPDATE rust_enrollment_rows SET attempts=?,nextAttempt=? WHERE namespace=? AND operation=? AND record=?",arrayOf(attempts+1,next,namespace,operation,row.record))
  return true
 }
 /** Adopt only a verified retained effect under the SAME current binding.
  * Never replace newer domain/local revision or delete pending operations.
  * Later pending records sharing the old base are rebased with all retry and
  * receipt fields intact. Unknown/incompatible remote bases require review.
  */
 fun reconcileReceipt(db:SupportSQLiteDatabase,namespace:String,operation:String,binding:String,owner:String,row:Staged):Boolean{
  check(db.inTransaction());requireReady(db,namespace,operation,binding)
  db.query("SELECT payload,physical,revision,serverRevision,child FROM rust_enrollment_rows WHERE namespace=? AND operation=? AND record=?",arrayOf(namespace,operation,row.record)).use{c->if(!c.moveToFirst()||c.getString(0)!=row.payload||c.getString(1)!=row.physical||c.getLong(2)!=row.revision||c.getLong(3)!=row.serverRevision||c.getString(4)!=row.child)return false}
  var remote=0L
  db.query("SELECT child,serverRevision,adopted FROM rust_enrollment_receipts WHERE namespace=? AND operation=? AND record=?",arrayOf(namespace,operation,row.record)).use{c->if(!c.moveToFirst()||c.getString(0)!=row.child)return false;if(c.getInt(2)==1)return true;remote=c.getLong(1)}
  if(remote!=Math.addExact(row.serverRevision,1L))return false
  var revision=0L;var server=0L
  db.query("SELECT revision,serverRevision FROM health_sync_revisions WHERE ownerId=? AND recordId=?",arrayOf(owner,row.record)).use{c->if(!c.moveToFirst())return false;revision=c.getLong(0);server=c.getLong(1)}
  if(revision<row.revision||server!=row.serverRevision)return false
  // Any pending from another base or not newer than the staged snapshot has
  // ambiguous ordering. Do not silently reinterpret it as a later local edit.
  if(db.query("SELECT 1 FROM health_sync_outbox WHERE ownerId=? AND recordId=? AND (baseRevision!=? OR revision<=?) LIMIT 1",arrayOf(owner,row.record,row.serverRevision,row.revision)).use{it.moveToFirst()})return false
  // A changed physical value with no later local revision is a legacy/direct
  // writer, not an integrated journaled edit. Refuse rather than invent one.
  if(revision==row.revision&&RustDomainRows.storedPhysical(db,row.record)!=row.physical)return false
  db.execSQL("UPDATE health_sync_revisions SET serverRevision=? WHERE ownerId=? AND recordId=?",arrayOf(remote,owner,row.record))
  db.execSQL("UPDATE health_sync_outbox SET baseRevision=? WHERE ownerId=? AND recordId=? AND baseRevision=?",arrayOf(remote,owner,row.record,row.serverRevision))
  db.execSQL("UPDATE rust_enrollment_receipts SET adopted=1 WHERE namespace=? AND operation=? AND record=?",arrayOf(namespace,operation,row.record));return true
 }
 /** Receipt-only old-context effect. It never adopts a server base, alters
  * local data/pending, or starts work. Caller must possess the exact dispatch
  * capability captured before the authorized transport call and verify wire
  * receipt outside this store. Wipe lifecycle gate is enforced by that host.
  */
 fun archiveReceipt(db:SupportSQLiteDatabase,namespace:String,operation:String,binding:String,row:Staged,serverRevision:Long,receiptHash:String):Boolean{
  check(db.inTransaction());require(serverRevision==Math.addExact(row.serverRevision,1L)&&receiptHash.matches(Regex("^[a-f0-9]{64}$")))
  requireReady(db,namespace,operation,binding)
  db.query("SELECT payload,physical,revision,serverRevision,child,attempts FROM rust_enrollment_rows WHERE namespace=? AND operation=? AND record=?",arrayOf(namespace,operation,row.record)).use{c->if(!c.moveToFirst()||c.getString(0)!=row.payload||c.getString(1)!=row.physical||c.getLong(2)!=row.revision||c.getLong(3)!=row.serverRevision||c.getString(4)!=row.child||c.getInt(5)<=0)return false}
  db.query("SELECT child,serverRevision,receiptHash FROM rust_enrollment_receipts WHERE namespace=? AND operation=? AND record=?",arrayOf(namespace,operation,row.record)).use{c->if(c.moveToFirst())return c.getString(0)==row.child&&c.getLong(1)==serverRevision&&c.getString(2)==receiptHash}
  db.execSQL("INSERT INTO rust_enrollment_receipts VALUES(?,?,?,?,?,?,0)",arrayOf(namespace,operation,row.record,row.child,serverRevision,receiptHash));return true
 }
 fun acknowledge(db:SupportSQLiteDatabase,namespace:String,operation:String,binding:String,owner:String,row:Staged,serverRevision:Long,receiptHash:String,now:Long):Boolean {
  check(db.inTransaction());require(now>=0);require(serverRevision==Math.addExact(row.serverRevision,1L)&&receiptHash.matches(Regex("^[a-f0-9]{64}$")))
  db.query("SELECT payload,physical,revision,serverRevision,child FROM rust_enrollment_rows WHERE namespace=? AND operation=? AND record=?",arrayOf(namespace,operation,row.record)).use{c->check(c.moveToFirst()&&c.getString(0)==row.payload&&c.getString(1)==row.physical&&c.getLong(2)==row.revision&&c.getLong(3)==row.serverRevision&&c.getString(4)==row.child)}
  db.query("SELECT state,binding FROM rust_enrollments WHERE namespace=? AND operation=?",arrayOf(namespace,operation)).use{c->check(c.moveToFirst()&&c.getString(0)=="READY"&&c.getString(1)==binding)}
  db.query("SELECT child,serverRevision,receiptHash,adopted FROM rust_enrollment_receipts WHERE namespace=? AND operation=? AND record=?",arrayOf(namespace,operation,row.record)).use{c->if(c.moveToFirst()){
   if(c.getString(0)!=row.child||c.getLong(1)!=serverRevision||c.getString(2)!=receiptHash)return false
   if(c.getInt(3)==1)return true
   // Receipt-only archival is not adoption; current-context OCC still required.
   if(!validateSend(db,owner,row))return false
   db.execSQL("INSERT INTO health_sync_revisions(ownerId,recordId,revision,payloadHash,tombstone,updatedAt,serverRevision) VALUES(?,?,?,?,0,?,?) ON CONFLICT(ownerId,recordId) DO UPDATE SET serverRevision=excluded.serverRevision",arrayOf(owner,row.record,row.revision,com.cyclecare.app.data.local.HealthSyncPayloadCodec.hash(row.payload),now,serverRevision))
   db.execSQL("UPDATE rust_enrollment_receipts SET adopted=1 WHERE namespace=? AND operation=? AND record=?",arrayOf(namespace,operation,row.record));return true
  }}
  // A verified remote effect must survive local OCC failure. Keep it distinct
  // from adopted base, do not resend or silently consider enrollment complete.
  val adopted=validateSend(db,owner,row)
  db.execSQL("INSERT INTO rust_enrollment_receipts VALUES(?,?,?,?,?,?,?)",arrayOf(namespace,operation,row.record,row.child,serverRevision,receiptHash,if(adopted)1 else 0))
  if(!adopted)return false
  db.execSQL("INSERT INTO health_sync_revisions(ownerId,recordId,revision,payloadHash,tombstone,updatedAt,serverRevision) VALUES(?,?,?,?,0,?,?) ON CONFLICT(ownerId,recordId) DO UPDATE SET serverRevision=excluded.serverRevision",arrayOf(owner,row.record,row.revision,com.cyclecare.app.data.local.HealthSyncPayloadCodec.hash(row.payload),now,serverRevision))
  return true
 }
}
