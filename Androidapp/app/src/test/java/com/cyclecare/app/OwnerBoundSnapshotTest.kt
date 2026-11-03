package com.cyclecare.app

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.cyclecare.app.data.local.*
import com.cyclecare.app.data.repository.CycleRepository
import kotlin.concurrent.thread
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.UUID

/**
 * Repository-level delayed-switch tests. The switch is injected through the
 * repository's internal afterContextCaptured seam, which runs exactly between
 * capturing the database/owner and the first database call, so the interleaving
 * is deterministic and does not depend on Room's own suspension behavior.
 * Plain temporary Room databases: not SQLCipher/Keystore, and the ViewModel
 * publication guards are not exercised here.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OwnerBoundSnapshotTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val names = mutableListOf<String>()
    private val handles = mutableListOf<CycleCareDatabase>()
    private fun open(): CycleCareDatabase {
        val name = "owner-bound-${UUID.randomUUID()}".also { names += it }
        context.getDatabasePath(name).parentFile?.mkdirs()
        return Room.databaseBuilder(context, CycleCareDatabase::class.java, name)
            .allowMainThreadQueries().build().also { handles += it }
    }
    @After fun cleanup() { handles.forEach { if (it.isOpen) it.close() }; names.forEach { context.deleteDatabase(it) } }

    private fun profile(goals: String) = OnboardingProfile(
        id = 1, isCompleted = true, completedAtTimestamp = 1L, isAgeVerified = true, isTermsAgreed = true,
        accountMode = "PRIVATE", selectedGoalsCsv = goals, lastPeriodDateString = "2026-10-05",
        approximatePeriodChoice = "EXACT", cycleLengthOption = "28", cycleLengthDays = 28, periodDurationDays = 5
    )

    @Test fun snapshotCapturedBeforeSwitchBelongsToTheOriginalOwnerAndIsNotCurrent() = runBlocking {
        val a = open(); val b = open()
        a.cycleDao().saveSettings(CycleSettings(id = 1, isOnboardingCompleted = true, selectedGoalsCsv = "from-a"))
        a.onboardingDao().insertOrUpdateProfile(profile("from-a"))
        b.cycleDao().saveSettings(CycleSettings(id = 1, isOnboardingCompleted = false, selectedGoalsCsv = "from-b"))
        val repo = CycleRepository(a).apply { activateDatabaseForTest(a, "A") }
        repo.afterContextCaptured = { repo.activateDatabaseForTest(b, "B") }
        val snapshot = repo.getStoredSnapshot()
        assertEquals("from-a", snapshot.settings.selectedGoalsCsv)
        assertEquals("from-a", snapshot.profile?.selectedGoalsCsv)
        assertSame(a, snapshot.context.database)
        assertEquals("A", snapshot.context.owner)
        assertFalse(repo.isCurrentContext(snapshot.context))
    }

    @Test fun switchingAwayAndBackToTheSameDatabaseStillInvalidatesACapturedContext() = runBlocking {
        val a = open(); val b = open()
        val repo = CycleRepository(a).apply { activateDatabaseForTest(a, "A") }
        val captured = repo.getStoredSnapshot().context
        assertTrue(repo.isCurrentContext(captured))
        repo.activateDatabaseForTest(b, "B")
        repo.activateDatabaseForTest(a, "A")
        assertSame(captured.database, a)
        assertFalse(repo.isCurrentContext(captured))
    }

    @Test fun onboardingCompletionStaysWithItsCapturedDatabaseAndOwnerAcrossASwitch() = runBlocking {
        val a = open(); val b = open()
        val repo = CycleRepository(a).apply { activateDatabaseForTest(a, "A") }
        repo.afterContextCaptured = { repo.activateDatabaseForTest(b, "B") }
        val completion = repo.completeOnboardingWithProfile(profile("done-by-a"))
        assertEquals("done-by-a", a.onboardingDao().getProfile()?.selectedGoalsCsv)
        assertNotNull(a.healthSyncDao().revision("A", "profile"))
        assertNotNull(a.healthSyncDao().revision("A", "settings"))
        assertNull(b.onboardingDao().getProfile())
        assertNull(b.healthSyncDao().revision("B", "profile"))
        assertNull(b.healthSyncDao().revision("A", "profile"))
        assertEquals("done-by-a", completion.settings.selectedGoalsCsv)
        assertFalse(repo.isCurrentContext(completion.context))
    }

    @Test fun concurrentSwitchesNeverYieldADatabaseOwnerMismatchInACapturedContext() = runBlocking {
        val a = open(); val b = open()
        val repo = CycleRepository(a).apply { activateDatabaseForTest(a, "A") }
        @Volatile var stop = false
        val flipper = thread {
            var toB = true
            while (!stop) { if (toB) repo.activateDatabaseForTest(b, "B") else repo.activateDatabaseForTest(a, "A"); toB = !toB }
        }
        try {
            repeat(2000) {
                val ctx = repo.getStoredSnapshot().context
                assertTrue("database/owner paired", (ctx.database === a && ctx.owner == "A") || (ctx.database === b && ctx.owner == "B"))
            }
        } finally { stop = true; flipper.join() }
    }
}
