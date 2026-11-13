package com.cyclecare.app

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import com.cyclecare.app.data.repository.CycleCareComposition
import com.cyclecare.app.data.supabase.SupabaseSyncWorker
import com.cyclecare.app.security.AuthPreferences
import com.cyclecare.app.security.IdentityPublicationAdmission
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Stage-D worker consent-first witness (review round 3): the sync worker
 * must check isCloudSyncAllowed BEFORE the composition's repository (and
 * therefore the encrypted health store) is initialized. A revoked-consent
 * run returns success having touched nothing. The consent-true path cannot
 * run on the host tier (CycleCareDatabase.getDatabase loads SQLCipher
 * natively inside doWork) and remains owed to the device tier - only the
 * ordering guarantee under review is witnessed here.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class StageDWorkerConsentTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @After fun cleanup() {
        CycleCareComposition.resetForTests()
    }

    @Test fun revokedConsentSkipsSyncWithoutInitializingTheHealthStore() = runBlocking {
        CycleCareComposition.resetForTests()
        val prefs = AuthPreferences(
            context,
            context.getSharedPreferences("stage-d-worker-${UUID.randomUUID()}", Context.MODE_PRIVATE),
            true
        ).apply {
            userId = "owner-a"
            accountMode = "PRIVATE"
            isCloudSyncAllowed = false
            isAiSharingAllowed = false
            attachPublicationAdmission(IdentityPublicationAdmission.forAndroid())
        }
        CycleCareComposition.authPreferencesFactoryForTests = { prefs }

        val worker = TestListenableWorkerBuilder<SupabaseSyncWorker>(context).build()
        val result = worker.doWork()

        assertEquals(ListenableWorker.Result.success(), result)
        val holder = CycleCareComposition.current(context)
        assertNull("revoked consent must not initialize the repository/health store", holder.peekRepository())
        assertEquals("no recovery work for a skipped sync", 0, holder.recovery!!.roundsRun)
    }
}
