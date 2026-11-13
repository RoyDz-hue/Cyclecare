package com.cyclecare.app.data.local.rust
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
/** Source preparation ONLY: NOT registered; DB stays version9. Full seven-domain-table plus metadata Room schema,
 * generated identity hash and full migration validation still required
 * before registering any migration. Never changes keys/domain/sync columns.
 */
internal val RUST_FLOAT_METADATA_DRAFT=object:Migration(9,10){
 override fun migrate(db:SupportSQLiteDatabase){
  RustEnrollmentStage.schema(db);RustEnrollmentStage.senderSchema(db)
  db.execSQL("CREATE TABLE IF NOT EXISTS rust_float_bits(dateString TEXT NOT NULL PRIMARY KEY,normalizedHash TEXT NOT NULL,bbtBits INTEGER NOT NULL,sleepBits INTEGER NOT NULL)")
  db.execSQL("CREATE TABLE IF NOT EXISTS rust_commit_receipts(namespace TEXT NOT NULL,operationId TEXT NOT NULL,intentDigest TEXT NOT NULL,version INTEGER NOT NULL,resultJson TEXT NOT NULL,PRIMARY KEY(namespace,operationId))")
  db.execSQL("CREATE TRIGGER IF NOT EXISTS rust_float_update AFTER UPDATE ON cycle_day_logs BEGIN DELETE FROM rust_float_bits WHERE dateString=OLD.dateString OR dateString=NEW.dateString; END")
  db.execSQL("CREATE TRIGGER IF NOT EXISTS rust_float_delete AFTER DELETE ON cycle_day_logs BEGIN DELETE FROM rust_float_bits WHERE dateString=OLD.dateString; END")
  db.execSQL("CREATE TRIGGER IF NOT EXISTS rust_float_insert AFTER INSERT ON cycle_day_logs BEGIN DELETE FROM rust_float_bits WHERE dateString=NEW.dateString; END")
 }
}

/** RECONSTRUCTION (2026-11-13, labeled NEW WORK, not recovered bytes):
 * applies the same draft DDL as RUST_FLOAT_METADATA_DRAFT.migrate, for
 * fresh-file onCreate callbacks (the Migration only covers 9->10 upgrades).
 * The Stage-C/D witness fixtures register both so fresh v10 files always
 * carry the rust_* objects; single source of truth is the migration body. */
internal fun applyRustMetadataDraft(db:SupportSQLiteDatabase)=RUST_FLOAT_METADATA_DRAFT.migrate(db)
