package com.cyclecare.app.data.local.rust
import androidx.sqlite.db.SupportSQLiteDatabase
import com.cyclecare.app.data.local.CycleDayLog
import com.cyclecare.app.data.local.HealthSyncPayloadCodec
/** Candidate Room-side common read/write seam, NOT yet selected by DAOs.
 * Guarded Room transaction owns every call. Sidecar hash uses normalized codec,
 * bits restores exact signedzero only after finite/numeric/hash validation.
 * Hash differs from Rust scratch physical hash; no silent cross-format adapter.
 */
internal class CorruptRustFloat:IllegalStateException()
internal object RustFloatRows {
 private fun valid(ok:Boolean){if(!ok)throw CorruptRustFloat()}
 fun readAll(db:SupportSQLiteDatabase)=read(db,null)
 fun readOne(db:SupportSQLiteDatabase,date:String):CycleDayLog?=read(db,date).singleOrNull()
 private fun read(db:SupportSQLiteDatabase,date:String?):List<CycleDayLog> {
  val out=mutableListOf<CycleDayLog>()
  val query=if(date==null)androidx.sqlite.db.SimpleSQLiteQuery("SELECT * FROM cycle_day_logs ORDER BY dateString DESC")else androidx.sqlite.db.SimpleSQLiteQuery("SELECT * FROM cycle_day_logs WHERE dateString=? LIMIT 1",arrayOf(date))
  db.query(query).use{c->
   fun s(k:String)=c.getString(c.getColumnIndexOrThrow(k))
   fun i(k:String)=c.getInt(c.getColumnIndexOrThrow(k))
   fun f(k:String)=c.getFloat(c.getColumnIndexOrThrow(k))
   while(c.moveToNext()){
    val row=CycleDayLog(s("dateString"),s("flowLevel"),s("energyLevel"),s("moodLevel"),s("symptomsCsv"),s("vaginalHealthCsv"),s("urinaryHealthCsv"),s("cervicalMucus"),s("ovulationTest"),f("bbtCelsius"),f("sleepHours"),s("sleepQuality"),i("waterGlasses"),s("notes"),i("painIntensity"))
    valid(row.bbtCelsius.isFinite()&&row.sleepHours.isFinite())
    db.query("SELECT normalizedHash,bbtBits,sleepBits FROM rust_float_bits WHERE dateString=?",arrayOf(row.dateString)).use{b->
     if(b.moveToFirst()){
      val bb=b.getLong(1);val sb=b.getLong(2);valid(bb in 0..0xffffffffL&&sb in 0..0xffffffffL)
      val bf=Float.fromBits(bb.toInt());val sf=Float.fromBits(sb.toInt());valid(bf.isFinite()&&sf.isFinite()&&bf.toDouble()==c.getDouble(c.getColumnIndexOrThrow("bbtCelsius"))&&sf.toDouble()==c.getDouble(c.getColumnIndexOrThrow("sleepHours")))
      valid(b.getString(0)==normalizedHash(row));out.add(row.copy(bbtCelsius=bf,sleepHours=sf))
     }else out.add(row)
    }
   }
  };return out
 }
 private fun normalizedHash(row:CycleDayLog)=HealthSyncPayloadCodec.hash(HealthSyncPayloadCodec.logPayload(row.copy(bbtCelsius=if(row.bbtCelsius==0f)0f else row.bbtCelsius,sleepHours=if(row.sleepHours==0f)0f else row.sleepHours)))
 /** Domain writer must execute first; trigger removes prior bits. Calling this
  * without same-transaction domain write is prohibited by caller contract.
  */
 fun recordAfterDomainWrite(db:SupportSQLiteDatabase,row:CycleDayLog){
  check(db.inTransaction()) { "paired_domain_bits_transaction_required" }
  require(row.bbtCelsius.isFinite()&&row.sleepHours.isFinite())
  val actual=readOne(db,row.dateString)?:throw CorruptRustFloat()
  require(normalizedHash(actual)==normalizedHash(row))
  db.execSQL("INSERT OR REPLACE INTO rust_float_bits(dateString,normalizedHash,bbtBits,sleepBits) VALUES(?,?,?,?)",arrayOf<Any>(row.dateString,normalizedHash(row),row.bbtCelsius.toRawBits().toLong()and 0xffffffffL,row.sleepHours.toRawBits().toLong()and 0xffffffffL))
 }
}
