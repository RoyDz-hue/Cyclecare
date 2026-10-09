package com.example.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [CycleDayLog::class, CycleSettings::class, OnboardingProfile::class],
    version = 3,
    exportSchema = false
)
abstract class CycleCareDatabase : RoomDatabase() {
    abstract fun cycleDao(): CycleDayLogDao
    abstract fun onboardingDao(): OnboardingProfileDao

    companion object {
        @Volatile
        private var INSTANCE: CycleCareDatabase? = null

        fun getDatabase(context: Context): CycleCareDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    CycleCareDatabase::class.java,
                    "cycle_care_database"
                )
                    .fallbackToDestructiveMigration()
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
