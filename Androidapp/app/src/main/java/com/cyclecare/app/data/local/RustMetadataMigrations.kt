package com.cyclecare.app.data.local

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** Rust metadata schema, version 9 -> 10. Additive only: two new metadata
 * tables and three invalidation triggers on cycle_day_logs. No existing
 * table, index, trigger or row is touched, so upgrade preservation holds by
 * construction: every v9 row carries over untouched.
 *
 * The DDL is the exact contract the Rust-bound host already applies in its
 * file-backed fixtures. rust_float_bits caches normalized float bit patterns
 * per log date and must be invalidated on any cycle_day_logs write;
 * rust_commit_receipts persists commit receipts keyed by
 * (namespace, operationId). Persisted expressions must equal this DDL
 * verbatim; do not paraphrase or reformat the statements.
 *
 * INERT: deliberately NOT registered in CycleCareDatabase.getDatabase's
 * addMigrations chain, and the @Database version stays 9. Registration plus
 * the version bump is the gated activation step (real Room migration
 * registration with upgrade preservation must land before any code accesses
 * the new metadata). */
object RustMetadataMigrations {
    fun createMigration9To10(): Migration = object : Migration(9, 10) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("CREATE TABLE IF NOT EXISTS rust_float_bits(dateString TEXT NOT NULL PRIMARY KEY,normalizedHash TEXT NOT NULL,bbtBits INTEGER NOT NULL,sleepBits INTEGER NOT NULL)")
            db.execSQL("CREATE TABLE IF NOT EXISTS rust_commit_receipts(namespace TEXT NOT NULL,operationId TEXT NOT NULL,intentDigest TEXT NOT NULL,version INTEGER NOT NULL,resultJson TEXT NOT NULL,PRIMARY KEY(namespace,operationId))")
            db.execSQL("CREATE TRIGGER IF NOT EXISTS rust_float_update AFTER UPDATE ON cycle_day_logs BEGIN DELETE FROM rust_float_bits WHERE dateString=OLD.dateString OR dateString=NEW.dateString; END")
            db.execSQL("CREATE TRIGGER IF NOT EXISTS rust_float_delete AFTER DELETE ON cycle_day_logs BEGIN DELETE FROM rust_float_bits WHERE dateString=OLD.dateString; END")
            db.execSQL("CREATE TRIGGER IF NOT EXISTS rust_float_insert AFTER INSERT ON cycle_day_logs BEGIN DELETE FROM rust_float_bits WHERE dateString=NEW.dateString; END")
        }
    }
}
