package com.cyclecare.app.data.local

import androidx.room.Database
import androidx.room.RoomDatabase

/**
 * androidTest mirror of the Stage-D v9 anchor (test source set): an
 * independent Room database over the SAME seven entity classes at version 9.
 * Room's identity hash fingerprints entity schema, so a file this class
 * creates on-device carries exactly what a historical v9 install wrote. Files
 * it creates are used DIRECTLY as v9 fixtures; no v10 file is ever stripped
 * or relabeled as v9. Test-owned only - always created at a test-prefixed
 * namespace with a test-owned passphrase.
 */
@Database(
    entities = [CycleDayLog::class, CycleSettings::class, OnboardingProfile::class, HealthSyncRevision::class, HealthSyncOutboxEntry::class, HealthSyncCursor::class, HealthSyncConflictEntity::class],
    version = 9,
    exportSchema = false
)
abstract class V9FixtureAnchorDatabase : RoomDatabase() {
    abstract fun cycleDao(): CycleDayLogDao
    abstract fun onboardingDao(): OnboardingProfileDao
    abstract fun healthSyncDao(): HealthSyncLocalDao
}
