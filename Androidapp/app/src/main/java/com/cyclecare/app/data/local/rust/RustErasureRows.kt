package com.cyclecare.app.data.local.rust
import androidx.sqlite.db.SupportSQLiteDatabase
import com.cyclecare.app.data.local.CycleCareDatabase
/** Complete original admitted wipe DB body, independent oracle/preentry fallback. */
internal object RustErasureRows{
 fun apply(sql:SupportSQLiteDatabase,owner:String?,syncOwner:String){
    listOf("cycle_day_logs","onboarding_profiles","cycle_settings","rust_float_bits").forEach{table->sql.execSQL("DELETE FROM $table")}
    listOf("health_sync_outbox","health_sync_revisions","health_sync_cursors","health_sync_conflicts").forEach{table->sql.execSQL("DELETE FROM $table WHERE ownerId=?",arrayOf(syncOwner))}
    val namespace=owner?.takeIf{it.isNotBlank()}?.let(CycleCareDatabase::namespaceFor)?:"cycle_care_database"
    sql.execSQL("DELETE FROM rust_commit_receipts WHERE namespace=?",arrayOf(namespace))
    // Stage tables may not exist before candidate schema registration.
    listOf("rust_enrollment_rows","rust_enrollment_receipts","rust_enrollments").forEach{table->
     val exists=sql.query("SELECT 1 FROM sqlite_master WHERE type='table' AND name=?",arrayOf(table)).use{it.moveToFirst()}
     if(exists)sql.execSQL("DELETE FROM $table WHERE namespace=?",arrayOf(namespace))
    }
    RustDomainRows.write(sql,"settings",RustDomainRows.Row.Settings(com.cyclecare.app.data.local.CycleSettings(isOnboardingCompleted=false,lastPeriodStartDate="",averageCycleLength=28,periodDuration=5)))
 }
}
