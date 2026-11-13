package com.cyclecare.app.data.local

import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

/**
 * Room boundary evidence, REPEATABLE in-repo form: the project's own Gradle KSP
 * generates the probe impl from production entities/DAOs AS-IS; tests run the
 * real generated DAOs + real production Migration objects against framework
 * SQLite under Robolectric. Scratch/disposable only: no production files, no
 * Keystore, no passphrase. The v4 legacy shape below is SYNTHETIC inference
 * (v9 DDL minus the effects of migrations 5-9, plus the legacy appLockPin
 * column) - not preserved user data. The PIN handoff leg is device-dependent:
 * on host, AuthPreferences falls back to insecure storage and its pin setter
 * refuses the write (deliberate fail-safe), so no PIN-migration success is
 * claimed here.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RoomBoundaryHostTest {

    private val fixtureColumns: Map<String, List<String>> by lazy { parseFixture() }

    private fun parseFixture(): Map<String, List<String>> {
        // repo-relative; unit-test working dir is the app module
        val sql = File("../rust/core/fixtures/schema/room-ddl-18dd603.sql").readText()
        val out = LinkedHashMap<String, List<String>>()
        Regex("CREATE TABLE IF NOT EXISTS `([^`]+)` \\((.*?)PRIMARY KEY\\(", RegexOption.DOT_MATCHES_ALL).findAll(sql).forEach { m ->
            val cols = m.groupValues[2].split(",").map { it.trim() }.filter { it.isNotEmpty() }
                .map { col -> col.replace(Regex(" DEFAULT .*$"), "").replace(Regex("\\s+"), " ") }
            out[m.groupValues[1]] = cols
        }
        return out
    }

    private fun liveColumns(db: SupportSQLiteDatabase, table: String): List<String> {
        val c = db.query("PRAGMA table_info(`$table`)")
        val cols = mutableListOf<String>()
        while (c.moveToNext()) {
            val name = c.getString(1); val type = c.getString(2); val nn = c.getInt(3)
            cols.add("`$name` $type${if (nn == 1) " NOT NULL" else ""}")
        }
        c.close(); return cols
    }

    private fun dump(db: SupportSQLiteDatabase, table: String, orderBy: String): List<String> {
        val c = db.query("SELECT * FROM `$table` ORDER BY $orderBy")
        val names = c.columnNames
        val selectExpr = names.joinToString(", ") { "typeof(`$it`), `$it`" }
        c.close()
        val tc = db.query("SELECT $selectExpr FROM `$table` ORDER BY $orderBy")
        val rows = mutableListOf<String>()
        while (tc.moveToNext()) {
            val parts = names.mapIndexed { i, n ->
                val t = tc.getString(i * 2)
                val v = if (tc.isNull(i * 2 + 1)) "<NULL>" else tc.getString(i * 2 + 1)
                "$n:$t=$v"
            }
            rows.add(parts.joinToString("|"))
        }
        tc.close(); return rows
    }

    // Column order is compared as a SET: ALTER TABLE ADD COLUMN appends, so a
    // migrated DB legitimately differs in position from a fresh CREATE TABLE.
    private fun checkSchema(w: SupportSQLiteDatabase, label: String) {
        for ((table, expected) in fixtureColumns) {
            assertEquals("$label: table $table column set", expected.toSet(), liveColumns(w, table).toSet())
        }
    }

    @Test
    fun actualRoomDatabaseSchemaMatchesFixtureAndDaoCrudWorks() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val db = Room.inMemoryDatabaseBuilder(context, RoomHostProbeDatabase::class.java)
            .allowMainThreadQueries().build()
        val dumpOut = StringBuilder()
        checkSchema(db.openHelper.writableDatabase, "fresh v9")
        val idxc = db.openHelper.writableDatabase.query("SELECT name FROM sqlite_master WHERE type = 'index' AND name LIKE 'index_health_sync%'")
        val idxNames = mutableListOf<String>(); while (idxc.moveToNext()) idxNames.add(idxc.getString(0)); idxc.close()
        assertEquals(setOf("index_health_sync_outbox_ownerId_nextAttemptAt", "index_health_sync_conflicts_ownerId_resolvedAt"), idxNames.toSet())

        val dao = db.cycleDao()
        // three logs inserted OUT OF ORDER so DESC ordering is genuinely comparative
        dao.insertOrUpdateLog(CycleDayLog(dateString = "2026-10-02", flowLevel = "LIGHT"))
        dao.insertOrUpdateLog(CycleDayLog(dateString = "2026-09-28", flowLevel = "SPOTTING", painIntensity = 2, notes = "early"))
        dao.insertOrUpdateLog(CycleDayLog(dateString = "2026-10-01", flowLevel = "HEAVY", painIntensity = 8, notes = "n1"))
        dao.insertOrUpdateLog(CycleDayLog(dateString = "2026-10-01", flowLevel = "MEDIUM", painIntensity = 4, notes = "replaced"))
        assertEquals("MEDIUM", dao.getLogByDate("2026-10-01")!!.flowLevel)
        assertEquals(listOf("2026-10-02", "2026-10-01", "2026-09-28"), dao.getAllLogsList().map { it.dateString })
        assertEquals(3, dao.getAllLogs().first().size)
        // comparative DESC dump BEFORE any delete (3 distinct out-of-order rows)
        dumpOut.appendLine("== cycle_day_logs:DESC")
        dump(db.openHelper.writableDatabase, "cycle_day_logs", "dateString DESC").forEach { dumpOut.appendLine(it) }
        dao.deleteLog("2026-10-02")
        assertNull(dao.getLogByDate("2026-10-02"))

        dao.saveSettings(CycleSettings(isOnboardingCompleted = true, lastPeriodStartDate = "2026-09-20", averageCycleLength = 31, periodDuration = 6, lutealPhaseDays = 12))
        assertEquals(31, dao.getSettings()!!.averageCycleLength)
        assertEquals(1, dao.getSettings()!!.id)

        val odao = db.onboardingDao()
        odao.insertOrUpdateProfile(OnboardingProfile(id = 1, completedAtTimestamp = 1700000000123, lastPeriodDateString = "2026-09-20", cycleLengthDays = 31, periodDurationDays = 6))
        assertEquals("2026-09-20", odao.getProfile()!!.lastPeriodDateString)

        val h = db.healthSyncDao()
        h.putRevision(HealthSyncRevision(ownerId = "ownerA", recordId = "r1", revision = 3L, payloadHash = "h", tombstone = false, updatedAt = 111L))
        assertEquals(3L, h.revision("ownerA", "r1")!!.revision)
        h.enqueue(HealthSyncOutboxEntry(ownerId = "ownerA", operationId = "op1", recordId = "r1", kind = "upsert", baseRevision = 0L, revision = 3L, payloadJson = "{}", payloadHash = "h", consentGeneration = 1L, nextAttemptAt = 50L))
        assertEquals(1, h.pending("ownerA").size)
        assertEquals(0, h.pending("ownerB").size)
        h.putCursor(HealthSyncCursor(ownerId = "ownerA", changeSeq = 42L))
        assertEquals(42L, h.cursor("ownerA")!!.changeSeq)
        h.putConflict(HealthSyncConflictEntity(ownerId = "ownerA", recordId = "r1", operationId = "op1", localRevision = 3L, localPayloadJson = "{}", localKind = "upsert", remoteRevision = 4L, remotePayloadJson = "{}", remoteTombstone = false, createdAt = 222L))
        assertEquals(1, h.conflicts("ownerA").size)
        assertEquals(0, h.conflicts("ownerB").size)

        val w = db.openHelper.writableDatabase
        // identity hash written by the REAL generated ProbeDatabase_Impl
        val ihc = w.query("SELECT identity_hash FROM room_master_table")
        ihc.moveToFirst(); val identityHash = ihc.getString(0); ihc.close()
        // Pins the probe's generated schema identity to the production APK's
        // CycleCareDatabase_Impl v9 identity hash (extract a45c7f95...386b /
        // f5d4d04 classes.dex, independently relayed by reviewer 2026-11-03).
        assertEquals("b05de874b4e7059205fe6d45fe1c4044", identityHash)
        File("build/room-boundary/probe-identity-hash.txt").also { it.parentFile.mkdirs() }.writeText(identityHash + "\n")
        listOf("cycle_day_logs" to "dateString", "cycle_settings" to "id", "onboarding_profiles" to "id",
            "health_sync_revisions" to "ownerId,recordId", "health_sync_outbox" to "ownerId,operationId",
            "health_sync_cursors" to "ownerId", "health_sync_conflicts" to "ownerId,recordId").forEach { (t, o) ->
            dumpOut.appendLine("== $t"); dump(w, t, o).forEach { dumpOut.appendLine(it) }
        }
        File("build/room-boundary/kotlin-dump.txt").also { it.parentFile.mkdirs() }.writeText(dumpOut.toString())
        db.close()
    }

    @Test
    fun realMigrationObjects4to9PreserveRows() {
        val context = RuntimeEnvironment.getApplication()
        val path = File("build/room-boundary/scratch-mig.db").also { it.parentFile.mkdirs() }; path.delete()
        // SYNTHETIC v4 legacy shape (inference: v9 DDL minus migration 5-9 effects, plus legacy appLockPin)
        val raw = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context).name(path.absolutePath)
                .callback(object : SupportSQLiteOpenHelper.Callback(4) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        db.execSQL("CREATE TABLE IF NOT EXISTS `cycle_day_logs` (`dateString` TEXT NOT NULL, `flowLevel` TEXT NOT NULL, `energyLevel` TEXT NOT NULL, `moodLevel` TEXT NOT NULL, `symptomsCsv` TEXT NOT NULL, `vaginalHealthCsv` TEXT NOT NULL, `urinaryHealthCsv` TEXT NOT NULL, `cervicalMucus` TEXT NOT NULL, `ovulationTest` TEXT NOT NULL, `bbtCelsius` REAL NOT NULL, `sleepHours` REAL NOT NULL, `sleepQuality` TEXT NOT NULL, `waterGlasses` INTEGER NOT NULL, `notes` TEXT NOT NULL, `painIntensity` INTEGER NOT NULL, PRIMARY KEY(`dateString`))")
                        db.execSQL("CREATE TABLE IF NOT EXISTS `cycle_settings` (`id` INTEGER NOT NULL, `isOnboardingCompleted` INTEGER NOT NULL, `selectedGoalsCsv` TEXT NOT NULL, `lastPeriodStartDate` TEXT NOT NULL, `averageCycleLength` INTEGER NOT NULL, `periodDuration` INTEGER NOT NULL, `lutealPhaseDays` INTEGER NOT NULL, `isAppLockEnabled` INTEGER NOT NULL, `lockTimeoutMinutes` INTEGER NOT NULL, `isDiscreetNotificationMode` INTEGER NOT NULL, `isPregnancyMode` INTEGER NOT NULL, `pregnancyDueDate` TEXT NOT NULL, `remindPeriod` INTEGER NOT NULL, `remindFertile` INTEGER NOT NULL, `remindDailyLog` INTEGER NOT NULL, `appLockPin` TEXT, PRIMARY KEY(`id`))")
                        db.execSQL("CREATE TABLE IF NOT EXISTS `onboarding_profiles` (`id` INTEGER NOT NULL, `isCompleted` INTEGER NOT NULL, `completedAtTimestamp` INTEGER NOT NULL, `isAgeVerified` INTEGER NOT NULL, `isTermsAgreed` INTEGER NOT NULL, `accountMode` TEXT NOT NULL, `selectedGoalsCsv` TEXT NOT NULL, `lastPeriodDateString` TEXT NOT NULL, `approximatePeriodChoice` TEXT NOT NULL, `cycleLengthOption` TEXT NOT NULL, `cycleLengthDays` INTEGER NOT NULL, `periodDurationDays` INTEGER NOT NULL, `remindFertileWindow` INTEGER NOT NULL, `typicalSymptomsCsv` TEXT NOT NULL, `typicalEnergy` TEXT NOT NULL, `typicalSleepQuality` TEXT NOT NULL, `hasCompletedDeepBaseline` INTEGER NOT NULL, PRIMARY KEY(`id`))")
                    }
                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {}
                }).build())
        val legacy = raw.writableDatabase
        legacy.execSQL("INSERT INTO cycle_day_logs (dateString, flowLevel, energyLevel, moodLevel, symptomsCsv, vaginalHealthCsv, urinaryHealthCsv, cervicalMucus, ovulationTest, bbtCelsius, sleepHours, sleepQuality, waterGlasses, notes, painIntensity) VALUES ('2026-08-15','HEAVY','','','','','','','NONE',0.0,0.0,'',0,'legacy note',7)")
        legacy.execSQL("INSERT INTO cycle_settings (id, isOnboardingCompleted, selectedGoalsCsv, lastPeriodStartDate, averageCycleLength, periodDuration, lutealPhaseDays, isAppLockEnabled, lockTimeoutMinutes, isDiscreetNotificationMode, isPregnancyMode, pregnancyDueDate, remindPeriod, remindFertile, remindDailyLog, appLockPin) VALUES (1,1,'g','2026-08-01',29,5,13,1,5,1,0,'',1,1,1,'1234')")
        legacy.execSQL("INSERT INTO onboarding_profiles (id, isCompleted, completedAtTimestamp, isAgeVerified, isTermsAgreed, accountMode, selectedGoalsCsv, lastPeriodDateString, approximatePeriodChoice, cycleLengthOption, cycleLengthDays, periodDurationDays, remindFertileWindow, typicalSymptomsCsv, typicalEnergy, typicalSleepQuality, hasCompletedDeepBaseline) VALUES (1,1,1700000000000,1,1,'PRIVATE','g','2026-08-01','EXACT','',29,5,0,'','','',0)")
        raw.close()

        // REAL Migration objects from production CycleCareDatabase companion bytecode
        val db = Room.databaseBuilder(context, RoomHostProbeDatabase::class.java, path.absolutePath)
            .addMigrations(
                CycleCareDatabase.createMigration4To5(context),
                CycleCareDatabase.createMigration5To6(),
                CycleCareDatabase.createMigration6To7(),
                CycleCareDatabase.createMigration7To8(),
                CycleCareDatabase.createMigration8To9())
            .allowMainThreadQueries().build()
        val w = db.openHelper.writableDatabase

        checkSchema(w, "post-migration v9")
        assertFalse(liveColumns(w, "cycle_settings").joinToString().contains("appLockPin"))
        val logs = dump(w, "cycle_day_logs", "dateString")
        assertEquals(1, logs.size); assertTrue(logs[0].contains("legacy note")); assertTrue(logs[0].contains("painIntensity:integer=7"))
        val settings = dump(w, "cycle_settings", "id")
        assertEquals(1, settings.size); assertTrue(settings[0].contains("averageCycleLength:integer=29"))
        val profiles = dump(w, "onboarding_profiles", "id")
        assertTrue(profiles[0].contains("lastPeriodDateString:text=2026-08-01"))
        assertTrue(profiles[0].contains("lastPeriodEndDateString:text=")) // added by 8->9 with default ''
        db.close()
        path.delete()
    }
}
