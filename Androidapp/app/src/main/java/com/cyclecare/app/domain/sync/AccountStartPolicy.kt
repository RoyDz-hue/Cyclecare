package com.cyclecare.app.domain.sync

enum class AccountStartDestination { HOME, ONBOARDING, RETRY }

/** CLEAN: reads succeeded, no pending conflicts. CONFLICTED: unresolved sync
 * conflicts. UNKNOWN: a read failed - never treat as new user and never as
 * clean (a sync overwrite on unknown state could destroy local history). */
enum class LocalDataState { CLEAN, CONFLICTED, UNKNOWN }

object AccountStartPolicy {
    /** Unresolved sync conflicts never route to onboarding: rerunning setup
     * would overwrite personal answers and the conflict review lives in
     * Settings, unreachable while onboarding owns the shell. With valid local
     * data the user goes HOME (sync stays gated until the review is resolved);
     * without it the safe destination is a non-destructive RETRY. */
    fun decide(localValid: Boolean, localState: LocalDataState, remoteProfile: Boolean?): AccountStartDestination {
        val expected=decideKotlin(localValid,localState,remoteProfile)
        return com.cyclecare.app.rust.CycleCareCoreBridge.accountStart(localValid,localState,remoteProfile)?.takeIf {it==expected} ?: expected
    }

    internal fun decideKotlin(localValid: Boolean, localState: LocalDataState, remoteProfile: Boolean?): AccountStartDestination = when {
        localState == LocalDataState.UNKNOWN -> AccountStartDestination.RETRY
        localState == LocalDataState.CONFLICTED && localValid -> AccountStartDestination.HOME
        localState == LocalDataState.CONFLICTED -> AccountStartDestination.RETRY
        localValid -> AccountStartDestination.HOME
        remoteProfile == true -> AccountStartDestination.HOME
        remoteProfile == false -> AccountStartDestination.ONBOARDING
        else -> AccountStartDestination.RETRY
    }
}
