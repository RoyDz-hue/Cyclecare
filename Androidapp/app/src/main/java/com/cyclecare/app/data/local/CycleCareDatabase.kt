package com.cyclecare.app.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.cyclecare.app.security.AuthPreferences
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

@Database(
    entities = [CycleDayLog::class, CycleSettings::class, OnboardingProfile::class, HealthSyncRevision::class, HealthSyncOutboxEntry::class, HealthSyncCursor::class, HealthSyncConflictEntity::class],
    version = 9,
    exportSchema = false
)
abstract class CycleCareDatabase : RoomDatabase() {
    abstract fun cycleDao(): CycleDayLogDao
    abstract fun onboardingDao(): OnboardingProfileDao
    abstract fun healthSyncDao(): HealthSyncLocalDao

    companion object {
        private const val LEGACY_DATABASE_NAME = "cycle_care_database"

        @Volatile
        private var INSTANCE: CycleCareDatabase? = null
        private val OWNER_INSTANCES = ConcurrentHashMap<String, CycleCareDatabase>()

        /** Reserved prefix marking instrumentation-owned owner identities. */
        internal const val TEST_OWNER_IDENTITY_PREFIX = "instrumented-test-"

        /** Names of instances created through the instrumentation seam. */
        private val TEST_INSTANCE_NAMES = mutableSetOf<String>()

        fun createMigration4To5(context: Context, authPreferences: (Context) -> AuthPreferences = { AuthPreferences(it) }): Migration = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // 1. Legacy plaintext PIN handoff. Column existence is probed
                // structurally (PRAGMA), not inferred from a swallowed
                // exception: any failure while a handoff is required aborts
                // the migration and Room rolls the whole upgrade back, so the
                // plaintext copy is never destroyed unverified.
                var pinHandoffRequired = false
                var pinHandoffVerified = false
                val hasPinColumn = db.query("PRAGMA table_info(cycle_settings)").use { c ->
                    generateSequence { if (c.moveToNext()) c.getString(1) else null }.toList()
                }.contains("appLockPin")
                if (hasPinColumn) {
                    val rawPin = db.query("SELECT appLockPin FROM cycle_settings WHERE id = 1").use { c ->
                        if (c.moveToFirst()) c.getString(0) else null
                    }
                    if (!rawPin.isNullOrBlank()) {
                        pinHandoffRequired = true
                        // One durable path for every store state: empty,
                        // matching (enabled or not), memory-ahead-of-disk.
                        // Conflicting stores fail closed inside.
                        pinHandoffVerified = authPreferences(context).ensureMigratedPinDurable(rawPin)
                    }
                }
                check(!pinHandoffRequired || pinHandoffVerified) { "pin_handoff_unverified" }

                // 2. Safely recreate cycle_settings table without plaintext appLockPin
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `cycle_settings_new` (
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
                        `remindDailyLog` INTEGER NOT NULL
                    )
                    """.trimIndent()
                )

                db.execSQL(
                    """
                    INSERT INTO `cycle_settings_new` (
                        `id`, `isOnboardingCompleted`, `selectedGoalsCsv`, `lastPeriodStartDate`,
                        `averageCycleLength`, `periodDuration`, `lutealPhaseDays`, `isAppLockEnabled`,
                        `lockTimeoutMinutes`, `isDiscreetNotificationMode`, `isPregnancyMode`,
                        `pregnancyDueDate`, `remindPeriod`, `remindFertile`, `remindDailyLog`
                    )
                    SELECT 
                        `id`, `isOnboardingCompleted`, `selectedGoalsCsv`, `lastPeriodStartDate`,
                        `averageCycleLength`, `periodDuration`, `lutealPhaseDays`, `isAppLockEnabled`,
                        `lockTimeoutMinutes`, `isDiscreetNotificationMode`, `isPregnancyMode`,
                        `pregnancyDueDate`, `remindPeriod`, `remindFertile`, `remindDailyLog`
                    FROM `cycle_settings`
                    """.trimIndent()
                )

                db.execSQL("DROP TABLE `cycle_settings`")
                db.execSQL("ALTER TABLE `cycle_settings_new` RENAME TO `cycle_settings`")
            }
        }

        /** Backward-compatible entry point: resolves the current auth identity; null remains legacy guest. */
        fun getDatabase(context: Context): CycleCareDatabase =
            getDatabase(context, AuthPreferences(context.applicationContext).userId)

        /** Null means the original, deliberately unassigned guest database. */
        fun getDatabase(context: Context, ownerIdentity: String?): CycleCareDatabase {
            val name = ownerIdentity?.takeIf { it.isNotBlank() }?.let(::namespaceFor)
                ?: LEGACY_DATABASE_NAME
            if (name == LEGACY_DATABASE_NAME) {
                return INSTANCE ?: synchronized(this) {
                    INSTANCE ?: buildDatabase(context, name).also { INSTANCE = it }
                }
            }
            require(!ownerIdentity.startsWith(TEST_OWNER_IDENTITY_PREFIX)) {
                "Reserved instrumentation owner prefix reached the production path."
            }
            return OWNER_INSTANCES[name] ?: synchronized(this) {
                OWNER_INSTANCES[name] ?: buildDatabase(context, name).also { OWNER_INSTANCES[name] = it }
            }
        }

        fun getDatabase(context: Context, authPreferences: AuthPreferences): CycleCareDatabase =
            getDatabase(context, authPreferences.userId)

        /**
         * Instrumentation-only seam: test-owned owner namespace AND test-owned key
         * material (injected [keyManager]). Confinement is bidirectional: this seam
         * REQUIRES the reserved test prefix (arbitrary real owner identities are
         * refused) and the production path refuses the prefix, so neither path can
         * hand back the other's handle. A cached instance not created here (a
         * foreign/production handle) is never returned.
         */
        internal fun getDatabase(context: Context, ownerIdentity: String, keyManager: DatabaseKeyManager): CycleCareDatabase {
            require(ownerIdentity.startsWith(TEST_OWNER_IDENTITY_PREFIX)) {
                "Test injection requires the reserved test-owned owner prefix."
            }
            val name = namespaceFor(ownerIdentity)
            return synchronized(this) {
                val cached = OWNER_INSTANCES[name]
                if (cached != null) {
                    check(name in TEST_INSTANCE_NAMES) {
                        "Refusing cached instance not created by the test seam."
                    }
                    cached
                } else {
                    buildDatabase(context, name, keyManager).also {
                        OWNER_INSTANCES[name] = it
                        TEST_INSTANCE_NAMES += name
                    }
                }
            }
        }

        /** Instrumentation-only: drop a cached TEST instance so a close+reopen oracle is possible. */
        internal fun evictCachedInstanceForTest(databaseName: String) {
            synchronized(this) {
                check(OWNER_INSTANCES.remove(databaseName) != null || databaseName in TEST_INSTANCE_NAMES) {
                    "Only test-seam instances may be evicted."
                }
                TEST_INSTANCE_NAMES -= databaseName
            }
        }

        /** Test-only owned cleanup: close + evict a CACHED owner instance if one
         * exists; NEVER creates. Absent names are a no-op returning false, so
         * cleanup cannot mint a database or key material just to delete it. */
        internal fun closeAndEvictCachedOwnerInstance(ownerIdentity: String): Boolean {
            val name = namespaceFor(ownerIdentity)
            return synchronized(this) { OWNER_INSTANCES.remove(name)?.also { it.close() } != null }
        }

        /** Raw auth IDs never become filenames; this is stable across app restarts. */
        fun namespaceFor(ownerIdentity: String): String {
            val digest = MessageDigest.getInstance("SHA-256")
                .digest(ownerIdentity.toByteArray(Charsets.UTF_8))
            return "cycle_care_owner_" + digest.joinToString("") { "%02x".format(it) }
        }

        fun createMigration5To6(): Migration = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS `health_sync_revisions` (`ownerId` TEXT NOT NULL, `recordId` TEXT NOT NULL, `revision` INTEGER NOT NULL, `payloadHash` TEXT, `tombstone` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`ownerId`, `recordId`))")
                db.execSQL("CREATE TABLE IF NOT EXISTS `health_sync_outbox` (`ownerId` TEXT NOT NULL, `operationId` TEXT NOT NULL, `recordId` TEXT NOT NULL, `kind` TEXT NOT NULL, `baseRevision` INTEGER NOT NULL, `revision` INTEGER NOT NULL, `payloadJson` TEXT, `payloadHash` TEXT NOT NULL, `consentGeneration` INTEGER NOT NULL, `attempts` INTEGER NOT NULL, `nextAttemptAt` INTEGER NOT NULL, `lastError` TEXT, PRIMARY KEY(`ownerId`, `operationId`))")
                db.execSQL("CREATE TABLE IF NOT EXISTS `health_sync_cursors` (`ownerId` TEXT NOT NULL, `changeSeq` INTEGER NOT NULL, PRIMARY KEY(`ownerId`))")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_health_sync_outbox_ownerId_nextAttemptAt` ON `health_sync_outbox` (`ownerId`, `nextAttemptAt`)")
            }
        }

        fun createMigration6To7(): Migration = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `health_sync_outbox` ADD COLUMN `serverReceipt` TEXT")
                db.execSQL("ALTER TABLE `health_sync_outbox` ADD COLUMN `serverRevision` INTEGER")
                db.execSQL("CREATE TABLE IF NOT EXISTS `health_sync_conflicts` (`ownerId` TEXT NOT NULL, `recordId` TEXT NOT NULL, `operationId` TEXT NOT NULL, `localRevision` INTEGER NOT NULL, `localPayloadJson` TEXT, `localKind` TEXT NOT NULL, `remoteRevision` INTEGER NOT NULL, `remotePayloadJson` TEXT, `remoteTombstone` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL, `resolvedAt` INTEGER, `resolution` TEXT, PRIMARY KEY(`ownerId`, `recordId`))")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_health_sync_conflicts_ownerId_resolvedAt` ON `health_sync_conflicts` (`ownerId`, `resolvedAt`)")
            }
        }

        fun createMigration7To8(): Migration = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `health_sync_revisions` ADD COLUMN `serverRevision` INTEGER NOT NULL DEFAULT 0")
            }
        }

        fun createMigration8To9(): Migration = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Raw period end date, preserved exactly as the user entered
                // it; separate from the typical-duration estimate.
                db.execSQL("ALTER TABLE `onboarding_profiles` ADD COLUMN `lastPeriodEndDateString` TEXT NOT NULL DEFAULT ''")
            }
        }

        private fun buildDatabase(context: Context, databaseName: String, keyManager: DatabaseKeyManager? = null): CycleCareDatabase {
                val appContext = context.applicationContext
                System.loadLibrary("sqlcipher")
                val passphrase = (keyManager ?: DatabaseKeyManager(appContext)).getOrCreatePassphrase()
                DatabaseEncryption.ensureEncrypted(appContext, databaseName, passphrase)
                val encryptedFactory = SupportOpenHelperFactory(passphrase.toByteArray(Charsets.UTF_8))
                val instance = Room.databaseBuilder(
                    appContext,
                    CycleCareDatabase::class.java,
                    databaseName
                )
                    .openHelperFactory(encryptedFactory)
                    .addMigrations(createMigration4To5(appContext), createMigration5To6(), createMigration6To7(), createMigration7To8(), createMigration8To9())
                    .build()
                return instance
        }
    }
}
