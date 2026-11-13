package com.cyclecare.app.data.local

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.security.KeyStore
import java.util.UUID
import kotlinx.coroutines.runBlocking
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * On-device proof of the production encrypted-local-store path. Confinement is
 * structural, not naming convention: the instrumentation seam lives behind the
 * isolated .debug/.lab application id (the production id refuses it), uses its
 * own instance cache (production caches are unreachable), and requires an
 * injected manager bound to TEST-OWNED key stores, so no call can read
 * production prefs or the production Keystore alias and no production handle
 * can be returned or evicted. The legacy guest database is never opened.
 *
 * Per-run unique owner/prefs/alias (UUID) so re-runs cannot collide; the
 * two-phase suite's stable names are the only deliberate exception.
 * All data synthetic. No network; the consent gate is never opened.
 *
 * Oracles: correct-key roundtrip through the REAL production factory;
 * wrong-key REFUSAL with the cipher-specific cause
 * (net.zetetic.database.sqlcipher.SQLiteNotADatabaseException - class verified
 * against the actual sqlcipher-android 4.19.1 AAR) AND correct-key reopen of
 * the known row afterwards; durable reopen-from-disk after close+cache-eviction
 * with the stored wrapped passphrase; non-plaintext header (secondary only).
 */
@RunWith(AndroidJUnit4::class)
class EncryptedDatabaseInstrumentedTest {

    private val context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private val runSuffix = UUID.randomUUID().toString()
    private val ownerIdentity = CycleCareDatabase.TEST_OWNER_IDENTITY_PREFIX + "db-" + runSuffix
    private val dbName = CycleCareDatabase.namespaceFor(ownerIdentity)
    private val testPrefsName = TEST_OWNED_KEYSTORE_PREFIX + "db_prefs_" + runSuffix
    private val testKeyAlias = TEST_OWNED_KEYSTORE_PREFIX + "db_key_" + runSuffix

    private val openHandles = mutableListOf<CycleCareDatabase>()

    private fun keyManager() =
        DatabaseKeyManager(context, preferencesName = testPrefsName, keyAlias = testKeyAlias)

    private fun openDb(): CycleCareDatabase =
        CycleCareDatabase.getTestDatabase(context, ownerIdentity, keyManager()).also { openHandles += it }

    @After
    fun tearDown() {
        // Attempt EVERY owned cleanup step, collect failures, assert once at the
        // end: a failing step never skips later steps. Absent / never-created
        // artifacts are valid states, not errors. Only test-owned artifacts are
        // touched; evictTestInstance is idempotent and structurally cannot
        // reach production caches.
        val failures = mutableListOf<String>()
        openHandles.forEach { handle ->
            runCatching { handle.close() }.onFailure { failures += "db close: $it" }
        }
        openHandles.clear()
        CycleCareDatabase.evictTestInstance(dbName)

        runCatching {
            val committed = context
                .getSharedPreferences(testPrefsName, android.content.Context.MODE_PRIVATE)
                .edit().clear().commit()
            if (!committed) failures += "test prefs clear did not commit"
        }.onFailure { failures += "test prefs clear: $it" }

        runCatching {
            val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            if (keyStore.containsAlias(testKeyAlias)) keyStore.deleteEntry(testKeyAlias)
            if (keyStore.containsAlias(testKeyAlias)) failures += "test keystore alias still present"
        }.onFailure { failures += "keystore cleanup: $it" }

        runCatching {
            val file = context.getDatabasePath(dbName)
            if (file.exists()) {
                if (!context.deleteDatabase(dbName)) failures += "deleteDatabase returned false"
                if (file.exists()) failures += "database file still exists after delete"
            }
        }.onFailure { failures += "db delete: $it" }

        assertTrue("test-owned cleanup failures: $failures", failures.isEmpty())
    }

    @Test
    fun encryptedRoundTripThroughProductionFactory() = runBlocking {
        val db = openDb()
        val log = CycleDayLog(
            dateString = "2026-11-06",
            flowLevel = "MEDIUM",
            moodLevel = "CALM",
            symptomsCsv = "cramps,headache",
            bbtCelsius = 36.6f,
            sleepHours = 7.5f,
            waterGlasses = 6,
            notes = "synthetic instrumented row",
            painIntensity = 3
        )
        db.cycleDao().insertOrUpdateLog(log)
        // Complete-object equality: every column, not a subset.
        assertEquals(log, db.cycleDao().getLogByDate("2026-11-06"))
    }

