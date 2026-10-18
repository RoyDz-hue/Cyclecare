package com.example.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.example.security.AuthPreferences
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

@Database(
    entities = [CycleDayLog::class, CycleSettings::class, OnboardingProfile::class],
    version = 5,
    exportSchema = false
)
abstract class CycleCareDatabase : RoomDatabase() {
    abstract fun cycleDao(): CycleDayLogDao
    abstract fun onboardingDao(): OnboardingProfileDao

    companion object {
        private const val LEGACY_DATABASE_NAME = "cycle_care_database"

        @Volatile
        private var INSTANCE: CycleCareDatabase? = null
        private val OWNER_INSTANCES = ConcurrentHashMap<String, CycleCareDatabase>()

        fun createMigration4To5(context: Context): Migration = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // 1. Check for any legacy plaintext PIN in cycle_settings and migrate to AuthPreferences
                try {
                    val cursor = db.query("SELECT appLockPin FROM cycle_settings WHERE id = 1")
                    if (cursor.moveToFirst()) {
                        val pinIdx = cursor.getColumnIndex("appLockPin")
                        if (pinIdx != -1) {
                            val rawPin = cursor.getString(pinIdx)
                            if (!rawPin.isNullOrBlank()) {
                                val authPrefs = AuthPreferences(context)
                                if (authPrefs.pin.isNullOrBlank()) {
                                    authPrefs.pin = rawPin
                                    authPrefs.isPinEnabled = true
                                }
                            }
                        }
                    }
                    cursor.close()
                } catch (_: Throwable) {
                    // Column might not exist or table is empty
                }

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
            return OWNER_INSTANCES[name] ?: synchronized(this) {
                OWNER_INSTANCES[name] ?: buildDatabase(context, name).also { OWNER_INSTANCES[name] = it }
            }
        }

        fun getDatabase(context: Context, authPreferences: AuthPreferences): CycleCareDatabase =
            getDatabase(context, authPreferences.userId)

        /** Raw auth IDs never become filenames; this is stable across app restarts. */
        fun namespaceFor(ownerIdentity: String): String {
            val digest = MessageDigest.getInstance("SHA-256")
                .digest(ownerIdentity.toByteArray(Charsets.UTF_8))
            return "cycle_care_owner_" + digest.joinToString("") { "%02x".format(it) }
        }

        private fun buildDatabase(context: Context, databaseName: String): CycleCareDatabase {
                val appContext = context.applicationContext
                System.loadLibrary("sqlcipher")
                val passphrase = DatabaseKeyManager(appContext).getOrCreatePassphrase()
                DatabaseEncryption.ensureEncrypted(appContext, databaseName, passphrase)
                val encryptedFactory = SupportOpenHelperFactory(passphrase.toByteArray(Charsets.UTF_8))
                val instance = Room.databaseBuilder(
                    appContext,
                    CycleCareDatabase::class.java,
                    databaseName
                )
                    .openHelperFactory(encryptedFactory)
                    .addMigrations(createMigration4To5(appContext))
                    .build()
                return instance
        }
    }
}
