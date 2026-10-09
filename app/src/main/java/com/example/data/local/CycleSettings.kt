package com.example.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "cycle_settings")
data class CycleSettings(
    @PrimaryKey
    val id: Int = 1,
    val isOnboardingCompleted: Boolean = false, // Defaults false so fresh users see onboarding flow
    val selectedGoalsCsv: String = "🌸 Understand my cycle,🩷 Track my periods",
    val lastPeriodStartDate: String = "", // YYYY-MM-DD
    val averageCycleLength: Int = 28, // In days (21 - 45)
    val periodDuration: Int = 5, // In days (2 - 10)
    val lutealPhaseDays: Int = 14,
    val isAppLockEnabled: Boolean = false,
    val appLockPin: String = "", // 4-digit PIN
    val lockTimeoutMinutes: Int = 1, // 0 = Immediately, 1 = 1m, 5 = 5m
    val isDiscreetNotificationMode: Boolean = true, // Subtle neutral notifications
    val isPregnancyMode: Boolean = false,
    val pregnancyDueDate: String = "",
    val remindPeriod: Boolean = true,
    val remindFertile: Boolean = true,
    val remindDailyLog: Boolean = true
)
