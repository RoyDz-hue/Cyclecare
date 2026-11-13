package com.cyclecare.app
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.sqlite.db.SupportSQLiteDatabase
import com.cyclecare.app.data.local.*
import com.cyclecare.app.data.local.rust.*
import java.util.UUID
import kotlinx.coroutines.*
import com.cyclecare.app.security.AuthPreferences
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
@RunWith(RobolectricTestRunner::class) @Config(sdk=[34])
class BatchStorageRoomTest {
 private val context:Context=ApplicationProvider.getApplicationContext();private val handles=mutableListOf<CycleCareDatabase>();private val names=mutableListOf<String>()
 private fun open():CycleCareDatabase {val name="completion-${UUID.randomUUID()}";names+=name;val db=Room.databaseBuilder(context,CycleCareDatabase::class.java,name).allowMainThreadQueries().build();handles+=db;val s=db.openHelper.writableDatabase;RustEnrollmentStage.schema(s);RustEnrollmentStage.senderSchema(s);s.execSQL("CREATE TABLE IF NOT EXISTS rust_float_bits(dateString TEXT NOT NULL PRIMARY KEY,normalizedHash TEXT NOT NULL,bbtBits INTEGER NOT NULL,sleepBits INTEGER NOT NULL)");for(event in listOf("INSERT","UPDATE","DELETE")){val refs=if(event=="INSERT")"NEW.dateString"else"OLD.dateString";s.execSQL("CREATE TRIGGER IF NOT EXISTS batch_float_${event.lowercase()} AFTER $event ON cycle_day_logs BEGIN DELETE FROM rust_float_bits WHERE dateString=$refs; END")};s.execSQL("CREATE TABLE IF NOT EXISTS rust_commit_receipts(namespace TEXT NOT NULL,operationId TEXT NOT NULL,intentDigest TEXT NOT NULL,version INTEGER NOT NULL,resultJson TEXT NOT NULL,PRIMARY KEY(namespace,operationId))");return db}
 @After fun close(){handles.forEach{it.close()};names.forEach{context.deleteDatabase(it)}}
 private fun profile()=OnboardingProfile(completedAtTimestamp=1234,accountMode="PRIVATE",selectedGoalsCsv="a,🌸,/\n",lastPeriodDateString="+10000-01-01",lastPeriodEndDateString="+10000-01-12",cycleLengthOption=" ",cycleLengthDays=Int.MIN_VALUE,periodDurationDays=11,remindFertileWindow=true,typicalSymptomsCsv="a,a,,b",typicalEnergy="E",typicalSleepQuality="S",hasCompletedDeepBaseline=true)
 private fun intent(s:SupportSQLiteDatabase,op:String="operation"):RustRoomBatch.Intent {
  fun base(id:String):RustRoomBatch.Base {var rev=0L;var server=0L;s.query("SELECT revision,serverRevision FROM health_sync_revisions WHERE ownerId='owner' AND recordId=?",arrayOf(id)).use{c->if(c.moveToFirst()){rev=c.getLong(0);server=c.getLong(1)}};return RustRoomBatch.Base(id,rev,RustDomainRows.physical(RustDomainRows.read(s,id)),server,RustDomainRows.storedPhysical(s,id))}
  val p=profile();val prior=(RustDomainRows.read(s,"settings") as? RustDomainRows.Row.Settings)?.value ?: CycleSettings();val settings=com.cyclecare.app.domain.onboarding.OnboardingCompletionMapping.settingsKotlin(prior,p,true)
  return RustRoomBatch.Intent("namespace",op,7,9876,emptyList(),listOf(RustRoomBatch.Mutation(base("settings"),RustDomainRows.Row.Settings(settings),"settings-child"),RustRoomBatch.Mutation(base("profile"),RustDomainRows.Row.Profile(p),"profile-child")),"typed-composite-v1")
 }
 private fun apply(db:CycleCareDatabase,i:RustRoomBatch.Intent,native:Boolean):RustRoomBatch.Outcome {var out:RustRoomBatch.Outcome?=null;db.runInTransaction{val s=db.openHelper.writableDatabase;out=if(native)RustBatchStorage.apply(s,"owner","binding",i) else RustRoomBatch.apply(s,"owner","binding",i)};return out!!}
 private fun snapshot(db:CycleCareDatabase):List<List<String?>> {val all=mutableListOf<List<String?>>();for(t in listOf("cycle_day_logs","rust_float_bits","onboarding_profiles","cycle_settings","health_sync_revisions","health_sync_outbox","rust_commit_receipts")){all+=listOf(t);db.openHelper.writableDatabase.query("SELECT * FROM $t ORDER BY 1,2").use{c->while(c.moveToNext())all+=(0 until c.columnCount).map{if(c.isNull(it))null else c.getString(it)}}};return all}
 @Test fun nativeActualSameConnectionRowsJournalsReceiptsEqualOriginalAndReplayExactlyOnce(){
  RustTrackNative.warmUp();val a=open();val b=open();val ia=intent(a.openHelper.writableDatabase);val ib=intent(b.openHelper.writableDatabase);assertEquals(ia,ib)
  val oa=apply(a,ia,false);val ob=apply(b,ib,true);assertEquals(oa,ob);assertEquals(snapshot(a),snapshot(b));val before=snapshot(b);val replay=apply(b,ib,true) as RustRoomBatch.Outcome.Committed;assertTrue(replay.replay);assertEquals((ob as RustRoomBatch.Outcome.Committed).receipt,replay.receipt);assertEquals(before,snapshot(b))
  assertEquals(RustRoomBatch.Outcome.InvalidReuse,apply(b,ib.copy(now=9877),true));assertEquals(before,snapshot(b))
 }
 @Test fun everyActualSqlEffectFailureRollsBackWholeTransactionAndFreshRetryCommits(){
  RustTrackNative.warmUp()
  val points=listOf("onboarding_profiles" to "NEW.id=1","cycle_settings" to "NEW.id=1","health_sync_revisions" to "NEW.recordId='profile'","health_sync_outbox" to "NEW.recordId='profile'","health_sync_revisions" to "NEW.recordId='settings'","health_sync_outbox" to "NEW.recordId='settings'","rust_commit_receipts" to "NEW.version=2")
  for((table,condition)in points)for(timing in listOf("BEFORE","AFTER")){val db=open();val s=db.openHelper.writableDatabase;val i=intent(s);val before=snapshot(db);s.execSQL("CREATE TRIGGER fail_completion $timing INSERT ON $table WHEN $condition BEGIN SELECT RAISE(ABORT,'completion fault'); END");assertNotNull("fault $timing $table $condition",runCatching{apply(db,i,true)}.exceptionOrNull());assertEquals(before,snapshot(db));s.execSQL("DROP TRIGGER fail_completion");assertTrue(apply(db,i,true) is RustRoomBatch.Outcome.Committed)}
 }
 @Test fun actualReadbackMismatchRollsBackAndConflictsOverflowHaveZeroWrites(){
  RustTrackNative.warmUp();val db=open();val s=db.openHelper.writableDatabase;val i=intent(s);val empty=snapshot(db)
  s.execSQL("CREATE TRIGGER ignore_completion BEFORE INSERT ON onboarding_profiles BEGIN SELECT RAISE(IGNORE); END");assertNotNull(runCatching{apply(db,i,true)}.exceptionOrNull());assertEquals(empty,snapshot(db));s.execSQL("DROP TRIGGER ignore_completion")
  for(index in 0..1){val bad=i.copy(mutations=i.mutations.mapIndexed{n,m->if(n==index)m.copy(base=m.base.copy(physical="different"))else m});assertEquals(RustRoomBatch.Outcome.Conflict,apply(db,bad,true));assertEquals(empty,snapshot(db))}
  for(index in 0..1){val record=if(index==0)"profile"else"settings";s.execSQL("INSERT OR REPLACE INTO health_sync_revisions(ownerId,recordId,revision,payloadHash,tombstone,updatedAt,serverRevision) VALUES('owner',?,?,'old',0,0,0)",arrayOf(record,Long.MAX_VALUE));val overflow=intent(s);val before=snapshot(db);assertTrue(runCatching{apply(db,overflow,true)}.exceptionOrNull() is ArithmeticException);assertEquals(before,snapshot(db));s.execSQL("DELETE FROM health_sync_revisions")}
 }
 @Test fun prepopulatedNonzeroServerMixedRevisionAndUpdateRowsMatchCompleteOriginal(){
  RustTrackNative.warmUp()
  for(profileRev in listOf(0L,17L,Long.MAX_VALUE-1))for(settingsRev in listOf(1L,29L))for(server in listOf(3L,Long.MAX_VALUE)){
   val a=open();val b=open()
   for(db in listOf(a,b)){val sql=db.openHelper.writableDatabase;db.runInTransaction{
    RustDomainRows.write(sql,"profile",RustDomainRows.Row.Profile(profile().copy(selectedGoalsCsv="old",lastPeriodEndDateString="OLD_END",typicalEnergy="OLD_ENERGY",hasCompletedDeepBaseline=false)))
    RustDomainRows.write(sql,"settings",RustDomainRows.Row.Settings(CycleSettings(lutealPhaseDays=19,lockTimeoutMinutes=7,isDiscreetNotificationMode=false,isPregnancyMode=true,pregnancyDueDate="KEEP_DUE",remindPeriod=true,remindDailyLog=true)))
    for((record,revision)in listOf("profile" to profileRev,"settings" to settingsRev))sql.execSQL("INSERT INTO health_sync_revisions(ownerId,recordId,revision,payloadHash,tombstone,updatedAt,serverRevision) VALUES('owner',?,?,'OLD_HASH',0,11,?)",arrayOf(record,revision,server))
    sql.execSQL("INSERT INTO health_sync_outbox(ownerId,operationId,recordId,kind,baseRevision,revision,payloadJson,payloadHash,consentGeneration,attempts,nextAttemptAt,lastError,serverReceipt,serverRevision) VALUES('owner','old-op','profile','UPSERT',2,3,'old-payload','old-hash',4,2,77,'keep-error','keep-receipt',8)")
    sql.execSQL("INSERT INTO rust_commit_receipts(namespace,operationId,intentDigest,version,resultJson) VALUES('namespace','prior-op','prior-digest',2,'prior-result')")
   }}
   val ia=intent(a.openHelper.writableDatabase);val ib=intent(b.openHelper.writableDatabase);assertEquals(ia,ib);assertEquals(apply(a,ia,false),apply(b,ib,true));assertEquals(snapshot(a),snapshot(b))
   val before=snapshot(b);val replay=apply(b,ib,true)as RustRoomBatch.Outcome.Committed;assertTrue(replay.replay);assertEquals(before,snapshot(b))
   val stored=(RustDomainRows.read(b.openHelper.writableDatabase,"settings") as RustDomainRows.Row.Settings).value;assertEquals(19,stored.lutealPhaseDays);assertEquals("KEEP_DUE",stored.pregnancyDueDate);assertTrue(stored.remindPeriod&&stored.remindDailyLog)
   b.openHelper.writableDatabase.query("SELECT baseRevision FROM health_sync_outbox WHERE ownerId='owner' AND operationId='profile-child'").use{assertTrue(it.moveToFirst());assertEquals(server,it.getLong(0))}
  }
 }

