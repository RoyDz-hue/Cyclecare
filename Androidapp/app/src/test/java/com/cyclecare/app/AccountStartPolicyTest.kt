package com.cyclecare.app
import com.cyclecare.app.domain.sync.*
import org.junit.Test
import org.junit.Assert.*
class AccountStartPolicyTest {
 @Test fun freshReinstallWithoutBackupNeedsSetup(){assertEquals(AccountStartDestination.ONBOARDING,AccountStartPolicy.decide(false,false,false))}
 @Test fun restoredRemoteProfileGoesHome(){assertEquals(AccountStartDestination.HOME,AccountStartPolicy.decide(false,false,true))}
 @Test fun goodLocalDataWorksOffline(){assertEquals(AccountStartDestination.HOME,AccountStartPolicy.decide(true,false,null))}
 @Test fun unknownRemoteNeverAssumesNewUser(){assertEquals(AccountStartDestination.RETRY,AccountStartPolicy.decide(false,false,null))}
 @Test fun damagedOrConflictedDataNeedsSetupWithoutWiping(){assertEquals(AccountStartDestination.ONBOARDING,AccountStartPolicy.decide(true,true,true))}
}
