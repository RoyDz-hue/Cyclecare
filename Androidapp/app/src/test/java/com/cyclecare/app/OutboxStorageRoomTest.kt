package com.cyclecare.app
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.sqlite.db.SupportSQLiteDatabase
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
class OutboxStorageRoomTest {
 private val context:Context=ApplicationProvider.getApplicationContext();private val handles=mutableListOf<CycleCareDatabase>();private val names=mutableListOf<String>()
 private fun open():CycleCareDatabase{val name="outbox-${UUID.randomUUID()}";names+=name;return Room.databaseBuilder(context,CycleCareDatabase::class.java,name).allowMainThreadQueries().build().also{handles+=it;RustEnrollmentStage.schema(it.openHelper.writableDatabase);RustEnrollmentStage.senderSchema(it.openHelper.writableDatabase)}}
 @After fun close(){handles.forEach{it.close()};names.forEach{context.deleteDatabase(it)}}
 private fun entry(base:Long=7,attempts:Int=2)=HealthSyncOutboxEntry("owner","op","2026-10-01","UPSERT",base,17,"synthetic 🌸/\n",HealthSyncPayloadCodec.hash("synthetic 🌸/\n"),3,attempts,77,"OLD_ERROR","OLD_RECEIPT",45)
 private fun seed(db:CycleCareDatabase,e:HealthSyncOutboxEntry,server:Long=3,revision:Boolean=true){db.runInTransaction{val s=db.openHelper.writableDatabase;if(revision)s.execSQL("INSERT INTO health_sync_revisions(ownerId,recordId,revision,payloadHash,tombstone,updatedAt,serverRevision) VALUES(?,?,?,?,?,?,?)",arrayOf<Any>(e.ownerId,e.recordId,99,"KEEP_HASH",1,987,server));for(row in listOf(e,e.copy(operationId="sibling",revision=18),e.copy(operationId="different-base",baseRevision=e.baseRevision xor 1L),e.copy(operationId="other-record",recordId="2026-10-02"),e.copy(operationId="other-owner",ownerId="other"))){s.execSQL("INSERT INTO health_sync_outbox VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?)",arrayOf<Any?>(row.ownerId,row.operationId,row.recordId,row.kind,row.baseRevision,row.revision,row.payloadJson,row.payloadHash,row.consentGeneration,row.attempts,row.nextAttemptAt,row.lastError,row.serverReceipt,row.serverRevision))}}}
 private fun snapshot(db:CycleCareDatabase):List<List<String?>>{val out=mutableListOf<List<String?>>();for(table in listOf("cycle_day_logs","onboarding_profiles","cycle_settings","health_sync_revisions","health_sync_outbox","health_sync_conflicts","health_sync_cursors")){out+=listOf(table);db.openHelper.writableDatabase.query("SELECT * FROM $table ORDER BY 1,2").use{c->while(c.moveToNext())out+=(0 until c.columnCount).map{if(c.isNull(it))null else c.getString(it)}}};return out}
 private fun apply(db:CycleCareDatabase,e:HealthSyncOutboxEntry,mode:Int,native:Boolean,server:Long=e.baseRevision+1,next:Long=Long.MAX_VALUE,error:String="arbitrary/'\n🌸\u0000tail"):Boolean{var result=false;db.runInTransaction{val sql=db.openHelper.writableDatabase;result=if(mode==0){if(native)RustOutboxStorage.acknowledge(sql,e,server)else RustOutboxRows.acknowledge(sql,e,server)}else{if(native)RustOutboxStorage.retry(sql,e,next,error)else RustOutboxRows.retry(sql,e,next,error)}};return result}
 @Test fun ackWholeDatabaseExactOriginalForSignedBasesMaxServersMissingRevisionAndSiblings(){
  RustTrackNative.warmUp();for(base in listOf(Long.MIN_VALUE,-1L,0L,7L,Long.MAX_VALUE-1))for(server in listOf(0L,Long.MAX_VALUE))for(revision in listOf(false,true)){val a=open();val b=open();val e=entry(base);seed(a,e,server,revision);seed(b,e,server,revision);assertEquals(apply(a,e,0,false),apply(b,e,0,true));assertEquals(snapshot(a),snapshot(b));val before=snapshot(b);assertFalse(apply(b,e,0,true));assertEquals(before,snapshot(b));b.openHelper.writableDatabase.query("SELECT baseRevision FROM health_sync_outbox WHERE operationId='sibling'").use{assertTrue(it.moveToFirst());assertEquals(base+1,it.getLong(0))}}
 }
 @Test fun ackMismatchedFiveCasFieldsAndOverflowOrBadReceiptHaveZeroWrites(){
  RustTrackNative.warmUp();val db=open();val e=entry();seed(db,e);val before=snapshot(db)
  for(bad in listOf(e.copy(ownerId="absent"),e.copy(operationId="absent"),e.copy(recordId="different"),e.copy(revision=18),e.copy(baseRevision=8),e.copy(payloadHash="different"),e.copy(consentGeneration=4))){assertFalse(apply(db,bad,0,true));assertFalse(apply(db,bad,1,true));assertEquals(before,snapshot(db))}
  for(native in listOf(false,true)){val overflow=runCatching{apply(db,e.copy(baseRevision=Long.MAX_VALUE),0,native,server=0)}.exceptionOrNull();assertTrue(overflow is ArithmeticException);assertEquals("long overflow",overflow?.message);val bad=runCatching{apply(db,e.copy(operationId="absent"),0,native,server=99)}.exceptionOrNull();assertTrue(bad is IllegalArgumentException);assertEquals("Failed requirement.",bad?.message);assertEquals(before,snapshot(db))}
 }
 @Test fun retryWholeDatabaseExactOriginalSignedAttemptsCasMissAndArbitraryText(){
  RustTrackNative.warmUp();for(attempts in listOf(Int.MIN_VALUE,-1,0,Int.MAX_VALUE-1))for(next in listOf(0L,Long.MAX_VALUE))for(stale in listOf(false,true)){val a=open();val b=open();val e=entry(attempts=attempts);seed(a,e);seed(b,e);val supplied=if(stale)e.copy(attempts=if(attempts==Int.MAX_VALUE-1)attempts-1 else attempts xor 1)else e;assertEquals(apply(a,supplied,1,false,next=next),apply(b,supplied,1,true,next=next));assertEquals(snapshot(a),snapshot(b));b.openHelper.writableDatabase.query("SELECT attempts,nextAttemptAt,lastError,serverReceipt,serverRevision FROM health_sync_outbox WHERE operationId='op'").use{assertTrue(it.moveToFirst());assertEquals(if(stale)attempts else attempts+1,it.getInt(0));assertEquals(if(stale)77L else next,it.getLong(1));if(!stale)assertEquals("arbitrary/'\n🌸\u0000tail",it.getString(2));assertEquals("OLD_RECEIPT",it.getString(3));assertEquals(45,it.getLong(4))}}
 }
 @Test fun retryMismatchSkipsOverflowButMatchedOverflowAndNegativeTimeRollback(){
  RustTrackNative.warmUp();val db=open();val e=entry(attempts=Int.MAX_VALUE);seed(db,e);val before=snapshot(db);assertFalse(apply(db,e.copy(operationId="absent"),1,true));assertEquals(before,snapshot(db));for(native in listOf(false,true)){val overflow=runCatching{apply(db,e,1,native)}.exceptionOrNull();assertTrue(overflow is ArithmeticException);assertEquals("integer overflow",overflow?.message);assertTrue(runCatching{apply(db,e.copy(operationId="absent"),1,native,next=-1)}.exceptionOrNull()is IllegalArgumentException);assertEquals(before,snapshot(db))}
 }
 @Test fun everyAckAndRetrySqlEffectFaultRollsBackAndFreshRetrySucceeds(){
  RustTrackNative.warmUp();for(timing in listOf("BEFORE","AFTER"))for(point in listOf("revision","delete","rebase","retry")){val db=open();val e=entry();seed(db,e);val s=db.openHelper.writableDatabase;val before=snapshot(db);val clause=when(point){"revision"->"UPDATE ON health_sync_revisions WHEN NEW.ownerId='owner'";"delete"->"DELETE ON health_sync_outbox WHEN OLD.operationId='op'";"rebase"->"UPDATE ON health_sync_outbox WHEN NEW.operationId='sibling'";else->"UPDATE ON health_sync_outbox WHEN NEW.operationId='op'"};s.execSQL("CREATE TRIGGER fault $timing $clause BEGIN SELECT RAISE(ABORT,'outbox fault'); END");assertNotNull(runCatching{apply(db,e,if(point=="retry")1 else 0,true)}.exceptionOrNull());assertEquals(before,snapshot(db));s.execSQL("DROP TRIGGER fault");assertTrue(apply(db,e,if(point=="retry")1 else 0,true))}
 }
 @Test fun privateSessionRejectsWrongModeOrderArgsClosedThreadAndReentry(){
  RustTrackNative.warmUp();val db=open();val e=entry();seed(db,e);val sql=db.openHelper.writableDatabase;val before=snapshot(db)
  val field=RustOutboxStorage::class.java.getDeclaredField("entered").apply{isAccessible=true};@Suppress("UNCHECKED_CAST")val entered=field.get(RustOutboxStorage)as ThreadLocal<Boolean>
  val cls=Class.forName("com.cyclecare.app.data.local.rust.RustOutboxStorage\$Session");val ctor=cls.declaredConstructors.single().apply{isAccessible=true}
  fun call(session:Any,name:String,vararg args:Any):Any?{val m=cls.declaredMethods.single{it.name==name}.apply{isAccessible=true};return try{m.invoke(session,*args)}catch(e:java.lang.reflect.InvocationTargetException){throw e.cause!!}}
  for(mode in 0..1){val end=runCatching{db.runInTransaction{entered.set(true);val session=ctor.newInstance(sql,e,mode,8L,99L,"error")
   try{for(name in listOf("matches","base","attempts","deleteAcked")){assertNotNull(runCatching{call(session,name)}.exceptionOrNull())};assertNotNull(runCatching{call(session,"outcome",1)}.exceptionOrNull());assertNotNull(runCatching{RustOutboxStorage.retry(sql,e,1,"reentry")}.exceptionOrNull())
    val error=java.util.concurrent.atomic.AtomicReference<Throwable>();val t=Thread{try{call(session,"ready")}catch(e:Throwable){error.set(e)}};t.start();t.join(5000);assertFalse(t.isAlive);assertTrue(error.get()is IllegalStateException)
    call(session,"ready");assertNotNull(runCatching{RustTrackNative.outboxDriver(session,1-mode)}.exceptionOrNull())
    if(mode==0){assertEquals(7L,call(session,"base"));assertEquals(8L,call(session,"acknowledged"));assertEquals(true,call(session,"matches"));assertNotNull(runCatching{call(session,"maxServer",9L)}.exceptionOrNull());call(session,"maxServer",8L);call(session,"deleteAcked");assertNotNull(runCatching{call(session,"rebase",9L)}.exceptionOrNull());call(session,"rebase",8L)}else{assertEquals(true,call(session,"matches"));assertEquals(2,call(session,"attempts"));assertNotNull(runCatching{call(session,"retryCas",4)}.exceptionOrNull());assertEquals(true,call(session,"retryCas",3))}
    assertNotNull(runCatching{call(session,"outcome",0)}.exceptionOrNull());assertEquals(true,call(session,"outcome",1));call(session,"close");assertNotNull(runCatching{call(session,"outcome",1)}.exceptionOrNull());throw IllegalStateException("adversarial rollback")
   }finally{call(session,"close");entered.remove()}
  }}.exceptionOrNull();assertEquals("adversarial rollback",end?.message);assertEquals(before,snapshot(db))}
 }
 @Test fun actualHostAckRetryBindOriginalFullDatabaseAndConsentSessionRefusal()=runBlocking{
  RustTrackNative.warmUp()
  for(mode in 0..1){val db=open();val e=entry();seed(db,e);val prefs=AuthPreferences(context,context.getSharedPreferences("sync-${UUID.randomUUID()}",Context.MODE_PRIVATE),true).apply{userId="owner";isCloudSyncAllowed=true;check(saveServerHealthConsentReceipt("owner",sessionGeneration,healthConsentGeneration,31,true))};val h=RustBoundRoomHost(prefs);val token=(h.publish(null,db,prefs.rustOperationSnapshot())as RustBoundRoomHost.Result.Value).value;val initial=snapshot(db);var expected:List<List<String?>>?=null;val control=runCatching{db.runInTransaction{if(mode==0)RustOutboxRows.acknowledge(db.openHelper.writableDatabase,e,8)else RustOutboxRows.retry(db.openHelper.writableDatabase,e,999,"synthetic");expected=snapshot(db);throw IllegalStateException("original rollback")}}.exceptionOrNull();assertEquals("original rollback",control?.message);assertEquals(initial,snapshot(db))
   suspend fun call(server:Long=31)=withContext(Dispatchers.IO){if(mode==0)h.acknowledgeSync(token,server,e,8)else h.retrySync(token,server,e,999,"synthetic")}
   assertEquals(RustBoundRoomHost.Result.Stale,call(32));assertEquals(initial,snapshot(db));val result=call();assertTrue(result is RustBoundRoomHost.Result.Value&&result.value);assertEquals(expected,snapshot(db));prefs.markPendingHealthSyncRevoke();assertEquals(RustBoundRoomHost.Result.Stale,call());assertEquals(expected,snapshot(db));prefs.userId="other";assertEquals(RustBoundRoomHost.Result.Stale,call());assertEquals(expected,snapshot(db))
  }
 }

}
