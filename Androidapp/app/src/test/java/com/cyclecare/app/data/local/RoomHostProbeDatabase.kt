package com.cyclecare.app.data.local

import androidx.room.Database
import androidx.room.RoomDatabase

/**
 * TEST-ONLY probe database for host Room boundary evidence.
 *
 * Uses the production @Database arguments verbatim (same 7 entities, version 9,
 * exportSchema=false) but lives under src/test so the project's own Gradle KSP
 * generates its impl - no production source is modified. The production
 * CycleCareDatabase class itself cannot be processed on a host because its
 * companion pulls SQLCipher/Keystore/AuthPreferences.
 *
 * Under Gradle KSP (unlike the KSP2 AA CLI harness) the bare @Insert
 * annotation defaults resolve normally, so production DAOs are used AS-IS.
 */
@Database(
    entities = [
        CycleDayLog::class,
        CycleSettings::class,
        OnboardingProfile::class,
        HealthSyncRevision::class,
        HealthSyncOutboxEntry::class,
        HealthSyncCursor::class,
        HealthSyncConflictEntity::class,
    ],
    version = 9,
    exportSchema = false,
)
abstract class RoomHostProbeDatabase : RoomDatabase() {
    abstract fun cycleDao(): CycleDayLogDao
    abstract fun onboardingDao(): OnboardingProfileDao
    abstract fun healthSyncDao(): HealthSyncLocalDao
}