 @Test fun singleSettingsWithProfileReadConflictsAndAllSupportedShapesExactOriginal(){
  RustTrackNative.warmUp()
  for(read in listOf(false,true))for(count in 1..2){val a=open();val b=open();val ia=intent(a.openHelper.writableDatabase);val ib=intent(b.openHelper.writableDatabase)
   fun shape(db:CycleCareDatabase,i:RustRoomBatch.Intent):RustRoomBatch.Intent{val sql=db.openHelper.writableDatabase;val base=RustRoomBatch.Base("profile",0,RustDomainRows.physical(null),0,RustDomainRows.storedPhysical(sql,"profile"));return i.copy(reads=if(read)listOf(base)else emptyList(),mutations=i.mutations.take(count))}
   val aa=shape(a,ia);val bb=shape(b,ib);assertEquals(apply(a,aa,false),apply(b,bb,true));assertEquals(snapshot(a),snapshot(b));assertTrue((apply(b,bb,true)as RustRoomBatch.Outcome.Committed).replay)
  }
  val db=open();val sql=db.openHelper.writableDatabase;val i=intent(sql).let{it.copy(reads=listOf(RustRoomBatch.Base("profile",0,"wrong",0,RustDomainRows.storedPhysical(sql,"profile"))),mutations=it.mutations.take(1))};val before=snapshot(db);assertEquals(RustRoomBatch.Outcome.Conflict,apply(db,i,true));assertEquals(before,snapshot(db))
 }
 @Test fun privateSessionRejectsOutOfOrderWrongArgsCrossThreadClosedAndReentrantCalls(){
  RustTrackNative.warmUp();val db=open();val sql=db.openHelper.writableDatabase;val intent=intent(sql);val before=snapshot(db)
  val field=RustBatchStorage::class.java.getDeclaredField("entered").apply{isAccessible=true}
  @Suppress("UNCHECKED_CAST") val entered=field.get(RustBatchStorage) as ThreadLocal<Boolean>
  val cls=Class.forName("com.cyclecare.app.data.local.rust.RustBatchStorage\$Session");val constructor=cls.declaredConstructors.single().apply{isAccessible=true}
  fun call(session:Any,name:String,vararg args:Any):Any? {val m=cls.declaredMethods.single{it.name==name}.apply{isAccessible=true};return try{m.invoke(session,*args)}catch(e:java.lang.reflect.InvocationTargetException){throw e.cause!!}}
  val escaped=runCatching{db.runInTransaction{
   entered.set(true);val session=constructor.newInstance(sql,"owner","binding",intent)
   try {
    for(name in listOf("domain","valid","revision","server")){assertNotNull(runCatching{call(session,name,0)}.exceptionOrNull())}
    assertNotNull(runCatching{call(session,"outcome",0)}.exceptionOrNull());assertEquals(before,snapshot(db))
    assertNotNull(runCatching{RustBatchStorage.apply(sql,"owner","binding",intent)}.exceptionOrNull())
    val error=java.util.concurrent.atomic.AtomicReference<Throwable>();val t=Thread{try{call(session,"receipt")}catch(e:Throwable){error.set(e)}};t.start();t.join(5000);assertFalse(t.isAlive);assertTrue(error.get() is IllegalStateException)
    assertEquals(0,call(session,"receipt"));assertNotNull(runCatching{call(session,"valid",1)}.exceptionOrNull())
    assertEquals(true,call(session,"valid",0));assertEquals(true,call(session,"valid",1));assertEquals(0L,call(session,"revision",0));assertEquals(0L,call(session,"revision",1));assertEquals(0L,call(session,"server",0));call(session,"domain",0)
    for(args in listOf(arrayOf<Any>(1,1L,0L),arrayOf<Any>(0,2L,0L),arrayOf<Any>(0,1L,1L))){assertNotNull(runCatching{call(session,"revisionWrite",*args)}.exceptionOrNull())}
    assertNotNull(runCatching{call(session,"finish")}.exceptionOrNull());assertNotNull(runCatching{call(session,"outcome",0)}.exceptionOrNull())
    call(session,"close");assertNotNull(runCatching{call(session,"revisionWrite",0,1L,0L)}.exceptionOrNull())
    throw IllegalStateException("rollback adversarial session fixture")
   } finally{call(session,"close");entered.remove()}
  }}.exceptionOrNull();assertEquals("rollback adversarial session fixture",escaped?.message);assertEquals(before,snapshot(db))
 }


