package com.cyclecare.app.domain.onboarding
import java.time.LocalDate
import com.cyclecare.app.data.local.OnboardingProfile
import com.cyclecare.app.data.local.CycleSettings
internal object OnboardingCompletionMapping {
    data class Input(val accountMode:String,val now:Long,val submittedGoals:List<String>,val lastPeriodDate:LocalDate,val lastPeriodEndDate:LocalDate?,val periodStartChoice:String,val cycleLengthOption:String,val cycleLength:Int,val periodDuration:Int,val remindFertile:Boolean,val submittedSymptoms:List<String>,val typicalEnergy:String,val typicalSleep:String,val hasCompletedDeepBaseline:Boolean)
    fun profile(i:Input):OnboardingProfile = com.cyclecare.app.rust.CycleCareCoreBridge.fullCompletionProfile(i) ?: profileKotlin(i)
    internal fun profileKotlin(i:Input):OnboardingProfile = with(i) {
OnboardingProfile(
                    id = 1,
                    isCompleted = true,
                    completedAtTimestamp = now,
                    isAgeVerified = true,
                    isTermsAgreed = true,
                    accountMode = accountMode,
                    selectedGoalsCsv = submittedGoals.joinToString(","),
                    lastPeriodDateString = if (periodStartChoice == "UNKNOWN") "" else lastPeriodDate.toString(),
                    // Raw episode record: the end date exactly as entered and
                    // confirmed, never clamped into the typical-duration range.
                    lastPeriodEndDateString = lastPeriodEndDate?.toString() ?: "",
                    approximatePeriodChoice = periodStartChoice,
                    cycleLengthOption = cycleLengthOption.ifBlank { "UNKNOWN" },
                    cycleLengthDays = cycleLength,
                    periodDurationDays = periodDuration,
                    remindFertileWindow = remindFertile,
                    typicalSymptomsCsv = submittedSymptoms.joinToString(","),
                    typicalEnergy = typicalEnergy,
                    typicalSleepQuality = typicalSleep,
                    hasCompletedDeepBaseline = hasCompletedDeepBaseline
                )
    }
    fun settings(current:CycleSettings,profile:OnboardingProfile,enableLock:Boolean):CycleSettings = com.cyclecare.app.rust.CycleCareCoreBridge.fullCompletionSettings(current,profile,enableLock) ?: settingsKotlin(current,profile,enableLock)
    internal fun settingsKotlin(current:CycleSettings,profile:OnboardingProfile,enableLock:Boolean):CycleSettings = current.copy(
                isOnboardingCompleted = true,
                selectedGoalsCsv = profile.selectedGoalsCsv,
                lastPeriodStartDate = profile.lastPeriodDateString,
                averageCycleLength = profile.cycleLengthDays,
                periodDuration = profile.periodDurationDays,
                remindFertile = profile.remindFertileWindow,
                isAppLockEnabled = enableLock
            )
}
