package com.cyclecare.app.data.local

import android.os.Process
import android.os.SystemClock
import android.util.Log
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.security.KeyStore
import kotlinx.coroutines.runBlocking
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.FixMethodOrder
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestName
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters

/**
 * Cross-process reopen + encrypted v9 -> v10 upgrade oracle.
 *
 * ORDER - deterministic AND grounded, not luck:
 *  - DECLARED: one class, @FixMethodOrder(NAME_ASCENDING) -> a_ before b_.
 *  - GROUNDED in the actual execution path: AndroidJUnitRunner builds a
 *    standard JUnit Request (androidx.test.internal.runner.TestRequestBuilder);
 *    the JUnit4 class runner's description order follows the declared sorter;
 *    androidx.test.orchestrator.CallbackLogic collects tests into an
 *    append-order ArrayList and returns a copy; AndroidTestOrchestrator
 *    iterates that list SEQUENTIALLY (TestRunnable lists the collected tests
 *    for the orchestrator, each method its own target). So collection order ==
 *    execution order == declared sorter order.
 *    (android/android-test: runner/.../TestRequestBuilder.java,
 *     runner/android_test_orchestrator/.../CallbackLogic.java, TestRunnable.java)
 *  - On Firebase Test Lab the EFFECTIVE orchestrator is Firebase's own latest
 *    ("Test Lab always uses the latest version of Orchestrator"); the local
 *    pin 1.6.1 (POM: test-services 1.6.0) governs gradle-invoked runs. The
 *    sequential-iteration contract is the same architecture; regardless, the
 *    nonce+SEALED sentinel below FAILS CLOSED on any order violation - a
 *    flipped collection is a loud failure, never a false pass.
 *  - NO extra billable order-trial run: the sentinel proves order on the SAME
 *    execution that produces the oracle.
 *
 * PROCESS RESTART (clean turnover, not abrupt crash): Orchestrator restarts
 * the app process around each method; a_ cleanly closes the fixture BEFORE
 * sealing; b_ asserts pid turnover plus a boot-monotonic tick ordering. The
 * tick supports a same-device/same-execution premise; it is NOT a same-boot
 * proof (no boot id is captured).
 *
 * RUNTIME CONFIG (explicit, fail-closed): clearPackageData=false; FRESH
 * per-execution nonce via instrumentation arg "cyclecare.phaseNonce" (gradle:
 * -Pcyclecare.phaseNonce=<uuid>; gcloud: --environment-variables
 * cyclecare.phaseNonce=<uuid>); NO sharding - both methods on ONE device in
 * ONE execution. Blank nonce fails closed in both phases.
 *
 * Stability exception (deliberate): STABLE test-owned names (owner, prefs,
 * alias, marker) so b_ reopens exactly what a_ sealed - the only stable-name
 * case; all other tests use per-run UUID names.
 *
 * a_ seeds an ENCRYPTED v9 fixture on-device via the anchor mirror
 * (V9FixtureAnchorDatabase = same 7 entity sources @ version 9; claim is
 * SOURCE-IDENTITY, with v9<->v10 entity-schema identity anchored by the
 * Stage-D unit witness - no hash oracle claimed here), wrapped in the
 * test-owned Keystore passphrase; fails closed on ANY preexisting owned
 * artifact (DB, marker, prefs file, Keystore alias, conversion sidecars)
 * checked BEFORE any key material is minted.
 * b_ reopens through the FULL production path (test-owned Keystore unwrap +
 * ensureEncrypted no-op + Room open runs the real 9->10
 * RUST_FLOAT_METADATA_DRAFT; the migration's own strict verifyMetadataShape
 * is the authoritative shape oracle), then performs verified cleanup.
 * Actual values (nonce, pids, ticks, recursive_triggers flag, control delta)
 * are logged to logcat on success for the execution record.
 */
private const val PHASE_OWNER = CycleCareDatabase.TEST_OWNER_IDENTITY_PREFIX + "v9phase-owner"
private const val PHASE_PREFS = TEST_OWNED_KEYSTORE_PREFIX + "v9phase_prefs"
private const val PHASE_ALIAS = TEST_OWNED_KEYSTORE_PREFIX + "v9phase_key"
private const val PHASE_LOG = "instrumented_test_v9phase_phase.log"
private const val TAG = "V9PhaseOracle"