    @Test
    fun wrongPassphraseIsRefusedAndFileUnharmed() = runBlocking {
        val db = openDb()
        db.cycleDao().insertOrUpdateLog(
            CycleDayLog(dateString = "2026-11-06", notes = "known-row", waterGlasses = 5)
        )
        db.close()
        openHandles.remove(db)
        CycleCareDatabase.evictTestInstance(dbName)

        val file = context.getDatabasePath(dbName)
        assertTrue(file.exists())

        val wrongDb = Room.databaseBuilder(context, CycleCareDatabase::class.java, file.absolutePath)
            .openHelperFactory(
                SupportOpenHelperFactory("definitely-the-wrong-passphrase-0123456789abcdef".toByteArray(Charsets.UTF_8))
            )
            .build()
        val failure = try {
            wrongDb.openHelper.writableDatabase
            null
        } catch (e: Exception) {
            e
        } finally {
            wrongDb.close()
        }
        assertNotNull("wrong passphrase must throw", failure)
        // Cipher-specific cause: SQLCipher 4.19.1 raises
        // SQLiteNotADatabaseException (verified against the actual AAR); the
        // framework wrapper may surface SQLiteDatabaseCorruptException, and the
        // message reports the unreadable header. A generic catch would not
        // prove the cipher refused the key.
        val cipherRefusal = generateSequence(failure as Throwable?) { it.cause }.any {
            it is net.zetetic.database.sqlcipher.SQLiteNotADatabaseException ||
                it is android.database.sqlite.SQLiteDatabaseCorruptException ||
                (it.message?.contains("file is not a database") == true) ||
                (it.message?.contains("file is encrypted") == true)
        }
        assertTrue("failure must be the cipher-level refusal, was: $failure", cipherRefusal)

        // The failed attempt must not have damaged the file.
        val reopened = openDb()
        assertEquals(
            CycleDayLog(dateString = "2026-11-06", notes = "known-row", waterGlasses = 5),
            reopened.cycleDao().getLogByDate("2026-11-06")
        )
    }

    @Test
    fun durableReopenAfterCloseWithStoredWrappedPassphrase() = runBlocking {
        val expected = CycleDayLog(dateString = "2026-11-06", notes = "reopen-oracle", waterGlasses = 4)
        val first = openDb()
        first.cycleDao().insertOrUpdateLog(expected)
        first.close()
        openHandles.remove(first)
        CycleCareDatabase.evictTestInstance(dbName)

        // Full production path again: unwrap the stored passphrase from the
        // test-owned Keystore alias and open the existing encrypted file.
        val second = openDb()
        assertEquals(expected, second.cycleDao().getLogByDate("2026-11-06"))
    }

    @Test
    fun storedFileIsNotPlaintextSqlite() {
        val db = openDb()
        db.openHelper.writableDatabase
        db.close()
        openHandles.remove(db)
        CycleCareDatabase.evictTestInstance(dbName)

        val header = ByteArray(16)
        context.getDatabasePath(dbName).inputStream().use { it.read(header) }
        // Secondary signal only; wrong-key refusal is the encryption proof.
        assertNotEquals("SQLite format 3", String(header, 0, 15, Charsets.UTF_8))
    }

    @Test
    fun testKeyMaterialIsSyntheticAndStableWithinRun() {
        // Routing demonstration only: the injected manager mints into the
        // test-owned prefs file and stays stable across calls. No production
        // store is read; confinement is enforced by the manager's store rule
        // and the seam's store binding.
        val first = keyManager().getOrCreatePassphrase()
        val second = keyManager().getOrCreatePassphrase()
        assertFalse(first.isBlank())
        assertEquals(first, second)
        val testPrefs = context.getSharedPreferences(
            testPrefsName, android.content.Context.MODE_PRIVATE
        )
        assertNotNull(testPrefs.getString("wrapped_sqlcipher_passphrase", null))
    }
}
