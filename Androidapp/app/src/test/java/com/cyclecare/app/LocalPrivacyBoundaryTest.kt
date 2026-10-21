package com.cyclecare.app

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.cyclecare.app.data.local.CycleCareDatabase
import com.cyclecare.app.data.local.CycleDayLog
import com.cyclecare.app.data.local.OwnerDataGate
import com.cyclecare.app.data.repository.CycleRepository
import com.cyclecare.app.data.supabase.SupabaseSyncService
import com.cyclecare.app.security.AuthPreferences
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LocalPrivacyBoundaryTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private fun database() = Room.inMemoryDatabaseBuilder(context, CycleCareDatabase::class.java).allowMainThreadQueries().build()

    @Test fun aBGuestSwitchingNeverReusesHealthRowsOrSettings() = runBlocking {
        val a = database(); val b = database(); val guest = database()
        try {
            val repository = CycleRepository(a)
            repository.saveLog(CycleDayLog("2026-10-18", notes = "A private note"))
            repository.activateDatabaseForTest(b, "B")
            assertNull(repository.getLogForDate("2026-10-18"))
            repository.saveLog(CycleDayLog("2026-10-18", notes = "B private note"))
            repository.activateDatabaseForTest(guest, null)
            assertNull(repository.getLogForDate("2026-10-18"))
            repository.activateDatabaseForTest(a, "A")
            assertEquals("A private note", repository.getLogForDate("2026-10-18")?.notes)
            repository.activateDatabaseForTest(b, "B")
            assertEquals("B private note", repository.getLogForDate("2026-10-18")?.notes)
        } finally { a.close(); b.close(); guest.close() }
    }

    @Test fun deniedConsentDoesNotResolveSessionOrMakeAnyNetworkRequest() = runBlocking {
        val db = database()
        try {
            val prefs = AuthPreferences(context, context.getSharedPreferences("unit-privacy-${java.util.UUID.randomUUID()}", Context.MODE_PRIVATE), true).apply { userId = "11111111-1111-4111-8111-111111111111" }
            prefs.isCloudSyncAllowed = false
            val requests = AtomicInteger(0)
            val client = OkHttpClient.Builder().addInterceptor { requests.incrementAndGet(); throw AssertionError("No health request permitted") }.build()
            val service = SupabaseSyncService(db, prefs, client)
            assertTrue(service.syncAll().isSuccess)
            assertEquals(0, service.pullRemoteUpdates(prefs.effectiveUserId))
            assertEquals(0, requests.get())
        } finally { db.close() }
    }

    @Test fun consentAloneCannotEnableLegacyOrConflictUnsafeHealthTransfer() = runBlocking {
        val db = database()
        try {
            val prefs = AuthPreferences(context, context.getSharedPreferences("unit-privacy-${java.util.UUID.randomUUID()}", Context.MODE_PRIVATE), true).apply { userId = "11111111-1111-4111-8111-111111111111" }
            OwnerDataGate.resumeAfterExplicitUserAction(context, prefs.userId)
            prefs.isCloudSyncAllowed = true
            val requests = AtomicInteger(0)
            val client = OkHttpClient.Builder().addInterceptor { requests.incrementAndGet(); throw AssertionError("Backend readiness must fail closed") }.build()
            val result = SupabaseSyncService(db, prefs, client).syncAll()
            assertFalse(result.isSuccess)
            assertTrue(result.message.contains("pending"))
            assertEquals(0, requests.get())
        } finally { db.close() }
    }

    @Test fun wipePauseIsDurableAndCannotBeUndoneByReconnect() = runBlocking {
        val db = database()
        try {
            val prefs = AuthPreferences(context, context.getSharedPreferences("unit-privacy-${java.util.UUID.randomUUID()}", Context.MODE_PRIVATE), true).apply { userId = "11111111-1111-4111-8111-111111111111" }
            prefs.isCloudSyncAllowed = true
            OwnerDataGate.markWiped(context, prefs.userId)
            val requests = AtomicInteger(0)
            val client = OkHttpClient.Builder().addInterceptor { requests.incrementAndGet(); throw AssertionError("Wiped namespace must not sync") }.build()
            val result = SupabaseSyncService(db, prefs, client).syncAll()
            assertTrue(result.message.contains("wiped"))
            assertTrue(OwnerDataGate.isWiped(context, prefs.userId))
            assertEquals(0, requests.get())
        } finally { db.close() }
    }
}
