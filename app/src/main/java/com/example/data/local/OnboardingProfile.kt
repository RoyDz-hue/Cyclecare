package com.example.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Room database entity storing user cycle tracking profile and preferences
 * collected during the progressive CycleCare onboarding journey.
 */
@Entity(tableName = "onboarding_profiles")
data class OnboardingProfile(
    @PrimaryKey
    val id: Int = 1,
    val isCompleted: Boolean = true,
    val completedAtTimestamp: Long = System.currentTimeMillis(),
    val isAgeVerified: Boolean = true,
    val isTermsAgreed: Boolean = true,
    val accountMode: String = "PRIVATE", // "PRIVATE" or "SYNC_ACCOUNT"
    val selectedGoalsCsv: String = "",
    val lastPeriodDateString: String = "", // YYYY-MM-DD
    val approximatePeriodChoice: String = "EXACT", // e.g., "EXACT", "Today", "A few days ago", etc.
    val cycleLengthOption: String = "25–28 days",
    val cycleLengthDays: Int = 28,
    val periodDurationDays: Int = 5,
    val remindFertileWindow: Boolean = true
)
