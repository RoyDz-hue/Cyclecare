package com.cyclecare.app.domain.sync

enum class AccountStartDestination { HOME, ONBOARDING, RETRY }

/** CLEAN: reads succeeded, no pending conflicts. CONFLICTED: unresolved sync
 * conflicts. UNKNOWN: a read failed - never treat as new user and never as
 * clean (a sync overwrite on unknown state could destroy local history). */
enum class LocalDataState { CLEAN, CONFLICTED, UNKNOWN }

object AccountStartPolicy {
    fun decide(localValid: Boolean, localState: LocalDataState, remoteProfile: Boolean?): AccountStartDestination = when {
        localState == LocalDataState.CONFLICTED -> AccountStartDestination.ONBOARDING
        localState == LocalDataState.UNKNOWN -> AccountStartDestination.RETRY
        localValid -> AccountStartDestination.HOME
        remoteProfile == true -> AccountStartDestination.HOME
        remoteProfile == false -> AccountStartDestination.ONBOARDING
        else -> AccountStartDestination.RETRY
    }
}
