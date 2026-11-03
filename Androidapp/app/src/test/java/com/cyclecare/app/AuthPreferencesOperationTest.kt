package com.cyclecare.app

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.cyclecare.app.security.AuthPreferences
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.UUID

/**
 * Real AuthPreferences (injected SharedPreferences, secure storage flagged available),
 * not real Keystore. Covers the locked snapshot and compare-and-commit boundary only,
 * not the ViewModel or UI wiring.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AuthPreferencesOperationTest {
    private fun preferences(): AuthPreferences {
        val context = ApplicationProvider.getApplicationContext<Context>()
        return AuthPreferences(context, context.getSharedPreferences("operation-${UUID.randomUUID()}", Context.MODE_PRIVATE), true)
    }

    @Test fun snapshotBindsOwnerSessionAndThatOwnersConsentGenerationTogether() {
        val prefs = preferences()
        prefs.userId = "owner-a"
        prefs.isCloudSyncAllowed = true
        val a = prefs.operationSnapshot()
        assertEquals("owner-a", a.owner)
        assertEquals(1L, a.consentGeneration)
        prefs.userId = "owner-b"
        val b = prefs.operationSnapshot()
        assertEquals("owner-b", b.owner)
        assertEquals(0L, b.consentGeneration)
        assertNotEquals(a.session, b.session)
    }

    @Test fun switchingAwayAndBackChangesTheSessionSoAnOldSnapshotIsRecognisedAsStale() {
        val prefs = preferences()
        prefs.userId = "owner-a"
        val before = prefs.operationSnapshot()
        prefs.userId = "owner-b"
        prefs.userId = "owner-a"
        val after = prefs.operationSnapshot()
        assertEquals(before.owner, after.owner)
        assertNotEquals(before.session, after.session)
        assertNull(prefs.runIfIdentityUnchanged(before.owner, before.session) { "ran" })
        assertEquals("ran", prefs.runIfIdentityUnchanged(after.owner, after.session) { "ran" })
    }

    @Test fun postChoiceGenerationReadInsideTheGuardedBlockIsTheOwnersNewGeneration() {
        val prefs = preferences()
        prefs.userId = "owner-a"
        val token = prefs.operationSnapshot()
        val generationAfterChoice = prefs.runIfIdentityUnchanged(token.owner, token.session) {
            prefs.isCloudSyncAllowed = true
            prefs.operationSnapshot().consentGeneration
        }
        assertEquals(token.consentGeneration + 1, generationAfterChoice)
    }
}
