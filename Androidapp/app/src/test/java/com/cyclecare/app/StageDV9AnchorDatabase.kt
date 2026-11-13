package com.cyclecare.app

import androidx.room.Database
import androidx.room.RoomDatabase
import com.cyclecare.app.data.local.CycleDayLog
import com.cyclecare.app.data.local.CycleDayLogDao
import com.cyclecare.app.data.local.CycleSettings
import com.cyclecare.app.data.local.HealthSyncConflictEntity
import com.cyclecare.app.data.local.HealthSyncCursor
import com.cyclecare.app.data.local.HealthSyncLocalDao
import com.cyclecare.app.data.local.HealthSyncOutboxEntry
import com.cyclecare.app.data.local.HealthSyncRevision
import com.cyclecare.app.data.local.OnboardingProfile
import com.cyclecare.app.data.local.OnboardingProfileDao

/**
 * Stage-D v9 ANCHOR: an independent Room database over the SAME seven entity
 * classes at version 9. Room's identity hash fingerprints entity schema, not
 * the version (every real upgrade relies on that), so a file this class
 * creates carries the hash a historical v9 install wrote - WITHOUT deriving
 * anything from the current v10 generator. The witnesses assert
 * anchor-hash == production-hash explicitly, which is the non-circular
 * evidence that current entity sources still produce the v9 schema
 * (git-verified unchanged since the c1d8586 gate: no data/local entity file
 * appears in c1d8586..HEAD except CycleCareDatabase.kt itself).
 *
 * Files created here are used DIRECTLY as v9 fixtures - no v10-created file
 * is ever stripped or relabeled as v9.
 */
@Database(
    entities = [CycleDayLog::class, CycleSettings::class, OnboardingProfile::class, HealthSyncRevision::class, HealthSyncOutboxEntry::class, HealthSyncCursor::class, HealthSyncConflictEntity::class],
    version = 9,
    exportSchema = false
)
abstract class StageDV9AnchorDatabase : RoomDatabase() {
    abstract fun cycleDao(): CycleDayLogDao
    abstract fun onboardingDao(): OnboardingProfileDao
    abstract fun healthSyncDao(): HealthSyncLocalDao
}
