package com.cyclecare.app.data.local.rust
import androidx.sqlite.db.SupportSQLiteDatabase
import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import com.cyclecare.app.data.local.*
import org.json.JSONArray
import org.json.JSONObject
/** Synchronous same-transaction typed full-column seam. Sync payload alone is
 * not a physical dependency: it omits local onboarding/security/reminder fields.
 */
internal object RustDomainRows {
 sealed class Row {
  data class Log(val value:CycleDayLog):Row()
  data class Settings(val value:CycleSettings):Row()
  data class Profile(val value:OnboardingProfile):Row()
 }
 fun payload(row:Row?):String?=when(row){null->null;is Row.Log->HealthSyncPayloadCodec.logPayload(row.value);is Row.Settings->HealthSyncPayloadCodec.settingsPayload(row.value);is Row.Profile->HealthSyncPayloadCodec.profilePayload(row.value)}
 fun record(row:Row):String=when(row){is Row.Log->row.value.dateString;is Row.Settings->"settings";is Row.Profile->"profile"}
 private fun columns(row:Row):LinkedHashMap<String,Any> = when(row){
  is Row.Log->row.value.let{linkedMapOf("dateString" to it.dateString,"flowLevel" to it.flowLevel,"energyLevel" to it.energyLevel,"moodLevel" to it.moodLevel,"symptomsCsv" to it.symptomsCsv,"vaginalHealthCsv" to it.vaginalHealthCsv,"urinaryHealthCsv" to it.urinaryHealthCsv,"cervicalMucus" to it.cervicalMucus,"ovulationTest" to it.ovulationTest,"bbtCelsius" to it.bbtCelsius,"sleepHours" to it.sleepHours,"sleepQuality" to it.sleepQuality,"waterGlasses" to it.waterGlasses,"notes" to it.notes,"painIntensity" to it.painIntensity)}
  is Row.Settings->row.value.let{linkedMapOf("id" to it.id,"isOnboardingCompleted" to it.isOnboardingCompleted,"selectedGoalsCsv" to it.selectedGoalsCsv,"lastPeriodStartDate" to it.lastPeriodStartDate,"averageCycleLength" to it.averageCycleLength,"periodDuration" to it.periodDuration,"lutealPhaseDays" to it.lutealPhaseDays,"isAppLockEnabled" to it.isAppLockEnabled,"lockTimeoutMinutes" to it.lockTimeoutMinutes,"isDiscreetNotificationMode" to it.isDiscreetNotificationMode,"isPregnancyMode" to it.isPregnancyMode,"pregnancyDueDate" to it.pregnancyDueDate,"remindPeriod" to it.remindPeriod,"remindFertile" to it.remindFertile,"remindDailyLog" to it.remindDailyLog)}
  is Row.Profile->row.value.let{linkedMapOf("id" to it.id,"isCompleted" to it.isCompleted,"completedAtTimestamp" to it.completedAtTimestamp,"isAgeVerified" to it.isAgeVerified,"isTermsAgreed" to it.isTermsAgreed,"accountMode" to it.accountMode,"selectedGoalsCsv" to it.selectedGoalsCsv,"lastPeriodDateString" to it.lastPeriodDateString,"lastPeriodEndDateString" to it.lastPeriodEndDateString,"approximatePeriodChoice" to it.approximatePeriodChoice,"cycleLengthOption" to it.cycleLengthOption,"cycleLengthDays" to it.cycleLengthDays,"periodDurationDays" to it.periodDurationDays,"remindFertileWindow" to it.remindFertileWindow,"typicalSymptomsCsv" to it.typicalSymptomsCsv,"typicalEnergy" to it.typicalEnergy,"typicalSleepQuality" to it.typicalSleepQuality,"hasCompletedDeepBaseline" to it.hasCompletedDeepBaseline)}
 }
 fun physical(row:Row?):String=HealthSyncPayloadCodec.hash(row?.let{r->JSONArray().put(record(r)).put(JSONArray().apply{columns(r).forEach{(k,v)->put(JSONArray().put(k).put(if(v is Float)v.toRawBits().toLong() and 0xffffffffL else v))}}).toString()})
 /** Storage fingerprint includes SQLite types and raw double bits, not only
  * typed values. Tiny REAL drift that rounds to same Float must still conflict.
  */
 fun storedPhysical(db:SupportSQLiteDatabase,id:String):String {
  val table=when(id){"settings"->"cycle_settings";"profile"->"onboarding_profiles";else->"cycle_day_logs"}
  val query=if(id=="settings"||id=="profile")androidx.sqlite.db.SimpleSQLiteQuery("SELECT * FROM $table WHERE id=1")else androidx.sqlite.db.SimpleSQLiteQuery("SELECT * FROM $table WHERE dateString=?",arrayOf(id))
  val values=JSONArray()
  db.query(query).use{c->if(c.moveToFirst())for(k in 0 until c.columnCount){val type=c.getType(k);val v:Any=when(type){android.database.Cursor.FIELD_TYPE_NULL->JSONObject.NULL;android.database.Cursor.FIELD_TYPE_INTEGER->c.getLong(k);android.database.Cursor.FIELD_TYPE_FLOAT->java.lang.Double.doubleToRawLongBits(c.getDouble(k)).toString();android.database.Cursor.FIELD_TYPE_STRING->c.getString(k);else->throw CorruptRustFloat()};values.put(JSONArray().put(c.getColumnName(k)).put(type).put(v))}}
  if(table=="cycle_day_logs")db.query("SELECT normalizedHash,bbtBits,sleepBits FROM rust_float_bits WHERE dateString=?",arrayOf(id)).use{c->if(c.moveToFirst())values.put(JSONArray().put("sidecar").put(c.getString(0)).put(c.getLong(1)).put(c.getLong(2)))}
  return HealthSyncPayloadCodec.hash(values.toString())
 }
 fun read(db:SupportSQLiteDatabase,id:String):Row? {
  if(id!="settings"&&id!="profile")return RustFloatRows.readOne(db,id)?.let(Row::Log)
  val table=if(id=="settings")"cycle_settings" else "onboarding_profiles"
  db.query("SELECT * FROM $table WHERE id=1 LIMIT 1").use{c->
   if(!c.moveToFirst())return null
   fun s(k:String)=c.getString(c.getColumnIndexOrThrow(k))
   fun i(k:String)=c.getInt(c.getColumnIndexOrThrow(k))
   fun l(k:String)=c.getLong(c.getColumnIndexOrThrow(k))
   fun b(k:String):Boolean {val v=i(k);if(v !in 0..1)throw CorruptRustFloat();return v==1}
   return if(id=="settings")Row.Settings(CycleSettings(i("id"),b("isOnboardingCompleted"),s("selectedGoalsCsv"),s("lastPeriodStartDate"),i("averageCycleLength"),i("periodDuration"),i("lutealPhaseDays"),b("isAppLockEnabled"),i("lockTimeoutMinutes"),b("isDiscreetNotificationMode"),b("isPregnancyMode"),s("pregnancyDueDate"),b("remindPeriod"),b("remindFertile"),b("remindDailyLog"))) else Row.Profile(OnboardingProfile(i("id"),b("isCompleted"),l("completedAtTimestamp"),b("isAgeVerified"),b("isTermsAgreed"),s("accountMode"),s("selectedGoalsCsv"),s("lastPeriodDateString"),s("lastPeriodEndDateString"),s("approximatePeriodChoice"),s("cycleLengthOption"),i("cycleLengthDays"),i("periodDurationDays"),b("remindFertileWindow"),s("typicalSymptomsCsv"),s("typicalEnergy"),s("typicalSleepQuality"),b("hasCompletedDeepBaseline")))
  }
 }
 fun write(db:SupportSQLiteDatabase,id:String,row:Row?){
  check(db.inTransaction())
  val table=when(id){"settings"->"cycle_settings";"profile"->"onboarding_profiles";else->{require(java.time.LocalDate.parse(id).toString()==id);"cycle_day_logs"}}
  if(row==null){if(id=="settings"||id=="profile")db.execSQL("DELETE FROM $table WHERE id=1")else db.execSQL("DELETE FROM $table WHERE dateString=?",arrayOf(id));return}
  require(record(row)==id)
  when(row){is Row.Settings->require(row.value.id==1);is Row.Profile->require(row.value.id==1);is Row.Log->require(row.value.bbtCelsius.isFinite()&&row.value.sleepHours.isFinite())}
  val values=ContentValues();columns(row).forEach{(key,v)->when(v){is String->values.put(key,v);is Int->values.put(key,v);is Long->values.put(key,v);is Float->values.put(key,v);is Boolean->values.put(key,if(v)1 else 0);else->error("unsupported typed column")}}
  val affected=if(row is Row.Log)db.update(table,SQLiteDatabase.CONFLICT_ABORT,values,"dateString=?",arrayOf(id))else db.update(table,SQLiteDatabase.CONFLICT_ABORT,values,"id=1",emptyArray())
  if(affected==0)check(db.insert(table,SQLiteDatabase.CONFLICT_ABORT,values)!=-1L)
  if(row is Row.Log)RustFloatRows.recordAfterDomainWrite(db,row.value)
 }
}
