package com.cyclecare.app.data.local

import android.content.Context
import net.zetetic.database.sqlcipher.SQLiteDatabase
import java.io.File

/** Converts the legacy Room SQLite file once, preserving it until the encrypted replacement verifies. */
internal object DatabaseEncryption {
    fun ensureEncrypted(context: Context, databaseName: String, passphrase: String) {
        val databaseFile = context.getDatabasePath(databaseName)
        val directory = databaseFile.parentFile
            ?: throw IllegalStateException("Local database directory is unavailable.")
        if (!directory.exists() && !directory.mkdirs()) {
            throw IllegalStateException("Local database directory could not be created.")
        }

        val tempFile = File(directory, "${databaseFile.name}.encrypted.tmp")
        val plaintextBackup = File(directory, "${databaseFile.name}.plaintext.backup")
        val key = passphrase.toByteArray(Charsets.UTF_8)

        if (isReadable(databaseFile, key)) {
            removeOrThrow(tempFile)
            removeOrThrow(plaintextBackup)
            removeSidecars(databaseFile)
            return
        }

        // Recover a complete encrypted export left behind by an interrupted prior conversion.
        if (isReadable(tempFile, key)) {
            promoteEncryptedFile(tempFile, databaseFile, plaintextBackup, key)
            return
        }
        removeOrThrow(tempFile)

        // Recover the original file if a process stopped after moving it aside.
        if (!databaseFile.exists() && plaintextBackup.exists()) {
            renameOrThrow(plaintextBackup, databaseFile)
        }
        if (!databaseFile.exists()) return // Room will create a new encrypted database.

        if (!isReadablePlaintext(databaseFile)) {
            if (plaintextBackup.exists() && isReadablePlaintext(plaintextBackup)) {
                removeOrThrow(databaseFile)
                renameOrThrow(plaintextBackup, databaseFile)
            } else {
                // A file that is neither readable as plaintext nor with this key is preserved.
                throw IllegalStateException("Local database encryption could not be verified; original data was preserved.")
            }
        }

        exportPlaintextToEncrypted(databaseFile, tempFile, passphrase)
        if (!isReadable(tempFile, key)) {
            removeOrThrow(tempFile)
            throw IllegalStateException("Encrypted database verification failed; original data was preserved.")
        }
        promoteEncryptedFile(tempFile, databaseFile, plaintextBackup, key)
    }

    private fun exportPlaintextToEncrypted(sourceFile: File, destinationFile: File, passphrase: String) {
        removeOrThrow(destinationFile)
        var source: SQLiteDatabase? = null
        var attached = false
        try {
            // An empty key opens a standard SQLite database; no key material is written to logs.
            source = SQLiteDatabase.openDatabase(
                sourceFile.absolutePath,
                ByteArray(0),
                null,
                SQLiteDatabase.OPEN_READWRITE,
                null
            )
            // Fold any previous Room WAL contents into the source before exporting all tables.
            source.rawQuery("PRAGMA wal_checkpoint(TRUNCATE)", null).use { it.moveToFirst() }
            source.execSQL(
                "ATTACH DATABASE ? AS encrypted KEY ?",
                arrayOf<Any>(destinationFile.absolutePath, passphrase)
            )
            attached = true
            source.rawQuery("SELECT sqlcipher_export('encrypted')", null).use { cursor ->
                check(cursor.moveToFirst()) { "SQLCipher export did not return a result." }
            }
            source.execSQL("DETACH DATABASE encrypted")
            attached = false
        } catch (error: Exception) {
            removeOrThrow(destinationFile)
            throw IllegalStateException("Existing local data could not be encrypted; the plaintext source was preserved.", error)
        } finally {
            if (attached) runCatching { source?.execSQL("DETACH DATABASE encrypted") }
            source?.close()
        }
    }

    private fun promoteEncryptedFile(
        tempFile: File,
        databaseFile: File,
        plaintextBackup: File,
        key: ByteArray
    ) {
        // The verified temp copy is safe to promote. Keep one recoverable source until final verification.
        if (databaseFile.exists()) {
            removeOrThrow(plaintextBackup)
            renameOrThrow(databaseFile, plaintextBackup)
        }
        if (!tempFile.renameTo(databaseFile)) {
            if (plaintextBackup.exists() && !databaseFile.exists()) plaintextBackup.renameTo(databaseFile)
            throw IllegalStateException("Encrypted database could not replace the old file; original data was preserved.")
        }
        if (!isReadable(databaseFile, key)) {
            removeOrThrow(databaseFile)
            if (plaintextBackup.exists()) renameOrThrow(plaintextBackup, databaseFile)
            throw IllegalStateException("Final encrypted database verification failed; original data was restored.")
        }
        removeOrThrow(plaintextBackup)
        removeSidecars(databaseFile)
    }

    private fun isReadable(file: File, key: ByteArray): Boolean = isReadable(file, key, SQLiteDatabase.OPEN_READONLY)

    private fun isReadablePlaintext(file: File): Boolean = isReadable(file, ByteArray(0), SQLiteDatabase.OPEN_READWRITE)

    private fun isReadable(file: File, key: ByteArray, flags: Int): Boolean {
        if (!file.exists() || file.length() == 0L) return false
        var database: SQLiteDatabase? = null
        return try {
            database = SQLiteDatabase.openDatabase(file.absolutePath, key, null, flags, null)
            database.isDatabaseIntegrityOk
        } catch (_: Exception) {
            false
        } finally {
            database?.close()
        }
    }

    private fun removeSidecars(databaseFile: File) {
        listOf("-wal", "-shm", "-journal").forEach { suffix ->
            removeOrThrow(File(databaseFile.parentFile, databaseFile.name + suffix))
        }
    }

    private fun removeOrThrow(file: File) {
        if (file.exists() && !file.delete()) {
            throw IllegalStateException("A temporary local database file could not be safely removed.")
        }
    }

    private fun renameOrThrow(source: File, destination: File) {
        if (!source.renameTo(destination)) {
            throw IllegalStateException("A local database recovery file could not be safely moved.")
        }
    }
}