@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class V9FixtureProcessRestartInstrumentedTest {

    private val context
        get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val dbName = CycleCareDatabase.namespaceFor(PHASE_OWNER)
    private val openHandles = mutableListOf<CycleCareDatabase>()

    @get:Rule
    val testName = TestName()

    /** Fresh per-execution nonce supplied by the runner; blank fails closed. */
    private fun phaseNonce(): String =
        InstrumentationRegistry.getArguments().getString("cyclecare.phaseNonce")
            .also { assertTrue("runner must supply a fresh cyclecare.phaseNonce", !it.isNullOrBlank()) }!!

    @Test
    fun a_seedEncryptedV9Fixture() = runBlocking {
        val nonce = phaseNonce()

        // Fail CLOSED on ANY preexisting owned artifact - checked BEFORE any
        // key material is minted; never overwrite another execution's state.
        assertFalse("preexisting fixture db", context.getDatabasePath(dbName).exists())
        assertFalse("preexisting phase marker", File(context.filesDir, PHASE_LOG).exists())
        assertFalse(
            "preexisting test prefs file",
            File(context.applicationInfo.dataDir, "shared_prefs/$PHASE_PREFS.xml").exists()
        )
        assertFalse(
            "preexisting conversion sidecar",
            context.getDatabasePath("$dbName.encrypted.tmp").exists() ||
                context.getDatabasePath("$dbName.plaintext.backup").exists()
        )
        val probeStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        assertFalse("preexisting test keystore alias", probeStore.containsAlias(PHASE_ALIAS))

        require(PHASE_OWNER.startsWith(CycleCareDatabase.TEST_OWNER_IDENTITY_PREFIX)) {
            "Fixture owner must carry the reserved test prefix."
        }
        val passphrase = DatabaseKeyManager(context, PHASE_PREFS, PHASE_ALIAS).getOrCreatePassphrase()
        System.loadLibrary("sqlcipher")
        val anchor = Room.databaseBuilder(context, V9FixtureAnchorDatabase::class.java, dbName)
            .openHelperFactory(SupportOpenHelperFactory(passphrase.toByteArray(Charsets.UTF_8)))
            .build()
        try {
            anchor.cycleDao().insertOrUpdateLog(
                CycleDayLog(
                    dateString = "2001-02-01",
                    flowLevel = "LIGHT",
                    notes = "v9-sentinel",
                    waterGlasses = 7,
                    painIntensity = 2
                )
            )
            anchor.cycleDao().saveSettings(
                CycleSettings(isOnboardingCompleted = true, averageCycleLength = 30, periodDuration = 6)
            )
            assertEquals(7, anchor.cycleDao().getLogByDate("2001-02-01")!!.waterGlasses)
            assertEquals(30, anchor.cycleDao().getSettings()!!.averageCycleLength)
            anchor.openHelper.writableDatabase.query("PRAGMA user_version").use { c ->
                assertTrue(c.moveToFirst())
                assertEquals(9, c.getInt(0))
            }
        } finally {
            anchor.close()
        }
        assertFalse(anchor.isOpen)

        val sealed = "SEALED|$nonce|${Process.myPid()}|${SystemClock.elapsedRealtimeNanos()}"
        val marker = File(context.filesDir, PHASE_LOG)
        marker.writeText(sealed)
        assertEquals(sealed, marker.readText())
        Log.i(TAG, "phase=seed nonce=$nonce pid=${Process.myPid()} tick=${SystemClock.elapsedRealtimeNanos()}")
    }

    @Test
    fun b_verifyUpgradeAcrossProcessRestart() = runBlocking {
        val nonce = phaseNonce()

        // 1. Fail-closed order/readiness: exact sealed shape + THIS nonce.
        val marker = File(context.filesDir, PHASE_LOG)
        assertTrue(
            "phase A sentinel must exist; absence proves a_ did not complete first",
            marker.exists()
        )
        val parts = marker.readText().split("|")
        assertEquals("sealed marker must be SEALED|nonce|pid|tick", 4, parts.size)
        assertEquals("SEALED", parts[0])
        assertEquals("fixture belongs to a different execution (stale)", nonce, parts[1])

        // 2. Clean process-restart proof (pid turnover + tick ordering;
        // same-device/execution premise, NOT a same-boot proof).
        assertNotEquals(parts[2].toInt(), Process.myPid())
        assertTrue(parts[3].toLong() < SystemClock.elapsedRealtimeNanos())

        // 3. FULL production path on the encrypted v9 fixture: test-owned
        // Keystore unwrap, ensureEncrypted no-op, Room open runs the real
        // 9->10 migration with its own fail-closed shape verification.
        val db = CycleCareDatabase
            .getTestDatabase(context, PHASE_OWNER, DatabaseKeyManager(context, PHASE_PREFS, PHASE_ALIAS))
            .also { openHandles += it }

        db.openHelper.writableDatabase.query("PRAGMA user_version").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("migration 9->10 must have run", 10, c.getInt(0))
        }

        // 4. Sentinel data survived the upgrade: COMPLETE objects, every column.
        assertEquals(
            CycleDayLog(
                dateString = "2001-02-01",
                flowLevel = "LIGHT",
                notes = "v9-sentinel",
                waterGlasses = 7,
                painIntensity = 2
            ),
            db.cycleDao().getLogByDate("2001-02-01")
        )
        assertEquals(
            CycleSettings(isOnboardingCompleted = true, averageCycleLength = 30, periodDuration = 6),
            db.cycleDao().getSettings()
        )

        // 5. rust_* metadata presence (name-set only; the migration's own
        // strict verifyMetadataShape is the authoritative shape oracle).
        val expectedTables = setOf(
            "rust_domain_epoch", "rust_enrollments", "rust_enrollment_rows",
            "rust_enrollment_receipts", "rust_float_bits", "rust_commit_receipts"
        )
        val expectedTriggers = buildSet {
            addAll(setOf("rust_float_update", "rust_float_delete", "rust_float_insert"))
            for (t in listOf("cycle_day_logs", "cycle_settings", "onboarding_profiles"))
                for (a in listOf("insert", "update", "delete")) add("rust_epoch_${t}_$a")
        }
        fun names(type: String): Set<String> {
            val out = mutableSetOf<String>()
            db.openHelper.writableDatabase
                .query("SELECT name FROM sqlite_master WHERE type=? AND name LIKE 'rust_%'", arrayOf(type))
                .use { c -> while (c.moveToNext()) out += c.getString(0) }
            return out
        }
        assertEquals(expectedTables, names("table"))
        assertEquals(expectedTriggers, names("trigger"))

        // 6. Live epoch-trigger semantics against the ACTUAL connection flag
        // (queried + recorded, never set on the production DB); the expected
        // REPLACE delta comes from an INDEPENDENT framework-SQLite control
        // carrying the same trigger pattern under the same observed flag.
        fun epoch(): Long = db.openHelper.writableDatabase
            .query("SELECT value FROM rust_domain_epoch WHERE id=1").use { c ->
                assertTrue(c.moveToFirst()); c.getLong(0)
            }
        val actualFlag = db.openHelper.writableDatabase
            .query("PRAGMA recursive_triggers").use { c ->
                assertTrue(c.moveToFirst()); c.getInt(0)
            }
        val controlReplaceDelta = android.database.sqlite.SQLiteDatabase.create(null).use { c2 ->
            c2.execSQL("PRAGMA recursive_triggers=$actualFlag")
            c2.execSQL("CREATE TABLE t(id INTEGER PRIMARY KEY, v TEXT)")
            c2.execSQL("CREATE TABLE e(id INTEGER PRIMARY KEY CHECK(id=1), value INTEGER NOT NULL)")
            c2.execSQL("INSERT INTO e VALUES(1,0)")
            c2.execSQL("CREATE TRIGGER tr_i AFTER INSERT ON t BEGIN UPDATE e SET value=value+1 WHERE id=1; END")
            c2.execSQL("CREATE TRIGGER tr_d AFTER DELETE ON t BEGIN UPDATE e SET value=value+1 WHERE id=1; END")
            c2.execSQL("INSERT INTO t VALUES(1,'a')")            // +1
            c2.execSQL("INSERT OR REPLACE INTO t VALUES(1,'b')") // + REPLACE delta
            c2.compileStatement("SELECT value FROM e WHERE id=1").use { it.simpleQueryForLong() } - 1L
        }
        assertTrue("control REPLACE delta must be 1 or 2, was $controlReplaceDelta", controlReplaceDelta in 1L..2L)

        val e0 = epoch()
        db.cycleDao().insertOrUpdateLog(CycleDayLog(dateString = "2026-11-07", notes = "epoch-new"))
        assertEquals("plain INSERT bumps epoch by 1", e0 + 1, epoch())
        db.cycleDao().insertOrUpdateLog(CycleDayLog(dateString = "2026-11-07", notes = "epoch-replaced"))
        assertEquals(
            "REPLACE delta must match the independent control ($controlReplaceDelta) at recursive_triggers=$actualFlag",
            e0 + 1 + controlReplaceDelta,
            epoch()
        )
        Log.i(
            TAG,
            "phase=verify nonce=$nonce pid=${Process.myPid()} sealerPid=${parts[2]} " +
                "tick=${SystemClock.elapsedRealtimeNanos()} sealerTick=${parts[3]} " +
                "recursiveTriggers=$actualFlag controlReplaceDelta=$controlReplaceDelta"
        )

        // 7. Wrong-key refusal on the upgraded file with the verified
        // cipher-specific cause, then correct-key recovery.
        db.close()
        openHandles.remove(db)
        CycleCareDatabase.evictTestInstance(dbName)
        val file = context.getDatabasePath(dbName)
        val wrongDb = Room.databaseBuilder(context, CycleCareDatabase::class.java, file.absolutePath)
            .openHelperFactory(
                SupportOpenHelperFactory("wrong-passphrase-v9-oracle".toByteArray(Charsets.UTF_8))
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
        val cipherRefusal = generateSequence(failure as Throwable?) { it.cause }.any {
            it is net.zetetic.database.sqlcipher.SQLiteNotADatabaseException ||
                it is android.database.sqlite.SQLiteDatabaseCorruptException ||
                (it.message?.contains("file is not a database") == true) ||
                (it.message?.contains("file is encrypted") == true)
        }
        assertTrue("failure must be the cipher-level refusal, was: $failure", cipherRefusal)

        val recovered = CycleCareDatabase
            .getTestDatabase(context, PHASE_OWNER, DatabaseKeyManager(context, PHASE_PREFS, PHASE_ALIAS))
            .also { openHandles += it }
        assertEquals(
            CycleDayLog(
                dateString = "2001-02-01",
                flowLevel = "LIGHT",
                notes = "v9-sentinel",
                waterGlasses = 7,
                painIntensity = 2
            ),
            recovered.cycleDao().getLogByDate("2001-02-01")
        )
    }

    @After
    fun tearDown() {
        // @After runs after EVERY method and b_ needs a_'s artifacts: cleanup
        // is gated to the TERMINAL method. After a_, everything is preserved
        // deliberately; a failed a_ leaves artifacts only on a disposable
        // device and the nonce rule rejects them later.
        if (testName.methodName.startsWith("a_")) return

        // Attempt EVERY owned cleanup step, collect failures, assert once.
        // Absent/never-created artifacts are valid; only test-owned artifacts
        // are touched; the evict is idempotent.
        val failures = mutableListOf<String>()
        openHandles.forEach { handle ->
            runCatching { handle.close() }.onFailure { failures += "db close: $it" }
        }
        openHandles.clear()
        CycleCareDatabase.evictTestInstance(dbName)

        runCatching {
            val committed = context
                .getSharedPreferences(PHASE_PREFS, android.content.Context.MODE_PRIVATE)
                .edit().clear().commit()
            if (!committed) failures += "phase prefs clear did not commit"
        }.onFailure { failures += "phase prefs clear: $it" }

        runCatching {
            val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            if (keyStore.containsAlias(PHASE_ALIAS)) keyStore.deleteEntry(PHASE_ALIAS)
            if (keyStore.containsAlias(PHASE_ALIAS)) failures += "phase keystore alias still present"
        }.onFailure { failures += "keystore cleanup: $it" }

        runCatching {
            val file = context.getDatabasePath(dbName)
            if (file.exists()) {
                if (!context.deleteDatabase(dbName)) failures += "deleteDatabase returned false"
                if (file.exists()) failures += "database file still exists after delete"
            }
            // Owned ensureEncrypted conversion artifacts (custom names not
            // covered by deleteDatabase): only files derived from THIS dbName.
            val dir = file.parentFile
            listOf("$dbName.encrypted.tmp", "$dbName.plaintext.backup").forEach { sidecar ->
                val f = dir?.resolve(sidecar)
                if (f != null && f.exists() && !f.delete()) failures += "sidecar $sidecar delete returned false"
            }
        }.onFailure { failures += "db delete: $it" }

        runCatching {
            val marker = File(context.filesDir, PHASE_LOG)
            if (marker.exists() && !marker.delete()) failures += "phase marker delete returned false"
        }.onFailure { failures += "phase marker delete: $it" }

        assertTrue("phase cleanup failures: $failures", failures.isEmpty())
    }
}
