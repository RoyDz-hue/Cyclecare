package com.cyclecare.app.data.local.rust
import androidx.sqlite.db.SupportSQLiteDatabase
import com.cyclecare.app.data.local.CycleCareDatabase
/** DB effects only. Durable one-way gate/preferences/revoke/callout ordering stays host. */
internal object RustErasureStorage{
 private val entered=ThreadLocal<Boolean>()
 fun apply(sql:SupportSQLiteDatabase,owner:String?,syncOwner:String){
  check(entered.get()!=true){"erasure executor reentry"}
  if(!RustTrackNative.completionReady())return RustErasureRows.apply(sql,owner,syncOwner)
  val session=Session(sql,owner,syncOwner);entered.set(true)
  try{session.outcome(RustTrackNative.erasureDriver(session))}finally{session.close();entered.remove()}
 }
 private class Session(private val sql:SupportSQLiteDatabase,private val owner:String?,private val syncOwner:String){
  private val thread=Thread.currentThread();private var active=true;private var inCall=false;private var stage="ready";private var index=0;private var namespace:String?=null
  private val globals=listOf("cycle_day_logs","onboarding_profiles","cycle_settings","rust_float_bits");private val owned=listOf("health_sync_outbox","health_sync_revisions","health_sync_cursors","health_sync_conflicts");private val optional=listOf("rust_enrollment_rows","rust_enrollment_receipts","rust_enrollments")
  private inline fun <T> admitted(expected:String,block:()->T):T{check(active&&Thread.currentThread()===thread&&sql.inTransaction()&&entered.get()==true&&!inCall&&stage==expected){"erasure callback authority/order"};inCall=true;try{return block()}finally{inCall=false}}
  private fun at(i:Int,tables:List<String>):String{check(i==index&&i in tables.indices);return tables[i]}
  fun close(){active=false}
  fun ready():Unit=admitted("ready"){stage="global"}
  fun global(i:Int):Unit=admitted("global"){val table=at(i,globals);sql.execSQL("DELETE FROM $table");index++;if(index==globals.size){index=0;stage="owned"}}
  fun owned(i:Int):Unit=admitted("owned"){val table=at(i,owned);sql.execSQL("DELETE FROM $table WHERE ownerId=?",arrayOf(syncOwner));index++;if(index==owned.size){index=0;stage="receipts"}}
  fun receipts():Unit=admitted("receipts"){
   val namespace=owner?.takeIf{it.isNotBlank()}?.let(CycleCareDatabase::namespaceFor)?:"cycle_care_database";this.namespace=namespace
   sql.execSQL("DELETE FROM rust_commit_receipts WHERE namespace=?",arrayOf(namespace));stage="exists"
  }
  fun exists(i:Int):Boolean=admitted("exists"){val table=at(i,optional);val exists=sql.query("SELECT 1 FROM sqlite_master WHERE type='table' AND name=?",arrayOf(table)).use{it.moveToFirst()};if(exists)stage="stageDelete"else advance();exists}
  private fun advance(){index++;stage=if(index==optional.size)"defaults"else"exists"}
  fun stageDelete(i:Int):Unit=admitted("stageDelete"){val table=at(i,optional);val namespace=checkNotNull(namespace);sql.execSQL("DELETE FROM $table WHERE namespace=?",arrayOf(namespace));advance()}
  fun defaults():Unit=admitted("defaults"){RustDomainRows.write(sql,"settings",RustDomainRows.Row.Settings(com.cyclecare.app.data.local.CycleSettings(isOnboardingCompleted=false,lastPeriodStartDate="",averageCycleLength=28,periodDuration=5)));stage="done"}
  fun outcome(code:Int){check(active&&Thread.currentThread()===thread&&sql.inTransaction()&&!inCall&&stage=="done"&&code==1)}
 }
}
