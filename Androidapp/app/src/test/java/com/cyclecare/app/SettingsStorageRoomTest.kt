package com.cyclecare.app
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.sqlite.db.SupportSQLiteDatabase
import com.cyclecare.app.data.local.*
import com.cyclecare.app.data.local.rust.*
import java.util.UUID
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
@RunWith(RobolectricTestRunner::class) @Config(sdk=[34])
class SettingsStorageRoomTest {
 private val context:Context=ApplicationProvider.getApplicationContext();private val handles=mutableListOf<CycleCareDatabase>();private val names=mutableListOf<String>()
 private fun open():CycleCareDatabase {val name="completion-${UUID.randomUUID()}";names+=name;val db=Room.databaseBuilder(context,CycleCareDatabase::class.java,name).allowMainThreadQueries().build();handles+=db;val s=db.openHelper.writableDatabase;RustEnrollmentStage.schema(s);RustEnrollmentStage.senderSchema(s);s.execSQL("CREATE TABLE IF NOT EXISTS rust_commit_receipts(namespace TEXT NOT NULL,operationId TEXT NOT NULL,intentDigest TEXT NOT NULL,version INTEGER NOT NULL,resultJson TEXT NOT NULL,PRIMARY KEY(namespace,operationId))");return db}
 @After fun close(){handles.forEach{it.close()};names.forEach{context.deleteDatabase(it)}}
 private fun profile()=OnboardingProfile(completedAtTimestamp=1234,accountMode="PRIVATE",selectedGoalsCsv="a,🌸,/\n",lastPeriodDateString="+10000-01-01",lastPeriodEndDateString="+10000-01-12",cycleLengthOption=" ",cycleLengthDays=Int.MIN_VALUE,periodDurationDays=11,remindFertileWindow=true,typicalSymptomsCsv="a,a,,b",typicalEnergy="E",typicalSleepQuality="S",hasCompletedDeepBaseline=true)
 private fun intent(s:SupportSQLiteDatabase,op:String="operation"):RustRoomBatch.Intent {
  fun base(id:String):RustRoomBatch.Base {var rev=0L;var server=0L;s.query("SELECT revision,serverRevision FROM health_sync_revisions WHERE ownerId='owner' AND recordId=?",arrayOf(id)).use{c->if(c.moveToFirst()){rev=c.getLong(0);server=c.getLong(1)}};return RustRoomBatch.Base(id,rev,RustDomainRows.physical(RustDomainRows.read(s,id)),server,RustDomainRows.storedPhysical(s,id))}
  val p=profile();val prior=(RustDomainRows.read(s,"settings") as? RustDomainRows.Row.Settings)?.value ?: CycleSettings();val settings=com.cyclecare.app.domain.onboarding.OnboardingCompletionMapping.settingsKotlin(prior,p,true)
  return RustRoomBatch.Intent("namespace",op,7,9876,emptyList(),listOf(RustRoomBatch.Mutation(base("settings"),RustDomainRows.Row.Settings(settings),"settings-child"),RustRoomBatch.Mutation(base("profile"),RustDomainRows.Row.Profile(p),"profile-child")),"typed-composite-v1")
 }
 private fun apply(db:CycleCareDatabase,i:RustRoomBatch.Intent,native:Boolean):RustRoomBatch.Outcome {var out:RustRoomBatch.Outcome?=null;db.runInTransaction{val s=db.openHelper.writableDatabase;out=if(native)RustSettingsStorage.apply(s,"owner","binding",i) else RustRoomBatch.apply(s,"owner","binding",i)};return out!!}
 private fun snapshot(db:CycleCareDatabase):List<List<String?>> {val all=mutableListOf<List<String?>>();for(t in listOf("onboarding_profiles","cycle_settings","health_sync_revisions","health_sync_outbox","rust_commit_receipts")){all+=listOf(t);db.openHelper.writableDatabase.query("SELECT * FROM $t ORDER BY 1,2").use{c->while(c.moveToNext())all+=(0 until c.columnCount).map{if(c.isNull(it))null else c.getString(it)}}};return all}
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
  val field=RustSettingsStorage::class.java.getDeclaredField("entered").apply{isAccessible=true}
  @Suppress("UNCHECKED_CAST") val entered=field.get(RustSettingsStorage) as ThreadLocal<Boolean>
  val cls=Class.forName("com.cyclecare.app.data.local.rust.RustSettingsStorage\$Session");val constructor=cls.declaredConstructors.single().apply{isAccessible=true}
  fun call(session:Any,name:String,vararg args:Any):Any? {val m=cls.declaredMethods.single{it.name==name}.apply{isAccessible=true};return try{m.invoke(session,*args)}catch(e:java.lang.reflect.InvocationTargetException){throw e.cause!!}}
  val escaped=runCatching{db.runInTransaction{
   entered.set(true);val session=constructor.newInstance(sql,"owner","binding",intent)
   try {
    for(name in listOf("domain","valid","revision","server")){assertNotNull(runCatching{call(session,name,0)}.exceptionOrNull())}
    assertNotNull(runCatching{call(session,"outcome",0)}.exceptionOrNull());assertEquals(before,snapshot(db))
    assertNotNull(runCatching{RustSettingsStorage.apply(sql,"owner","binding",intent)}.exceptionOrNull())
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
  RustTrackNative.warmUp();val field=RustSettingsStorage::class.java.getDeclaredField("entered").apply{isAccessible=true};@Suppress("UNCHECKED_CAST")val entered=field.get(RustSettingsStorage)as ThreadLocal<Boolean>
  val cls=Class.forName("com.cyclecare.app.data.local.rust.RustSettingsStorage\$Session");val ctor=cls.declaredConstructors.single().apply{isAccessible=true};val close=cls.getDeclaredMethod("close").apply{isAccessible=true}
  for(shape in listOf(0 to 1,1 to 2)){val db=open();val sql=db.openHelper.writableDatabase;val i=intent(sql);val before=snapshot(db)
   assertNotNull(runCatching{db.runInTransaction{entered.set(true);val session=ctor.newInstance(sql,"owner","binding",i);try{RustTrackNative.settingsDriver(session,shape.first,shape.second)}finally{close.invoke(session);entered.remove()}}}.exceptionOrNull());assertEquals(before,snapshot(db))
  }
 }

}
