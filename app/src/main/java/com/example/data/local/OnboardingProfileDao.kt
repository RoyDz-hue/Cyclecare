package com.example.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * Data Access Object for persisting and observing the user's onboarding profile.
 */
@Dao
interface OnboardingProfileDao {

    @Query("SELECT * FROM onboarding_profiles WHERE id = 1 LIMIT 1")
    fun getProfileFlow(): Flow<OnboardingProfile?>

    @Query("SELECT * FROM onboarding_profiles WHERE id = 1 LIMIT 1")
    suspend fun getProfile(): OnboardingProfile?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdateProfile(profile: OnboardingProfile)

    @Query("DELETE FROM onboarding_profiles")
    suspend fun deleteProfile()
}
