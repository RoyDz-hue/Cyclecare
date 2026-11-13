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
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

/**
 * On-device proof of the production encrypted-local-store path, hermetically
 * confined by construction:
 *  - Owner identity carries the RESERVED test prefix
 *    [CycleCareDatabase.TEST_OWNER_IDENTITY_PREFIX]; the production
 *    getDatabase path refuses that prefix and this seam requires it, so
 *    neither path can hand back the other's handle. The legacy guest database
 *    is unreachable here.
 *  - Key material lives in a TEST-PREFIXED SharedPreferences file and
 *    Keystore alias; DatabaseKeyManager refuses any other injected names.
 *  - Per-run unique names (UUID suffix) so re-runs can never collide; the
 *    two-phase process-restart suite is the only deliberate stable-name
 *    exception and lives in its own class.
 *  - All data synthetic. No network; the consent gate is never opened.
 *    Instrumented-process startup is hermetic by inspection: no custom
 *    Application class, no startup providers in the source manifest, no
 *    enqueued WorkManager work on a fresh install.
 *
 * Oracles: correct-key roundtrip through the REAL production factory;
 * wrong-key REFUSAL with cipher-specific cause AND correct-key reopen of the
 * known row afterwards; durable reopen-from-disk after close+cache-eviction
 * with the stored wrapped passphrase; non-plaintext header (secondary only).
 * Remaining oracles are tracked in V9FixtureProcessRestartInstrumentedTest
 * (cross-process reopen + encrypted v9->v10 upgrade) - not claimed here.
 */
@RunWith(AndroidJUnit4::class)
class EncryptedDatabaseInstrumentedTest {

    private val context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private val runSuffix = UUID.randomUUID().toString()
    private val ownerIdentity = CycleCareDatabase.TEST_OWNER_IDENTITY_PREFIX + "db-" + runSuffix
    private val dbName = CycleCareDatabase.namespaceFor(ownerIdentity)
    private val testPrefsName = DatabaseKeyManager.TEST_OWNED_PREFIX + "db_prefs_" + runSuffix
    private val testKeyAlias = DatabaseKeyManager.TEST_OWNED_PREFIX + "db_key_" + runSuffix

    private val openHandles = mutableListOf<CycleCareDatabase>()

    private fun keyManager() =
        DatabaseKeyManager(context, preferencesName = testPrefsName, keyAlias = testKeyAlias)

    private fun openDb(): CycleCareDatabase =
        CycleCareDatabase.getDatabase(context, ownerIdentity, keyManager()).also { openHandles += it }

    @After
    fun tearDown() {
        // Independent, verified cleanup steps - each failure surfaces, no broad
        // runCatching. Only test-owned artifacts are touched.
        openHandles.forEach { it.close() }
        openHandles.clear()
        CycleCareDatabase.evictCachedInstanceForTest(dbName)

        val prefsCleared = context
            .getSharedPreferences(testPrefsName, android.content.Context.MODE_PRIVATE)
            .edit().clear().commit()
        assertTrue("test prefs clear must commit", prefsCleared)

        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (keyStore.containsAlias(testKeyAlias)) keyStore.deleteEntry(testKeyAlias)
        assertFalse("test keystore alias must be gone", keyStore.containsAlias(testKeyAlias))

        assertTrue("test database file must be deleted", context.deleteDatabase(dbName))
        assertFalse("test database path must not exist", context.getDatabasePath(dbName).exists())
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
        val readBack = db.cycleDao().getLogByDate("2026-11-06")
        assertNotNull(readBack)
        assertEquals(log, readBack)
    }

    @Test
    fun wrongPassphraseIsRefusedAndFileUnharmed() = runBlocking {
        val db = openDb()
        db.cycleDao().insertOrUpdateLog(
            CycleDayLog(dateString = "2026-11-06", notes = "known-row", waterGlasses = 5)
        )
        db.close()
        openHandles.remove(db)
        CycleCareDatabase.evictCachedInstanceForTest(dbName)

        val file = context.getDatabasePath(dbName)
        assertTrue(file.exists())

        val wrongFactory = SupportOpenHelperFactory(
            "definitely-the-wrong-passphrase-0123456789abcdef".toByteArray(Charsets.UTF_8)
        )
        val wrongDb = Room.databaseBuilder(context, CycleCareDatabase::class.java, file.absolutePath)
            .openHelperFactory(wrongFactory)
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
        // Cipher-specific cause: SQLCipher raises SQLiteDatabaseCorruptException
        // (or an SQLiteException whose message reports the unreadable header)
        // BEFORE any identity or migration logic can run. A generic catch-all
        // pass would not prove the cipher refused the key.
        val cipherRefusal = generateSequence(failure as Throwable?) { it.cause }.any {
            it is android.database.sqlite.SQLiteDatabaseCorruptException ||
                it is net.zetetic.database.sqlcipher.SQLiteDatabaseCorruptException ||
                (it.message?.contains("file is not a database") == true) ||
                (it.message?.contains("file is encrypted") == true)
        }
        assertTrue("failure must be the cipher-level refusal, was: $failure", cipherRefusal)

        // The failed attempt must not have damaged the file: correct key still
        // opens it and the known row is intact.
        val reopened = openDb()
        val row = reopened.cycleDao().getLogByDate("2026-11-06")
        assertNotNull(row)
        assertEquals("known-row", row!!.notes)
        assertEquals(5, row.waterGlasses)
    }

    @Test
    fun durableReopenAfterCloseWithStoredWrappedPassphrase() = runBlocking {
        val first = openDb()
        first.cycleDao().insertOrUpdateLog(
            CycleDayLog(dateString = "2026-11-06", notes = "reopen-oracle", waterGlasses = 4)
        )
        first.close()
        openHandles.remove(first)
        CycleCareDatabase.evictCachedInstanceForTest(dbName)

        // Full production path again: unwrap the stored passphrase from the
        // test-owned Keystore alias and open the existing encrypted file.
        val second = openDb()
        val readBack = second.cycleDao().getLogByDate("2026-11-06")
        assertNotNull(readBack)
        assertEquals("reopen-oracle", readBack!!.notes)
        assertEquals(4, readBack.waterGlasses)
    }

    @Test
    fun storedFileIsNotPlaintextSqlite() {
        val db = openDb()
        db.openHelper.writableDatabase
        db.close()
        openHandles.remove(db)
        CycleCareDatabase.evictCachedInstanceForTest(dbName)

        val header = ByteArray(16)
        context.getDatabasePath(dbName).inputStream().use { it.read(header) }
        // Secondary signal only; wrong-key refusal is the encryption proof.
        assertNotEquals("SQLite format 3", String(header, 0, 15, Charsets.UTF_8))
    }

    @Test
    fun testKeyMaterialIsSyntheticAndStableWithinRun() {
        // Routing demonstration only: the injected manager mints into the
        // test-owned prefs file, and stays stable across calls. No production
        // prefs file or alias is read here; confinement is enforced by the
        // manager's prefix rule and the seam's prefix rule, not inspected
        // against production state.
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
