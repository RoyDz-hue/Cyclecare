package com.cyclecare.app.domain.sync

enum class AccountStartDestination { HOME, ONBOARDING, RETRY }
object AccountStartPolicy {
    fun decide(localValid: Boolean, localDamagedOrConflicted: Boolean, remoteProfile: Boolean?): AccountStartDestination = when {
        localDamagedOrConflicted -> AccountStartDestination.ONBOARDING
        localValid -> AccountStartDestination.HOME
        remoteProfile == true -> AccountStartDestination.HOME
        remoteProfile == false -> AccountStartDestination.ONBOARDING
        else -> AccountStartDestination.RETRY
    }
}
