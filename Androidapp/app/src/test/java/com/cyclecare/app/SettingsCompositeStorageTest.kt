package com.cyclecare.app
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.cyclecare.app.data.local.*
import com.cyclecare.app.data.local.rust.*
import com.cyclecare.app.security.AuthPreferences
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.UUID
@RunWith(RobolectricTestRunner::class) @Config(sdk=[34])
class SettingsCompositeStorageTest {
 private val context:Context=ApplicationProvider.getApplicationContext();private val handles=mutableListOf<CycleCareDatabase>();private val names=mutableListOf<String>()
 private fun open():CycleCareDatabase {val name="composite-${UUID.randomUUID()}";names+=name;return Room.databaseBuilder(context,CycleCareDatabase::class.java,name).allowMainThreadQueries().build().also{handles+=it;val s=it.openHelper.writableDatabase;RustEnrollmentStage.schema(s);RustEnrollmentStage.senderSchema(s);s.execSQL("CREATE TABLE IF NOT EXISTS rust_commit_receipts(namespace TEXT NOT NULL,operationId TEXT NOT NULL,intentDigest TEXT NOT NULL,version INTEGER NOT NULL,resultJson TEXT NOT NULL,PRIMARY KEY(namespace,operationId))")}}
 @After fun close(){handles.forEach{it.close()};names.forEach{context.deleteDatabase(it)}}
 private fun profile()=OnboardingProfile(completedAtTimestamp=44,selectedGoalsCsv="KEEP_GOALS",lastPeriodDateString="2026-10-01",lastPeriodEndDateString="KEEP_RAW_END",approximatePeriodChoice="UNKNOWN",cycleLengthOption="KEEP_OPTION",cycleLengthDays=29,periodDurationDays=11,typicalEnergy="KEEP_ENERGY",hasCompletedDeepBaseline=true)
 private fun settings()=CycleSettings(selectedGoalsCsv="s",lastPeriodStartDate="2026-10-01",averageCycleLength=29,periodDuration=11,lutealPhaseDays=17,isPregnancyMode=true,pregnancyDueDate="KEEP_DUE",remindDailyLog=true)
 private fun seed(db:CycleCareDatabase,hasProfile:Boolean){db.runInTransaction{val s=db.openHelper.writableDatabase;RustDomainRows.write(s,"settings",RustDomainRows.Row.Settings(settings()));if(hasProfile)RustDomainRows.write(s,"profile",RustDomainRows.Row.Profile(profile()));for(id in listOf("profile","settings"))s.execSQL("INSERT INTO health_sync_revisions(ownerId,recordId,revision,payloadHash,tombstone,updatedAt,serverRevision) VALUES('owner',?,8,'old',0,12,7)",arrayOf(id))}}
 private fun host(db:CycleCareDatabase):Triple<RustBoundRoomHost,RustBoundRoomHost.Token,AuthPreferences>{val p=AuthPreferences(context,context.getSharedPreferences("p-${UUID.randomUUID()}",Context.MODE_PRIVATE),true).apply{userId="owner"};val h=RustBoundRoomHost(p);val t=(h.publish(null,db,p.rustOperationSnapshot()) as RustBoundRoomHost.Result.Value).value;return Triple(h,t,p)}
 private fun snapshot(db:CycleCareDatabase):List<List<String?>>{val out=mutableListOf<List<String?>>();for(table in listOf("cycle_settings","onboarding_profiles","health_sync_revisions","health_sync_outbox","rust_commit_receipts")){out+=listOf(table);db.openHelper.writableDatabase.query("SELECT * FROM $table ORDER BY 1,2").use{c->while(c.moveToNext())out+=(0 until c.columnCount).map{if(c.isNull(it))null else c.getString(it)}}};return out}
 @Test fun actualBulkAndAnchorHostRoutesPreserveOriginalPolicyAndExactJournalShape()=runBlocking{
  RustTrackNative.warmUp()
  for(hasProfile in listOf(false,true))for(anchor in listOf(false,true))for(average in listOf(29,33)){val db=open();seed(db,hasProfile);val(h,t,_)=host(db);val result=withContext(Dispatchers.IO){if(anchor)h.setPeriodAnchor(t,"op",listOf("s-child","p-child"),123,"+10000-01-01")else h.updateSettings(t,"op",listOf("s-child","p-child"),123,settings().copy(averageCycleLength=average,periodDuration=6),29)};assertTrue(result.toString(),result is RustBoundRoomHost.Result.Value)
   val s=(RustDomainRows.read(db.openHelper.writableDatabase,"settings")as RustDomainRows.Row.Settings).value;val p=(RustDomainRows.read(db.openHelper.writableDatabase,"profile")as? RustDomainRows.Row.Profile)?.value
   val expectedS=if(anchor)settings().copy(lastPeriodStartDate="+10000-01-01")else settings().copy(averageCycleLength=average,periodDuration=6);assertEquals(expectedS,s)
   val expectedP=if(!hasProfile)null else if(anchor)profile().copy(lastPeriodDateString="+10000-01-01",approximatePeriodChoice="EXACT")else profile().copy(cycleLengthDays=average,periodDurationDays=6,cycleLengthOption=if(average!=29)"$average days"else"KEEP_OPTION");assertEquals(expectedP,p)
   db.openHelper.writableDatabase.query("SELECT recordId,baseRevision,revision,payloadJson FROM health_sync_outbox ORDER BY recordId").use{c->var count=0;while(c.moveToNext()){assertEquals(7,c.getLong(1));assertEquals(9,c.getLong(2));assertEquals(RustDomainRows.payload(RustDomainRows.read(db.openHelper.writableDatabase,c.getString(0))),c.getString(3));count++};assertEquals(if(hasProfile)2 else 1,count)}
   db.openHelper.writableDatabase.query("SELECT version,resultJson FROM rust_commit_receipts").use{assertTrue(it.moveToFirst());assertEquals(2,it.getInt(0));val arr=org.json.JSONArray(it.getString(1));assertEquals("android-room-batch-v2",arr.getString(0));assertEquals("settings",arr.getJSONArray(1).getJSONArray(0).getString(0));assertEquals(if(hasProfile)2 else 1,arr.getJSONArray(1).length())}
  }
 }
 @Test fun actualBulkAndAnchorSqlFailureRollsBackAllEffectsThenRetrySucceeds()=runBlocking{
  RustTrackNative.warmUp()
  for(anchor in listOf(false,true))for((table,condition)in listOf("cycle_settings" to "NEW.id=1","onboarding_profiles" to "NEW.id=1","health_sync_revisions" to "NEW.recordId='settings'","health_sync_outbox" to "NEW.recordId='settings'","health_sync_revisions" to "NEW.recordId='profile'","health_sync_outbox" to "NEW.recordId='profile'","rust_commit_receipts" to "NEW.version=2")){
   val db=open();seed(db,true);val(h,t,_)=host(db);val sql=db.openHelper.writableDatabase;val before=snapshot(db);val verb=if(table in listOf("cycle_settings","onboarding_profiles"))"UPDATE"else"INSERT";sql.execSQL("CREATE TRIGGER fail_composite AFTER $verb ON $table WHEN $condition BEGIN SELECT RAISE(ABORT,'composite fault'); END")
   suspend fun call()=withContext(Dispatchers.IO){if(anchor)h.setPeriodAnchor(t,"op",listOf("s","p"),123,"2026-10-11")else h.updateSettings(t,"op",listOf("s","p"),123,settings().copy(averageCycleLength=33),29)}
   assertEquals(RustBoundRoomHost.Result.Storage,call());assertEquals(before,snapshot(db));sql.execSQL("DROP TRIGGER fail_composite");assertTrue(call() is RustBoundRoomHost.Result.Value)
  }
 }
 @Test fun actualBulkAndAnchorStaleSessionAndInvalidDateLeaveAllRowsUntouched()=runBlocking{
  RustTrackNative.warmUp();val db=open();seed(db,true);val(h,t,p)=host(db);val before=snapshot(db)
  val invalid=withContext(Dispatchers.IO){h.setPeriodAnchor(t,"bad",listOf("s","p"),123,"invalid")};assertEquals(RustBoundRoomHost.Result.Storage,invalid);assertEquals(before,snapshot(db));p.userId="new-owner"
  assertEquals(RustBoundRoomHost.Result.Stale,withContext(Dispatchers.IO){h.setPeriodAnchor(t,"op",listOf("s","p"),123,"2026-10-11")});assertEquals(RustBoundRoomHost.Result.Stale,withContext(Dispatchers.IO){h.updateSettings(t,"op",listOf("s","p"),123,settings(),29)});assertEquals(before,snapshot(db))
 }
 @Test fun originalSettingsRepositoryTokenReplacementAndLeaseWitnessesWithNativeReady(){RustTrackNative.warmUp();for(method in SettingsBoundPortBindingTest::class.java.methods.filter{it.isAnnotationPresent(Test::class.java)}){val f=SettingsBoundPortBindingTest();try{try{method.invoke(f)}catch(e:java.lang.reflect.InvocationTargetException){throw e.cause!!}}finally{f.cleanup()}}}
 @Test fun actualHostNativeCompositeEqualsCompleteOriginalExecutorFullDatabaseDigestAndReceipt()=runBlocking{
  RustTrackNative.warmUp()
  for(hasProfile in listOf(false,true))for(anchor in listOf(false,true))for(average in listOf(29,33)){
   val db=open();seed(db,hasProfile);val(h,t,_)=host(db);val sql=db.openHelper.writableDatabase;val initial=snapshot(db)
   val expectedSettings=if(anchor)settings().copy(lastPeriodStartDate="+10000-01-01")else settings().copy(averageCycleLength=average,periodDuration=6)
   val expectedProfile=if(!hasProfile)null else if(anchor)profile().copy(lastPeriodDateString="+10000-01-01",approximatePeriodChoice="EXACT")else profile().copy(cycleLengthDays=average,periodDurationDays=6,cycleLengthOption=if(average!=29)"$average days"else"KEEP_OPTION")
   val op=t.preference.operation;val namespace=CycleCareDatabase.namespaceFor("owner");val binding=org.json.JSONArray().put(op.owner?:org.json.JSONObject.NULL).put(op.effectiveUserId).put(op.session).put(op.consentGeneration).put(t.preference.localOnly).put(t.preference.accountMode).put(t.epoch).put(t.instance).put(t.lifecycle.generation).toString()
   var expected:List<List<String?>>?=null
   val rollback=runCatching{db.runInTransaction{
    val rows=listOfNotNull(RustDomainRows.Row.Settings(expectedSettings),expectedProfile?.let(RustDomainRows.Row::Profile));val mutations=rows.mapIndexed{index,row->val id=RustDomainRows.record(row);RustRoomBatch.Mutation(RustRoomBatch.Base(id,8,RustDomainRows.physical(RustDomainRows.read(sql,id)),7,RustDomainRows.storedPhysical(sql,id)),row,if(index==0)"s-child"else"p-child")}
    assertTrue(RustRoomBatch.apply(sql,"owner",binding,RustRoomBatch.Intent(namespace,"same-op",op.consentGeneration,123,emptyList(),mutations,"typed-composite-v1")) is RustRoomBatch.Outcome.Committed);expected=snapshot(db);throw IllegalStateException("control rollback")
   }}.exceptionOrNull();assertEquals("control rollback",rollback?.message);assertEquals(initial,snapshot(db))
   val result=withContext(Dispatchers.IO){if(anchor)h.setPeriodAnchor(t,"same-op",listOf("s-child","p-child"),123,"+10000-01-01")else h.updateSettings(t,"same-op",listOf("s-child","p-child"),123,settings().copy(averageCycleLength=average,periodDuration=6),29)}
   assertTrue(result is RustBoundRoomHost.Result.Value);assertEquals("full physical rows/journal/outbox/digest/receipt exact complete original",expected,snapshot(db))
  }
 }

}
