package com.cyclecare.app
import com.cyclecare.app.domain.sync.*
import org.junit.Test
import org.junit.Assert.*
class AccountStartPolicyTest {
 @Test fun freshReinstallWithoutBackupNeedsSetup(){assertEquals(AccountStartDestination.ONBOARDING,AccountStartPolicy.decide(false,LocalDataState.CLEAN,false))}
 @Test fun restoredRemoteProfileGoesHome(){assertEquals(AccountStartDestination.HOME,AccountStartPolicy.decide(false,LocalDataState.CLEAN,true))}
 @Test fun goodLocalDataWorksOffline(){assertEquals(AccountStartDestination.HOME,AccountStartPolicy.decide(true,LocalDataState.CLEAN,null))}
 @Test fun unknownRemoteNeverAssumesNewUser(){assertEquals(AccountStartDestination.RETRY,AccountStartPolicy.decide(false,LocalDataState.CLEAN,null))}
 @Test fun damagedOrConflictedDataNeedsSetupWithoutWiping(){assertEquals(AccountStartDestination.ONBOARDING,AccountStartPolicy.decide(true,LocalDataState.CONFLICTED,true))}
 @Test fun unreadableLocalDataRetriesNeverOnboards(){assertEquals(AccountStartDestination.RETRY,AccountStartPolicy.decide(false,LocalDataState.UNKNOWN,false))}
 @Test fun unreadableLocalDataNeverAssumesClean(){assertEquals(AccountStartDestination.RETRY,AccountStartPolicy.decide(true,LocalDataState.UNKNOWN,true))}
}