 @Test fun privateSessionRejectsNativeCountMismatchAndWholeTransactionRollsBack(){
  RustTrackNative.warmUp();val field=RustBatchStorage::class.java.getDeclaredField("entered").apply{isAccessible=true};@Suppress("UNCHECKED_CAST")val entered=field.get(RustBatchStorage)as ThreadLocal<Boolean>
  val cls=Class.forName("com.cyclecare.app.data.local.rust.RustBatchStorage\$Session");val ctor=cls.declaredConstructors.single().apply{isAccessible=true};val close=cls.getDeclaredMethod("close").apply{isAccessible=true}
  for(shape in listOf(0 to 1,1 to 2,0 to 16,16 to 2)){val db=open();val sql=db.openHelper.writableDatabase;val i=intent(sql);val before=snapshot(db)
   assertNotNull(runCatching{db.runInTransaction{entered.set(true);val session=ctor.newInstance(sql,"owner","binding",i);try{RustTrackNative.batchDriver(session,shape.first,shape.second)}finally{close.invoke(session);entered.remove()}}}.exceptionOrNull());assertEquals(before,snapshot(db))
  }
 }

 private fun logRows(count:Int)=List(count){n->RustDomainRows.Row.Log(CycleDayLog(java.time.LocalDate.of(2026,10,1).plusDays(n.toLong()).toString(),flowLevel="LIGHT",symptomsCsv="a,a,,🌸",vaginalHealthCsv="LOCAL_V",urinaryHealthCsv="LOCAL_U",bbtCelsius=Float.fromBits(listOf(Int.MIN_VALUE,1,0x7f7fffff,0x3f800001)[n%4]),sleepHours=Float.fromBits(listOf(1,Int.MIN_VALUE,0x00800000,0x3f000001)[n%4]),notes="synthetic local-only /\n🌸",waterGlasses=Int.MIN_VALUE,painIntensity=Int.MAX_VALUE))}
 private fun base(s:SupportSQLiteDatabase,id:String):RustRoomBatch.Base{var rev=0L;var server=0L;s.query("SELECT revision,serverRevision FROM health_sync_revisions WHERE ownerId='owner' AND recordId=?",arrayOf(id)).use{c->if(c.moveToFirst()){rev=c.getLong(0);server=c.getLong(1)}};return RustRoomBatch.Base(id,rev,RustDomainRows.physical(RustDomainRows.read(s,id)),server,RustDomainRows.storedPhysical(s,id))}
 private fun logIntent(s:SupportSQLiteDatabase,rows:List<RustDomainRows.Row>,delete:Boolean=false,reads:Int=0)=RustRoomBatch.Intent("namespace","logs",7,999,List(reads){base(s,RustDomainRows.record(rows[it]))},rows.mapIndexed{n,row->RustRoomBatch.Mutation(base(s,RustDomainRows.record(row)),if(delete)null else row,"child$n")},"typed-log-fixture")
 @Test fun allSixteenLogsRawFloatSidecarsFullDatabaseAndDeletesMatchCompleteOriginal(){
  RustTrackNative.warmUp()
  for(count in listOf(1,2,16))for(delete in listOf(false,true))for(reads in listOf(0,count)){
   val a=open();val b=open();val rows=logRows(count)
   for(db in listOf(a,b))db.runInTransaction{val sql=db.openHelper.writableDatabase;for((n,row)in rows.withIndex()){if(delete)RustDomainRows.write(sql,RustDomainRows.record(row),row);sql.execSQL("INSERT INTO health_sync_revisions(ownerId,recordId,revision,payloadHash,tombstone,updatedAt,serverRevision) VALUES('owner',?,?,'old',0,11,?)",arrayOf(RustDomainRows.record(row),if(n==count-1)Long.MAX_VALUE-1 else n.toLong(),if(n==0)Long.MAX_VALUE else 13L))}}
   val ia=logIntent(a.openHelper.writableDatabase,rows,delete,reads);val ib=logIntent(b.openHelper.writableDatabase,rows,delete,reads);assertEquals(ia,ib);assertEquals(apply(a,ia,false),apply(b,ib,true));assertEquals(snapshot(a),snapshot(b));val before=snapshot(b);assertTrue((apply(b,ib,true)as RustRoomBatch.Outcome.Committed).replay);assertEquals(before,snapshot(b));assertEquals(RustRoomBatch.Outcome.InvalidReuse,apply(b,ib.copy(now=1000),true));assertEquals(before,snapshot(b))
   for(row in rows){val id=RustDomainRows.record(row);if(delete)assertNull(RustDomainRows.read(b.openHelper.writableDatabase,id))else assertEquals(RustDomainRows.physical(row),RustDomainRows.physical(RustDomainRows.read(b.openHelper.writableDatabase,id)))}
   b.openHelper.writableDatabase.query("SELECT kind,tombstone,health_sync_revisions.payloadHash FROM health_sync_outbox JOIN health_sync_revisions USING(ownerId,recordId)").use{c->var n=0;while(c.moveToNext()){assertEquals(if(delete)"DELETE"else"UPSERT",c.getString(0));assertEquals(if(delete)1 else 0,c.getInt(1));assertEquals(delete,c.isNull(2));n++};assertEquals(count,n)}
  }
 }
 @Test fun allSixteenMutationOverflowDependencyConflictAndBadDateHaveZeroEffects(){
  RustTrackNative.warmUp();val db=open();val sql=db.openHelper.writableDatabase;val rows=logRows(16);val original=logIntent(sql,rows,reads=16);val empty=snapshot(db)
  for(n in 0..15){val bad=original.copy(reads=original.reads.mapIndexed{i,b->if(i==n)b.copy(physical="different")else b});assertEquals(RustRoomBatch.Outcome.Conflict,apply(db,bad,true));assertEquals(empty,snapshot(db));val mutation=original.copy(mutations=original.mutations.mapIndexed{i,m->if(i==n)m.copy(base=m.base.copy(physical="different"))else m});assertEquals(RustRoomBatch.Outcome.Conflict,apply(db,mutation,true));assertEquals(empty,snapshot(db))
   sql.execSQL("INSERT INTO health_sync_revisions(ownerId,recordId,revision,payloadHash,tombstone,updatedAt,serverRevision) VALUES('owner',?,?,'old',0,0,0)",arrayOf(RustDomainRows.record(rows[n]),Long.MAX_VALUE));val overflow=logIntent(sql,rows,reads=16);val before=snapshot(db);assertTrue(runCatching{apply(db,overflow,true)}.exceptionOrNull() is ArithmeticException);assertEquals(before,snapshot(db));sql.execSQL("DELETE FROM health_sync_revisions")
  }
  for(id in listOf("2026-1-01","invalid","+1000000000-01-01")){val bad=original.copy(reads=listOf(RustRoomBatch.Base(id,0,RustDomainRows.physical(null))));assertNotNull(runCatching{apply(db,bad,true)}.exceptionOrNull());assertEquals(empty,snapshot(db))}
 }

