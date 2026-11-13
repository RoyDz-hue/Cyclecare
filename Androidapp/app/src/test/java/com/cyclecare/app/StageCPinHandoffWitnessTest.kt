package com.cyclecare.app

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import com.cyclecare.app.data.local.CycleCareDatabase
import com.cyclecare.app.security.AuthPreferences
import java.util.UUID
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Stage-C PIN prefs+SQL boundary WRITTEN witnesses for the hardened
 * createMigration4To5 handoff (plaintext cycle_settings.appLockPin ->
 * AuthPreferences secure PIN). UNEXECUTED until an authorized run produces
 * XML. Tier: tier-agnostic host evidence (Robolectric, real on-disk SQLite
 * files with actual close/reopen, SharedPreferences test seam; no Keystore,
 * no SQLCipher, no native claims). Scope: failure/reopen truth at each
 * prefs/SQL boundary of THIS migration. The OS-level window between a
 * commit()-acknowledged prefs write and the SQL drop cannot be exercised
 * host-side and is a stated residual, not a covered case. No real key,
 * account or wipe operation used.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class StageCPinHandoffWitnessTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val dbNames = mutableListOf<String>()
    private val prefsNames = mutableListOf<String>()
    private val helpers = mutableListOf<SupportSQLiteOpenHelper>()

    @After fun cleanup() {
        helpers.forEach { it.close() }
        dbNames.forEach { context.deleteDatabase(it) }
        prefsNames.forEach { context.deleteSharedPreferences(it) }
    }

    /** A REAL file-backed SQLite database; [reopen] closes and reopens it. */
    private class ScratchDb(val helper: SupportSQLiteOpenHelper, val name: String, val context: Context) {
        var db: SupportSQLiteDatabase = helper.writableDatabase
        fun reopen() {
            db.close(); helper.close()
            db = newHelper(context, name).writableDatabase
        }
    }

    private fun newHelper(context: Context, name: String): SupportSQLiteOpenHelper =
        FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context).name(name)
                .callback(object : SupportSQLiteOpenHelper.Callback(1) {
                    override fun onConfigure(db: SupportSQLiteDatabase) {}
                    override fun onCreate(db: SupportSQLiteDatabase) {}
                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {}
                }).build()
        )

    private fun scratchDb(): ScratchDb {
        val name = "stage-c-pin-${UUID.randomUUID()}"; dbNames += name
        val helper = newHelper(context, name); helpers += helper
        return ScratchDb(helper, name, context)
    }

    private fun testPrefs(secure: Boolean = true): Pair<String, AuthPreferences> {
        val name = "stage-c-pin-${UUID.randomUUID()}".also { prefsNames += it }
        return name to AuthPreferences(context, context.getSharedPreferences(name, Context.MODE_PRIVATE), secure)
    }

    private fun freshPrefsOn(name: String) =
        AuthPreferences(context, context.getSharedPreferences(name, Context.MODE_PRIVATE), true)

    /** The v4 cycle_settings shape: the 15 carried columns + appLockPin. */
    private fun createV4Settings(db: SupportSQLiteDatabase, plaintextPin: String?) {
        db.execSQL("""CREATE TABLE `cycle_settings` (
            `id` INTEGER NOT NULL PRIMARY KEY,
            `isOnboardingCompleted` INTEGER NOT NULL,
            `selectedGoalsCsv` TEXT NOT NULL,
            `lastPeriodStartDate` TEXT NOT NULL,
            `averageCycleLength` INTEGER NOT NULL,
            `periodDuration` INTEGER NOT NULL,
            `lutealPhaseDays` INTEGER NOT NULL,
            `isAppLockEnabled` INTEGER NOT NULL,
            `lockTimeoutMinutes` INTEGER NOT NULL,
            `isDiscreetNotificationMode` INTEGER NOT NULL,
            `isPregnancyMode` INTEGER NOT NULL,
            `pregnancyDueDate` TEXT NOT NULL,
            `remindPeriod` INTEGER NOT NULL,
            `remindFertile` INTEGER NOT NULL,
            `remindDailyLog` INTEGER NOT NULL,
            `appLockPin` TEXT
        )""".trimIndent())
        db.execSQL(
            "INSERT INTO cycle_settings VALUES(1,1,'goal-x','2026-09-28',31,6,14,1,5,1,0,'',1,1,0,?)",
            arrayOf(plaintextPin)
        )
    }

    private fun migrateInTransaction(db: SupportSQLiteDatabase, prefs: AuthPreferences) {
        db.beginTransaction()
        try {
            CycleCareDatabase.createMigration4To5(context) { prefs }.migrate(db)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    private fun settingColumns(db: SupportSQLiteDatabase): List<String> =
        db.query("PRAGMA table_info(cycle_settings)").use { c -> generateSequence { if (c.moveToNext()) c.getString(1) else null }.toList() }

    private fun settingsRow(db: SupportSQLiteDatabase): String =
        db.query("SELECT * FROM cycle_settings").use { c ->
            c.moveToFirst(); (0 until c.columnCount).joinToString("|") { i -> if (c.isNull(i)) "<null>" else c.getString(i) }
        }
    private val expectedRow = "1|1|goal-x|2026-09-28|31|6|14|1|5|1|0||1|1|0"

    @Test fun handoffMigratesPinVerifiedAndDropsPlaintextAcrossRealReopen() {
        val scratch = scratchDb()
        createV4Settings(scratch.db, "4471")
        val (prefsName, prefs) = testPrefs()
        migrateInTransaction(scratch.db, prefs)
        // Real close + reopen of the SQLite FILE, not a second wrapper.
        scratch.reopen()
        assertFalse("plaintext column dropped", settingColumns(scratch.db).contains("appLockPin"))
        assertEquals("settings row carried", expectedRow, settingsRow(scratch.db))
        val stored = prefs.pin
        assertNotNull(stored)
        assertTrue("stored as PBKDF2, never plaintext", stored!!.startsWith("pbkdf2:"))
        assertTrue(prefs.isPinEnabled)
        assertTrue(prefs.verifyPin("4471"))
        // A fresh AuthPreferences over the same backing store sees the
        // committed truth, not migration-transient state.
        val reopened = freshPrefsOn(prefsName)
        assertTrue(reopened.verifyPin("4471"))
        assertTrue(reopened.isPinEnabled)
        assertFalse(reopened.verifyPin("0000"))
    }

    @Test fun rerunAfterCrashCommittedPrefsConvergesAndSecondMigrateIsStable() {
        val scratch = scratchDb()
        createV4Settings(scratch.db, "4471")
        val (_, prefs) = testPrefs()
        // Simulate a prior attempt whose durable prefs commit landed before
        // the SQL rolled back: the store already holds the migrated PIN,
        // enabled. The handoff must skip the copy, verify, and proceed.
        assertTrue(prefs.ensureMigratedPinDurable("4471"))
        migrateInTransaction(scratch.db, prefs)
        assertFalse(settingColumns(scratch.db).contains("appLockPin"))
        assertTrue(prefs.verifyPin("4471"))
        // Full rerun on the migrated shape: no plaintext column to probe, the
        // table rebuild repeats, nothing is disturbed.
        migrateInTransaction(scratch.db, prefs)
        scratch.reopen()
        assertEquals(expectedRow, settingsRow(scratch.db))
        assertTrue(prefs.verifyPin("4471"))
    }

    @Test fun conflictingSecureStoreFailsClosedAndPlaintextSurvivesForRetry() {
        val scratch = scratchDb()
        createV4Settings(scratch.db, "4471")
        val (_, prefs) = testPrefs()
        // The secure store already holds a DIFFERENT enabled PIN: the handoff
        // cannot verify, so the migration must refuse rather than destroy the
        // v4 copy.
        assertTrue(prefs.ensureMigratedPinDurable("9999"))
        val thrown = try { migrateInTransaction(scratch.db, prefs); null } catch (t: Throwable) { t }
        assertTrue("expected pin_handoff_unverified, got $thrown", thrown is IllegalStateException && (thrown.message ?: "").contains("pin_handoff_unverified"))
        scratch.reopen()
        assertTrue("plaintext PIN survives the refused upgrade", settingColumns(scratch.db).contains("appLockPin"))
        assertEquals("1|1|goal-x|2026-09-28|31|6|14|1|5|1|0||1|1|0|4471", settingsRow(scratch.db))
        assertTrue("the conflicting PIN was never overwritten", prefs.verifyPin("9999"))
        assertFalse(prefs.verifyPin("4471"))
        // Recovery: once the conflict is resolved (store cleared), the retry
        // converges and hands off the original PIN.
        prefs.pin = null
        migrateInTransaction(scratch.db, prefs)
        scratch.reopen()
        assertFalse(settingColumns(scratch.db).contains("appLockPin"))
        assertTrue(prefs.verifyPin("4471"))
        assertTrue(prefs.isPinEnabled)
    }

    @Test fun matchingPinButDisabledStoreKeepsLockOffAndIsDurablyReEncoded() {
        val scratch = scratchDb()
        createV4Settings(scratch.db, "4471")
        val (prefsName, prefs) = testPrefs()
        // PIN and enablement are SEPARATE policy state in this app
        // (setPinProtectionEnabled flips isPinEnabled without touching the
        // stored PIN; cycle_settings carries its own isAppLockEnabled), and
        // the original migration never touched a non-empty store. So a
        // matching-but-disabled store must be durably re-encoded WITHOUT
        // flipping enablement: the handoff repairs persistence, not policy.
        prefs.pin = "4471"
        assertFalse(prefs.isPinEnabled)
        migrateInTransaction(scratch.db, prefs)
        scratch.reopen()
        assertFalse(settingColumns(scratch.db).contains("appLockPin"))
        assertTrue(prefs.verifyPin("4471"))
        assertFalse("enablement preserved, never inferred from PIN presence", prefs.isPinEnabled)
        // Durable across a fresh wrapper: same encoding verification, same
        // preserved disabled flag.
        val reopened = freshPrefsOn(prefsName)
        assertTrue(reopened.verifyPin("4471"))
        assertFalse(reopened.isPinEnabled)
    }

    /** commit() reports false while memory updates: the failure signal the
     * durable handoff must honor. Models the contract, not true disk loss.
     * Every fluent mutator is overridden to return THIS wrapper: the Editor
     * contract returns the Editor from each call, so delegating them would
     * hand back the inner Editor and let a production chain like
     * edit().putString(..).putBoolean(..).commit() reach the inner commit()
     * past the fault. Counters prove the fault actually intercepted. */
    private class FaultPreferences(private val delegate: android.content.SharedPreferences, var commitSucceeds: Boolean) :
        android.content.SharedPreferences by delegate {
        var commitCalls = 0
        var refusedCommits = 0
        override fun edit(): android.content.SharedPreferences.Editor {
            val inner = delegate.edit()
            val outer = this
            return object : android.content.SharedPreferences.Editor by inner {
                override fun putString(key: String?, value: String?): android.content.SharedPreferences.Editor { inner.putString(key, value); return this }
                override fun putStringSet(key: String?, values: MutableSet<String>?): android.content.SharedPreferences.Editor { inner.putStringSet(key, values); return this }
                override fun putInt(key: String?, value: Int): android.content.SharedPreferences.Editor { inner.putInt(key, value); return this }
                override fun putLong(key: String?, value: Long): android.content.SharedPreferences.Editor { inner.putLong(key, value); return this }
                override fun putFloat(key: String?, value: Float): android.content.SharedPreferences.Editor { inner.putFloat(key, value); return this }
                override fun putBoolean(key: String?, value: Boolean): android.content.SharedPreferences.Editor { inner.putBoolean(key, value); return this }
                override fun remove(key: String?): android.content.SharedPreferences.Editor { inner.remove(key); return this }
                override fun clear(): android.content.SharedPreferences.Editor { inner.clear(); return this }
                override fun commit(): Boolean {
                    outer.commitCalls++
                    return if (outer.commitSucceeds) inner.commit() else { outer.refusedCommits++; inner.apply(); false }
                }
            }
        }
    }

    @Test fun commitFaultAfterMemoryUpdateFailsClosedOnEveryCallUntilDurable() {
        val scratch = scratchDb()
        createV4Settings(scratch.db, "4471")
        val fault = FaultPreferences(context.getSharedPreferences("stage-c-pin-${UUID.randomUUID()}".also { prefsNames += it }, Context.MODE_PRIVATE), false)
        val prefs = AuthPreferences(context, fault, true)
        // First call: empty store, encode, commit reports false -> closed.
        val thrown1 = try { migrateInTransaction(scratch.db, prefs); null } catch (t: Throwable) { t }
        assertTrue("expected pin_handoff_unverified, got $thrown1", thrown1 is IllegalStateException && (thrown1.message ?: "").contains("pin_handoff_unverified"))
        assertTrue("fault commit intercepted at least once", fault.refusedCommits >= 1)
        assertNotNull("fault left memory ahead of disk", prefs.pin)
        // Second call: store now APPEARS populated from memory; the handoff
        // must re-commit durably (still false) instead of trusting the
        // in-memory state and dropping the plaintext.
        val thrown2 = try { migrateInTransaction(scratch.db, prefs); null } catch (t: Throwable) { t }
        assertTrue("expected pin_handoff_unverified on retry, got $thrown2", thrown2 is IllegalStateException && (thrown2.message ?: "").contains("pin_handoff_unverified"))
        assertTrue("fault commit intercepted on every refused call", fault.refusedCommits >= 2 && fault.refusedCommits == fault.commitCalls)
        scratch.reopen()
        assertTrue("plaintext survives both refused attempts", settingColumns(scratch.db).contains("appLockPin"))
        // Persistence restored: the retry commits durably and converges.
        fault.commitSucceeds = true
        migrateInTransaction(scratch.db, prefs)
        assertTrue("recovery commit observed after refusals", fault.commitCalls > fault.refusedCommits)
        scratch.reopen()
        assertFalse(settingColumns(scratch.db).contains("appLockPin"))
        assertTrue(prefs.verifyPin("4471"))
        assertTrue(prefs.isPinEnabled)
    }

    @Test fun unavailableSecureStoreFailsClosedAndNeverDropsPlaintext() {
        val scratch = scratchDb()
        createV4Settings(scratch.db, "4471")
        // No-op write injection: secure=false makes every PIN write a silent
        // no-op, exactly the Keystore-unavailable production failure mode.
        val (_, insecurePrefs) = testPrefs(secure = false)
        val thrown = try { migrateInTransaction(scratch.db, insecurePrefs); null } catch (t: Throwable) { t }
        assertTrue("expected pin_handoff_unverified, got $thrown", thrown is IllegalStateException && (thrown.message ?: "").contains("pin_handoff_unverified"))
        scratch.reopen()
        assertTrue("plaintext survives", settingColumns(scratch.db).contains("appLockPin"))
        assertEquals("1|1|goal-x|2026-09-28|31|6|14|1|5|1|0||1|1|0|4471", settingsRow(scratch.db))
    }

    @Test fun noPinV4FileMigratesCleanlyAndLeavesStoreEmpty() {
        val scratch = scratchDb()
        createV4Settings(scratch.db, null)
        val (_, prefs) = testPrefs()
        migrateInTransaction(scratch.db, prefs)
        scratch.reopen()
        assertFalse(settingColumns(scratch.db).contains("appLockPin"))
        assertEquals(expectedRow, settingsRow(scratch.db))
        assertNull(prefs.pin)
        assertFalse(prefs.isPinEnabled)
    }

    @Test fun failedVerifyNeverReencodesAndSuccessfulLegacyVerifyMigratesOnce() {
        val (name, prefs) = testPrefs()
        prefs.pin = "4471"
        val encoded = prefs.pin!!
        assertFalse(prefs.verifyPin("0000"))
        assertEquals("failed verify leaves the stored encoding untouched", encoded, prefs.pin)
        assertTrue(prefs.verifyPin("4471"))
        assertEquals(encoded, prefs.pin)
        // Legacy plaintext stored directly (pre-hardening install shape):
        // successful verify re-encodes once; the legacy value is gone when a
        // fresh wrapper reads the committed store.
        val backing = context.getSharedPreferences(name, Context.MODE_PRIVATE)
        backing.edit().putString("quick_unlock_pin", "4471").commit()
        assertTrue(prefs.verifyPin("4471"))
        assertTrue("legacy value re-encoded", prefs.pin!!.startsWith("pbkdf2:"))
        val reopened = AuthPreferences(context, backing, true)
        assertTrue(reopened.verifyPin("4471"))
        assertFalse("plaintext no longer the stored value", reopened.pin == "4471")
    }
}
