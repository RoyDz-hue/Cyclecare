package com.cyclecare.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.cyclecare.app.data.local.CycleCareDatabase
import com.cyclecare.app.data.local.CycleDayLog
import com.cyclecare.app.data.local.OwnerScopedDatabaseProvider
import com.cyclecare.app.data.supabase.SupabaseSyncService
import com.cyclecare.app.security.AuthPreferences
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.FileInputStream
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/** Offline acceptance only. Uses unique test owners; no real account or provider credentials. */
@RunWith(AndroidJUnit4::class)
class PrivateDataAcceptanceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun encryptedHealthRecordsAreIsolatedAcrossOwners() = runBlocking {
        val a = "acceptance-a-${UUID.randomUUID()}"
        val b = "acceptance-b-${UUID.randomUUID()}"
        val dbA = CycleCareDatabase.getDatabase(context, a)
        val dbB = CycleCareDatabase.getDatabase(context, b)
        try {
            dbA.cycleDao().insertOrUpdateLog(CycleDayLog("2026-10-19", notes = "private fixture A"))
            assertNull(dbB.cycleDao().getLogByDate("2026-10-19"))
            dbB.cycleDao().insertOrUpdateLog(CycleDayLog("2026-10-19", notes = "private fixture B"))
            assertEquals("private fixture A", dbA.cycleDao().getLogByDate("2026-10-19")?.notes)
            assertEquals("private fixture B", dbB.cycleDao().getLogByDate("2026-10-19")?.notes)
            for (owner in listOf(a, b)) {
                val bytes = ByteArray(16)
                FileInputStream(context.getDatabasePath(CycleCareDatabase.namespaceFor(owner))).use {
                    assertEquals(16, it.read(bytes))
                }
                assertFalse("Health database must not have a plaintext SQLite header", bytes.toString(Charsets.US_ASCII).startsWith("SQLite format 3"))
            }
        } finally {
            dbA.close(); dbB.close()
            context.deleteDatabase(CycleCareDatabase.namespaceFor(a))
            context.deleteDatabase(CycleCareDatabase.namespaceFor(b))
        }
    }

    @Test
    fun consentAndDatabaseResolutionFollowCurrentOwner() {
        val preferences = AuthPreferences(context)
        assertTrue("Acceptance requires encrypted preferences", preferences.isSecureStorageAvailable)
        val previousOwner = preferences.userId
        val a = "acceptance-a-${UUID.randomUUID()}"
        val b = "acceptance-b-${UUID.randomUUID()}"
        val provider = OwnerScopedDatabaseProvider(context, preferences)
        var dbA: CycleCareDatabase? = null
        var dbB: CycleCareDatabase? = null
        try {
            preferences.userId = a
            preferences.isAiSharingAllowed = true
            preferences.isCloudSyncAllowed = true
            dbA = provider.current()
            preferences.userId = b
            assertFalse(preferences.isAiSharingAllowed)
            assertFalse(preferences.isCloudSyncAllowed)
            dbB = provider.current()
            assertNotSame(dbA, dbB)
            preferences.userId = a
            assertTrue(preferences.isAiSharingAllowed)
            assertTrue(preferences.isCloudSyncAllowed)
            assertSame(dbA, provider.current())
            preferences.isAiSharingAllowed = false
            preferences.isCloudSyncAllowed = false
        } finally {
            preferences.userId = previousOwner
            dbA?.close(); dbB?.close()
            context.deleteDatabase(CycleCareDatabase.namespaceFor(a))
            context.deleteDatabase(CycleCareDatabase.namespaceFor(b))
        }
    }

    @Test
    fun unreleasedSyncMakesNoNetworkCallEvenWithLocalConsent() = runBlocking {
        val preferences = AuthPreferences(context)
        val previousOwner = preferences.userId
        val owner = "acceptance-sync-${UUID.randomUUID()}"
        val calls = AtomicInteger(0)
        val client = OkHttpClient.Builder().addInterceptor {
            calls.incrementAndGet()
            throw AssertionError("The unreleased sync protocol must not contact a backend")
        }.build()
        val database = CycleCareDatabase.getDatabase(context, owner)
        try {
            preferences.userId = owner
            preferences.isCloudSyncAllowed = true
            val result = SupabaseSyncService(database, preferences, client).syncAll()
            assertFalse(result.isSuccess)
            assertTrue(result.message.contains("paused"))
            assertEquals(0, calls.get())
        } finally {
            preferences.isCloudSyncAllowed = false
            preferences.userId = previousOwner
            database.close()
            context.deleteDatabase(CycleCareDatabase.namespaceFor(owner))
        }
    }
}