 private fun host(db:CycleCareDatabase):Triple<RustBoundRoomHost,RustBoundRoomHost.Token,AuthPreferences>{val p=AuthPreferences(context,context.getSharedPreferences("batch-${UUID.randomUUID()}",Context.MODE_PRIVATE),true).apply{userId="owner"};val h=RustBoundRoomHost(p);val t=(h.publish(null,db,p.rustOperationSnapshot())as RustBoundRoomHost.Result.Value).value;return Triple(h,t,p)}
 @Test fun actualPrepareRowsCommitRouteEqualsOriginalFullDatabaseAndBoundReplay()=runBlocking{
  RustTrackNative.warmUp();val db=open();val sql=db.openHelper.writableDatabase;val(h,t,p)=host(db);val rows=logRows(14)+listOf(RustDomainRows.Row.Settings(CycleSettings(lutealPhaseDays=19)),RustDomainRows.Row.Profile(profile()));val prepared=(withContext(Dispatchers.IO){h.prepareRows(t,"sixteen",777,rows,List(16){"child$it"})}as RustBoundRoomHost.Result.Value).value
  val initial=snapshot(db);val op=t.preference.operation;val binding=org.json.JSONArray().put(op.owner?:org.json.JSONObject.NULL).put(op.effectiveUserId).put(op.session).put(op.consentGeneration).put(t.preference.localOnly).put(t.preference.accountMode).put(t.epoch).put(t.instance).put(t.lifecycle.generation).toString();var expected:List<List<String?>>?=null;var outcome:RustRoomBatch.Outcome?=null
  val control=runCatching{db.runInTransaction{outcome=RustRoomBatch.apply(sql,"owner",binding,prepared.intent);expected=snapshot(db);throw IllegalStateException("original rollback")}}.exceptionOrNull();assertEquals("original rollback",control?.message);assertEquals(initial,snapshot(db))
  val native=withContext(Dispatchers.IO){h.commitTrack(prepared)}as RustBoundRoomHost.Result.Value;assertEquals(outcome,native.value);assertEquals(expected,snapshot(db));assertTrue((withContext(Dispatchers.IO){h.commitTrack(prepared)}as RustBoundRoomHost.Result.Value).value.let{it is RustRoomBatch.Outcome.Committed&&it.replay});assertEquals(expected,snapshot(db));p.userId="other";assertEquals(RustBoundRoomHost.Result.Stale,withContext(Dispatchers.IO){h.commitTrack(prepared)});assertEquals(expected,snapshot(db))
 }
 @Test fun actualPrepareTrackSaveDeleteRoutesPreserveFloatAndDependencySemantics()=runBlocking{
  RustTrackNative.warmUp()
  for(hasSettings in listOf(false,true))for(flow in listOf("LIGHT","HEAVY")){
   val db=open();val sql=db.openHelper.writableDatabase;if(hasSettings)db.runInTransaction{RustDomainRows.write(sql,"settings",RustDomainRows.Row.Settings(CycleSettings(lastPeriodStartDate="2026-09-01",lutealPhaseDays=19)))};val(h,t,_)=host(db);val row=(logRows(1)[0]as RustDomainRows.Row.Log).value.copy(flowLevel=flow)
   val prepared=(withContext(Dispatchers.IO){h.prepareTrackAuto(t,"save",row.dateString,row,123,listOf("l","s"))}as RustBoundRoomHost.Result.Value).value;assertTrue(withContext(Dispatchers.IO){h.commitTrack(prepared)}is RustBoundRoomHost.Result.Value);assertEquals(RustDomainRows.physical(RustDomainRows.Row.Log(row)),RustDomainRows.physical(RustDomainRows.read(sql,row.dateString)));assertNotNull(RustDomainRows.read(sql,"settings"))
   val deletion=(withContext(Dispatchers.IO){h.prepareTrack(t,"delete",row.dateString,null,124,listOf("d"))}as RustBoundRoomHost.Result.Value).value;assertTrue(withContext(Dispatchers.IO){h.commitTrack(deletion)}is RustBoundRoomHost.Result.Value);assertNull(RustDomainRows.read(sql,row.dateString));sql.query("SELECT bbtBits FROM rust_float_bits WHERE dateString=?",arrayOf(row.dateString)).use{assertFalse(it.moveToFirst())};sql.query("SELECT kind,baseRevision FROM health_sync_outbox WHERE operationId='d'").use{assertTrue(it.moveToFirst());assertEquals("DELETE",it.getString(0))}
  }
 }
 @Test fun everySixteenRowOutboxFailureAndLastReadbackFailureRollbackAllPriorEffects(){
  RustTrackNative.warmUp();val rows=logRows(16)
  for(delete in listOf(false,true))for(n in 0..15){val db=open();val sql=db.openHelper.writableDatabase;if(delete)db.runInTransaction{rows.forEach{RustDomainRows.write(sql,RustDomainRows.record(it),it)}};val i=logIntent(sql,rows,delete,16);val before=snapshot(db);sql.execSQL("CREATE TRIGGER last_fault AFTER INSERT ON health_sync_outbox WHEN NEW.operationId='child$n' BEGIN SELECT RAISE(ABORT,'fault'); END");assertNotNull(runCatching{apply(db,i,true)}.exceptionOrNull());assertEquals(before,snapshot(db));sql.execSQL("DROP TRIGGER last_fault");assertTrue(apply(db,i,true)is RustRoomBatch.Outcome.Committed)}
  val db=open();val sql=db.openHelper.writableDatabase;val i=logIntent(sql,rows);val before=snapshot(db);sql.execSQL("CREATE TRIGGER ignore_last BEFORE INSERT ON cycle_day_logs WHEN NEW.dateString='2026-10-16' BEGIN SELECT RAISE(IGNORE); END");assertNotNull(runCatching{apply(db,i,true)}.exceptionOrNull());assertEquals(before,snapshot(db))
 }

 @Test fun typedFloatEqualityDoesNotBypassStoredDoubleFingerprintConflict(){
  RustTrackNative.warmUp();val db=open();val sql=db.openHelper.writableDatabase;val row=RustDomainRows.Row.Log(CycleDayLog("2026-10-01",bbtCelsius=1f,sleepHours=2f));db.runInTransaction{RustDomainRows.write(sql,row.value.dateString,row)};sql.execSQL("UPDATE cycle_day_logs SET bbtCelsius=1.000000001 WHERE dateString='2026-10-01'");val i=logIntent(sql,listOf(row));val typed=i.mutations[0].base.physical;val stored=i.mutations[0].base.storage;sql.execSQL("UPDATE cycle_day_logs SET bbtCelsius=1.000000002 WHERE dateString='2026-10-01'");assertEquals(typed,RustDomainRows.physical(RustDomainRows.read(sql,row.value.dateString)));assertNotEquals(stored,RustDomainRows.storedPhysical(sql,row.value.dateString));val before=snapshot(db);assertEquals(RustRoomBatch.Outcome.Conflict,apply(db,i,true));assertEquals(before,snapshot(db))
 }

}
